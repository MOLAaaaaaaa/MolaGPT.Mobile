package com.molagpt.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/*
 * 配色系统：
 *   品牌主色 --primary-color #be727f / hover #a05a66；
 *   浅色面板 #ffffff / #f8f9fa / #f1f3f5，文字 #212529 / #6c757d，描边 #dee2e6；
 *   深色面板 #1a1a1a / #1e1e1e / #252525，文字 #e0e0e0 / #a0a0a0，描边 #333。
 * 改色集中在此处，其余组件均使用 MaterialTheme 令牌。
 */

// 品牌
private val Brand = Color(0xFFBE727F)
private val BrandHover = Color(0xFFA05A66)
private val BrandLight = Color(0xFFCF8996)

private val LightColors = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF7E6E9),   // 淡粉容器（选中/徽章）
    onPrimaryContainer = Color(0xFF5A2A33),
    secondary = BrandHover,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF1E1E4),
    onSecondaryContainer = Color(0xFF4A2128),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF212529),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF212529),
    surfaceVariant = Color(0xFFF1F3F5),
    onSurfaceVariant = Color(0xFF6C757D),
    outline = Color(0xFFDEE2E6),
    outlineVariant = Color(0xFFE9ECEF),
    error = Color(0xFFF44336),
    onError = Color.White,
    surfaceTint = Brand,
)

private val DarkColors = darkColorScheme(
    primary = BrandLight,
    onPrimary = Color(0xFF3A1F25),
    primaryContainer = Color(0xFF5A2A33),
    onPrimaryContainer = Color(0xFFF7D9DE),
    secondary = BrandLight,
    onSecondary = Color(0xFF3A1F25),
    secondaryContainer = Color(0xFF4A2128),
    onSecondaryContainer = Color(0xFFF1E1E4),
    background = Color(0xFF121212),
    onBackground = Color(0xFFE0E0E0),
    surface = Color(0xFF1A1A1A),
    onSurface = Color(0xFFE0E0E0),
    surfaceVariant = Color(0xFF252525),
    onSurfaceVariant = Color(0xFFA0A0A0),
    outline = Color(0xFF333333),
    outlineVariant = Color(0xFF2A2A2A),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF3A0E0E),
    surfaceTint = BrandLight,
)

@Composable
fun MolaTheme(
    themeMode: String = "auto",
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content,
    )
}
