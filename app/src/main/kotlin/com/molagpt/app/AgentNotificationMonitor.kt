package com.molagpt.app

import com.molagpt.app.core.common.Logger
import com.molagpt.app.core.model.AgentPhase
import com.molagpt.app.core.model.RelayEnvelope
import com.molagpt.app.core.model.RelayEvent
import com.molagpt.app.core.model.RelayMachine
import com.molagpt.app.core.model.RelaySessionMeta
import com.molagpt.app.core.model.isBusy
import com.molagpt.app.core.model.isStalled
import com.molagpt.app.core.model.phaseEnum
import com.molagpt.app.core.network.AgentControlService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal enum class AgentAlertKind { Permission, Completed, Failed }

internal fun hasPendingApproval(meta: RelaySessionMeta, nowMs: Long): Boolean =
    meta.phaseEnum == AgentPhase.Waiting && meta.needsAttention &&
        meta.updatedAtMs > 0 && nowMs - meta.updatedAtMs < 60_000L

internal data class AgentAlert(
    val kind: AgentAlertKind,
    val sessionId: String,
    val backendId: String,
    val title: String,
    val toolName: String? = null,
    val message: String? = null,
)

internal data class AgentEventCursor(
    val meta: RelaySessionMeta,
    val sinceSeq: Long,
)

internal data class AgentTrackerUpdate(
    val fetches: List<AgentEventCursor> = emptyList(),
    val alerts: List<AgentAlert> = emptyList(),
)

/**
 * Pure state machine behind Agent notifications.  The relay session list is a
 * coarse snapshot; [RelaySessionMeta.seq] tells us which transcripts changed,
 * then the monitor fetches only those event tails.  Cursors live for the whole
 * application process so page navigation cannot replay old completion alerts.
 */
internal class AgentNotificationTracker {
    private data class SessionState(
        var lastSeq: Long,
        var phase: AgentPhase,
        var needsAttention: Boolean,
        var title: String,
        var backendId: String,
        var active: Boolean,
        var terminalSeq: Long = 0L,
        var manualWatchUntilMs: Long = 0L,
        var lastSeenAtMs: Long,
        // One-shot gate for Completed/Failed alerts: armed when a turn
        // demonstrably starts (watch tap, UserPrompt/Permission event, or an
        // idle→busy meta transition) and consumed by the first terminal alert.
        // A relay that re-delivers a turn's terminal — e.g. a desktop
        // re-projecting a growing external transcript — can then never fire
        // more than one notification per actually started turn.
        var terminalAlertArmed: Boolean = false,
    )

    private val states = mutableMapOf<String, SessionState>()

    /** Phone-originated sends are watched immediately, before relay meta turns busy. */
    @Synchronized
    fun watch(meta: RelaySessionMeta, nowMs: Long = System.currentTimeMillis()) {
        val state = states.getOrPut(meta.conversationId) {
            SessionState(
                lastSeq = meta.seq,
                phase = meta.phaseEnum,
                needsAttention = meta.needsAttention,
                title = meta.title,
                backendId = meta.backendId,
                active = true,
                lastSeenAtMs = nowMs,
            )
        }
        state.title = meta.title
        state.backendId = meta.backendId
        state.active = true
        state.terminalAlertArmed = true
        state.manualWatchUntilMs = nowMs + MANUAL_WATCH_GRACE_MS
        state.lastSeenAtMs = nowMs
    }

