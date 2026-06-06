package com.molagpt.app.feature.file

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.molagpt.app.core.model.FileInfo
import com.molagpt.app.core.model.UploadStatus

/** 输入框上方的待发送/已上传文件条（横向）。 */
@Composable
fun AttachmentStrip(files: List<FileInfo>, modifier: Modifier = Modifier) {
    if (files.isEmpty()) return
    LazyRow(modifier = modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        items(files, key = { it.id }) { file -> FileChip(file, Modifier.padding(end = 8.dp)) }
    }
}

@Composable
fun FileChip(file: FileInfo, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (file.uploadStatus == UploadStatus.UPLOADING) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        }
        Column(modifier = Modifier.padding(start = if (file.uploadStatus == UploadStatus.UPLOADING) 8.dp else 0.dp)) {
            Text(file.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (file.uploadStatus == UploadStatus.FAILED) {
                Text("上传失败", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
