package app.tgplayer.local

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import app.tgplayer.data.MediaKind
import app.tgplayer.data.playableKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A playable file on the phone. [uri] is a file:// URI, which the player and history key on. */
data class LocalItem(
    val path: String,
    val title: String,
    val kind: MediaKind,
    val sizeBytes: Long,
    val modified: Long,
    val durationMs: Long = 0,
    val artist: String? = null,
) {
    val uri: String get() = Uri.fromFile(File(path)).toString()
}

/** A row in the file browser: a folder or a playable file. */
data class Entry(val file: File, val item: LocalItem?) {
    val isDir get() = item == null
}

/** Files on the phone: the All-files-access gate, a folder browser, and MediaStore libraries. */
object LocalMedia {
    fun hasAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /** Android 11+: the system screen with the "Allow access to manage all files" switch for this app. */
    fun allFilesAccessIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))

    /** Internal storage first, then SD cards / USB drives. */
    fun roots(context: Context): List<Pair<String, File>> {
        val internal = "Internal storage" to Environment.getExternalStorageDirectory()
        val removable = context.getExternalFilesDirs(null).drop(1).mapNotNull { dir ->
            dir?.path?.substringBefore("/Android/")?.let { File(it) }?.takeIf { it.canRead() }
        }.mapIndexed { i, f -> (if (i == 0) "SD card" else "External drive ${i + 1}") to f }
        return listOf(internal) + removable
    }

    /** Folders first, then playable files, both by name. Other files (PDFs, APKs…) are hidden. */
    suspend fun list(dir: File, showHidden: Boolean): List<Entry> = withContext(Dispatchers.IO) {
        val children = dir.listFiles().orEmpty().filter { showHidden || !it.name.startsWith('.') }
        val folders = children.filter { it.isDirectory }.sortedBy { it.name.lowercase() }.map { Entry(it, null) }
        val files = children.filter { it.isFile }.mapNotNull { f -> itemOf(f)?.let { Entry(f, it) } }.sortedBy { it.file.name.lowercase() }
        folders + files
    }

    fun itemOf(f: File): LocalItem? {
        val kind = playableKind(null, f.name) ?: return null
        return LocalItem(f.path, f.nameWithoutExtension, kind, f.length(), f.lastModified())
    }

    /** Every video on the phone (MediaStore), newest first, for the Video tab's grid. */
    suspend fun allVideos(context: Context): List<LocalItem> = withContext(Dispatchers.IO) {
        videos(context).sortedByDescending { it.modified }
    }

    @Suppress("DEPRECATION") // DATA is still filled in and readable with all-files access
    private fun videos(context: Context): List<LocalItem> {
        val cols = arrayOf(MediaStore.Video.Media.DATA, MediaStore.Video.Media.DURATION, MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DATE_MODIFIED)
        return context.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cols, null, null, null)?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val path = c.getString(0) ?: continue
                    add(LocalItem(path, File(path).nameWithoutExtension, MediaKind.VIDEO, c.getLong(2), c.getLong(3) * 1000, c.getLong(1)))
                }
            }
        }.orEmpty().filter { File(it.path).exists() }
    }

    @Suppress("DEPRECATION")
    suspend fun songs(context: Context): List<LocalItem> = withContext(Dispatchers.IO) {
        val cols = arrayOf(
            MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.DATE_MODIFIED,
        )
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cols, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE",
        )?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val path = c.getString(0) ?: continue
                    val artist = c.getString(2)?.takeIf { it != MediaStore.UNKNOWN_STRING }
                    add(LocalItem(path, c.getString(1) ?: File(path).nameWithoutExtension, MediaKind.AUDIO, c.getLong(4), c.getLong(5) * 1000, c.getLong(3), artist))
                }
            }
        }.orEmpty()
    }

    /**
     * Subtitle files next to a video with the same base name (movie.mkv → movie.srt, movie.en.srt…).
     * [folderSubs] is the folder's [subtitleFilesIn]; a playlist passes one shared list per folder, because
     * listing a big folder (WhatsApp Video) once per video made opening it take seconds.
     */
    fun sidecarSubtitles(videoPath: String, folderSubs: List<File> = subtitleFilesIn(File(videoPath).parentFile)): List<File> {
        val name = File(videoPath).name
        return folderSubs.filter { isSidecar(name, it.name) }.sortedBy { it.name }
    }

    /** Just the subtitle files in [dir] (usually none), so matching them against videos is cheap. Blocking I/O. */
    fun subtitleFilesIn(dir: File?): List<File> =
        dir?.listFiles().orEmpty().filter { it.name.substringAfterLast('.', "").lowercase() in SUBTITLE_EXT }

    /** [fileName] is a subtitle for [videoName]: same base name, maybe a language tag (movie.en.srt). */
    fun isSidecar(videoName: String, fileName: String): Boolean {
        val base = videoName.substringBeforeLast('.')
        val name = fileName.substringBeforeLast('.')
        return fileName.substringAfterLast('.', "").lowercase() in SUBTITLE_EXT && (name == base || name.startsWith("$base."))
    }

    val SUBTITLE_EXT = setOf("srt", "vtt", "ass", "ssa", "ttml")
}
