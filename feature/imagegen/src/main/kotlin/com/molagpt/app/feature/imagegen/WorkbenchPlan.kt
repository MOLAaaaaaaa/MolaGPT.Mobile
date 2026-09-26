package com.molagpt.app.feature.imagegen

import android.content.Context
import com.molagpt.app.core.storage.ImageMode
import com.molagpt.app.core.storage.ImageParams
import com.molagpt.app.core.storage.ImageRunKind
import kotlin.math.roundToInt
import kotlinx.serialization.json.Json

/** 画幅：值是发给接口的尺寸，标签是比例。 */
internal val SizeOptions = listOf(
    "1024x1024" to "1:1",
    "1024x1536" to "2:3",
    "1536x1024" to "3:2",
    "2048x2048" to "1:1 · 2K",
    "1152x2048" to "9:16",
    "2048x1152" to "16:9",
    "2160x3840" to "9:16 · 4K",
    "3840x2160" to "16:9 · 4K",
)

internal val CountOptions = listOf(1, 2, 4)
internal val QualityOptions = listOf("auto" to "自动", "high" to "高", "medium" to "中", "low" to "低")
internal val FormatOptions = listOf("png" to "PNG", "jpeg" to "JPEG", "webp" to "WebP")
internal val BackgroundOptions = listOf("auto" to "自动", "transparent" to "透明", "opaque" to "不透明")
internal val ModerationOptions = listOf("auto" to "标准", "low" to "宽松")
internal val EffortOptions = listOf("low" to "低", "medium" to "中", "high" to "高")

private val SIZE_RE = Regex("""(\d+)\s*[xX×*]\s*(\d+)""")

internal fun parseSize(size: String): Pair<Int, Int>? {
    val m = SIZE_RE.find(size) ?: return null
    val w = m.groupValues[1].toIntOrNull() ?: return null
    val h = m.groupValues[2].toIntOrNull() ?: return null
    return if (w > 0 && h > 0) w to h else null
}

internal fun maxEdge(size: String): Int = parseSize(size)?.let { maxOf(it.first, it.second) } ?: 0

/** 接口要求边长是 16 的倍数，且不超过 3840。 */
internal fun normalizeSize(raw: String): String? {
    val (w, h) = parseSize(raw) ?: return null
    fun snap(v: Int) = ((v / 16f).roundToInt() * 16).coerceIn(256, 3840)
    return "${snap(w)}x${snap(h)}"
}

internal fun sizeChipLabel(size: String): String =
    SizeOptions.firstOrNull { it.first == size }?.second?.substringBefore(" ·")
        ?: parseSize(size)?.let { (w, h) -> "$w×$h" }
        ?: size

/**
 * 这一轮拿什么当输入。规则与桌面端一致：
 * 仅生成的模型永远出新图；给了图就编辑它；对话模式没给图时续改上一张；其余生成新图。
 */
internal fun planKind(
    editable: Boolean,
    mode: ImageMode,
    refCount: Int,
    perImage: Boolean,
    hasHead: Boolean,
): ImageRunKind = when {
    !editable -> ImageRunKind.NEW
    refCount == 1 -> ImageRunKind.BASE
    refCount >= 2 -> if (perImage) ImageRunKind.PER_IMAGE else ImageRunKind.REFS
    mode == ImageMode.CHAT && hasHead -> ImageRunKind.CHAIN
    else -> ImageRunKind.NEW
}

/** 张数只对「生成新图」有意义；对话模式下一次一张，方便接着改。 */
internal fun countApplies(editable: Boolean, mode: ImageMode): Boolean = !editable || mode == ImageMode.GENERATE

internal fun planLabel(kind: ImageRunKind, count: Int, refCount: Int): String = when (kind) {
    ImageRunKind.NEW -> if (count > 1) "生成新图 · $count 张" else "生成新图"
    ImageRunKind.CHAIN -> "续改上一张"
    ImageRunKind.BASE -> "编辑底图"
    ImageRunKind.REFS -> "参考 $refCount 张图"
    ImageRunKind.PER_IMAGE -> "逐张编辑 · $refCount 张"
}

internal fun planPlaceholder(kind: ImageRunKind): String = when (kind) {
    ImageRunKind.NEW -> "描述画面…"
    ImageRunKind.CHAIN, ImageRunKind.BASE, ImageRunKind.PER_IMAGE -> "描述修改内容…"
    ImageRunKind.REFS -> "描述组合效果…"
}

internal fun kindTitle(kind: ImageRunKind): String = when (kind) {
    ImageRunKind.NEW -> "生成"
    ImageRunKind.CHAIN -> "续改"
    ImageRunKind.BASE -> "编辑"
    ImageRunKind.REFS -> "参考图"
    ImageRunKind.PER_IMAGE -> "逐张编辑"
}

/** 输入区的默认值和上次打开的任务。跨任务共用，重开 App 后还在。 */
internal class WorkbenchPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("image_workbench", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    var lastTaskId: String?
        get() = prefs.getString("last_task", null)
        set(value) = prefs.edit().putString("last_task", value).apply()

    var providerId: String?
        get() = prefs.getString("provider", null)
        set(value) = prefs.edit().putString("provider", value).apply()

    var modelId: String?
        get() = prefs.getString("model", null)
        set(value) = prefs.edit().putString("model", value).apply()

    var size: String
        get() = prefs.getString("size", null) ?: "1024x1024"
        set(value) = prefs.edit().putString("size", value).apply()

    var count: Int
        get() = prefs.getInt("count", 1).takeIf { it in CountOptions } ?: 1
        set(value) = prefs.edit().putInt("count", value).apply()

    var params: ImageParams
        get() = prefs.getString("params", null)
            ?.let { runCatching { json.decodeFromString(ImageParams.serializer(), it) }.getOrNull() }
            ?: ImageParams()
        set(value) = prefs.edit().putString("params", json.encodeToString(ImageParams.serializer(), value)).apply()
}
