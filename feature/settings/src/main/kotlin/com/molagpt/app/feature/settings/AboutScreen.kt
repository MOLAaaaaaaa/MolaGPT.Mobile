package com.molagpt.app.feature.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.molagpt.app.core.render.MolaLogo
import kotlinx.coroutines.launch

private const val GitHubUrl = "https://github.com/MOLAaaaaaaa/MolaGPT.Mobile"

// GitHub 官方 mark 轮廓（SVG path data，viewBox 0 0 24 24）
private const val GitHubMarkPath =
    "M12 .297c-6.63 0-12 5.373-12 12 0 5.303 3.438 9.8 8.205 11.385.6.113.82-.258.82-.577 0-.285-.01-1.04-.015-2.04-3.338.724-4.042-1.61-4.042-1.61C4.422 18.07 3.633 17.7 3.633 17.7c-1.087-.744.084-.729.084-.729 1.205.084 1.838 1.236 1.838 1.236 1.07 1.835 2.809 1.305 3.495.998.108-.776.417-1.305.76-1.605-2.665-.3-5.466-1.332-5.466-5.93 0-1.31.465-2.38 1.235-3.22-.135-.303-.54-1.523.105-3.176 0 0 1.005-.322 3.3 1.23.96-.267 1.98-.399 3-.405 1.02.006 2.04.138 3 .405 2.28-1.552 3.285-1.23 3.285-1.23.645 1.653.24 2.873.12 3.176.765.84 1.23 1.91 1.23 3.22 0 4.61-2.805 5.625-5.475 5.92.42.36.81 1.096.81 2.22 0 1.606-.015 2.896-.015 3.286 0 .315.21.69.825.57C20.565 22.092 24 17.592 24 12.297c0-6.627-5.373-12-12-12"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    versionName: String,
    buildTime: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var checking by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }

    updateInfo?.let { info ->
        UpdateAvailableDialog(info = info, onDismiss = { updateInfo = null })
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        topBar = {
            TopAppBar(
                title = { Text("关于") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ProductHeader(versionName = versionName, buildTime = buildTime)

            ActionButtons(
                checking = checking,
                onCheckUpdate = {
                    scope.launch {
                        checking = true
                        val info = checkForUpdate(versionName)
                        checking = false
                        if (info != null) updateInfo = info
                        else snackbarHostState.showSnackbar("已是最新版本")
                    }
                },
                onOpenGitHub = {
                    runCatching { uriHandler.openUri(GitHubUrl) }
                        .onFailure {
                            scope.launch { snackbarHostState.showSnackbar("无法打开 GitHub 主页") }
                        }
                },
            )

            SectionLabel("开源项目")
            Text(
                text = "MolaGPT Android 构建于以下优秀的开源项目之上，在此致谢。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
            DependencyCard(dependencies = Dependencies)

            Text(
                text = "「远程 Agent 控制」的桥接 / 中继架构与 CLI 控制协议设计参考了开源项目 Remodex（Apache-2.0，github.com/Emanuele-web04/remodex），本实现为独立编写，未直接复制其源码，在此一并致谢。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp).padding(bottom = 8.dp),
            )
            Text(
                text = "「抹茶画图」工作台由 DisaWdcba 贡献，产品思路参考开源项目 SimpleAIPainting（github.com/DisaWdcba/SimpleAIPainting），在此致谢。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp).padding(bottom = 12.dp),
            )

            SectionLabel("开放源代码协议")
            LicenseExpander()

            Text(
                text = "MolaGPT Android by MOLAaaaaaaa",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 20.dp),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ProductHeader(versionName: String, buildTime: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MolaLogo()
        Text(
            text = "MolaGPT Android",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 18.dp),
        )
        Text(
            text = "版本 $versionName · 构建 $buildTime",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
            textAlign = TextAlign.Center,
        )
        Text(
            text = "自由、开放的 MolaGPT 及其他 LLM API 的移动端实现。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ActionButtons(checking: Boolean, onCheckUpdate: () -> Unit, onOpenGitHub: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 22.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Button(
            onClick = onCheckUpdate,
            enabled = !checking,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(999.dp),
        ) {
            if (checking) CircularProgressIndicator(
                modifier = Modifier.size(17.dp),
                strokeWidth = 2.dp,
                color = LocalContentColor.current,
            ) else Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = null,
                modifier = Modifier.size(17.dp),
            )
            Text("检查更新", modifier = Modifier.padding(start = 8.dp))
        }
        OutlinedButton(
            onClick = onOpenGitHub,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(999.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
        ) {
            GitHubGlyph(modifier = Modifier.size(17.dp))
            Text("GitHub", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 6.dp),
    )
}

@Composable
private fun DependencyCard(dependencies: List<DependencyNotice>) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 22.dp),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f),
        tonalElevation = 0.dp,
    ) {
        Column {
            dependencies.forEachIndexed { index, item ->
                DependencyRow(item)
                if (index != dependencies.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.58f),
                    )
                }
            }
        }
    }
}

