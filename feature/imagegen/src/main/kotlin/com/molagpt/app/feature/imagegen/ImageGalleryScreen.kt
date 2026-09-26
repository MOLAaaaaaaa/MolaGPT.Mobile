package com.molagpt.app.feature.imagegen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.molagpt.app.core.storage.GalleryImage
import com.molagpt.app.core.storage.ImageTaskRepository
import com.molagpt.app.feature.file.ImagePreviewAction
import com.molagpt.app.feature.file.ImagePreviewHost
import com.molagpt.app.feature.file.PreviewableImage
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class ImageGalleryViewModel(private val repository: ImageTaskRepository) : ViewModel() {
    internal val images: StateFlow<List<GalleryImage>?> = repository.observeGallery()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    internal fun url(src: String): String = repository.files.url(src)
}

/** 所有任务里生成成功的图，新的在前。点开预览可左右翻，「查看任务」回到它所在的那一轮。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageGalleryScreen(
    viewModel: ImageGalleryViewModel,
    onBack: () -> Unit,
    onOpenTask: (taskId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val images by viewModel.images.collectAsStateWithLifecycle()
    val list = images.orEmpty()
    val urls = remember(list) { list.map { viewModel.url(it.output.src) } }
    val taskByUrl = remember(list, urls) { urls.zip(list.map { it.taskId }).toMap() }

    ImagePreviewHost(
        modifier = modifier,
        extraAction = ImagePreviewAction("查看任务") { url -> taskByUrl[url]?.let(onOpenTask) },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("画廊", style = MaterialTheme.typography.titleMedium)
                            if (list.isNotEmpty()) {
                                Text(
                                    "${list.size} 张",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                    },
                )
            },
        ) { inner ->
            if (images != null && list.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) {
                    Text(
                        "暂无图片",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize().padding(inner),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = bottom + 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(urls.size, key = { i -> "${list[i].versionId}:$i" }) { i ->
                        val url = urls[i]
                        PreviewableImage(
                            url = url,
                            gallery = urls,
                            modifier = Modifier
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(6.dp)),
                        )
                    }
                }
            }
        }
    }
}
