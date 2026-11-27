package com.endpix.nativeapp.ui

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * 缓存项目的公共备份: files/cache_projects 会随卸载删除, 所以每个缓存额外复制一份到
 * 公共下载目录 Download/Endpix/cache_projects。重装后启动时自动把这些文件恢复回内部目录,
 * 缓存列表就能"应用删了再下载回来还在"。
 *
 * 写入: Android 10+ 走 MediaStore.Downloads(无需权限); Android 9- 写公共目录(WRITE_EXTERNAL_STORAGE)。
 * 读取: 重装后 MediaStore 只返回"本应用所有"的文件, 旧安装的会被隐藏, 因此：
 *   - 用户在缓存列表里授予「所有文件访问」(MANAGE_EXTERNAL_STORAGE) 后, 直接按路径读写, 重装后可恢复;
 *   - 未授权时同一次安装内仍可用 MediaStore 恢复; 未授权+重装则缓存文件仍在下载目录,
 *     缓存列表会提示去授权, 授权后自动恢复。
 */
object CacheBackup {
    private const val REL = "Download/Endpix/cache_projects/"
    private const val MIME_BIN = "application/octet-stream"
    private const val MIME_WEBP = "image/webp"

    private fun collection(): Uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI

    /** 公共下载目录(有「所有文件访问」权限时可直接读写). */
    fun publicDir(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "Endpix/cache_projects").apply { runCatching { if (!exists()) mkdirs() } }

    /** Android 11+ 且用户已授予「所有文件访问」. */
    fun hasAllFiles(): Boolean =
        Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()

    private fun primarySuffix(id: String): String = if (id.startsWith("r_")) ".replay" else ".snap"
    private fun suffixes(id: String): List<String> = listOf(primarySuffix(id), ".webp", ".meta")

    private fun mimeOf(name: String) = if (name.endsWith(".webp")) MIME_WEBP else MIME_BIN

    /** 把内部缓存 c_<id>.snap/.webp/.meta 备份到公共下载目录(覆盖同名旧文件). */
    fun backup(ctx: Context, id: String) {
        try {
            if (hasAllFiles()) {
                copyToPublic(ctx, id)
            } else if (Build.VERSION.SDK_INT >= 29) {
                val dir = projectCacheDir(ctx)
                for (suf in suffixes(id)) {
                    val src = File(dir, id + suf)
                    if (!src.exists() || src.length() <= 0L) continue
                    putMedia(ctx, id + suf, mimeOf(id + suf), src)
                }
            } else {
                copyToPublic(ctx, id)
            }
        } catch (_: Throwable) { }
    }

    /** 删除公共备份(缓存列表里删条目时调用). */
    fun delete(ctx: Context, id: String) {
        try {
            if (hasAllFiles() || Build.VERSION.SDK_INT < 29) {
                val dir = publicDir()
                for (suf in suffixes(id)) File(dir, id + suf).delete()
            } else {
                for (suf in suffixes(id)) {
                    try {
                        ctx.contentResolver.delete(
                            collection(),
                            "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?",
                            arrayOf(id + suf, REL)
                        )
                    } catch (_: Throwable) { }
                }
            }
        } catch (_: Throwable) { }
    }

    /** 启动/打开缓存列表时调用: 把公共目录里内部缺失的缓存文件恢复回 files/cache_projects, 返回恢复条数. */
    fun restore(ctx: Context): Int {
        return try {
            when {
                hasAllFiles() -> restoreDirect(ctx)
                Build.VERSION.SDK_INT >= 29 -> restoreMedia(ctx)
                else -> restoreDirect(ctx)
            }
        } catch (_: Throwable) { 0 }
    }

