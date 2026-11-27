package com.molagpt.app

import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.molagpt.app.core.common.Logger

/** Main-thread state shared by a service and the application component that requests it. */
internal class ForegroundServiceRequest<T : Any>(private val type: Class<out Service>) {
    private var desired: T? = null
    private var pending: T? = null
    private var service: Service? = null
    private var updateNotification: ((T) -> Unit)? = null
    private var shown: T? = null
    private var startRejected = false

    fun update(context: Context, value: T?, appForeground: Boolean) {
        if (desired != value || !appForeground) startRejected = false
        desired = value
        if (value == null) {
            // A pending start must first satisfy Android's startForeground requirement.
            if (pending == null && service != null) stop(context)
            return
        }
        val current = service
        if (current != null) {
            if (shown != value) promote(current, value)
        } else if (pending == null && appForeground && !startRejected) {
            try {
                pending = value
                if (ContextCompat.startForegroundService(context, Intent(context, type)) == null) {
                    pending = null
                    startRejected = true
                    Logger.w(type.simpleName, "前台服务未启动")
                }
            } catch (error: RuntimeException) {
                pending = null
                startRejected = true
                Logger.w(type.simpleName, "无法启动前台服务", error)
            }
        }
    }

    fun started(instance: Service, render: (T) -> Unit) {
        val value = desired ?: pending
        pending = null
        if (value == null) {
            instance.stopSelf()
            return
        }
        service = instance
        updateNotification = render
        promote(instance, value)
        if (desired == null && service === instance) stop(instance)
    }

    fun destroyed(instance: Service) {
        if (service !== instance) return
        service = null
        updateNotification = null
        shown = null
    }

    fun timedOut(instance: Service) {
        Logger.w(type.simpleName, "dataSync 前台服务达到系统时限")
        destroyed(instance)
        instance.stopSelf()
    }

    private fun promote(instance: Service, value: T) {
        try {
            updateNotification?.invoke(value)
            shown = value
        } catch (error: RuntimeException) {
            Logger.w(type.simpleName, "无法进入前台服务状态", error)
            destroyed(instance)
            startRejected = true
            instance.stopSelf()
        }
    }

    private fun stop(context: Context) {
        try {
            context.stopService(Intent(context, type))
            service?.let(::destroyed)
        } catch (error: RuntimeException) {
            Logger.w(type.simpleName, "无法停止前台服务", error)
        }
    }
}
