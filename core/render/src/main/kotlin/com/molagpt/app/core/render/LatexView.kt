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

/** 把 JLaTeXMath Drawable 光栅化为 Bitmap 并缓存（重渲染昂贵）。 */
private object LatexBitmapCache {
    private val cache = LruCache<String, Bitmap>(128)
    private val failed = LruCache<String, Boolean>(256)

    @Synchronized
    fun getOrNull(expr: String, textSizePx: Float, colorArgb: Int): Bitmap? {
        val normalized = normalizeJLatexExpression(expr)
        if (normalized.isBlank() || textSizePx <= 0f) return null
        if (failed.get(normalized) == true) return null
        val key = "$normalized|$textSizePx|$colorArgb"
        cache.get(key)?.let { return it }
        return runCatching {
            val drawable = JLatexMathDrawable.builder(normalized)
                .textSize(textSizePx)
                .color(colorArgb)
                .build()
            val w = drawable.intrinsicWidth.coerceAtLeast(1)
            val h = drawable.intrinsicHeight.coerceAtLeast(1)
            val bmp = createBitmap(w, h)
            drawable.setBounds(0, 0, w, h)
            drawable.draw(Canvas(bmp))
            cache.put(key, bmp)
            bmp
        }.getOrElse {
            failed.put(normalized, true)
            null
        }
    }
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