    /** 老版本已有缓存没有公共备份: 启动时补一份(只补公共目录里缺的). */
    fun backupAll(ctx: Context) {
        try {
            val dir = projectCacheDir(ctx)
            val bases = dir.listFiles()
                ?.filter {
                    it.isFile && (
                        (it.name.startsWith("c_") && it.name.endsWith(".snap")) ||
                        (it.name.startsWith("r_") && it.name.endsWith(".replay"))
                    )
                }
                ?.map { it.name.substringBeforeLast('.') }
                ?: return
            if (bases.isEmpty()) return
            if (hasAllFiles()) {
                val pub = publicDir()
                for (b in bases) if (!File(pub, b + primarySuffix(b)).exists()) copyToPublic(ctx, b)
                return
            }
            if (Build.VERSION.SDK_INT >= 29) {
                val existing = HashSet<String>()
                try {
                    ctx.contentResolver.query(
                        collection(),
                        arrayOf(MediaStore.Downloads.DISPLAY_NAME),
                        "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
                        arrayOf("Download/Endpix/cache_projects%"),
                        null
                    )?.use { c ->
                        val ni = c.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                        while (c.moveToNext()) c.getString(ni)?.let { existing.add(it) }
                    }
                } catch (_: Throwable) { }
                for (b in bases) if (b + primarySuffix(b) !in existing) backup(ctx, b)
            } else {
                val pub = publicDir()
                for (b in bases) if (!File(pub, b + primarySuffix(b)).exists()) backup(ctx, b)
            }
        } catch (_: Throwable) { }
    }

    private fun copyToPublic(ctx: Context, id: String) {
        val srcDir = projectCacheDir(ctx)
        val dst = publicDir()
        for (suf in suffixes(id)) {
            val src = File(srcDir, id + suf)
            if (!src.exists() || src.length() <= 0L) continue
            try {
                src.inputStream().use { i -> File(dst, id + suf).outputStream().use { o -> i.copyTo(o) } }
            } catch (_: Throwable) { }
        }
    }

    private fun putMedia(ctx: Context, name: String, mime: String, src: File) {
        val resolver = ctx.contentResolver
        try {
            resolver.delete(
                collection(),
                "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?",
                arrayOf(name, REL)
            )
        } catch (_: Throwable) { }
        val cv = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, REL)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = try { resolver.insert(collection(), cv) } catch (_: Throwable) { null } ?: return
        try {
            resolver.openOutputStream(uri)?.use { o -> src.inputStream().use { i -> i.copyTo(o) } }
            val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        } catch (_: Throwable) {
            try { resolver.delete(uri, null, null) } catch (_: Throwable) { }
        }
    }

    private fun restoreMedia(ctx: Context): Int {
        val resolver = ctx.contentResolver
        val map = HashMap<String, Uri>()
        try {
            resolver.query(
                collection(),
                arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME),
                "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
                arrayOf("Download/Endpix/cache_projects%"),
                null
            )?.use { c ->
                val ni = c.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                val ii = c.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                while (c.moveToNext()) {
                    val nm = c.getString(ni) ?: continue
                    if ((!nm.startsWith("c_") && !nm.startsWith("r_")) || !nm.contains('.')) continue
                    map[nm] = ContentUris.withAppendedId(collection(), c.getLong(ii))
                }
            }
        } catch (_: Throwable) { }
        if (map.isEmpty()) return 0
        val dir = projectCacheDir(ctx)
        var restored = 0
        for ((name, uri) in map) {
            if (!name.endsWith(".snap") && !name.endsWith(".replay")) continue
            val dst = File(dir, name)
            if (dst.exists() && dst.length() > 64L) continue
            val base = name.substringBeforeLast('.')
            var ok = false
            for (suf in suffixes(base)) {
                val u = map[base + suf] ?: continue
                try {
                    resolver.openInputStream(u)?.use { i ->
                        File(dir, base + suf).outputStream().use { o -> i.copyTo(o) }
                    }
                    if (suf == primarySuffix(base)) ok = true
                } catch (_: Throwable) { }
            }
            if (ok && dst.length() > 64L) restored++
        }
        return restored
    }

    private fun restoreDirect(ctx: Context): Int {
        val pub = publicDir()
        if (!pub.exists() || !pub.canRead()) return 0
        val dir = projectCacheDir(ctx)
        var restored = 0
        pub.listFiles()?.forEach { f ->
            if (!f.isFile || (!f.name.startsWith("c_") && !f.name.startsWith("r_"))) return@forEach
            val dst = File(dir, f.name)
            if (dst.exists() && dst.length() > 0L) return@forEach
            try {
                f.inputStream().use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
                if ((f.name.endsWith(".snap") || f.name.endsWith(".replay")) && dst.length() > 64L) restored++
            } catch (_: Throwable) { }
        }
        return restored
    }
}
