package com.clipdown.app.update

import com.clipdown.app.BuildConfig
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 更新检查。
 *
 * 通道设计（重要）：
 * - 清单地址用 **`releases/latest/download/update.json`** 这种"最新发布固定链接"，
 *   不走 GitHub API —— 既不需要 token，也不吃 60 次/小时的匿名速率限制。
 * - 因此发布仓库**必须是公开仓库**：私有仓库的 Release 资产无法匿名下载。
 *   源码仓库保持私有，APK 与清单发布到公开分发仓库（见 gradle.properties 的 clipdown.updateRepo）。
 */
object UpdateRepository {

    private const val TAG = "ClipDownUpdate"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * 拉取更新清单。
     * @return 成功时返回 [UpdateInfo]；**尚无任何发布时返回 null**（不是错误）；失败时返回 failure。
     */
    fun fetch(): Result<UpdateInfo?> = runCatching {
        val manifestUrl = BuildConfig.UPDATE_MANIFEST_URL
        android.util.Log.d(TAG, "检查更新：$manifestUrl")
        val request = Request.Builder()
            .url(manifestUrl)
            .header("Accept", "application/json")
            .header("User-Agent", "ClipDown-Updater/${BuildConfig.VERSION_NAME}")
            .build()

        client.newCall(request).execute().use { resp ->
            android.util.Log.d(TAG, "清单响应：HTTP ${resp.code}")
            when {
                // 还没有发布过任何 Release：视为"无更新"，不报错
                resp.code == 404 -> null
                !resp.isSuccessful -> error("检查更新失败：HTTP ${resp.code}")
                else -> {
                    val body = resp.body?.string().orEmpty()
                    if (body.isBlank()) null else parse(body)
                }
            }
        }
    }.onFailure {
        android.util.Log.w(TAG, "检查更新异常：${it.javaClass.simpleName}: ${it.message}", it)
    }

    /** 解析清单（独立出来便于单测） */
    fun parse(body: String): UpdateInfo {
        val m = json.decodeFromString<UpdateManifest>(body)
        return UpdateInfo(
            versionCode = m.versionCode,
            versionName = m.versionName.ifBlank { "未知版本" },
            notes = m.notes.trim(),
            apkUrl = m.apkUrl?.takeIf { it.isNotBlank() } ?: BuildConfig.UPDATE_APK_URL,
            sha256 = m.sha256?.takeIf { it.isNotBlank() },
            sizeBytes = m.sizeBytes?.takeIf { it > 0 },
            mandatory = m.mandatory
        )
    }

    /** 当前安装版本号（比较基准） */
    val currentVersionCode: Int get() = BuildConfig.VERSION_CODE

    val currentVersionName: String get() = BuildConfig.VERSION_NAME
}
