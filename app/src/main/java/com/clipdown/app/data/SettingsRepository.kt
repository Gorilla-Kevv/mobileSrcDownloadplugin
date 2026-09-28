package com.clipdown.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.clipdown.parser.config.ParserConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "clipdown_settings")

/**
 * 应用设置仓库。
 *
 * 采用 DataStore 持久化，所有读取都以 Flow 暴露，UI 层可直接 collectAsState。
 * 需要同步读取的场景（如 Application 启动、服务初始化）使用 [snapshot]。
 */
class SettingsRepository(private val context: Context) {

    object Keys {
        val FLOAT_ENABLED = booleanPreferencesKey("float_enabled")
        val BUBBLE_X = intPreferencesKey("bubble_x")
        val BUBBLE_Y = intPreferencesKey("bubble_y")
        val AUTO_POPUP = booleanPreferencesKey("auto_popup")
        val AUTO_DOWNLOAD = booleanPreferencesKey("auto_download")
        val POPUP_AUTO_DISMISS = intPreferencesKey("popup_auto_dismiss_ms")
        val WIFI_ONLY = booleanPreferencesKey("wifi_only")
        val MAX_CONCURRENT = intPreferencesKey("max_concurrent")
        val SAVE_ALBUM = booleanPreferencesKey("save_album")
        val REMOTE_ENDPOINT = stringPreferencesKey("remote_endpoint")
        val REMOTE_TOKEN = stringPreferencesKey("remote_token")
        val REMOTE_ENABLED = booleanPreferencesKey("remote_enabled")
        val ENABLED_PLATFORMS = stringPreferencesKey("enabled_platforms")
        val ACCESSIBILITY_HINT_SHOWN = booleanPreferencesKey("a11y_hint_shown")
        val SEEN_LINKS = stringSetPreferencesKey("seen_links")
    }

    val floatEnabled: Flow<Boolean> = context.settingsDataStore.data.map { it[Keys.FLOAT_ENABLED] ?: true }
    val autoPopup: Flow<Boolean> = context.settingsDataStore.data.map { it[Keys.AUTO_POPUP] ?: true }

    /** 解析成功后自动下载（单资源/视频变体组直下；图集等仍弹选择卡） */
    val autoDownload: Flow<Boolean> = context.settingsDataStore.data.map { it[Keys.AUTO_DOWNLOAD] ?: true }
    val wifiOnly: Flow<Boolean> = context.settingsDataStore.data.map { it[Keys.WIFI_ONLY] ?: false }
    val maxConcurrent: Flow<Int> = context.settingsDataStore.data.map { it[Keys.MAX_CONCURRENT] ?: 3 }
    val saveToAlbum: Flow<Boolean> = context.settingsDataStore.data.map { it[Keys.SAVE_ALBUM] ?: true }
    val remoteEndpoint: Flow<String> = context.settingsDataStore.data.map { it[Keys.REMOTE_ENDPOINT] ?: DEFAULT_REMOTE }
    val remoteToken: Flow<String> = context.settingsDataStore.data.map { it[Keys.REMOTE_TOKEN] ?: "" }
    val remoteEnabled: Flow<Boolean> = context.settingsDataStore.data.map { it[Keys.REMOTE_ENABLED] ?: false }
    val enabledPlatforms: Flow<Set<String>> =
        context.settingsDataStore.data.map { parsePlatforms(it[Keys.ENABLED_PLATFORMS]) }

    /** 已识别过的链接记忆（持久化）：命中者不再自动弹窗，仅用户手动识别时触发 */
    val seenLinks: Flow<Set<String>> = context.settingsDataStore.data.map { it[Keys.SEEN_LINKS] ?: emptySet() }

