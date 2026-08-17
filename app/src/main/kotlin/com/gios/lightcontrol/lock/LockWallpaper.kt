package com.gios.lightcontrol.lock

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * The picture behind the clock.
 *
 * Loaded from a document the user picked, not from `WallpaperManager`. Reading the system
 * wallpaper from a sideloaded app has needed a storage permission since Android 13 and returns
 * whatever the launcher set, which on this phone is nothing; a persisted SAF grant is one tap in
 * settings, survives reboots, and cannot fail in a way that needs adb to diagnose.
 *
 * Decoded once per lock face rather than cached in a singleton. A full-screen bitmap is a few
 * megabytes and the face is torn down every unlock — holding it for the life of the process would
 * be paying for it all day to save a decode that happens while the screen is off anyway.
 */
object LockWallpaper {

    fun load(context: Context, raw: String?, maxWidth: Int, maxHeight: Int): ImageBitmap? {
        val uri = raw?.takeIf { it.isNotBlank() }?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?: return null
        return runCatching {
            // Two passes. A phone photo is 12 megapixels and the panel is under one, so decoding
            // at full size to draw it scaled down is 40MB of allocation for pixels nobody sees —
            // on a device this size that is an OutOfMemory on the lock screen, which is the single
            // worst place in the app to throw.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            while (
                bounds.outWidth / sample > maxWidth * 2 ||
                bounds.outHeight / sample > maxHeight * 2
            ) {
                sample *= 2
            }

            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }?.asImageBitmap()
        }.getOrNull()
    }
}
