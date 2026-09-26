package com.clipdown.downloader.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream

/**
 * 落盘写入器。
 *
 * Android 10+ 走 MediaStore（分区存储），不申请任何存储权限即可写入公共媒体目录；
 * Android 9 及以下退化为直接写公共目录文件并触发媒体扫描。
 */
object MediaStoreWriter {

    private const val APP_DIR = "ClipDown"

    /**
     * @param mimeType 决定写入 Movies / Pictures / Music 分类
     * @return 写入后的 content Uri；失败返回 null（此时调用方可保留私有目录副本）
     */
    fun save(context: Context, file: File, fileName: String, mimeType: String): Uri? {
        if (!file.exists() || file.length() == 0L) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveQ(context, file, fileName, mimeType)
        } else {
            saveLegacy(context, file, fileName)
        }
    }

    private fun saveQ(context: Context, file: File, fileName: String, mimeType: String): Uri? {
        val (collection, relativePath) = when {
            mimeType.startsWith("video") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI to
                "${Environment.DIRECTORY_MOVIES}/$APP_DIR"
            mimeType.startsWith("audio") -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to
                "${Environment.DIRECTORY_MUSIC}/$APP_DIR"
            else -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI to
                "${Environment.DIRECTORY_PICTURES}/$APP_DIR"
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = context.contentResolver.insert(collection, values) ?: return null
        return try {
            context.contentResolver.openOutputStream(uri)?.use { os: OutputStream ->
                FileInputStream(file).use { it.copyTo(os) }
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
            uri
        } catch (e: Exception) {
            context.contentResolver.delete(uri, null, null)
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(context: Context, file: File, fileName: String): Uri? {
        return runCatching {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                APP_DIR
            )
            if (!dir.exists()) dir.mkdirs()
            val target = File(dir, fileName)
            file.copyTo(target, overwrite = true)
            Uri.fromFile(target)
        }.getOrNull()
    }

    /** 删除已入库的媒体文件 */
    fun delete(context: Context, uriString: String?) {
        val uri = uriString?.let { Uri.parse(it) } ?: return
        runCatching { context.contentResolver.delete(uri, null, null) }
    }
}