@Composable
private fun DependencyRow(item: DependencyNotice) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(item.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                item.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        Surface(
            shape = RoundedCornerShape(9.dp),
            color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Text(
                item.license,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun LicenseExpander() {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LicenseGlyph(modifier = Modifier.size(19.dp))
                Text(
                    text = "查看完整开源许可证",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).padding(start = 10.dp),
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp).rotate(if (expanded) 90f else 270f),
                )
            }
            if (expanded) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.68f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Text(
                        text = LicenseNotice,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(14.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun GitHubGlyph(modifier: Modifier = Modifier) {
    val color = LocalContentColor.current
    val mark = remember { PathParser().parsePathString(GitHubMarkPath).toPath() }
    Canvas(modifier = modifier) {
        val s = size.minDimension / 24f
        withTransform({
            translate((size.width - 24f * s) / 2f, (size.height - 24f * s) / 2f)
            scale(s, s, pivot = Offset.Zero)
        }) {
            drawPath(mark, color)
        }
    }
}

@Composable
private fun LicenseGlyph(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier) {
        val stroke = 2.dp.toPx()
        drawRoundRect(color, Offset(size.width * 0.18f, size.height * 0.08f), Size(size.width * 0.64f, size.height * 0.84f), androidx.compose.ui.geometry.CornerRadius(4.dp.toPx(), 4.dp.toPx()), style = Stroke(stroke))
        drawLine(color, Offset(size.width * 0.34f, size.height * 0.36f), Offset(size.width * 0.66f, size.height * 0.36f), stroke, StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.34f, size.height * 0.54f), Offset(size.width * 0.66f, size.height * 0.54f), stroke, StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.34f, size.height * 0.72f), Offset(size.width * 0.55f, size.height * 0.72f), stroke, StrokeCap.Round)
    }
}

private data class DependencyNotice(
    val name: String,
    val description: String,
    val license: String,
)

private val Dependencies = listOf(
    DependencyNotice("Jetpack Compose", "声明式 Android UI 框架", "Apache-2.0"),
    DependencyNotice("Kotlin Coroutines", "异步任务与流式响应处理", "Apache-2.0"),
    DependencyNotice("Room", "本地 SQLite 数据访问层", "Apache-2.0"),
    DependencyNotice("DataStore", "应用偏好与设置存储", "Apache-2.0"),
    DependencyNotice("Ktor + OkHttp", "网络请求、SSE 与代理接口通信", "Apache-2.0"),
    DependencyNotice("kotlinx.serialization", "JSON 序列化与同步数据解析", "Apache-2.0"),
    DependencyNotice("Coil", "图片加载与缓存", "Apache-2.0"),
    DependencyNotice("Telephoto", "图片缩放与手势预览", "Apache-2.0"),
    DependencyNotice("commonmark", "Markdown 解析与富文本渲染", "BSD-2-Clause"),
    DependencyNotice("JLaTeXMath", "数学公式原生 Canvas 渲染", "GPL-2.0"),
)

private const val LicenseNotice = """
MolaGPT Android 使用了以下开源组件，并依据其许可证要求保留相应的版权与许可声明。

================================================================
Apache License 2.0
================================================================
适用组件：
  - Jetpack Compose
  - Kotlin Coroutines
  - Room
  - DataStore
  - Ktor
  - OkHttp
  - kotlinx.serialization
  - Coil
  - Telephoto

Licensed under the Apache License, Version 2.0 (the "License"); you may not use
these files except in compliance with the License. You may obtain a copy of the
License at: http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed
under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
CONDITIONS OF ANY KIND, either express or implied. See the License for the
specific language governing permissions and limitations under the License.

================================================================
BSD 2-Clause License
================================================================
适用组件：
  - commonmark (commonmark-java)

Copyright (c) 2015, Robin Stocker
All rights reserved.

Redistribution and use in source and binary forms, with or without modification,
are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.
2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
ANY EXPRESS OR IMPLIED WARRANTIES ARE DISCLAIMED.

================================================================
GNU General Public License, version 2
================================================================
适用组件：
  - JLaTeXMath / jlatexmath-android (ru.noties)

This program is free software; you can redistribute it and/or modify it under
the terms of the GNU General Public License as published by the Free Software
Foundation; either version 2 of the License, or (at your option) any later
version. See https://www.gnu.org/licenses/old-licenses/gpl-2.0.html for details.
"""
