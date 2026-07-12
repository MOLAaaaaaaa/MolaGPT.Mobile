package com.molagpt.app.core.render

import android.util.Base64

/**
 * 把图片来源统一成 Coil3 能稳定加载的 model：
 * - `data:[mime];base64,xxx` → 解码为 ByteArray（Coil3 对 ByteArray 直出稳定，超大 data URI 字符串常渲染失败）。
 * - file:// / http(s) / 其它 → 原样返回，交给 Coil。
 *
 * 全项目图片渲染统一入口，`RemoteImage` 与图像工作台等均复用，避免各处重复实现。
 */
fun decodeImageModel(url: String): Any {
    if (url.isBlank()) return url
    if (!url.startsWith("data:", ignoreCase = true)) return url
    val comma = url.indexOf(',')
    if (comma < 0 || !url.substring(0, comma).contains("base64", ignoreCase = true)) return url
    return runCatching {
        Base64.decode(url.substring(comma + 1), Base64.DEFAULT)
    }.getOrDefault(url)
}