    suspend fun setFloatEnabled(value: Boolean) = edit { it[Keys.FLOAT_ENABLED] = value }
    suspend fun setAutoPopup(value: Boolean) = edit { it[Keys.AUTO_POPUP] = value }
    suspend fun setAutoDownload(value: Boolean) = edit { it[Keys.AUTO_DOWNLOAD] = value }
    suspend fun setWifiOnly(value: Boolean) = edit { it[Keys.WIFI_ONLY] = value }
    suspend fun setMaxConcurrent(value: Int) = edit { it[Keys.MAX_CONCURRENT] = value.coerceIn(1, 8) }
    suspend fun setSaveToAlbum(value: Boolean) = edit { it[Keys.SAVE_ALBUM] = value }
    suspend fun setRemoteEndpoint(value: String) = edit { it[Keys.REMOTE_ENDPOINT] = value.trim() }
    suspend fun setRemoteToken(value: String) = edit { it[Keys.REMOTE_TOKEN] = value.trim() }
    suspend fun setRemoteEnabled(value: Boolean) = edit { it[Keys.REMOTE_ENABLED] = value }
    suspend fun setEnabledPlatforms(value: Set<String>) = edit { it[Keys.ENABLED_PLATFORMS] = value.joinToString(",") }
    suspend fun saveBubblePosition(x: Int, y: Int) = edit { it[Keys.BUBBLE_X] = x; it[Keys.BUBBLE_Y] = y }

    /** 记录一条已识别链接（带上限防无限膨胀） */
    suspend fun markLinkSeen(url: String) = edit { prefs ->
        val current = prefs[Keys.SEEN_LINKS] ?: emptySet()
        val merged = if (current.size >= SEEN_LIMIT) current.drop(current.size / 2).toSet() + url
        else current + url
        prefs[Keys.SEEN_LINKS] = merged
    }

    suspend fun bubblePosition(): Pair<Int, Int> {
        val p = context.settingsDataStore.data.first()
        return (p[Keys.BUBBLE_X] ?: -1) to (p[Keys.BUBBLE_Y] ?: -1)
    }

    suspend fun popupDismissMs(): Int {
        val p = context.settingsDataStore.data.first()
        return p[Keys.POPUP_AUTO_DISMISS] ?: 15_000
    }

    /** 供 Application / Service 在启动时同步读取一次配置快照 */
    suspend fun snapshot(): SettingsSnapshot = SettingsSnapshot(
        floatEnabled = context.settingsDataStore.data.map { it[Keys.FLOAT_ENABLED] ?: true }.first(),
        remoteEnabled = context.settingsDataStore.data.map { it[Keys.REMOTE_ENABLED] ?: false }.first(),
        remoteEndpoint = context.settingsDataStore.data.map { it[Keys.REMOTE_ENDPOINT] ?: DEFAULT_REMOTE }.first(),
        remoteToken = context.settingsDataStore.data.map { it[Keys.REMOTE_TOKEN] ?: "" }.first(),
        enabledPlatforms = parsePlatforms(context.settingsDataStore.data.map { it[Keys.ENABLED_PLATFORMS] }.first()),
        maxConcurrent = context.settingsDataStore.data.map { it[Keys.MAX_CONCURRENT] ?: 3 }.first(),
        wifiOnly = context.settingsDataStore.data.map { it[Keys.WIFI_ONLY] ?: false }.first()
    )

    fun parserConfig(): ParserConfig = ParserConfig(
        remoteEndpoint = runCatching { kotlinx.coroutines.runBlocking { remoteEndpoint.first() } }.getOrDefault(DEFAULT_REMOTE),
        remoteEnabled = runCatching { kotlinx.coroutines.runBlocking { remoteEnabled.first() } }.getOrDefault(false),
        remoteToken = runCatching { kotlinx.coroutines.runBlocking { remoteToken.first() } }.getOrDefault(""),
        enabledPlatforms = runCatching { kotlinx.coroutines.runBlocking { enabledPlatforms.first() } }.getOrDefault(emptySet())
    )

    private suspend fun edit(action: suspend (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsDataStore.edit(action)
    }

    private fun parsePlatforms(raw: String?): Set<String> =
        if (raw.isNullOrBlank()) emptySet() else raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    data class SettingsSnapshot(
        val floatEnabled: Boolean,
        val remoteEnabled: Boolean,
        val remoteEndpoint: String,
        val remoteToken: String,
        val enabledPlatforms: Set<String>,
        val maxConcurrent: Int,
        val wifiOnly: Boolean
    )

    companion object {
        /** 默认远端兜底解析服务：官方 cobalt 公共实例，用户可自建后替换 */
        const val DEFAULT_REMOTE = "https://api.cobalt.tools"

        /** 识别记忆上限 */
        private const val SEEN_LIMIT = 400
    }
}