    /** Reconcile a session-list snapshot and identify event tails that changed. */
    @Synchronized
    fun observe(
        sessions: List<RelaySessionMeta>,
        machines: List<RelayMachine> = emptyList(),
        nowMs: Long = System.currentTimeMillis(),
    ): AgentTrackerUpdate {
        val fetches = mutableListOf<AgentEventCursor>()
        val alerts = mutableListOf<AgentAlert>()
        val seen = HashSet<String>(sessions.size)

        for (meta in sessions) {
            seen += meta.conversationId
            val phase = meta.phaseEnum
            // The desktop owns `phase` and is the only writer of its terminal.
            // Once that desktop is gone the busy state can never resolve itself,
            // so it must not keep this session — and the ongoing foreground
            // notification behind it — alive forever.
            val stalled = meta.isStalled(machines, nowMs)
            val state = states[meta.conversationId]
            if (state == null) {
                val busy = (meta.isBusy || phase == AgentPhase.Waiting) && !stalled
                states[meta.conversationId] = SessionState(
                    lastSeq = meta.seq,
                    phase = phase,
                    needsAttention = meta.needsAttention,
                    title = meta.title,
                    backendId = meta.backendId,
                    active = busy,
                    lastSeenAtMs = nowMs,
                    terminalAlertArmed = busy,
                )
                // A currently unresolved approval remains actionable after an app
                // restart.  Completion history, by contrast, is only baselined.
                if (!stalled && hasPendingApproval(meta, nowMs)) alerts += permissionAlert(meta)
                continue
            }

            state.title = meta.title
            state.backendId = meta.backendId
            state.lastSeenAtMs = nowMs
            if (stalled) {
                alerts += applyStall(state, meta)
                if (meta.seq > state.lastSeq) state.lastSeq = meta.seq
                state.phase = phase
                state.needsAttention = false
                continue
            }
            if (meta.seq > state.lastSeq) {
                fetches += AgentEventCursor(meta, state.lastSeq)
            } else {
                alerts += applyMetaFallback(state, meta, phase, nowMs)
            }
        }

        // A relay/network outage must not stop monitoring immediately.  Retire a
        // vanished active session only after a generous stale window.
        for ((id, state) in states) {
            if (id !in seen && state.active && nowMs - state.lastSeenAtMs >= MISSING_SESSION_TIMEOUT_MS) {
                state.active = false
            }
            if (state.manualWatchUntilMs in 1..nowMs && state.phase == AgentPhase.Idle) {
                state.manualWatchUntilMs = 0L
                state.active = false
            }
        }

        return AgentTrackerUpdate(fetches, alerts)
    }

    /** Fold one successfully fetched event tail and produce one-shot alerts. */
    @Synchronized
    fun applyEvents(
        meta: RelaySessionMeta,
        events: List<RelayEnvelope>,
        nowMs: Long = System.currentTimeMillis(),
    ): List<AgentAlert> {
        val state = states.getOrPut(meta.conversationId) {
            SessionState(
                lastSeq = 0L,
                phase = meta.phaseEnum,
                needsAttention = false,
                title = meta.title,
                backendId = meta.backendId,
                active = false,
                lastSeenAtMs = nowMs,
            )
        }
        state.title = meta.title
        state.backendId = meta.backendId
        state.lastSeenAtMs = nowMs

        val alerts = mutableListOf<AgentAlert>()
        var sawTerminal = false
        var sawPermission = false
        // Busy-transition arming must compare against the state BEFORE this batch:
        // an activity event inside the batch already flips `active` to true.
        val wasActive = state.active
        val fresh = events.asSequence()
            .filter { it.seq > state.lastSeq }
            .sortedBy { it.seq }
            .toList()

        // A replacement contains old turns too. Only its tail can describe a
        // newly completed watched turn; replayed user prompts must not arm alerts.
        if (fresh.any { it.event == RelayEvent.HistoryReset }) {
            val tail = fresh.lastOrNull()?.event
            val terminal = tail is RelayEvent.TurnDone || tail is RelayEvent.TurnFailed
            if (state.terminalAlertArmed && terminal) {
                if (tail is RelayEvent.TurnFailed) alerts += terminalAlert(AgentAlertKind.Failed, meta, tail.message)
                if (tail is RelayEvent.TurnDone && tail.reason == null) alerts += terminalAlert(AgentAlertKind.Completed, meta)
            }
            state.active = !terminal && fresh.size > 1
            state.terminalAlertArmed = state.active
            state.lastSeq = fresh.maxOf { it.seq }
            if (terminal) state.terminalSeq = state.lastSeq
            state.phase = meta.phaseEnum
            state.needsAttention = hasPendingApproval(meta, nowMs)
            return alerts
        }

        for (env in fresh) {
            when (val event = env.event) {
                is RelayEvent.PermissionPrompt -> {
                    state.active = true
                    state.terminalAlertArmed = true
                    sawPermission = true
                    if (hasPendingApproval(meta, nowMs)) alerts += AgentAlert(
                        kind = AgentAlertKind.Permission,
                        sessionId = meta.conversationId,
                        backendId = meta.backendId,
                        title = meta.title,
                        toolName = event.toolName,
                        message = event.description,
                    )
                }
                is RelayEvent.TurnDone -> {
                    state.active = false
                    state.manualWatchUntilMs = 0L
                    state.terminalSeq = env.seq
                    sawTerminal = true
                    if (state.terminalAlertArmed) {
                        state.terminalAlertArmed = false
                        if (event.reason == null) alerts += terminalAlert(AgentAlertKind.Completed, meta)
                    }
                }
                is RelayEvent.TurnFailed -> {
                    state.active = false
                    state.manualWatchUntilMs = 0L
                    state.terminalSeq = env.seq
                    sawTerminal = true
                    if (state.terminalAlertArmed) {
                        state.terminalAlertArmed = false
                        alerts += terminalAlert(AgentAlertKind.Failed, meta, event.message)
                    }
                }
                is RelayEvent.UserPrompt -> {
                    state.active = true
                    state.terminalAlertArmed = true
                    state.manualWatchUntilMs = 0L
                }
                is RelayEvent.ToolProgress,
                is RelayEvent.AnswerSnapshot,
                is RelayEvent.ThinkingSnapshot -> {
                    state.active = true
                    state.manualWatchUntilMs = 0L
                }
                RelayEvent.Unknown, RelayEvent.HistoryReset -> Unit
            }
            if (env.seq > state.lastSeq) state.lastSeq = env.seq
        }

        // A successful empty/truncated history response still advances the relay
        // cursor.  Phase/attention provide a coarse fallback for its missing event.
        if (meta.seq > state.lastSeq) state.lastSeq = meta.seq
        if (!sawTerminal) {
            val phase = meta.phaseEnum
            if (!sawPermission && hasPendingApproval(meta, nowMs) && !state.needsAttention) {
                alerts += permissionAlert(meta)
            }
            alerts += applyTerminalFallback(state, meta, phase)
            applyBusyState(state, meta, phase, wasActive)
        }
        state.phase = meta.phaseEnum
        state.needsAttention = meta.needsAttention
        return alerts
    }

