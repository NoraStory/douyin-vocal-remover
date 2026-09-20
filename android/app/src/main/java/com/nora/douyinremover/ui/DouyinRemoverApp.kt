package com.nora.douyinremover.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nora.douyinremover.douyin.ResolvedMediaItem
import com.nora.douyinremover.settings.AudioOutputFormat
import com.nora.douyinremover.settings.ProcessingSettings

@Composable
fun DouyinRemoverApp(viewModel: AppViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val reducedMotion = androidx.compose.ui.platform.LocalView.current.isInTouchMode

    DouyinRemoverContent(
        uiState = uiState,
        settings = settings,
        onInputChange = viewModel::updateInput,
        onResolve = viewModel::resolve,
        onSelectItem = viewModel::selectItem,
        onProcess = viewModel::processSelected,
        onUpdateSettings = viewModel::updateSettings,
        reducedMotion = !reducedMotion
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DouyinRemoverContent(
    uiState: AppUiState,
    settings: ProcessingSettings,
    onInputChange: (String) -> Unit,
    onResolve: () -> Unit,
    onSelectItem: (ResolvedMediaItem) -> Unit,
    onProcess: () -> Unit,
    onUpdateSettings: (ProcessingSettings) -> Unit,
    reducedMotion: Boolean
) {
    var showOptions by remember { mutableStateOf(false) }
    var showOutputSettings by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)
                    )
                )
            )
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = 24.dp,
                bottom = 120.dp
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        Text(
                            text = "抖音去人声",
                            style = MaterialTheme.typography.headlineLarge
                        )
                        Text(
                            text = "下载视频，本地分离人声并导出音乐",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.62f)
                        )
                    }
                    IconButton(onClick = { showOptions = true }) {
                        Icon(Icons.Outlined.Tune, contentDescription = "处理选项")
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = uiState.input,
                    onValueChange = onInputChange,
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Outlined.ContentPaste, contentDescription = null) },
                    trailingIcon = {
                        if (uiState.input.isNotBlank()) {
                            IconButton(onClick = { onInputChange("") }) {
                                Icon(Icons.Outlined.Clear, contentDescription = "清空")
                            }
                        }
                    },
                    placeholder = { Text("抖音链接、视频 ID 或用户 ID") },
                    singleLine = true
                )
            }

            item {
                Button(
                    onClick = onResolve,
                    enabled = !uiState.isResolving,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (uiState.isResolving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.Outlined.CloudDownload, contentDescription = null)
                    }
                    Spacer(Modifier.size(8.dp))
                    Text(if (uiState.isResolving) "解析中" else "解析")
                }
            }

            item {
                AnimatedContent(
                    targetState = uiState.error,
                    label = "error"
                ) { error ->
                    if (error != null) {
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            items(uiState.resolvedItems) { item ->
                ResolvedItemRow(
                    item = item,
                    selected = item == uiState.selectedItem,
                    onClick = { onSelectItem(item) }
                )
            }

            if (uiState.resolvedItems.isNotEmpty()) {
                item {
                    Button(
                        onClick = onProcess,
                        enabled = uiState.selectedItem != null && !uiState.isProcessing,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.MusicNote, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text(if (uiState.isProcessing) "处理中" else "下载并去人声")
                    }
                }
            }

            if (uiState.isProcessing) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(uiState.progressText, style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }

            if (uiState.outputPath != null) {
                item {
                    Text(
                        text = uiState.outputPath,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = !reducedMotion,
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
            )
        }
    }

    if (showOptions) {
        ModalBottomSheet(
            onDismissRequest = { showOptions = false },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("处理选项", style = MaterialTheme.typography.titleLarge)
                OutputFormatSelector(
                    value = settings.outputFormat,
                    onSelect = { format -> onUpdateSettings(settings.copy(outputFormat = format)) }
                )
                SettingSwitch(
                    title = "成功后删除源文件",
                    checked = settings.deleteSourceAfterSuccess,
                    onCheckedChange = { onUpdateSettings(settings.copy(deleteSourceAfterSuccess = it)) }
                )
                SettingSwitch(
                    title = "保留处理记录",
                    checked = settings.keepHistory,
                    onCheckedChange = { onUpdateSettings(settings.copy(keepHistory = it)) }
                )
                Button(
                    onClick = {
                        showOutputSettings = true
                        showOptions = false
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.Settings, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("输出与推理设置")
                }
            }
        }
    }

    if (showOutputSettings) {
        ModalBottomSheet(
            onDismissRequest = { showOutputSettings = false },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("输出与推理设置", style = MaterialTheme.typography.titleLarge)
                Text("质量优先模式：手机端使用 fp32 htdemucs 模型。", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { showOutputSettings = false }, modifier = Modifier.fillMaxWidth()) {
                    Text("完成")
                }
            }
        }
    }
}

@Composable
private fun ResolvedItemRow(
    item: ResolvedMediaItem,
    selected: Boolean,
    onClick: () -> Unit
) {
    val alpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0.72f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "selectedAlpha"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else MaterialTheme.colorScheme.surface
            )
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = if (item.isImage) Icons.Outlined.AudioFile else Icons.Outlined.MusicNote,
            contentDescription = null,
            modifier = Modifier.alpha(alpha)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title.ifBlank { "未命名内容" },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = item.qualityLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)
            )
        }
    }
}

@Composable
private fun OutputFormatSelector(
    value: AudioOutputFormat,
    onSelect: (AudioOutputFormat) -> Unit
) {
    val options = AudioOutputFormat.entries
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, format ->
            SegmentedButton(
                selected = value == format,
                onClick = { onSelect(format) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size)
            ) {
                Text(format.name.replace("_", " "))
            }
        }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(title)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
