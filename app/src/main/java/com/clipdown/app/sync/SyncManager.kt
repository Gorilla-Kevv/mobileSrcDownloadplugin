package com.clipdown.app.sync

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * 跨端同步管理器单例。
 *
 * 负责本地 SyncServer 服务的启动/停止控制、局域网 IP 检测、
 * 6位安全配对码（PIN）生成与持久化、连接状态监听。
 */
object SyncManager {

    private const val TAG = "SyncManager"
    private const val PREFS_NAME = "clipdown_sync_prefs"
    private const val KEY_PIN = "sync_pin"
    private const val KEY_PORT = "sync_port"
    private const val KEY_AUTO_START = "sync_auto_start"

    const val DEFAULT_PORT = 8765

    private var server: SyncServer? = null
    private var appContext: Context? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _isServerRunning = MutableStateFlow(false)
    val isServerRunning: StateFlow<Boolean> = _isServerRunning.asStateFlow()

    private val _localIp = MutableStateFlow<String?>(null)
    val localIp: StateFlow<String?> = _localIp.asStateFlow()

    private val _port = MutableStateFlow(DEFAULT_PORT)
    val port: StateFlow<Int> = _port.asStateFlow()

    private val _pairingPin = MutableStateFlow("123456")
    val pairingPin: StateFlow<String> = _pairingPin.asStateFlow()

    private val _lastSyncTime = MutableStateFlow(0L)
    val lastSyncTime: StateFlow<Long> = _lastSyncTime.asStateFlow()

    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        val sp = getPrefs(app)

        var pin = sp.getString(KEY_PIN, null)
        if (pin.isNullOrBlank() || pin.length != 6) {
            pin = generatePin()
            sp.edit().putString(KEY_PIN, pin).apply()
        }
        _pairingPin.value = pin
        _port.value = sp.getInt(KEY_PORT, DEFAULT_PORT)
        refreshIp()

        val autoStart = sp.getBoolean(KEY_AUTO_START, false)
        if (autoStart) {
            startServer()
        }
    }

    fun refreshIp() {
        _localIp.value = NetworkUtil.getLocalIpAddress()
    }

    fun startServer(): Boolean {
        val ctx = appContext ?: return false
        if (server?.isRunning == true) {
            _isServerRunning.value = true
            return true
        }

        refreshIp()
        val s = SyncServer(
            context = ctx,
            port = _port.value,
            pinProvider = { _pairingPin.value }
        )
        s.start()
        server = s
        _isServerRunning.value = s.isRunning
        getPrefs(ctx).edit().putBoolean(KEY_AUTO_START, true).apply()
        Log.i(TAG, "SyncServer started, status: ${s.isRunning}, IP: ${_localIp.value}:${_port.value}")
        return s.isRunning
    }

    fun stopServer() {
        server?.close()
        server = null
        _isServerRunning.value = false
        appContext?.let {
            getPrefs(it).edit().putBoolean(KEY_AUTO_START, false).apply()
        }
        Log.i(TAG, "SyncServer stopped")
    }

    fun toggleServer(): Boolean {
        return if (_isServerRunning.value) {
            stopServer()
            false
        } else {
            startServer()
        }
    }

    fun regeneratePin(): String {
        val newPin = generatePin()
        _pairingPin.value = newPin
        appContext?.let {
            getPrefs(it).edit().putString(KEY_PIN, newPin).apply()
        }
        return newPin
    }

    fun getConnectUrl(): String {
        val ip = _localIp.value ?: "127.0.0.1"
        return "http://$ip:${_port.value}"
    }

    private fun generatePin(): String {
        return (100000..999999).random().toString()
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
