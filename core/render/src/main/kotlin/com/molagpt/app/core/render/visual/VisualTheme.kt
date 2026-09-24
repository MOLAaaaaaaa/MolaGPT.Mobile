package com.molagpt.app.core.render.visual

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.log10

/** 内嵌组件共用的配色，全部从当前 Material 主题推出，切换明暗时跟着变。 */
@Immutable
class VisualPalette(
    val dark: Boolean,
    private val seriesColors: List<Color>,
    val text: Color,
    val muted: Color,
    val grid: Color,
    val axis: Color,
    val border: Color,
    val surface: Color,
    val elevated: Color,
    val good: Color,
    val bad: Color,
    val accent: Color,
) {
    fun series(index: Int): Color = seriesColors[Math.floorMod(index, seriesColors.size)]

    /** 网页里 `--mola-chart-1..6` 用的同一组颜色。 */
    val chartColors: List<Color> get() = seriesColors
}

// 与桌面端 Tokens 的 Color.Chart.1..6 同值：同一张图在两端颜色一致。
private val LIGHT_SERIES = listOf(
    Color(0xFFBE727F), Color(0xFF3B82A0), Color(0xFFC9832F),
    Color(0xFF4E9A6B), Color(0xFF7A6AB8), Color(0xFF5B6B7A),
)
private val DARK_SERIES = listOf(
    Color(0xFFE09AA6), Color(0xFF6BB3D1), Color(0xFFE7AE6A),
    Color(0xFF7CC49A), Color(0xFFA596E0), Color(0xFF9AA8B6),
)

@Composable
fun rememberVisualPalette(): VisualPalette {
    val cs = MaterialTheme.colorScheme
    return remember(cs) {
        val dark = cs.background.luminance() < 0.5f
        VisualPalette(
            dark = dark,
            seriesColors = if (dark) DARK_SERIES else LIGHT_SERIES,
            text = cs.onSurface,
            muted = cs.onSurfaceVariant,
            grid = cs.outlineVariant,
            axis = cs.onSurfaceVariant.copy(alpha = 0.55f),
            border = cs.outlineVariant,
            surface = cs.surface,
            elevated = if (dark) Color(0xFF2A2A2A) else Color.White,
            good = if (dark) Color(0xFF4CAF50) else Color(0xFF2E7D32),
            bad = cs.error,
            accent = cs.primary,
        )
    }
}

internal object VisualFormat {
    /** 坐标轴刻度：按步长定小数位，同一根轴上位数一致。 */
    fun tick(value: Double, step: Double): String {
        if (value.isNaN() || value.isInfinite()) return "–"
        if (abs(value) < step * 1e-6) return "0"
        val a = abs(value)
        if (a >= 1e6 || (a < 1e-3 && a > 0)) return scientific(value, 2)
        val decimals = ceil(-log10(step)).toInt().coerceIn(0, 6)
        return String.format(Locale.ROOT, "%.${decimals}f", value)
    }

    /** 读数：最多三位小数，去掉末尾的 0。 */
    fun readout(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return "–"
        val a = abs(value)
        if (a >= 1e5 || (a < 1e-3 && a > 0)) return scientific(value, 3)
        return trim(String.format(Locale.ROOT, "%.3f", value))
    }

    fun percent(ratio: Double): String = trim(String.format(Locale.ROOT, "%.1f", ratio * 100)) + "%"

    private fun scientific(value: Double, digits: Int): String {
        val text = String.format(Locale.ROOT, "%.${digits}e", value)
        val mantissa = trim(text.substringBefore('e'))
        val exponent = text.substringAfter('e').toIntOrNull() ?: 0
        return mantissa + "e" + (if (exponent >= 0) "+" else "-") + abs(exponent)
    }

    private fun trim(text: String): String {
        if ('.' !in text) return text
        val trimmed = text.trimEnd('0').trimEnd('.')
        return if (trimmed == "-0") "0" else trimmed
    }
}
