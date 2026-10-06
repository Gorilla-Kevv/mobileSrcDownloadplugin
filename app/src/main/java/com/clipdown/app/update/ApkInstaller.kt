package com.clipdown.app.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import com.clipdown.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * APK 下载与安装。
 *
 * 流程：下载到应用缓存目录 → FileProvider 授权 URI → 交给系统安装器。
 * 覆盖安装要求**新旧 APK 签名一致**（本工程用固定 keystore，见 signing.properties），
 * 签名一致时系统会原地升级并保留数据；签名不同会提示"应用未安装"。
 */
object ApkInstaller {

    private const val DIR = "update"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** 已下载的 APK 落点（缓存目录，系统可回收；升级后无用） */
    fun apkFile(context: Context, versionName: String): File {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        return File(dir, "ClipDown-$versionName.apk")
    }

    /**
     * 下载 APK。已存在且大小匹配时直接复用（重复点"下载并安装"不会重下）。
     * 必须在协程里调用（内部切 IO）。
     */
    suspend fun download(
        context: Context,
        info: UpdateInfo,
        onProgress: (Float) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        val target = apkFile(context, info.versionName)
        if (target.exists() && target.length() > 0 &&
            (info.sizeBytes == null || target.length() == info.sizeBytes)
        ) {
            onProgress(1f)
            return@withContext target
        }

        val tmp = File(target.parentFile, "${target.name}.part")
        val request = Request.Builder()
            .url(info.apkUrl)
            .header("Accept", "application/octet-stream")
            .header("User-Agent", "ClipDown-Updater/${BuildConfig.VERSION_NAME}")
            .build()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("下载失败：HTTP ${resp.code}")
            val body = resp.body ?: error("下载失败：响应为空")
            val total = body.contentLength().takeIf { it > 0 } ?: (info.sizeBytes ?: -1L)
            tmp.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        if (total > 0) onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }

        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }

        info.sha256?.let { expect ->
            val actual = sha256(target)
            if (!actual.equals(expect, ignoreCase = true)) {
                target.delete()
                error("安装包校验失败（sha256 不匹配），已删除")
            }
        }
        target
    }

    /** 交给系统安装器（用户需在系统弹窗里确认） */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** 是否已授予"安装未知应用"权限（Android 8+ 覆盖安装必须） */
    fun canInstallPackages(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /** 跳系统设置让用户开"安装未知应用" */
    fun openInstallPermissionSettings(context: Context) {
        val pkg = context.packageName
        val intents = listOf(
            Intent("android.settings.MANAGE_UNKNOWN_APP_SOURCES").setData(android.net.Uri.parse("package:$pkg")),
            Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
        )
        intents.firstOrNull { it.resolveActivity(context.packageManager) != null }?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(it)
        }
    }

    @Suppress("unused")
    fun hasInstallPermission(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.REQUEST_INSTALL_PACKAGES) ==
            PackageManager.PERMISSION_GRANTED

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
