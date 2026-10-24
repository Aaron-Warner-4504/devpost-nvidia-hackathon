package ai.enthral.sentinel.data.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PreparedImage(val bytes: ByteArray, val mimeType: String)

/** Reads a picked/shared image and keeps it under the server's 5 MB limit. */
interface ImagePreparer {
    /** Null when the image cannot be read. */
    suspend fun prepare(uri: String): PreparedImage?
    suspend fun thumbnail(uri: String, maxPx: Int): Bitmap?
}

class AndroidImagePreparer(private val context: Context) : ImagePreparer {
    private val resolver get() = context.contentResolver

    override suspend fun prepare(uri: String): PreparedImage? = withContext(Dispatchers.IO) {
        try {
            val u = Uri.parse(uri)
            val mime = resolver.getType(u)?.lowercase() ?: "image/jpeg"
            val raw = resolver.openInputStream(u)?.use { readCapped(it) } ?: return@withContext null
            if (raw.size <= LIMIT && mime in PASS_THROUGH) return@withContext PreparedImage(raw, mime)
            downscale(raw)
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    override suspend fun thumbnail(uri: String, maxPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val u = Uri.parse(uri)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxPx) }
            resolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private fun readCapped(input: java.io.InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (out.size() < MAX_READ) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun downscale(raw: ByteArray): PreparedImage? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        if (bounds.outWidth <= 0) return null
        var edge = 2048
        while (edge >= 512) {
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, edge) }
            val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size, opts) ?: return null
            for (quality in intArrayOf(85, 70, 55)) {
                val out = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
                if (out.size() <= LIMIT) {
                    bmp.recycle()
                    return PreparedImage(out.toByteArray(), "image/jpeg")
                }
            }
            bmp.recycle()
            edge /= 2
        }
        return null
    }

    private fun sampleSize(w: Int, h: Int, maxPx: Int): Int {
        var s = 1
        while (maxOf(w, h) / (s * 2) >= maxPx) s *= 2
        return s
    }

    private companion object {
        const val LIMIT = 4_500_000 // server accepts 5 MB; leave headroom for multipart framing
        const val MAX_READ = 40_000_000
        val PASS_THROUGH = setOf("image/png", "image/jpeg", "image/webp", "image/gif")
    }
}
