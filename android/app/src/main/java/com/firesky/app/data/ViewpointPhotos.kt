package com.firesky.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlin.math.max

/**
 * Camera-viewpoint photos. A picked photo is downsized and re-encoded as JPEG so it stays well
 * under the server's per-photo limit, and kept in app storage under the id it is uploaded with.
 */
object ViewpointPhotos {
    private const val TAG = "ViewpointPhotos"
    private const val MAX_EDGE = 1280
    private const val MAX_BYTES = 900_000

    class Processed(val id: String, val width: Int, val height: Int)

    fun file(context: Context, id: String): File = File(File(context.filesDir, "viewpoint_photos"), "$id.jpg")

    fun delete(context: Context, id: String) {
        runCatching { file(context, id).delete() }
    }

    /** Null when the picked file can't be read as an image or can't be made small enough. */
    suspend fun import(context: Context, uri: Uri): Processed? = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            // A bounds-only decode returns null by design; the size is read from the options afterwards.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val opened = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds); true } ?: false
            if (!opened || bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                Log.w(TAG, "Could not read the picked photo's size")
                return@withContext null
            }
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_EDGE * 2) sample *= 2
            val decoded = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return@withContext null
            val rotation = resolver.openInputStream(uri)?.use { exifRotation(it) } ?: 0

            val scale = MAX_EDGE.toFloat() / max(decoded.width, decoded.height)
            val matrix = Matrix().apply {
                if (scale < 1f) postScale(scale, scale)
                if (rotation != 0) postRotate(rotation.toFloat())
            }
            val bitmap = if (matrix.isIdentity) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)

            val encoded = ByteArrayOutputStream()
            var quality = 86
            do {
                encoded.reset()
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, encoded)
                quality -= 8
            } while (encoded.size() > MAX_BYTES && quality >= 40)
            if (encoded.size() > MAX_BYTES) return@withContext null

            val id = UUID.randomUUID().toString()
            val target = file(context, id)
            target.parentFile?.mkdirs()
            target.writeBytes(encoded.toByteArray())
            Processed(id, bitmap.width, bitmap.height)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Could not import the picked photo", error)
            null
        }
    }

    private fun exifRotation(stream: InputStream): Int =
        when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
}
