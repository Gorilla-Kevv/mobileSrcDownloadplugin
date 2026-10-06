package com.clipdown.app.update

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 更新检查的 UI 状态 */
sealed interface UpdateUiState {
    /** 尚未检查（或未发起） */
    data object Idle : UpdateUiState

    /** 正在请求清单 */
    data object Checking : UpdateUiState

    /** 已是最新 */
    data class UpToDate(val currentVersionName: String) : UpdateUiState

    /** 发现新版本 */
    data class Available(val info: UpdateInfo) : UpdateUiState

    /** 失败（网络异常等） */
    data class Failed(val message: String) : UpdateUiState
}

/**
 * 更新中心：进程级单例，供「设置页手动检查」与「启动自动检查」共用同一份状态。
 *
 * 状态用 Compose 的 mutableStateOf 承载，任何界面读取都会自动重组。
 */
object UpdateCenter {

    private var appContext: Context? = null

    /** 应用启动时在 Application.onCreate 调用一次 */
    fun install(context: Context) {
        appContext = context.applicationContext
    }

    var state: UpdateUiState by mutableStateOf(UpdateUiState.Idle)
        private set

    /** 下载进度 0f..1f（仅在下载升级包时有效） */
    var progress: Float by mutableFloatStateOf(0f)
        private set

    var downloading: Boolean by mutableStateOf(false)
        private set

    /** 启动自动检查弹出过一次后，用户选择"稍后" */
    var launchPromptDismissed: Boolean by mutableStateOf(false)

    /**
     * 检查更新。
     * @return 有可用更新时返回其 [UpdateInfo]，否则 null
     */
    suspend fun check(): UpdateInfo? {
        state = UpdateUiState.Checking
        val result = UpdateRepository.fetch()
        val info = result.getOrNull()
        state = when {
            result.isFailure -> UpdateUiState.Failed(
                result.exceptionOrNull()?.message ?: "检查更新失败"
            )
            info == null -> UpdateUiState.UpToDate(UpdateRepository.currentVersionName)
            info.isNewerThan(UpdateRepository.currentVersionCode) -> UpdateUiState.Available(info)
            else -> UpdateUiState.UpToDate(UpdateRepository.currentVersionName)
        }
        return (state as? UpdateUiState.Available)?.info
    }

    /** 下载升级包并拉起系统安装器 */
    suspend fun downloadAndInstall() {
        val ctx = appContext ?: return
        val info = (state as? UpdateUiState.Available)?.info ?: return
        downloading = true
        progress = 0f
        try {
            val apk = ApkInstaller.download(ctx, info) { p -> progress = p }
            ApkInstaller.install(ctx, apk)
        } catch (t: Throwable) {
            state = UpdateUiState.Failed(t.message ?: "下载升级包失败")
        } finally {
            downloading = false
        }
    }

    /** 用户已安装未知应用权限未开时，跳系统设置 */
    fun openInstallPermission() {
        appContext?.let { ApkInstaller.openInstallPermissionSettings(it) }
    }

    fun canInstallPackages(): Boolean = appContext?.let { ApkInstaller.canInstallPackages(it) } ?: false
}
