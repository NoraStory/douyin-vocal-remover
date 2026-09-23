package com.nora.douyinremover.updater

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.updateDataStore by preferencesDataStore(name = "update_state")

/** 更新检查状态（UI 观察用） */
data class UpdateState(
    val latestVersion: String = "",
    val apkUrl: String = "",
    val apkSize: Long = -1,
    val releaseNotes: String = "",
    val source: String = "",
    val lastCheckedAt: Long = 0
) {
    val hasUpdate: Boolean get() = latestVersion.isNotBlank()
}

class UpdateRepository(private val context: Context) {

    private object Keys {
        val latestVersion = stringPreferencesKey("latest_version")
        val apkUrl = stringPreferencesKey("apk_url")
        val apkSize = longPreferencesKey("apk_size")
        val releaseNotes = stringPreferencesKey("release_notes")
        val source = stringPreferencesKey("source")
        val lastCheckedAt = longPreferencesKey("last_checked_at")
    }

    val state: Flow<UpdateState> = context.updateDataStore.data.map { v ->
        UpdateState(
            latestVersion = v[Keys.latestVersion].orEmpty(),
            apkUrl = v[Keys.apkUrl].orEmpty(),
            apkSize = v[Keys.apkSize] ?: -1L,
            releaseNotes = v[Keys.releaseNotes].orEmpty(),
            source = v[Keys.source].orEmpty(),
            lastCheckedAt = v[Keys.lastCheckedAt] ?: 0L
        )
    }

    suspend fun save(info: UpdateInfo, currentVersion: String) {
        context.updateDataStore.edit { v ->
            v[Keys.latestVersion] = info.latestVersion
            v[Keys.apkUrl] = info.apkUrl
            v[Keys.apkSize] = info.apkSize
            v[Keys.releaseNotes] = info.releaseNotes
            v[Keys.source] = info.source
            v[Keys.lastCheckedAt] = System.currentTimeMillis()
        }
    }

    suspend fun clear() {
        context.updateDataStore.edit { it.clear() }
    }
}
