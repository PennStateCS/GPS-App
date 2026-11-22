package app.surrealar.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/** Thumbnail decoding at roughly the size it is shown, never the full image. */
object ThumbnailBitmaps {

    /** Largest power of two that keeps both sides at or above [reqPx]. */
    fun sampleSize(width: Int, height: Int, reqPx: Int): Int {
        var s = 1
        while (width / (s * 2) >= reqPx && height / (s * 2) >= reqPx) s *= 2
        return s
    }

    /** Decodes [path] down-sampled to about [reqPx]; null when unreadable or out of memory. */
    fun decodeSampled(path: String, reqPx: Int = 256): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null
        else BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, reqPx)
        })
    } catch (_: OutOfMemoryError) {
        null
    }
}