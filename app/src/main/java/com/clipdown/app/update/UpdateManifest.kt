package com.clipdown.app.update

import kotlinx.serialization.Serializable

/**
 * 发布端生成的更新清单（`update.json`，随 GitHub Release 资产一起上传）。
 *
 * 约定：**versionCode 是唯一的比较依据**（versionName 只用于展示），
 * 因此发布脚本必须保证 versionCode 单调递增。
 */
@Serializable
data class UpdateManifest(
    val versionCode: Int = 0,
    val versionName: String = "",
    val notes: String = "",
    /** 为空时回落到 BuildConfig.UPDATE_APK_URL（release/latest/download/<apkName>） */
    val apkUrl: String? = null,
    /** 可选：APK 的 sha256，下载后校验 */
    val sha256: String? = null,
    val sizeBytes: Long? = null,
    /** 强制更新（暂只用于文案提示，不阻塞使用） */
    val mandatory: Boolean = false
)

/** 归一化后的更新信息 */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val notes: String,
    val apkUrl: String,
    val sha256: String?,
    val sizeBytes: Long?,
    val mandatory: Boolean
) {
    fun isNewerThan(currentVersionCode: Int): Boolean = versionCode > currentVersionCode

    fun sizeText(): String = when {
        sizeBytes == null || sizeBytes <= 0 -> ""
        sizeBytes < 1024 * 1024 -> "%.0f KB".format(sizeBytes / 1024.0)
        else -> "%.1f MB".format(sizeBytes / 1024.0 / 1024.0)
    }
}
