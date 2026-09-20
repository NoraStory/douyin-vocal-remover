package com.nora.douyinremover.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nora.douyinremover.audio.AudioProcessor
import com.nora.douyinremover.audio.FfmpegMediaEncoder
import com.nora.douyinremover.audio.OnnxDemucsSeparator
import com.nora.douyinremover.audio.SeparationRequest
import com.nora.douyinremover.douyin.DouyinApi
import com.nora.douyinremover.douyin.ResolvedMediaItem
import com.nora.douyinremover.settings.AudioOutputFormat
import com.nora.douyinremover.settings.ProcessingSettings
import com.nora.douyinremover.settings.SettingsRepository
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
    val progressText: String = "",
    val outputPath: String? = null,
    val error: String? = null
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application
    private val douyinApi = DouyinApi(context)
    private val settingsRepository = SettingsRepository(context)
    private val audioProcessor = AudioProcessor(
        encoder = FfmpegMediaEncoder(),
        separator = OnnxDemucsSeparator(context)
    )

    val settings: StateFlow<ProcessingSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProcessingSettings())

    private val _uiState = MutableStateFlow(AppUiState())
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

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

    fun resolve() {
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
                    _uiState.update { it.copy(isResolving = false, error = error.message ?: "解析失败") }
                }
        }
    }

    fun processSelected() {
        val item = _uiState.value.selectedItem ?: return
        val currentSettings = settings.value
        viewModelScope.launch {
            _uiState.update { it.copy(isProcessing = true, progressText = "下载媒体", error = null, outputPath = null) }
            runCatching { process(item, currentSettings) }
                .onSuccess { output ->
                    _uiState.update {
                        it.copy(
                            isProcessing = false,
                            progressText = "完成",
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
                            error = error.message ?: "处理失败"
                        )
                    }
                }
        }
    }

    private suspend fun process(item: ResolvedMediaItem, settings: ProcessingSettings): String {
        val workDir = File(context.getExternalFilesDir(null), "processing").apply { mkdirs() }
        val input = File(workDir, "source_${System.currentTimeMillis()}.${if (item.isImage) "img" else "mp4"}")
        val bytes = douyinApi.download(item.url, emptyMap())
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
                silenceThresholdDb = settings.silenceThresholdDb
            )
        )
        if (settings.deleteSourceAfterSuccess) input.delete()
        return output.absolutePath
    }

    private fun safeName(value: String): String =
        value.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").trim('_').ifBlank { "output" }
}
