package com.zota.traductor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlin.math.max

/**
 * Carga de imágenes para OCR: decodifica con downscale (evita OOM con fotos de
 * cámara de 12–50 MP) y aplica la orientación EXIF.
 */
object ImageLoad {

    /** Lado mayor máximo tras el downscale (suficiente para PP-OCR). */
    const val MAX_DIM = 2560

    fun load(ctx: Context, uri: Uri, maxDim: Int = MAX_DIM): Bitmap? {
        // 1) tamaño original
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        } catch (_: Throwable) {
            return null
        }
        val ow = bounds.outWidth
        val oh = bounds.outHeight
        if (ow <= 0 || oh <= 0) return null

        var sample = 1
        while (max(ow, oh) / sample > maxDim) sample *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bmp: Bitmap = try {
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        } catch (_: Throwable) {
            return null
        }

        // 2) orientación EXIF
        val orientation = try {
            ctx.contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (_: Throwable) {
            ExifInterface.ORIENTATION_NORMAL
        }

        val rotated = applyOrientation(bmp, orientation)
        if (rotated !== bmp) bmp.recycle()
        return rotated
    }

    private fun applyOrientation(bmp: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return bmp
        }
        return try {
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        } catch (_: Throwable) {
            bmp
        }
    }
}
