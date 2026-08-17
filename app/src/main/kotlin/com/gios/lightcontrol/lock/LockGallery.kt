package com.gios.lightcontrol.lock

import android.Manifest
import android.os.Environment
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The phone's own photos, read straight off the filesystem.
 *
 * Lifted from BrightChat, where it exists because **the system photo picker is useless on this
 * phone**. The picker reads MediaStore, and nothing on LightOS keeps MediaStore current — there is
 * no media provider doing the scanning a normal Android build does — so a photo taken minutes ago
 * simply is not offered. That is why v2.11's SAF picker was the wrong choice here even though it
 * needed no permission: it is the same MediaStore behind a different door.
 *
 * Walking DCIM and Pictures cannot go stale. The directory listing *is* the source of truth, and a
 * photo is visible the moment it is written — which on a phone whose camera app we also wrote is
 * the difference between choosing a background and waiting for one to appear.
 *
 * Reading other apps' image files by path needs `READ_MEDIA_IMAGES`, a normal runtime prompt on
 * 33+, and it is also what makes the direct path read legal.
 */
object LockGallery {

    /** How many photos the grid will hold. A camera roll on this phone is not a photo library. */
    private const val MAX_PHOTOS = 600

    /** Grid cells are a third of the width inside the gutters — 256 is a touch under the pixel
     *  size that lands at, invisible on a thumbnail and a decode step faster. */
    private const val THUMB_DIM = 256

    /** 8MB of thumbnails, sized in *bytes* rather than entries: a count-based cache of ~190KB
     *  bitmaps quietly retains tens of megabytes for the life of the process. */
    private const val THUMB_CACHE_BYTES = 8 * 1024 * 1024

    private val thumbnails = object : LruCache<String, ImageBitmap>(THUMB_CACHE_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    /** What the camera and screenshots actually write. */
    private val EXTENSIONS = setOf("jpg", "jpeg", "png", "heic", "heif", "webp", "gif", "bmp")

    val permission: String = Manifest.permission.READ_MEDIA_IMAGES

    /** One photo on disk. [takenAt] is the file's mtime — EXIF would be more correct but means
     *  opening every file to sort the grid, and for a camera roll the two agree. */
    data class Photo(val file: File, val takenAt: Long) {
        val key: String get() = file.path
    }

    /**
     * Every image under DCIM and Pictures, newest first. Off-main: this touches the filesystem.
     * Returns empty rather than throwing when the permission is missing — the caller is showing a
     * prompt in that case anyway.
     */
    suspend fun scan(): List<Photo> = withContext(Dispatchers.IO) {
        runCatching {
            roots()
                .filter { it.isDirectory }
                .flatMap { root ->
                    root.walkTopDown()
                        // Deep enough for DCIM/Camera and Pictures/Screenshots; not so deep that a
                        // stray folder of assets turns into a long walk.
                        .maxDepth(3)
                        // .thumbnails holds the launcher's own cached crops — junk here.
                        .onEnter { !it.name.startsWith(".") }
                        .filter {
                            it.isFile && it.length() > 0L &&
                                // .trashed-* and .pending-* are MediaProvider's own bookkeeping
                                // and pass the extension filter otherwise.
                                !it.name.startsWith(".") &&
                                it.extension.lowercase() in EXTENSIONS
                        }
                        .toList()
                }
                .map { Photo(it, it.lastModified()) }
                .sortedByDescending { it.takenAt }
                .take(MAX_PHOTOS)
        }.getOrDefault(emptyList())
    }

    /** A downsampled, EXIF-upright thumbnail, or null if the file will not decode — a
     *  partially-written camera file, most likely. Cached in memory. */
    suspend fun thumbnail(photo: Photo): ImageBitmap? {
        thumbnails.get(photo.key)?.let { return it }
        return withContext(Dispatchers.IO) {
            val image = LockBackground.decodeUpright(photo.file, THUMB_DIM)?.asImageBitmap()
                ?: return@withContext null
            thumbnails.put(photo.key, image)
            image
        }
    }

    /**
     * DCIM and Pictures. `getExternalStoragePublicDirectory` is deprecated in favour of
     * MediaStore, which is precisely the thing that does not work on this phone, so the
     * deprecation is noted and ignored.
     */
    @Suppress("DEPRECATION")
    private fun roots(): List<File> = listOf(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
    )
}
