package com.molagpt.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

/*
 * 配色系统：
 *   品牌主色 --primary-color #be727f / hover #a05a66；
 *   浅色面板 #ffffff / #f8f9fa / #f1f3f5，文字 #212529 / #6c757d，描边 #dee2e6；
 *   深色面板 #1a1a1a / #1e1e1e / #252525，文字 #e0e0e0 / #a0a0a0，描边 #333。
 * 改色集中在此处，其余组件均使用 MaterialTheme 令牌。
 *
 * 弹层容器（surfaceContainer*）与 inverse/errorContainer 显式写出：留空会落到 Material 默认的
 * 淡紫灰。取值沿用上面的中性灰阶，明暗层级与默认值一致。tertiary 保持原来观感（豆沙粉），
 * 一并写死，免得 Material 升级时跟着漂移。
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
    tertiary = Color(0xFF7D5260),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFD8E4),
    onTertiaryContainer = Color(0xFF31111D),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF212529),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF212529),
    surfaceVariant = Color(0xFFF1F3F5),
    onSurfaceVariant = Color(0xFF6C757D),
    outline = Color(0xFFDEE2E6),
    outlineVariant = Color(0xFFE9ECEF),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFDEE2E6),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8F9FA),
    surfaceContainer = Color(0xFFF1F3F5),
    surfaceContainerHigh = Color(0xFFEEF1F4),
    surfaceContainerHighest = Color(0xFFE9ECEF),
    inverseSurface = Color(0xFF2D2D2D),
    inverseOnSurface = Color(0xFFF1F3F5),
    inversePrimary = BrandLight,
    error = Color(0xFFF44336),
    onError = Color.White,
    errorContainer = Color(0xFFFDECEC),
    onErrorContainer = Color(0xFF8A1C1C),
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
    tertiary = Color(0xFFEFB8C8),
    onTertiary = Color(0xFF492532),
    tertiaryContainer = Color(0xFF633B48),
    onTertiaryContainer = Color(0xFFFFD8E4),
    background = Color(0xFF121212),
    onBackground = Color(0xFFE0E0E0),
    surface = Color(0xFF1A1A1A),
    onSurface = Color(0xFFE0E0E0),
    surfaceVariant = Color(0xFF252525),
    onSurfaceVariant = Color(0xFFA0A0A0),
    outline = Color(0xFF333333),
    outlineVariant = Color(0xFF2A2A2A),
    surfaceBright = Color(0xFF3A3A3A),
    surfaceDim = Color(0xFF121212),
    surfaceContainerLowest = Color(0xFF0F0F0F),
    surfaceContainerLow = Color(0xFF1A1A1A),
    surfaceContainer = Color(0xFF1E1E1E),
    surfaceContainerHigh = Color(0xFF252525),
    surfaceContainerHighest = Color(0xFF2D2D2D),
    inverseSurface = Color(0xFFE0E0E0),
    inverseOnSurface = Color(0xFF1A1A1A),
    inversePrimary = BrandHover,
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF3A0E0E),
    errorContainer = Color(0xFF3A1C1C),
    onErrorContainer = Color(0xFFFFC9C9),
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
    ) {
        // 选区与 Desktop 一致：品牌色 35%。
        val primary = MaterialTheme.colorScheme.primary
        CompositionLocalProvider(
            LocalTextSelectionColors provides TextSelectionColors(
                handleColor = primary,
                backgroundColor = primary.copy(alpha = 0.35f),
            ),
            content = content,
        )
    }
}