    @Synchronized
    fun hasActiveSessions(): Boolean = states.values.any { it.active }

    @Synchronized
    fun activeSessionId(): String? = states.entries.firstOrNull { it.value.active }?.key

    @Synchronized
    fun reset() = states.clear()

    /**
     * Retire a session whose owning desktop went away mid-turn.  Only a turn the
     * user actually started (armed) reports back — a long-dead session picked up
     * from the list on a cold start stays silent.
     */
    private fun applyStall(state: SessionState, meta: RelaySessionMeta): List<AgentAlert> {
        if (!state.active) return emptyList()
        state.active = false
        state.manualWatchUntilMs = 0L
        if (!state.terminalAlertArmed) return emptyList()
        state.terminalAlertArmed = false
        return listOf(terminalAlert(AgentAlertKind.Failed, meta, "桌面端已离线，任务中断"))
    }

    private fun applyMetaFallback(
        state: SessionState,
        meta: RelaySessionMeta,
        phase: AgentPhase,
        nowMs: Long,
    ): List<AgentAlert> {
        val alerts = mutableListOf<AgentAlert>()
        if (hasPendingApproval(meta, nowMs) && !state.needsAttention) alerts += permissionAlert(meta)
        alerts += applyTerminalFallback(state, meta, phase)
        applyBusyState(state, meta, phase, wasActive = state.active)
        state.phase = phase
        state.needsAttention = meta.needsAttention
        return alerts
    }

    private fun applyTerminalFallback(
        state: SessionState,
        meta: RelaySessionMeta,
        phase: AgentPhase,
    ): List<AgentAlert> {
        if (!state.active || phase == state.phase) return emptyList()
        return when (phase) {
            AgentPhase.Completed -> {
                state.active = false
                state.manualWatchUntilMs = 0L
                if (state.terminalAlertArmed) {
                    state.terminalAlertArmed = false
                    listOf(terminalAlert(AgentAlertKind.Completed, meta))
                } else {
                    emptyList()
                }
            }
            AgentPhase.Failed -> {
                state.active = false
                state.manualWatchUntilMs = 0L
                if (state.terminalAlertArmed) {
                    state.terminalAlertArmed = false
                    listOf(terminalAlert(AgentAlertKind.Failed, meta))
                } else {
                    emptyList()
                }
            }
            else -> emptyList()
        }
    }

    private fun applyBusyState(
        state: SessionState,
        meta: RelaySessionMeta,
        phase: AgentPhase,
        wasActive: Boolean,
    ) {
        // Ignore a stale Running meta that carries exactly the terminal event seq.
        if ((meta.isBusy || phase == AgentPhase.Waiting) && meta.seq > state.terminalSeq) {
            // An idle→busy transition is a new turn starting: re-arm the one-shot
            // terminal alert (external turns may never deliver a UserPrompt event).
            if (!wasActive) state.terminalAlertArmed = true
            state.active = true
            state.manualWatchUntilMs = 0L
        }
    }

