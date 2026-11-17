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
 *
 * **阶段 84 关键约定（消除双份存储）**：
 * 本类只负责把私有目录里的临时文件**搬进**相册，调用方在拿到返回 Uri 后
 * 必须删除私有副本（见 [DownloadEngine]）。相册里的那份才是唯一实体，
 * 私有目录仅作为下载中转，避免同一文件占两份空间。
 */
object MediaStoreWriter {

    /** 默认子目录名（用户未自定义时） */
    const val DEFAULT_APP_DIR = "ClipDown"

    /**
     * @param mimeType 决定写入 Movies / Pictures / Music 分类
     * @param subDir 自定义子目录（相对公共媒体根，已由 DownloadConfig 规整）
     * @return 写入后的 content Uri；失败返回 null（此时调用方应保留私有目录副本）
     */
    fun save(
        context: Context,
        file: File,
        fileName: String,
        mimeType: String,
        subDir: String = DEFAULT_APP_DIR
    ): Uri? {
        if (!file.exists() || file.length() == 0L) return null
        val dir = subDir.ifBlank { DEFAULT_APP_DIR }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveQ(context, file, fileName, mimeType, dir)
        } else {
            saveLegacy(context, file, fileName, dir)
        }
    }

    private fun saveQ(
        context: Context,
        file: File,
        fileName: String,
        mimeType: String,
        subDir: String
    ): Uri? {
        val (collection, rootDir) = when {
            mimeType.startsWith("video") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI to
                Environment.DIRECTORY_MOVIES
            mimeType.startsWith("audio") -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to
                Environment.DIRECTORY_MUSIC
            else -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI to
                Environment.DIRECTORY_PICTURES
        }
        val relativePath = "$rootDir/$subDir"

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
    private fun saveLegacy(
        context: Context,
        file: File,
        fileName: String,
        subDir: String
    ): Uri? {
        return runCatching {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                subDir
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

    /**
     * 删除私有目录里的下载中转文件（阶段 84）。
     *
     * 只在**确认相册那份已写入成功**后调用，避免用户既占私有空间又占相册空间。
     * 删除失败不抛异常：残留文件会在下次启动的孤儿清理里被回收。
     */
    fun deletePrivateCopy(file: File?): Boolean {
        if (file == null) return true
        return runCatching {
            if (!file.exists()) true else file.delete()
        }.getOrDefault(false)
    }
}
