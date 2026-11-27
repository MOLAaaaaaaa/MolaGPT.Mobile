package com.molagpt.app.core.render

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.collection.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import ru.noties.jlatexmath.JLatexMathDrawable

/**
 * LaTeX 公式渲染件（JLaTeXMath 原生 Canvas → Bitmap，非 WebView）。
 * 结果按 (expr,size,color) 缓存为 Bitmap；块级公式可横向滚动避免超宽。
 * 解析失败降级显示原始表达式，绝不崩溃。
 */
@Composable
fun LatexView(
    expr: String,
    display: Boolean,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val colorArgb = LocalContentColor.current.toArgb()
    val textSizePx = with(density) { (if (display) 20.dp else 16.dp).toPx() }

    val bitmap = remember(expr, display, colorArgb, textSizePx) {
        LatexBitmapCache.getOrNull(expr, textSizePx, colorArgb)
    }

    if (bitmap != null) {
        if (display) {
            Box(modifier = modifier.horizontalScroll(rememberScrollState())) {
                Image(bitmap = bitmap.asImageBitmap(), contentDescription = expr)
            }
        } else {
            Image(bitmap = bitmap.asImageBitmap(), contentDescription = expr, modifier = modifier)
        }
    } else {
        Text(text = if (display) "$$ $expr $$" else "$ $expr $", modifier = modifier)
    }
}

/**
 * 行内公式渲染用：取 (expr,size,color) 的缓存位图，失败返回 null（调用方降级为字面文本）。
 * 供 MarkdownBlockView 的 InlineTextContent 使用——行内公式与正文同字号、随文排版。
 */
internal fun latexInlineBitmap(expr: String, textSizePx: Float, colorArgb: Int): Bitmap? =
    LatexBitmapCache.getOrNull(expr, textSizePx, colorArgb)

/**
 * 把 JLaTeXMath Drawable 光栅化为 Bitmap 并按字节预算缓存。
 * 区分语法解析失败与尺寸/OOM 资源失败：后者绝不写入永久失败表。
 */
private object LatexBitmapCache {
    // 8 MiB 位图缓存预算（按实际 allocationByteCount KB 计费，而非固定条数）。
    private const val MAX_CACHE_KB = 8 * 1024

    private val cache = object : LruCache<String, Bitmap>(MAX_CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.allocationByteCount / 1024).coerceAtLeast(1)
    }
    private val syntaxFailed = LruCache<String, Boolean>(256)

    @Synchronized
    fun getOrNull(expr: String, textSizePx: Float, colorArgb: Int): Bitmap? {
        val normalized = normalizeJLatexExpression(expr)
        if (normalized.isBlank() || textSizePx <= 0f) return null
        if (syntaxFailed.get(normalized) == true) return null
        val key = "$normalized|$textSizePx|$colorArgb"
        cache.get(key)?.let { return it }

        val drawable = try {
            JLatexMathDrawable.builder(normalized)
                .textSize(textSizePx)
                .color(colorArgb)
                .build()
        } catch (_: OutOfMemoryError) {
            cache.evictAll()
            return null
        } catch (_: Throwable) {
            syntaxFailed.put(normalized, true)
            return null
        }

        val target = computeBoundedLatexSize(
            rawWidth = drawable.intrinsicWidth.coerceAtLeast(1),
            rawHeight = drawable.intrinsicHeight.coerceAtLeast(1),
        ) ?: return null

        return try {
            val bmp = createBitmap(target.width, target.height)
            val canvas = Canvas(bmp)
            if (target.scale < 1f) {
                canvas.scale(target.scale, target.scale)
            }
            drawable.setBounds(0, 0, drawable.intrinsicWidth.coerceAtLeast(1), drawable.intrinsicHeight.coerceAtLeast(1))
            drawable.draw(canvas)
            if (bmp.allocationByteCount / 1024 <= MAX_CACHE_KB / 2) {
                cache.put(key, bmp)
            }
            bmp
        } catch (_: OutOfMemoryError) {
            cache.evictAll()
            null
        } catch (_: Throwable) {
            null
        }
    }
}

internal data class BoundedLatexSize(val width: Int, val height: Int, val scale: Float)

internal const val LATEX_MAX_BITMAP_WIDTH = 4096
internal const val LATEX_MAX_BITMAP_HEIGHT = 2048
internal const val LATEX_MAX_BITMAP_PIXELS = 4_000_000L
private const val LATEX_MIN_RENDER_SCALE = 0.45f

/**
 * 约束单张公式位图尺寸与总像素，避免超宽/超大型矩阵触发 OOM 或超出硬件纹理上限。
 * 超过上限时尝试等比缩放；缩放过小难以辨认时返回 null 交由调用方降级为原文。
 */
internal fun computeBoundedLatexSize(rawWidth: Int, rawHeight: Int): BoundedLatexSize? {
    if (rawWidth <= 0 || rawHeight <= 0) return null
    val widthScale = LATEX_MAX_BITMAP_WIDTH.toFloat() / rawWidth.toFloat()
    val heightScale = LATEX_MAX_BITMAP_HEIGHT.toFloat() / rawHeight.toFloat()
    val rawPixels = rawWidth.toLong() * rawHeight.toLong()
    val pixelScale = if (rawPixels > LATEX_MAX_BITMAP_PIXELS) {
        kotlin.math.sqrt(LATEX_MAX_BITMAP_PIXELS.toDouble() / rawPixels.toDouble()).toFloat()
    } else {
        1f
    }
    val scale = minOf(1f, widthScale, heightScale, pixelScale)
    if (scale < LATEX_MIN_RENDER_SCALE) return null
    val w = (rawWidth * scale).toInt().coerceIn(1, LATEX_MAX_BITMAP_WIDTH)
    val h = (rawHeight * scale).toInt().coerceIn(1, LATEX_MAX_BITMAP_HEIGHT)
    if (w.toLong() * h.toLong() > LATEX_MAX_BITMAP_PIXELS) return null
    return BoundedLatexSize(width = w, height = h, scale = scale)
}

/**
 * JLaTeXMath does not register `equation` or the unnumbered `*` aliases. Numbering is not shown
 * in this renderer anyway, so convert those wrappers to the equivalent environments it supports.
 * The caller still owns the original expression and uses it for the text fallback on failure.
 */
internal fun normalizeJLatexExpression(expression: String): String {
    var normalized = expression.trim()
    EQUATION_ENVIRONMENT.matchEntire(normalized)?.let { match ->
        normalized = match.groupValues[1].trim()
    }
    return STARRED_DISPLAY_ENVIRONMENT.replace(normalized) { match ->
        "\\${match.groupValues[1]}{${match.groupValues[2]}}"
    }
}

private val EQUATION_ENVIRONMENT = Regex(
    pattern = """\A\s*\\begin\{equation\*?\}([\s\S]*?)\\end\{equation\*?\}\s*\z""",
)
private val STARRED_DISPLAY_ENVIRONMENT = Regex(
    pattern = """\\(begin|end)\{(align|flalign|eqnarray|gather|multline)\*\}""",
)