    private fun permissionAlert(meta: RelaySessionMeta) = AgentAlert(
        kind = AgentAlertKind.Permission,
        sessionId = meta.conversationId,
        backendId = meta.backendId,
        title = meta.title,
    )

    private fun terminalAlert(kind: AgentAlertKind, meta: RelaySessionMeta, message: String? = null) = AgentAlert(
        kind = kind,
        sessionId = meta.conversationId,
        backendId = meta.backendId,
        title = meta.title,
        message = message,
    )

    private companion object {
        const val MANUAL_WATCH_GRACE_MS = 60_000L
        const val MISSING_SESSION_TIMEOUT_MS = 120_000L
    }
}

/**
 * Application-scope relay monitor.  It idles cheaply on the session list, polls
 * changed event tails while a task is active, and keeps the process alive with a
 * low-priority foreground service only for the lifetime of that active task.
 */
internal class AgentNotificationMonitor(
    private val service: AgentControlService,
    private val controller: AgentNotificationController,
    private val scope: CoroutineScope,
    private val notifyEnabled: () -> Boolean,
    private val isAuthenticated: () -> Boolean,
) {
    private val tracker = AgentNotificationTracker()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var foregroundRunning = false
    /** Forces one stop() on the first sync: a service (and its ongoing
     *  notification) can outlive the monitor that started it — e.g. the process
     *  was killed while a turn was in flight and Android restarted the service
     *  alone.  Without this the leftover notification only clears when something
     *  else happens to start and stop the service again. */
    @Volatile private var foregroundSynced = false

    init {
        scope.launch { runLoop() }
    }

    fun watch(meta: RelaySessionMeta) {
        if (!notifyEnabled() || !isAuthenticated()) return
        // This is called directly from the user's Send tap.  Start the foreground
        // service before the app can move to background; Android may reject a new
        // foreground-service launch after Home has already been pressed.
        tracker.watch(meta)
        syncForegroundService()
        wake.trySend(Unit)
    }

    private suspend fun runLoop() {
        while (true) {
            if (!notifyEnabled() || !isAuthenticated()) {
                tracker.reset()
                syncForegroundService()
                awaitWake(IDLE_POLL_MS)
                continue
            }

            runCatching {
                val snapshot = service.listSessions()
                val now = System.currentTimeMillis()
                snapshot.sessions
                    .filterNot { hasPendingApproval(it, now) && !it.isStalled(snapshot.machines, now) }
                    .forEach { controller.clearPermission(it.conversationId) }
                val update = tracker.observe(snapshot.sessions, snapshot.machines, now)
                update.alerts.forEach(controller::post)
                for (cursor in update.fetches) {
                    val events = runCatching {
                        service.eventHistory(cursor.meta.conversationId, cursor.sinceSeq)
                    }.getOrElse { error ->
                        Logger.w("AgentNotify", "event tail failed for ${cursor.meta.conversationId}: ${error.message}")
                        continue
                    }
                    tracker.applyEvents(cursor.meta, events).forEach(controller::post)
                }
            }.onFailure { error ->
                Logger.w("AgentNotify", "monitor poll failed: ${error.message}")
            }

            syncForegroundService()
            awaitWake(if (tracker.hasActiveSessions()) ACTIVE_POLL_MS else IDLE_POLL_MS)
        }
    }

    @Synchronized
    private fun syncForegroundService() {
        val activeId = tracker.activeSessionId()
        val shouldRun = activeId != null && notifyEnabled() && isAuthenticated()
        if (shouldRun && !foregroundRunning) {
            foregroundRunning = AgentMonitorForegroundService.start(controller.context, activeId)
        } else if (!shouldRun && (foregroundRunning || !foregroundSynced)) {
            AgentMonitorForegroundService.stop(controller.context)
            foregroundRunning = false
        }
        foregroundSynced = true
    }

    private suspend fun awaitWake(timeoutMs: Long) {
        withTimeoutOrNull(timeoutMs) { wake.receive() }
    }

    private companion object {
        const val ACTIVE_POLL_MS = 2_000L
        const val IDLE_POLL_MS = 15_000L
    }
}
