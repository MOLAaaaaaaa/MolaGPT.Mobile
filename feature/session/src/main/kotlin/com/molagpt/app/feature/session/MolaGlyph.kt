package com.molagpt.app.feature.session

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * MolaGPT 吉祥物翻车鱼的线框图标，用作 MolaGPT Chat 会话的来源标识。
 *
 * 尺寸、线宽与 Material Outlined 图标对齐（24 视口、约 2 单位描边），和同一列里的其他来源图标并排不显突兀。
 * 整体逆时针转 12°，呼应 Logo 抬头的姿态。颜色由 Icon 的 tint 决定。
 */
internal val MolaGlyph: ImageVector by lazy {
    val ink = SolidColor(Color.Black)
    ImageVector.Builder(
        name = "MolaGlyph",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        // 鱼身窄而高，原尺寸下比同列的气泡、图片图标显小，放大一点并略下移居中。
        addGroup(
            rotate = -12f,
            pivotX = 11.5f,
            pivotY = 12f,
            scaleX = 1.06f,
            scaleY = 1.06f,
            translationX = 0.5f,
            translationY = 0.5f,
        )
        // 轮廓：背鳍、波浪形截尾、臀鳍、圆钝的头部。
        addPath(
            pathData = addPathNodes(
                "M11.5 6.1Q12.8 3.6 14.6 1.6L15.6 8C16.6 8.4 17.4 9 17.6 9.8Q18.6 10.9 17.6 12" +
                    "Q18.6 13.1 17.6 14.2C17.4 15 16.6 15.6 15.6 16L14.6 22.4Q12.8 20.4 11.5 17.9" +
                    "C7.6 18 4.5 15.4 4.5 12C4.5 8.6 7.6 6 11.5 6.1Z",
            ),
            stroke = ink,
            strokeLineWidth = 1.9f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
        // 眼睛。
        addPath(
            pathData = addPathNodes("M8.2 9.15a1.25 1.25 0 1 1 0 2.5a1.25 1.25 0 1 1 0 -2.5Z"),
            fill = ink,
        )
        // 嘴角的笑。
        addPath(
            pathData = addPathNodes("M5.6 13.6q1 0.7 2 0.2"),
            stroke = ink,
            strokeLineWidth = 1.9f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
        clearGroup()
    }.build()
}
