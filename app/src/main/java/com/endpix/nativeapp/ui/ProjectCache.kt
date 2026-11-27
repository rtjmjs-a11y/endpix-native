package com.endpix.nativeapp.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import java.io.File

/**
 * 缓存项目: 关闭标签/导出项目时把当前完整快照(画布+图层+帧+撤销步骤)保留到列表。
 * 结构尽量简单: 每个缓存 = 1 个主文件(.snap 画布 / .replay 回放) + 1 个 .webp 缩略图 + 1 个 .meta 文本。
 * 画布缓存前缀 c_, 回放缓存前缀 r_; 缓存列表按类型分区显示。
 */
data class CacheProject(
    val id: String,
    val name: String,
    val w: Int,
    val h: Int,
    val timeMs: Long,
    val snap: File,          // 主文件: 画布=.snap, 回放=.replay
    val thumb: File?,
    val sizeBytes: Long,
    val kind: Int = 0,       // 0=画布 1=回放
    val extra: String = "",  // 回放: 帧数等信息
) {
    val isReplay: Boolean get() = kind == 1
}

object ToolHub {
    var current by mutableIntStateOf(0)   // 当前工具实时值(MainActivity 切换时立刻写)
}

object StudioHub {
    var mode by mutableIntStateOf(-1)   // -1 关闭; 0 新建 / 1 调整大小 / 2 模板库 / 3 缓存列表
}

object ProjectCacheHub {
    var open: (String) -> Unit = {}
    var openReplay: (String) -> Unit = {}
    var delete: (String) -> Unit = {}
    var saveActive: (onDone: (Boolean) -> Unit) -> Unit = { it(false) }
    var rev by mutableIntStateOf(0)
    fun bump() { rev++ }
}

/** 模板库/缓存列表的导出模式: 导出文件 or 分享到其他应用. */
object StudioShareHub {
    var shareTemplate: ((String) -> Unit)? = null
    var exportCache: ((String) -> Unit)? = null
    var shareCache: ((String) -> Unit)? = null
}

fun projectCacheDir(ctx: Context): File =
    File(ctx.filesDir, "cache_projects").apply { if (!exists()) mkdirs() }

private fun primarySuffixOf(name: String): String? = when {
    name.startsWith("c_") && name.endsWith(".snap") -> ".snap"
    name.startsWith("r_") && name.endsWith(".replay") -> ".replay"
    else -> null
}

fun loadCacheProjects(ctx: Context): List<CacheProject> {
    val dir = projectCacheDir(ctx)
    return dir.listFiles()
        ?.filter { it.isFile && primarySuffixOf(it.name) != null }
        ?.mapNotNull { f ->
            val suf = primarySuffixOf(f.name) ?: return@mapNotNull null
            val id = f.name.removeSuffix(suf)
            val meta = File(dir, "$id.meta")
            var name = ""
            var w = 0
            var h = 0
            var t = 0L
            var kind = if (suf == ".replay") 1 else 0
            var extra = ""
            try {
                if (meta.exists()) {
                    val lines = meta.readLines()
                    name = lines.getOrNull(0) ?: ""
                    w = lines.getOrNull(1)?.toIntOrNull() ?: 0
                    h = lines.getOrNull(2)?.toIntOrNull() ?: 0
                    t = lines.getOrNull(3)?.toLongOrNull() ?: 0L
                    kind = lines.getOrNull(4)?.toIntOrNull() ?: kind
                    extra = lines.getOrNull(5) ?: ""
                }
            } catch (_: Throwable) { }
            if (name.isBlank()) name =
                (if (kind == 1) "回放 " else "缓存 ") + id.removePrefix("c_").removePrefix("r_")
            val thumb = File(dir, "$id.webp").takeIf { it.exists() }
            CacheProject(id, name, w, h, if (t > 0) t else f.lastModified(), f, thumb,
                f.length(), kind, extra)
        }
        ?.sortedByDescending { it.timeMs }
        ?: emptyList()
}

fun formatCacheSize(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> String.format("%.2fGB", bytes / 1e9)
    bytes >= 1_000_000L -> String.format("%.1fMB", bytes / 1e6)
    bytes >= 1_000L -> String.format("%.0fKB", bytes / 1e3)
    else -> "${bytes}B"
}
