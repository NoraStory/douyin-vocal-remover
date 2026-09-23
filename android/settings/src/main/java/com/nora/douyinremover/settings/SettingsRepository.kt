package com.nora.douyinremover.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.appDataStore by preferencesDataStore(name = "processing_settings")

class SettingsRepository(private val context: Context) {
    private object Keys {
        val outputFormat = stringPreferencesKey("output_format")
        val deleteSource = booleanPreferencesKey("delete_source")
        val keepHistory = booleanPreferencesKey("keep_history")
        val inferenceBackend = stringPreferencesKey("inference_backend")
        val silenceThreshold = floatPreferencesKey("silence_threshold")
        val outputDirectory = stringPreferencesKey("output_directory")
        val proxyUrl = stringPreferencesKey("proxy_url")
        val showGuideOnLaunch = booleanPreferencesKey("show_guide_on_launch")
    }

    val settings: Flow<ProcessingSettings> = context.appDataStore.data.map { values ->
        ProcessingSettings(
            outputFormat = values[Keys.outputFormat]
                ?.let { value -> AudioOutputFormat.entries.firstOrNull { it.name == value } }
                ?: AudioOutputFormat.MP3_320,
            deleteSourceAfterSuccess = values[Keys.deleteSource] ?: false,
            keepHistory = values[Keys.keepHistory] ?: true,
            inferenceBackend = values[Keys.inferenceBackend]
                ?.let { value -> InferenceBackend.entries.firstOrNull { it.name == value } }
                ?: InferenceBackend.AUTO,
            silenceThresholdDb = values[Keys.silenceThreshold] ?: -55f,
            outputDirectoryUri = values[Keys.outputDirectory],
            proxyUrl = values[Keys.proxyUrl],
            showGuideOnLaunch = values[Keys.showGuideOnLaunch] ?: true
        )
    }

    suspend fun update(transform: (ProcessingSettings) -> ProcessingSettings) {
        val current = readCurrent()
        val updated = transform(current)
        context.appDataStore.edit { values ->
            values[Keys.outputFormat] = updated.outputFormat.name
            values[Keys.deleteSource] = updated.deleteSourceAfterSuccess
            values[Keys.keepHistory] = updated.keepHistory
            values[Keys.inferenceBackend] = updated.inferenceBackend.name
            values[Keys.silenceThreshold] = updated.silenceThresholdDb
            values[Keys.outputDirectory] = updated.outputDirectoryUri ?: ""
            values[Keys.proxyUrl] = updated.proxyUrl ?: ""
            values[Keys.showGuideOnLaunch] = updated.showGuideOnLaunch
        }
    }

    private suspend fun readCurrent(): ProcessingSettings {
        return context.appDataStore.data.map { values ->
            ProcessingSettings(
                outputFormat = values[Keys.outputFormat]
                    ?.let { value -> AudioOutputFormat.entries.firstOrNull { it.name == value } }
                    ?: AudioOutputFormat.MP3_320,
                deleteSourceAfterSuccess = values[Keys.deleteSource] ?: false,
                keepHistory = values[Keys.keepHistory] ?: true,
                inferenceBackend = values[Keys.inferenceBackend]
                    ?.let { value -> InferenceBackend.entries.firstOrNull { it.name == value } }
                    ?: InferenceBackend.AUTO,
                silenceThresholdDb = values[Keys.silenceThreshold] ?: -55f,
                outputDirectoryUri = values[Keys.outputDirectory],
                proxyUrl = values[Keys.proxyUrl],
                showGuideOnLaunch = values[Keys.showGuideOnLaunch] ?: true
            )
        }.first()
    }
}
