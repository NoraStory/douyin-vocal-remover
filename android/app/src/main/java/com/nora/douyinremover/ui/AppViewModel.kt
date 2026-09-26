package com.nora.douyinremover.ui

import android.app.Application
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nora.douyinremover.ProcessingForegroundService
import com.nora.douyinremover.audio.AudioProcessor
import com.nora.douyinremover.audio.FfmpegMediaEncoder
import com.nora.douyinremover.audio.NoAudioTrackException
import com.nora.douyinremover.audio.OnnxDemucsSeparator
import com.nora.douyinremover.audio.ProcessStage
import com.nora.douyinremover.audio.SeparationRequest
import com.nora.douyinremover.douyin.DouyinApi
import com.nora.douyinremover.douyin.NeedVerificationException
import com.nora.douyinremover.douyin.ResolvedMediaItem
import com.nora.douyinremover.settings.AudioOutputFormat
import com.nora.douyinremover.settings.ProcessingSettings
import com.nora.douyinremover.settings.SettingsRepository
import com.nora.douyinremover.updater.ApkDownloader
import com.nora.douyinremover.updater.ApkInstaller
import com.nora.douyinremover.updater.CancelFlag
import com.nora.douyinremover.updater.DownloadCancelledException
import com.nora.douyinremover.updater.ModelDownloader
import com.nora.douyinremover.updater.UpdateCheckWorker
import com.nora.douyinremover.updater.UpdateChecker
import com.nora.douyinremover.updater.UpdateInfo
import com.nora.douyinremover.updater.UpdateRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class AppUiState(
    val input: String = "",
    val resolvedItems: List<ResolvedMediaItem> = emptyList(),
    val selectedItem: ResolvedMediaItem? = null,
    val isResolving: Boolean = false,
    val isProcessing: Boolean = false,
    val isDownloading: Boolean = false,
    /** 当前处理阶段（ProcessStage.label） */
    val progressText: String = "",
    /** 全局进度 0-100 */
    val progressPercent: Int = 0,
    /** 阶段附加说明（如 "第 3/25 段"） */
    val progressDetail: String = "",
    val outputPath: String? = null,
    val downloadPath: String? = null,
    val error: String? = null,
    val showVerification: Boolean = false,
    val isLoggedIn: Boolean = false,
    /** 历史伴奏文件（Download/抖音去人声/伴奏 下按时间倒序） */
    val historyFiles: List<File> = emptyList(),
    /** 远端检测到的新版本（null = 无更新） */
    val updateInfo: UpdateInfo? = null,
    /** 是否强制更新（落后一个大版本及以上） */
    val forceUpdate: Boolean = false,
    /** 更新 APK 下载进度：null = 未在下载；(已下载字节, 总字节, 是否完成) */
    val updateDownloadProgress: Triple<Long, Long, Boolean>? = null,
    /** 模型是否已就绪（首次启动需下载） */
    val modelReady: Boolean = true,
    /** 需要下载的模型文件名 */
    val modelFileName: String = "",
    /** 模型下载进度：null = 未在下载；(已下载字节, 总字节, 是否完成) */
    val modelDownloadProgress: Triple<Long, Long, Boolean>? = null,
    /** 模型下载失败信息 */
    val modelDownloadError: String? = null
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application
    private val douyinApi = DouyinApi(context)
    private val settingsRepository = SettingsRepository(context)
    private val separator = OnnxDemucsSeparator(context)
    private val audioProcessor = AudioProcessor(
        encoder = FfmpegMediaEncoder(),
        separator = separator
    )
    private val updateChecker = UpdateChecker()
    private val updateRepository = UpdateRepository(context)
    private val apkDownloader = ApkDownloader()
    private val modelDownloader = ModelDownloader()
    private var updateCancelFlag = CancelFlag()
    private var modelCancelFlag = CancelFlag()
    private var currentVersion: String =
        application.packageManager.getPackageInfo(application.packageName, 0).versionName ?: "0.0.0"

    val settings: StateFlow<ProcessingSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProcessingSettings())

    private val _uiState = MutableStateFlow(AppUiState())
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

    init {
        // 登录态刷新：无感后台预热（dyparse 方案）——启动 1.5s 后静默访问抖音首页，
        // 拿到匿名会话 cookie 并持久化 24h，降低首次解析触发风控的概率。
        viewModelScope.launch {
            kotlinx.coroutines.delay(1_500)
            runCatching { douyinApi.isLoggedIn() }
                .onSuccess { loggedIn -> _uiState.update { it.copy(isLoggedIn = loggedIn) } }
        }
        refreshHistory()
        initUpdateAndModel()
    }

    /** 启动时：注册周期检测 + 立即首检 + 检查本地模型 */
    private fun initUpdateAndModel() {
        runCatching {
            UpdateCheckWorker.schedulePeriodic(context)
            UpdateCheckWorker.checkNow(context)
        }
        viewModelScope.launch {
            // 观察 Worker 的检测结果（DataStore）
            updateRepository.state.collect { state ->
                if (state.hasUpdate &&
                    UpdateChecker.compareVersions(currentVersion, state.latestVersion) > 0
                ) {
                    val info = UpdateInfo(
                        latestVersion = state.latestVersion,
                        apkUrl = state.apkUrl,
                        apkSize = state.apkSize,
                        releaseNotes = state.releaseNotes,
                        source = state.source
                    )
                    _uiState.update {
                        it.copy(
                            updateInfo = info,
                            forceUpdate = UpdateChecker.isForceUpdate(currentVersion, state.latestVersion)
                        )
                    }
                }
            }
        }
        checkModelReady()
    }

    /** 检查本地模型是否就绪（未就绪则 UI 显示下载卡片） */
    fun checkModelReady() {
        val ready = runCatching { separator.isModelReady() }.getOrDefault(false)
        _uiState.update {
            it.copy(
                modelReady = ready,
                modelFileName = runCatching { separator.requiredModelFileName() }.getOrDefault(""),
                modelDownloadError = if (ready) null else it.modelDownloadError
            )
        }
    }

    /** 下载缺失的模型（R2 主源 / Gitee 分卷 / GitHub 兜底 + 断点续传 + SHA-256），完成后自动就绪 */
    fun downloadModel() {
        if (_uiState.value.modelDownloadProgress != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(modelDownloadProgress = Triple(0L, -1L, false), modelDownloadError = null) }
            runCatching {
                modelDownloader.downloadModel(
                    modelFileName = _uiState.value.modelFileName,
                    modelsDir = separator.modelsDir,
                    onProgress = { done, total ->
                        _uiState.update { it.copy(modelDownloadProgress = Triple(done, total, false)) }
                    },
                    isCancelled = { modelCancelFlag.cancelled }
                )
            }.onSuccess {
                checkModelReady()
                _uiState.update { it.copy(modelDownloadProgress = Triple(1L, 1L, true)) }
            }.onFailure { error ->
                if (error is DownloadCancelledException) {
                    _uiState.update { it.copy(modelDownloadProgress = null) }
                } else {
                    _uiState.update {
                        it.copy(
                            modelDownloadProgress = null,
                            modelDownloadError = error.message ?: "模型下载失败"
                        )
                    }
                }
            }
        }
    }

    fun cancelModelDownload() {
        modelCancelFlag.cancelled = true
        modelCancelFlag = CancelFlag()
    }

    /** 立即更新：下载 APK（断点续传）→ 完成后拉起系统安装器 */
    fun startUpdate() {
        val info = _uiState.value.updateInfo ?: return
        if (info.apkUrl.isBlank()) {
            _uiState.update { it.copy(error = "更新包地址无效，请到项目主页手动下载") }
            return
        }
        if (_uiState.value.updateDownloadProgress != null) return
        if (!ApkInstaller.canInstall(context)) {
            ApkInstaller.requestInstallPermission(context)
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(updateDownloadProgress = Triple(0L, info.apkSize, false)) }
            runCatching {
                apkDownloader.download(
                    url = info.apkUrl,
                    targetFile = ApkInstaller.apkFile(context),
                    onProgress = { done, total ->
                        _uiState.update { it.copy(updateDownloadProgress = Triple(done, total, false)) }
                    },
                    isCancelled = { updateCancelFlag.cancelled }
                )
            }.onSuccess { apk ->
                _uiState.update { it.copy(updateDownloadProgress = Triple(1L, 1L, true)) }
                ApkInstaller.install(context, apk)
            }.onFailure { error ->
                if (error is DownloadCancelledException) {
                    _uiState.update { it.copy(updateDownloadProgress = null) }
                } else {
                    _uiState.update {
                        it.copy(
                            updateDownloadProgress = null,
                            error = "更新下载失败：${error.message ?: "未知错误"}"
                        )
                    }
                }
            }
        }
    }

    fun cancelUpdateDownload() {
        updateCancelFlag.cancelled = true
        updateCancelFlag = CancelFlag()
    }

    /** 普通更新点"暂不"：本次启动不再提醒（强制更新无此入口） */
    fun dismissUpdate() {
        _uiState.update { it.copy(updateInfo = null, forceUpdate = false) }
    }

    /** 扫描公共下载目录的历史伴奏（Download/抖音去人声/伴奏），按修改时间倒序 */
    fun refreshHistory() {
        viewModelScope.launch {
            val files = runCatching { listHistoryFiles() }.getOrDefault(emptyList())
            _uiState.update { it.copy(historyFiles = files) }
        }
    }

    private fun listHistoryFiles(): List<File> {
        val dir = instrumentalDownloadsDir() ?: return emptyList()
        return dir.listFiles { f -> f.isFile && f.extension in setOf("mp3", "wav", "flac") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    /** 历史伴奏的公共下载目录：Download/抖音去人声/伴奏（Android 10+ 公开可见） */
    private fun instrumentalDownloadsDir(): File? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "抖音去人声/伴奏"
            )
        } else {
            File(getApplication<Application>().getExternalFilesDir(null), "output").apply { mkdirs() }
        }
    }

    fun updateInput(value: String) {
        _uiState.update { it.copy(input = value, error = null) }
    }

    fun selectItem(item: ResolvedMediaItem) {
        _uiState.update { it.copy(selectedItem = item) }
    }

    fun updateSettings(value: ProcessingSettings) {
        viewModelScope.launch {
            settingsRepository.update { value }
        }
    }

    fun resolve(fromVerification: Boolean = false) {
        val input = _uiState.value.input.trim()
        if (input.isBlank()) {
            _uiState.update { it.copy(error = "请输入抖音链接、视频 ID 或用户 ID") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isResolving = true, error = null, resolvedItems = emptyList()) }
            runCatching { douyinApi.resolve(input) }
                .onSuccess { items ->
                    _uiState.update {
                        it.copy(
                            isResolving = false,
                            resolvedItems = items,
                            selectedItem = items.firstOrNull(),
                            error = if (items.isEmpty()) "没有解析到可下载内容" else null
                        )
                    }
                }
                .onFailure { error ->
                    if (error is NeedVerificationException && !fromVerification) {
                        // 风控：弹出验证/登录窗口，完成后自动重试
                        _uiState.update {
                            it.copy(
                                isResolving = false,
                                showVerification = true,
                                error = error.message
                            )
                        }
                    } else if (error is NeedVerificationException && fromVerification) {
                        // 已完成验证/登录但仍被拦：多半是 IP 被限流，冷却中；
                        // 不再重复弹窗，给出明确指引避免死循环
                        _uiState.update {
                            it.copy(
                                isResolving = false,
                                showVerification = false,
                                error = "验证后仍被风控拦截，可能触发了 IP 限流：请稍后重试、切换网络（Wi-Fi/流量）或更换 IP 后再解析"
                            )
                        }
                    } else {
                        _uiState.update { it.copy(isResolving = false, error = error.message ?: "解析失败") }
                    }
                }
        }
    }

    /** 用户在 WebView 中完成验证码/登录后调用：合并 cookie、收起弹窗并自动重试解析 */
    fun onVerificationCompleted(cookies: Map<String, String>) {
        viewModelScope.launch {
            runCatching { douyinApi.applyWebSessionCookies(cookies) }
            val loggedIn = runCatching { douyinApi.isLoggedIn() }.getOrDefault(false)
            _uiState.update { it.copy(showVerification = false, isLoggedIn = loggedIn) }
            // 自动重试刚才的解析（验证后仍被拦则不再重复弹窗，给出冷却指引）
            if (_uiState.value.input.isNotBlank()) {
                resolve(fromVerification = true)
            }
        }
    }

    fun onVerificationCancelled() {
        _uiState.update { it.copy(showVerification = false) }
    }

    /** 主动打开验证/登录窗口（页头登录状态入口） */
    fun openVerification() {
        _uiState.update { it.copy(showVerification = true, error = null) }
    }

    fun processSelected() {
        val item = _uiState.value.selectedItem ?: return
        val currentSettings = settings.value
        // 模型未就绪（首次启动未下载）时禁止分离，提示先下载模型
        if (!runCatching { separator.isModelReady() }.getOrDefault(false)) {
            _uiState.update { it.copy(error = "AI 模型尚未下载，请先在「资源下载」卡片中完成模型下载") }
            checkModelReady()
            return
        }
        viewModelScope.launch {
            // 前台服务保活：vivo OriginOS 等系统会在切后台/息屏后冻结进程，
            // 推理线程停摆表现为"卡住"。处理期间挂常驻通知防止冻结。
            ProcessingForegroundService.start(context)
            _uiState.update {
                it.copy(
                    isProcessing = true,
                    progressText = "下载媒体",
                    progressPercent = 0,
                    progressDetail = "",
                    error = null,
                    outputPath = null
                )
            }
            runCatching { process(item, currentSettings) }
                .onSuccess { output ->
                    _uiState.update {
                        it.copy(
                            isProcessing = false,
                            progressText = "完成",
                            progressPercent = 100,
                            progressDetail = "",
                            outputPath = output,
                            error = null
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isProcessing = false,
                            progressText = "",
                            progressPercent = 0,
                            progressDetail = "",
                            error = error.message ?: "处理失败"
                        )
                    }
                }
            ProcessingForegroundService.stop(context)
        }
    }

    /** 仅下载选中视频到系统下载目录（不做人声分离） */
    fun downloadSelected() {
        val item = _uiState.value.selectedItem ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isDownloading = true, error = null, downloadPath = null) }
            runCatching {
                val bytes = douyinApi.download(item.url, emptyMap())
                saveToDownloads(item, bytes)
            }.onSuccess { path ->
                _uiState.update { it.copy(isDownloading = false, downloadPath = path) }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(isDownloading = false, error = error.message ?: "下载失败")
                }
            }
        }
    }

    /** 保存到系统下载目录（Android 10+ 走 MediaStore，公开可见；旧版本存应用目录） */
    private fun saveToDownloads(item: ResolvedMediaItem, bytes: ByteArray): String {
        val fileName = "${safeName(item.title)}.${if (item.isImage) "jpg" else "mp4"}"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(
                    MediaStore.MediaColumns.MIME_TYPE,
                    if (item.isImage) "image/jpeg" else "video/mp4"
                )
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/抖音去人声"
                )
            }
            val collection = if (item.isImage) MediaStore.Downloads.EXTERNAL_CONTENT_URI else MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val uri = resolver.insert(collection, values)
                ?: throw IllegalStateException("无法创建下载文件")
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw IllegalStateException("无法写入下载文件")
            "下载目录/抖音去人声/$fileName"
        } else {
            val dir = File(context.getExternalFilesDir(null), "downloads").apply { mkdirs() }
            val file = File(dir, fileName)
            file.writeBytes(bytes)
            file.absolutePath
        }
    }

    private suspend fun process(item: ResolvedMediaItem, settings: ProcessingSettings): String {
        // 候选列表：选中条目优先，其余条目按序降级。
        // 抖音部分码率地址存在"只有视频流、无音频轨"的情况，
        // 提取音频失败（NoAudioTrackException）时自动换下一个码率重试。
        val candidates = buildList {
            add(item)
            addAll(_uiState.value.resolvedItems.filter { it.url != item.url })
        }.distinctBy { it.url }

        var lastError: Exception? = null
        for (candidate in candidates) {
            try {
                return processOne(candidate, settings)
            } catch (e: NoAudioTrackException) {
                android.util.Log.w(
                    "AppViewModel",
                    "no audio track on ${candidate.qualityLabel}, trying next candidate"
                )
                lastError = e
            }
        }
        throw lastError
            ?: NoAudioTrackException("该视频没有可用的音频轨道，请换一个视频或清晰度")
    }

    private suspend fun processOne(item: ResolvedMediaItem, settings: ProcessingSettings): String {
        val workDir = File(context.getExternalFilesDir(null), "processing").apply { mkdirs() }
        val input = File(workDir, "source_${System.currentTimeMillis()}.${if (item.isImage) "img" else "mp4"}")
        val bytes = douyinApi.download(item.url, emptyMap()) { downloaded, total ->
            val percent = if (total > 0) (downloaded * 100 / total).toInt() else -1
            val detail = if (total > 0) "${downloaded / 1024 / 1024}MB / ${total / 1024 / 1024}MB" else "${downloaded / 1024 / 1024}MB"
            _uiState.update {
                it.copy(
                    progressText = "下载媒体",
                    progressPercent = if (percent >= 0) (percent * 0.15).toInt() else 5,
                    progressDetail = detail
                )
            }
        }
        input.writeBytes(bytes)

        val format = settings.outputFormat
        val extension = when (format) {
            AudioOutputFormat.MP3_320,
            AudioOutputFormat.MP3_256,
            AudioOutputFormat.MP3_128 -> "mp3"
            AudioOutputFormat.WAV -> "wav"
            AudioOutputFormat.FLAC -> "flac"
        }
        val bitrate = when (format) {
            AudioOutputFormat.MP3_320 -> 320
            AudioOutputFormat.MP3_256 -> 256
            AudioOutputFormat.MP3_128 -> 128
            else -> 0
        }
        val outputDir = File(context.getExternalFilesDir(null), "output").apply { mkdirs() }
        val output = File(outputDir, "${safeName(item.title)}_伴奏.$extension")
        audioProcessor.process(
            SeparationRequest(
                inputPath = input.absolutePath,
                outputPath = output.absolutePath,
                outputFormat = extension,
                bitrateKbps = bitrate,
                silenceThresholdDb = settings.silenceThresholdDb,
                onProgress = { progress ->
                    _uiState.update {
                        it.copy(
                            progressText = progress.stage.label,
                            progressPercent = progress.overallPercent,
                            progressDetail = progress.detail
                        )
                    }
                }
            )
        )
        if (settings.deleteSourceAfterSuccess) input.delete()
        // 编码在应用私有目录完成，成功后转存公共下载目录（文件管理器可见、可直接分享）
        val publicPath = saveInstrumentalToDownloads(output, item.title, extension)
        output.delete()
        refreshHistory()
        return publicPath
    }

    /** 把生成的伴奏转存到公共下载目录 Download/抖音去人声/伴奏，返回展示路径 */
    private fun saveInstrumentalToDownloads(source: File, title: String, extension: String): String {
        val fileName = "${safeName(title)}_伴奏.$extension"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val mime = when (extension) {
                "mp3" -> "audio/mpeg"
                "wav" -> "audio/wav"
                else -> "audio/flac"
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/抖音去人声/伴奏"
                )
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("无法创建下载文件")
            resolver.openOutputStream(uri)?.use { out ->
                source.inputStream().use { it.copyTo(out) }
            } ?: throw IllegalStateException("无法写入下载文件")
            "下载目录/抖音去人声/伴奏/$fileName"
        } else {
            // Android 9 及以下无 MediaStore.Downloads，直接复制到公共下载目录
            val dir = instrumentalDownloadsDir()!!.apply { mkdirs() }
            val target = File(dir, fileName)
            source.copyTo(target, overwrite = true)
            target.absolutePath
        }
    }

    private fun safeName(value: String): String =
        value.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").trim('_').ifBlank { "output" }

    /** 分享历史伴奏文件（调出系统分享面板） */
    fun shareHistoryFile(file: File, onShare: (File) -> Unit) {
        onShare(file)
    }
}
