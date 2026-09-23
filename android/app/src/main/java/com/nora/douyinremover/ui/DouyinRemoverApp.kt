package com.nora.douyinremover.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.webkit.WebView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Login
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import com.nora.douyinremover.douyin.DouyinWebSession
import com.nora.douyinremover.douyin.ResolvedMediaItem
import com.nora.douyinremover.settings.AudioOutputFormat
import com.nora.douyinremover.settings.ProcessingSettings
import com.nora.douyinremover.updater.UpdateInfo
import kotlinx.coroutines.launch

/**
 * 排版间距体系（4pt 网格）：
 * - 页面水平边距 20dp，区块间距 16dp
 * - 卡片内边距 16dp，卡片内元素间距 12dp
 * - 图标与文字间距 10dp，相关元素 8dp
 */
private object Spacing {
    val screenH = 20.dp      // 页面水平边距
    val sectionV = 16.dp     // 区块间距
    val cardP = 16.dp        // 卡片内边距
    val cardGap = 12.dp      // 卡片内元素间距
    val itemGap = 10.dp      // 图标-文字间距
    val tightGap = 8.dp      // 相关元素间距
}

/** 搜索方式：与 DouyinTargetParser 的三种输入形态一一对应 */
enum class SearchMode(val label: String, val hint: String, val icon: ImageVector) {
    LINK("分享链接", "粘贴抖音分享链接或口令", Icons.Outlined.Link),
    VIDEO_ID("视频 ID", "输入纯数字视频/图文 ID", Icons.Outlined.Tag),
    USER_ID("用户主页", "输入用户主页链接或 sec_user_id", Icons.Outlined.Person)
}

