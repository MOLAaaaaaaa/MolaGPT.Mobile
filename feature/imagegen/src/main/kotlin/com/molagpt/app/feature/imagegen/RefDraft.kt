package com.molagpt.app.feature.imagegen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import java.io.ByteArrayOutputStream
import java.io.File

internal enum class MaskTool { Brush, Eraser }

/**
 * 输入区里的一张参考图（用户选的，或「以此为底图」取的结果），可以涂抹要修改的区域。
 *
 * 底图统一缩到长边 [MAX_EDGE] 以内再编码成 PNG 发出去，蒙版在同一尺寸上画，保证两者对齐。
 * [sourceSrc] 非空表示取自任务里的结果图：没涂抹时直接引用原文件，不再复制一份。
 */
@Stable
internal class RefDraft private constructor(
    val id: Long,
    val editBitmap: Bitmap,
    val sourceSrc: String?,
) {
    val previewBitmap: Bitmap = editBitmap.scaleDown(PREVIEW_EDGE)

    /** 白色 = 要改的区域。编辑器里叠一层半透明红色画出来。 */
    val maskBitmap: Bitmap = Bitmap.createBitmap(editBitmap.width, editBitmap.height, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.TRANSPARENT)
    }

    /** 每次落笔都变，驱动画布重绘。 */
    var maskVersion by mutableIntStateOf(0)
        private set

    var hasMask by mutableStateOf(false)
        private set

    fun drawMaskAt(position: Offset, viewport: IntSize, tool: MaskTool, brushSize: Float) {
        if (viewport.width <= 0 || viewport.height <= 0) return
        val dst = fittedRect(editBitmap.width, editBitmap.height, Size(viewport.width.toFloat(), viewport.height.toFloat()))
        if (!dst.contains(position.x, position.y)) return
        val x = ((position.x - dst.left) / dst.width() * editBitmap.width).coerceIn(0f, editBitmap.width.toFloat())
        val y = ((position.y - dst.top) / dst.height() * editBitmap.height).coerceIn(0f, editBitmap.height.toFloat())
        val scale = editBitmap.width / dst.width()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (tool == MaskTool.Brush) Color.WHITE else Color.TRANSPARENT
            style = Paint.Style.FILL
            if (tool == MaskTool.Eraser) xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        Canvas(maskBitmap).drawCircle(x, y, brushSize * scale / 2f, paint)
        maskVersion++
        // 落笔必然有涂抹；擦除才需要逐像素确认是不是擦干净了，放到松手时再算。
        if (tool == MaskTool.Brush) hasMask = true
    }

    fun onStrokeEnd() {
        hasMask = bitmapHasAlpha(maskBitmap)
    }

    fun clearMask() {
        maskBitmap.eraseColor(Color.TRANSPARENT)
        maskVersion++
        hasMask = false
    }

    /** 发送前的整理。在后台线程调用：PNG 编码和逐像素检查都不便宜。 */
    fun toPending(): ImageTaskManager.PendingRef {
        val masked = bitmapHasAlpha(maskBitmap)
        if (!masked && sourceSrc != null) return ImageTaskManager.PendingRef.Existing(sourceSrc)
        return ImageTaskManager.PendingRef.Fresh(
            png = editBitmap.toPngBytes(),
            maskPng = if (masked) buildAlphaMaskPng() else null,
            overlayPng = if (masked) buildOverlay().toPngBytes() else null,
        )
    }

    /** images/edits 的约定：透明处可改，不透明处保持原样。 */
    private fun buildAlphaMaskPng(): ByteArray {
        val alpha = Bitmap.createBitmap(maskBitmap.width, maskBitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(alpha)
        canvas.drawRect(0f, 0f, alpha.width.toFloat(), alpha.height.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        canvas.drawBitmap(maskBitmap, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
        return alpha.toPngBytes()
    }

    /** 给只认图片的对话式接口：底图上叠红色涂抹，让模型看出要改哪里。 */
    private fun buildOverlay(): Bitmap {
        val next = Bitmap.createBitmap(editBitmap.width, editBitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(next)
        canvas.drawBitmap(editBitmap, 0f, 0f, null)
        canvas.drawBitmap(maskBitmap, 0f, 0f, maskTintPaint())
        return next
    }

    companion object {
        private const val MAX_EDGE = 2048
        private const val PREVIEW_EDGE = 420

        fun fromUri(context: Context, uri: Uri): RefDraft? = runCatching {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            fromBytes(bytes, sourceSrc = null)
        }.getOrNull()

        fun fromFile(file: File, sourceSrc: String): RefDraft? = runCatching {
            fromBytes(file.readBytes(), sourceSrc)
        }.getOrNull()

        private fun fromBytes(bytes: ByteArray, sourceSrc: String?): RefDraft? {
            val bitmap = decodeSampled(bytes, MAX_EDGE) ?: return null
            return RefDraft(System.nanoTime(), bitmap.scaleDown(MAX_EDGE), sourceSrc)
        }
    }
}

/** 先按 2 的幂降采样解码，避免几千万像素的照片整张进内存。 */
private fun decodeSampled(bytes: ByteArray, maxEdge: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}

internal fun Bitmap.scaleDown(maxEdge: Int): Bitmap {
    val edge = maxOf(width, height)
    if (edge <= maxEdge) return this
    val scale = maxEdge.toFloat() / edge
    val matrix = Matrix().apply { postScale(scale, scale) }
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}

internal fun Bitmap.toPngBytes(): ByteArray = ByteArrayOutputStream().use { out ->
    compress(Bitmap.CompressFormat.PNG, 100, out)
    out.toByteArray()
}

private fun bitmapHasAlpha(bitmap: Bitmap): Boolean {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    return pixels.any { Color.alpha(it) > 0 }
}

internal fun fittedRect(bitmapWidth: Int, bitmapHeight: Int, viewport: Size): RectF {
    val scale = minOf(viewport.width / bitmapWidth, viewport.height / bitmapHeight)
    val width = bitmapWidth * scale
    val height = bitmapHeight * scale
    val left = (viewport.width - width) / 2f
    val top = (viewport.height - height) / 2f
    return RectF(left, top, left + width, top + height)
}

/** 把白色蒙版染成半透明红色。 */
internal fun maskTintPaint(): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    colorFilter = android.graphics.PorterDuffColorFilter(Color.argb(120, 244, 63, 94), PorterDuff.Mode.SRC_IN)
}