@Composable
fun DouyinRemoverApp(viewModel: AppViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    // 开屏使用说明：首次启动（或用户未勾选"下次不再显示"）时弹出；
    // 页头问号可随时重新打开。
    var showGuide by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(settings.showGuideOnLaunch) {
        showGuide = settings.showGuideOnLaunch
    }

    DouyinRemoverContent(
        uiState = uiState,
        settings = settings,
        showGuide = showGuide,
        onDismissGuide = { dontShowAgain ->
            showGuide = false
            if (dontShowAgain) {
                viewModel.updateSettings(settings.copy(showGuideOnLaunch = false))
            }
        },
        onUpdate = viewModel::startUpdate,
        onDismissUpdate = viewModel::dismissUpdate,
        onCancelUpdateDownload = viewModel::cancelUpdateDownload,
        onDownloadModel = viewModel::downloadModel,
        onCancelModelDownload = viewModel::cancelModelDownload,
        onInputChange = viewModel::updateInput,
        onResolve = viewModel::resolve,
        onSelectItem = viewModel::selectItem,
        onProcess = viewModel::processSelected,
        onDownload = viewModel::downloadSelected,
        onUpdateSettings = viewModel::updateSettings,
        onOpenLogin = viewModel::openVerification,
        onVerificationCompleted = viewModel::onVerificationCompleted,
        onVerificationCancelled = viewModel::onVerificationCancelled
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DouyinRemoverContent(
    uiState: AppUiState,
    settings: ProcessingSettings,
    showGuide: Boolean,
    onDismissGuide: (Boolean) -> Unit,
    onUpdate: () -> Unit,
    onDismissUpdate: () -> Unit,
    onCancelUpdateDownload: () -> Unit,
    onDownloadModel: () -> Unit,
    onCancelModelDownload: () -> Unit,
    onInputChange: (String) -> Unit,
    onResolve: () -> Unit,
    onSelectItem: (ResolvedMediaItem) -> Unit,
    onProcess: () -> Unit,
    onDownload: () -> Unit,
    onUpdateSettings: (ProcessingSettings) -> Unit,
    onOpenLogin: () -> Unit,
    onVerificationCompleted: (Map<String, String>) -> Unit,
    onVerificationCancelled: () -> Unit
) {
    var showOptions by remember { mutableStateOf(false) }
    var showGuideDialog by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val appContext = LocalContext.current.applicationContext

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.statusBars
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(
                start = Spacing.screenH,
                end = Spacing.screenH,
                top = 12.dp,
                bottom = 48.dp
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.sectionV)
        ) {
            item {
                HeaderSection(
                    onOpenOptions = { showOptions = true },
                    onOpenLogin = onOpenLogin,
                    onOpenGuide = { showGuideDialog = true },
                    onCopyLogs = {
                        scope.launch {
                            copyLogsToClipboard(appContext)
                        }
                    },
                    isLoggedIn = uiState.isLoggedIn,
                    formatLabel = settings.outputFormat.label()
                )
            }

            item {
                InputSection(
                    input = uiState.input,
                    onInputChange = onInputChange,
                    onResolve = onResolve,
                    isResolving = uiState.isResolving,
                    onPaste = {
                        scope.launch {
                            val clipboard = it.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.primaryClip?.getItemAt(0)?.text?.let { text ->
                                onInputChange(text.toString())
                            }
                        }
                    }
                )
            }

            uiState.error?.let { error ->
                item(key = "error") {
                    AnimatedVisibility(
                        visible = true,
                        enter = fadeIn() + scaleIn(initialScale = 0.92f),
                        exit = fadeOut() + scaleOut(targetScale = 0.92f)
                    ) {
                        ErrorBanner(
                            error = error,
                            onCopyLogs = {
                                scope.launch {
                                    copyLogsToClipboard(appContext)
                                }
                            }
                        )
                    }
                }
            }

            // 模型资源下载卡片（首次启动模型未就绪时显示）
            if (!uiState.modelReady) {
                item(key = "modelDownload") {
                    ModelDownloadCard(
                        fileName = uiState.modelFileName,
                        progress = uiState.modelDownloadProgress,
                        error = uiState.modelDownloadError,
                        onDownload = onDownloadModel,
                        onCancel = onCancelModelDownload
                    )
                }
            }

            if (uiState.resolvedItems.isEmpty() && !uiState.isResolving && uiState.error == null) {
                item(key = "empty") {
                    EmptyStateHint()
                }
            }

            if (uiState.resolvedItems.isNotEmpty()) {
                item(key = "section") {
                    SectionLabel("解析结果", uiState.resolvedItems.size)
                }

                items(
                    uiState.resolvedItems,
                    key = { "${it.url}-${it.qualityLabel}" }
                ) { item ->
                    ResolvedItemCard(
                        item = item,
                        selected = item == uiState.selectedItem,
                        onClick = { onSelectItem(item) }
                    )
                }

                item(key = "actions") {
                    Spacer(Modifier.height(4.dp))
                    ActionButtons(
                        onDownload = onDownload,
                        onProcess = onProcess,
                        isDownloading = uiState.isDownloading,
                        isProcessing = uiState.isProcessing,
                        enabled = uiState.selectedItem != null && !uiState.isProcessing && !uiState.isDownloading
                    )
                }
            }

            if (uiState.isProcessing) {
                item(key = "processing") {
                    ProcessingCard(
                        progressText = uiState.progressText,
                        progressPercent = uiState.progressPercent,
                        progressDetail = uiState.progressDetail
                    )
                }
            }

            uiState.downloadPath?.let { path ->
                item(key = "downloadSuccess") {
                    DownloadSuccessCard(path)
                }
            }

            uiState.outputPath?.let { path ->
                item(key = "success") {
                    SuccessCard(path)
                }
            }

            if (uiState.historyFiles.isNotEmpty()) {
                item(key = "historySection") {
                    SectionLabel("历史伴奏", uiState.historyFiles.size)
                }
                items(
                    uiState.historyFiles,
                    key = { it.absolutePath }
                ) { file ->
                    HistoryItemCard(
                        file = file,
                        onShare = { shareAudioFile(appContext, file) }
                    )
                }
            }
        }
    }

    if (showOptions) {
        ModalBottomSheet(
            onDismissRequest = { showOptions = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            dragHandle = null
        ) {
            OptionsSheetContent(
                settings = settings,
                onUpdateSettings = onUpdateSettings,
                onDismiss = { showOptions = false }
            )
        }
    }

    if (uiState.showVerification) {
        VerificationDialog(
            onCompleted = onVerificationCompleted,
            onDismiss = onVerificationCancelled
        )
    }

    // 开屏使用说明（首次启动自动弹出；页头问号可重新打开）
    if (showGuide) {
        GuideDialog(onDismiss = onDismissGuide)
    }
    if (showGuideDialog) {
        GuideDialog(onDismiss = { showGuideDialog = false })
    }

    // 更新弹窗：强制更新全屏不可关闭；普通更新为普通弹窗
    uiState.updateInfo?.let { info ->
        UpdateDialog(
            info = info,
            force = uiState.forceUpdate,
            downloadProgress = uiState.updateDownloadProgress,
            onUpdate = onUpdate,
            onDismiss = onDismissUpdate,
            onCancelDownload = onCancelUpdateDownload
        )
    }
}

/**
 * 更新弹窗：强制更新全屏不可跳过（无"暂不"），普通更新可关闭。
 * 下载中显示进度条，完成后自动拉起系统安装器。
 */
@Composable
private fun UpdateDialog(
    info: UpdateInfo,
    force: Boolean,
    downloadProgress: Triple<Long, Long, Boolean>?,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit,
    onCancelDownload: () -> Unit
) {
    Dialog(
        onDismissRequest = { if (!force && downloadProgress == null) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = !force,
            dismissOnBackPress = !force
        )
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = if (force) 8.dp else 20.dp)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.tightGap)
                ) {
                    Icon(
                        Icons.Outlined.Download,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Column {
                        Text(
                            if (force) "发现重要更新 v${info.latestVersion}" else "发现新版本 v${info.latestVersion}",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (force) {
                            Text(
                                "当前版本过旧，需要更新后才能继续使用",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }

                if (info.releaseNotes.isNotBlank()) {
                    Text(
                        info.releaseNotes.take(500),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                downloadProgress?.let { (done, total, _) ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val percent = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else 0
                        LinearProgressIndicator(
                            progress = { percent / 100f },
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "正在下载更新  ${formatFileSize(done)}" +
                                (if (total > 0) " / ${formatFileSize(total)}" else "") +
                                "  $percent%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.tightGap)
                ) {
                    if (!force && downloadProgress == null) {
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f).height(46.dp),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Text("暂不")
                        }
                    }
                    if (downloadProgress != null) {
                        OutlinedButton(
                            onClick = onCancelDownload,
                            modifier = Modifier.weight(1f).height(46.dp),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Text("取消下载")
                        }
                    }
                    Button(
                        onClick = onUpdate,
                        enabled = downloadProgress == null,
                        modifier = Modifier.weight(2f).height(46.dp),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Text(
                            if (downloadProgress != null) "下载中..." else "立即更新",
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

/**
 * 页头：标题 + 副标题左对齐，右上角复制日志与设置按钮；格式 Chip 与登录态 Chip 与标题基线对齐。
 */
@Composable
private fun HeaderSection(
    onOpenOptions: () -> Unit,
    onOpenLogin: () -> Unit,
    onOpenGuide: () -> Unit,
    onCopyLogs: () -> Unit,
    isLoggedIn: Boolean,
    formatLabel: String
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.tightGap)
        ) {
            // 使用说明入口（左上角问号）
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                onClick = onOpenGuide,
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        Icons.Outlined.HelpOutline,
                        contentDescription = "使用说明",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "抖音去人声",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "下载视频 · 本地分离人声 · 导出音乐",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.tightGap)) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    onClick = onCopyLogs,
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            Icons.Outlined.ContentCopy,
                            contentDescription = "复制日志",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    onClick = onOpenOptions,
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            Icons.Outlined.Settings,
                            contentDescription = "处理选项",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(Spacing.tightGap))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.tightGap)) {
            AssistChip(
                onClick = onOpenOptions,
                label = { Text(formatLabel, style = MaterialTheme.typography.labelMedium) },
                leadingIcon = {
                    Icon(Icons.Rounded.MusicNote, contentDescription = null, modifier = Modifier.size(16.dp))
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    leadingIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                border = null
            )
            AssistChip(
                onClick = onOpenLogin,
                label = {
                    Text(
                        if (isLoggedIn) "已登录抖音" else "未登录 · 点此登录",
                        style = MaterialTheme.typography.labelMedium
                    )
                },
                leadingIcon = {
                    Icon(
                        if (isLoggedIn) Icons.Filled.Check else Icons.Outlined.Login,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = if (isLoggedIn) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                    labelColor = if (isLoggedIn) MaterialTheme.colorScheme.onSecondaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    leadingIconContentColor = if (isLoggedIn) MaterialTheme.colorScheme.onSecondaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant
                ),
                border = null
            )
        }
    }
}

/**
 * 搜索输入卡片：搜索方式下拉栏 → 输入框 → 操作按钮，垂直节奏 12dp。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InputSection(
    input: String,
    onInputChange: (String) -> Unit,
    onResolve: () -> Unit,
    isResolving: Boolean,
    onPaste: (Context) -> Unit
) {
    val context = LocalContext.current
    var searchMode by rememberSaveable { mutableStateOf(SearchMode.LINK) }
    var dropdownExpanded by remember { mutableStateOf(false) }

    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
    ) {
        Column(
            modifier = Modifier.padding(Spacing.cardP),
            verticalArrangement = Arrangement.spacedBy(Spacing.cardGap)
        ) {
            // ── 搜索方式下拉栏 ──
            ExposedDropdownMenuBox(
                expanded = dropdownExpanded,
                onExpandedChange = { dropdownExpanded = it }
            ) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.tightGap)
                    ) {
                        Icon(
                            searchMode.icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            searchMode.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded)
                    }
                }
                ExposedDropdownMenu(
                    expanded = dropdownExpanded,
                    onDismissRequest = { dropdownExpanded = false },
                    containerColor = MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.medium,
                    shadowElevation = 8.dp
                ) {
                    SearchMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(Spacing.itemGap)
                                ) {
                                    Icon(
                                        mode.icon,
                                        contentDescription = null,
                                        tint = if (mode == searchMode) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Column(
                                        verticalArrangement = Arrangement.spacedBy(1.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            mode.label,
                                            style = MaterialTheme.typography.labelLarge,
                                            color = if (mode == searchMode) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            mode.hint,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (mode == searchMode) {
                                        Icon(
                                            Icons.Filled.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            },
                            onClick = {
                                searchMode = mode
                                dropdownExpanded = false
                            }
                        )
                    }
                }
            }

            // ── 输入框：占位随搜索方式切换 ──
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = {
                    Icon(
                        searchMode.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                trailingIcon = {
                    if (input.isNotBlank()) {
                        IconButton(onClick = { onInputChange("") }) {
                            Icon(
                                Icons.Outlined.Clear,
                                contentDescription = "清空",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                placeholder = {
                    Text(
                        when (searchMode) {
                            SearchMode.LINK -> "粘贴抖音链接、口令或分享文本"
                            SearchMode.VIDEO_ID -> "输入纯数字视频/图文 ID"
                            SearchMode.USER_ID -> "输入用户主页链接或 sec_user_id"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    cursorColor = MaterialTheme.colorScheme.primary
                )
            )

            // ── 分享口令识别提示 ──
            val isShareText = input.contains("复制打开抖音") ||
                Regex("[0-9A-Za-z]@T\\.l[0-9A-Za-z]").containsMatchIn(input)
            AnimatedVisibility(
                visible = isShareText && !isResolving,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        Icons.Outlined.Link,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        "已识别分享口令：将按标题搜索并匹配视频（需登录抖音）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            // ── 操作按钮：粘贴 1/3 + 解析 2/3 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.tightGap)
            ) {
                FilledTonalButton(
                    onClick = { onPaste(context) },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = MaterialTheme.shapes.medium,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Icon(Icons.Outlined.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("粘贴", style = MaterialTheme.typography.labelLarge)
                }

                Button(
                    onClick = onResolve,
                    enabled = !isResolving,
                    modifier = Modifier.weight(2f).height(48.dp),
                    shape = MaterialTheme.shapes.medium,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    if (isResolving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(Icons.Outlined.MusicNote, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (isResolving) "解析中..." else "解析",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun ErrorBanner(error: String, onCopyLogs: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        tonalElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
    ) {
        Column(modifier = Modifier.padding(horizontal = Spacing.cardP, vertical = 12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.itemGap)
            ) {
                Icon(
                    Icons.Outlined.Clear,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onCopyLogs) {
                    Icon(
                        Icons.Outlined.ContentCopy,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "复制日志",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    }
}

/** 收集本进程日志写入剪贴板，并 Toast 提示 */
private fun copyLogsToClipboard(context: Context) {
    val logs = AppLog.collect()
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("app_logs", logs))
    android.util.Log.i("CopyLog", "copied ${logs.length} chars to clipboard")
    android.widget.Toast.makeText(context, "日志已复制，可直接粘贴反馈", android.widget.Toast.LENGTH_SHORT).show()
}

@Composable
private fun EmptyStateHint() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.cardGap)
    ) {
        // 呼吸动画的音符图标
        val breath = remember { Animatable(1f) }
        LaunchedEffect(Unit) {
            while (true) {
                breath.animateTo(1.12f, animationSpec = tween(900, easing = FastOutSlowInEasing))
                breath.animateTo(1f, animationSpec = tween(900, easing = FastOutSlowInEasing))
            }
        }
        Box(
            modifier = Modifier
                .size(80.dp)
                .scale(breath.value)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.colorScheme.secondaryContainer
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Outlined.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(38.dp)
            )
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                "输入抖音链接开始使用",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "支持分享链接、分享口令、视频 ID 与用户主页",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}

/**
 * 区块标签：左侧竖条强调 + 标题 + 计数徽章，形成清晰的分区视觉锚点。
 */
@Composable
private fun SectionLabel(text: String, count: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.tightGap)
    ) {
        Box(
            modifier = Modifier
                .size(width = 4.dp, height = 16.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary)
        )
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.SemiBold
        )
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
            Text(
                "$count",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
    }
}

/**
 * 模型资源下载卡片：AI 分离模型已从 APK 分离，首次使用需联网下载一次。
 */
@Composable
private fun ModelDownloadCard(
    fileName: String,
    progress: Triple<Long, Long, Boolean>?,
    error: String?,
    onDownload: () -> Unit,
    onCancel: () -> Unit
) {
    ElevatedCard(
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(Spacing.cardP),
            verticalArrangement = Arrangement.spacedBy(Spacing.cardGap)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.itemGap)
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(38.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Outlined.Download,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "AI 模型资源下载",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "人声分离模型（${fileName.ifBlank { "htdemucs" }}）需要联网下载一次，之后更新应用无需重复下载",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            progress?.let { (done, total, _) ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val percent = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else 0
                    LinearProgressIndicator(
                        progress = { percent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "${formatFileSize(done)}" +
                            (if (total > 0) " / ${formatFileSize(total)}" else "") +
                            "  $percent%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            error?.let { message ->
                Text(
                    "下载失败：$message",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.tightGap)) {
                if (progress != null) {
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.weight(1f).height(42.dp),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Text("取消")
                    }
                }
                Button(
                    onClick = onDownload,
                    enabled = progress == null,
                    modifier = Modifier.weight(2f).height(42.dp),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text(
                        when {
                            progress != null -> "下载中..."
                            error != null -> "重试下载"
                            else -> "开始下载"
                        },
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

/**
 * 历史伴奏卡片：文件名 + 大小/日期两行，右侧分享按钮。
 */
@Composable
private fun HistoryItemCard(
    file: File,
    onShare: () -> Unit
) {
    val dateFmt = remember { java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()) }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.cardP, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.itemGap)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(38.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Outlined.History,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    file.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${formatFileSize(file.length())} · ${dateFmt.format(java.util.Date(file.lastModified()))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }
            IconButton(onClick = onShare) {
                Icon(
                    Icons.Rounded.Share,
                    contentDescription = "分享",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1 shl 20 -> "%.1fMB".format(bytes / 1024f / 1024f)
    bytes >= 1 shl 10 -> "${bytes / 1024}KB"
    else -> "${bytes}B"
}

/** 调出系统分享面板分享音频文件（FileProvider 提供 content:// URI） */
private fun shareAudioFile(context: Context, file: File) {
    runCatching {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = when (file.extension) {
                "mp3" -> "audio/mpeg"
                "wav" -> "audio/wav"
                else -> "audio/flac"
            }
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享伴奏"))
    }
}

/**
 * 结果卡片：图标圆标 + 标题/清晰度两行 + 选中态勾标，内边距 16dp。
 */
@Composable
private fun ResolvedItemCard(
    item: ResolvedMediaItem,
    selected: Boolean,
    onClick: () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.98f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "cardScale"
    )

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        tonalElevation = if (selected) 4.dp else 1.dp,
        shadowElevation = if (selected) 8.dp else 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(MaterialTheme.shapes.medium)
            .combinedClickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(Spacing.cardP),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (item.isImage) Icons.Outlined.AudioFile else Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title.ifBlank { "未命名内容" },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = item.qualityLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    else MaterialTheme.colorScheme.secondary
                )
            }
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

/**
 * 操作按钮区：下载（仅保存视频）与去除人声（下载 + 分离导出伴奏）两个独立操作。
 * 去人声是可选项；只想保存原视频时点「下载」即可。
 */
@Composable
private fun ActionButtons(
    onDownload: () -> Unit,
    onProcess: () -> Unit,
    isDownloading: Boolean,
    isProcessing: Boolean,
    enabled: Boolean
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled) 0.98f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "actionsScale"
    )

    Row(
        modifier = Modifier.fillMaxWidth().scale(scale),
        horizontalArrangement = Arrangement.spacedBy(Spacing.itemGap)
    ) {
        FilledTonalButton(
            onClick = onDownload,
            enabled = enabled,
            interactionSource = interactionSource,
            modifier = Modifier.weight(1f).height(56.dp),
            shape = MaterialTheme.shapes.large,
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        ) {
            if (isDownloading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.5.dp,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            } else {
                Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(
                if (isDownloading) "下载中..." else "下载",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
        }

        Button(
            onClick = onProcess,
            enabled = enabled,
            modifier = Modifier.weight(2f).height(56.dp),
            shape = MaterialTheme.shapes.large,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        ) {
            if (isProcessing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.5.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(Modifier.width(10.dp))
                Text("处理中...", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            } else {
                Icon(Icons.Rounded.MusicNote, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text("去除人声", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** 下载完成卡片（仅下载、未做分离）：路径文字点击可展开看全 */
@Composable
private fun DownloadSuccessCard(path: String) {
    var expanded by rememberSaveable(path) { mutableStateOf(false) }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 3.dp,
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .combinedClickable(onClick = { expanded = !expanded })
    ) {
        Row(
            modifier = Modifier.padding(Spacing.cardP),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.itemGap)
        ) {
            Icon(
                Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(28.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "下载完成",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                    maxLines = if (expanded) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                Icons.Rounded.KeyboardArrowDown,
                contentDescription = if (expanded) "收起" else "展开",
                tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f),
                modifier = Modifier
                    .size(22.dp)
                    .scale(if (expanded) 1f else -1f)
            )
        }
    }
}

/**
 * 处理进度卡片：步骤条（提取→分离→编码→收尾）+ 全局进度条 + 当前阶段说明。
 */
@Composable
private fun ProcessingCard(progressText: String, progressPercent: Int, progressDetail: String) {
    // 步骤顺序与 ProcessStage 一致；progressText 即当前阶段 label
    val steps = listOf("提取音频", "AI 分离人声", "编码导出", "收尾处理")
    val currentStep = steps.indexOf(progressText).coerceAtLeast(0)

    ElevatedCard(
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
    ) {
        Column(
            modifier = Modifier.padding(Spacing.cardP),
            verticalArrangement = Arrangement.spacedBy(Spacing.cardGap)
        ) {
            // 标题行：进度百分比 + 当前阶段
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.itemGap)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.5.dp,
                    color = MaterialTheme.colorScheme.primary
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "正在处理 · $progressText",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (progressDetail.isNotBlank()) {
                        Text(
                            progressDetail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    "$progressPercent%",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // 步骤条：圆点 + 连接线，已完成实心、当前呼吸、未开始空心
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                steps.forEachIndexed { index, step ->
                    val done = index < currentStep || progressPercent >= 100
                    val active = index == currentStep && progressPercent < 100
                    val stepColor = when {
                        done -> MaterialTheme.colorScheme.primary
                        active -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.outlineVariant
                    }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(if (active) 14.dp else 12.dp)
                                .clip(CircleShape)
                                .background(stepColor)
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            step,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (done || active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1
                        )
                    }
                    if (index < steps.lastIndex) {
                        Box(
                            modifier = Modifier
                                .weight(0.6f)
                                .height(2.dp)
                                .background(
                                    if (index < currentStep) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant
                                )
                        )
                    }
                }
            }

            // 全局进度条
            LinearProgressIndicator(
                progress = { progressPercent / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(MaterialTheme.shapes.extraSmall),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.primaryContainer
            )
        }
    }
}

/** 处理完成卡片：路径文字默认单行省略，点击可展开看全 */
@Composable
private fun SuccessCard(path: String) {
    var expanded by rememberSaveable(path) { mutableStateOf(false) }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 3.dp,
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .combinedClickable(onClick = { expanded = !expanded })
    ) {
        Row(
            modifier = Modifier.padding(Spacing.cardP),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.itemGap)
        ) {
            Icon(
                Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(28.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "处理完成",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    maxLines = if (expanded) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                Icons.Rounded.KeyboardArrowDown,
                contentDescription = if (expanded) "收起" else "展开",
                tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
                modifier = Modifier
                    .size(22.dp)
                    .scale(if (expanded) 1f else -1f)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OptionsSheetContent(
    settings: ProcessingSettings,
    onUpdateSettings: (ProcessingSettings) -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 20.dp)
            .windowInsetsPadding(WindowInsets.navigationBars),
        verticalArrangement = Arrangement.spacedBy(Spacing.sectionV)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.itemGap)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp)
                )
            }
            Text(
                "处理选项",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(Spacing.tightGap)) {
            Text(
                "输出格式",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutputFormatSelector(
                value = settings.outputFormat,
                onSelect = { format -> onUpdateSettings(settings.copy(outputFormat = format)) }
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(Spacing.tightGap)) {
            SettingRow("成功后删除源文件", "节省存储空间", settings.deleteSourceAfterSuccess) {
                onUpdateSettings(settings.copy(deleteSourceAfterSuccess = it))
            }
            SettingRow("保留处理记录", "在 App 内查看历史", settings.keepHistory) {
                onUpdateSettings(settings.copy(keepHistory = it))
            }
            SettingRow("静音裁剪", "自动去掉尾部静音段", settings.silenceThresholdDb < 0f) { value ->
                onUpdateSettings(settings.copy(silenceThresholdDb = if (value) -55f else 0f))
            }
        }

        Button(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = MaterialTheme.shapes.large
        ) {
            Text("完成", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OutputFormatSelector(value: AudioOutputFormat, onSelect: (AudioOutputFormat) -> Unit) {
    val options = AudioOutputFormat.entries
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth(), space = 4.dp) {
        options.forEachIndexed { index, format ->
            SegmentedButton(
                selected = value == format,
                onClick = { onSelect(format) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                label = { Text(format.label(), style = MaterialTheme.typography.labelSmall) }
            )
        }
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.cardP, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

private fun AudioOutputFormat.label(): String = when (this) {
    AudioOutputFormat.MP3_320 -> "MP3 320k"
    AudioOutputFormat.MP3_256 -> "MP3 256k"
    AudioOutputFormat.MP3_128 -> "MP3 128k"
    AudioOutputFormat.WAV -> "WAV"
    AudioOutputFormat.FLAC -> "FLAC"
}

/**
 * 抖音验证/登录弹窗：内嵌 WebView 加载抖音页面。
 * 用户手动完成滑块验证码或扫码/账密登录后，点击「已完成」收集 cookie（含 UIFID_TEMP、
 * session 系登录态字段），合并进 cookie 存储并自动重试解析。
 * 这是风控恢复的唯一可靠手段（48tools / dyparse 同款做法）。
 */
@Composable
private fun VerificationDialog(
    onCompleted: (Map<String, String>) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val webView = remember {
        WebView(context).apply {
            // 验证/登录页需要显示图片（二维码），不禁用图片加载
            DouyinWebSession.configure(this, blockImages = false)
        }
    }

    LaunchedEffect(Unit) {
        if (webView.url == null) {
            webView.loadUrl("https://www.douyin.com/")
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars)
            ) {
                // 标题栏
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.cardP, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "抖音验证 / 登录",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            "在页面中完成滑块验证或登录，然后点击下方按钮",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "关闭",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // WebView 主体
                AndroidView(
                    factory = { webView },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )

                // 底部操作
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(horizontal = Spacing.cardP, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.itemGap)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Text("取消", style = MaterialTheme.typography.labelLarge)
                    }
                    Button(
                        onClick = {
                            val cookies = DouyinWebSession.collectCookies()
                            onCompleted(cookies)
                        },
                        modifier = Modifier.weight(2f).height(48.dp),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Text(
                            "已完成验证 / 登录",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

/**
 * 开屏使用说明：使用步骤 + 支持格式介绍 + 三种搜索模式说明。
 * 首次启动自动弹出；勾选"下次不再显示"后不再自动弹出（页头问号仍可打开）。
 */
@Composable
private fun GuideDialog(onDismiss: (dontShowAgain: Boolean) -> Unit) {
    var dontShowAgain by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = { onDismiss(dontShowAgain) },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 标题
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.tightGap)
                ) {
                    Icon(
                        Icons.Outlined.HelpOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        "使用说明",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                // 使用步骤
                GuideSectionTitle("使用步骤")
                GuideStep("1", "粘贴链接", "从抖音分享口令复制，或直接粘贴视频链接 / ID")
                GuideStep("2", "点击解析", "选择想要的清晰度（默认选第一个）")
                GuideStep("3", "下载或去人声", "「下载」保存原视频；「去除人声」本地 AI 分离出伴奏")

                // 支持的格式
                GuideSectionTitle("支持的音频格式")
                GuideFormatRow("MP3 320k", "音质最好的有损格式，兼容一切设备，日常听歌首选")
                GuideFormatRow("MP3 256k / 128k", "体积更小，适合流量敏感或批量下载")
                GuideFormatRow("WAV", "无损原始波形，体积大，适合二次剪辑加工")
                GuideFormatRow("FLAC", "无损压缩，体积约为 WAV 的一半，发烧友首选")

                // 三种搜索模式
                GuideSectionTitle("三种搜索模式")
                GuideModeRow("分享链接", "粘贴「复制打开抖音…」整段口令或 v.douyin.com 短链，最常用")
                GuideModeRow("视频 ID", "输入纯数字视频 ID（链接末尾那串数字），适合已知 ID 时精确定位")
                GuideModeRow("用户主页", "粘贴作者主页链接，列出该用户的视频供挑选下载")

                // 底部：不再显示 + 知道了
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .weight(1f)
                            .clip(MaterialTheme.shapes.small)
                            .combinedClickable(onClick = { dontShowAgain = !dontShowAgain })
                            .padding(vertical = 4.dp)
                    ) {
                        Checkbox(
                            checked = dontShowAgain,
                            onCheckedChange = { dontShowAgain = it }
                        )
                        Text(
                            "下次打开不再显示",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(
                        onClick = { onDismiss(dontShowAgain) },
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Text("知道了", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun GuideSectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun GuideStep(number: String, title: String, detail: String) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(Spacing.tightGap)
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(20.dp)
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Text(
                    number,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
        Column {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun GuideFormatRow(name: String, detail: String) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(Spacing.tightGap)
    ) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.width(86.dp)
        ) {
            Text(
                name,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
            )
        }
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

@Composable
private fun GuideModeRow(name: String, detail: String) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(Spacing.tightGap)
    ) {
        Text(
            "·",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Column {
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
