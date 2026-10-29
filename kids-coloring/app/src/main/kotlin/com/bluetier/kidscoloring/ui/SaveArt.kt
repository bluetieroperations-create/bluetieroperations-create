package com.bluetier.kidscoloring.ui

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.provider.MediaStore
import com.bluetier.kidscoloring.art.Picture

/** Renders a filled picture to a square bitmap using plain Android 2D drawing. */
fun renderToBitmap(picture: Picture, fillOf: (String) -> Int?, sizePx: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.WHITE)
    val scale = sizePx / Picture.SIZE

    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
        strokeWidth = sizePx * 0.012f
        strokeJoin = Paint.Join.ROUND
    }

    val paths = picture.regions.map { region ->
        Path().apply {
            region.points.forEachIndexed { i, p ->
                val x = p.x * scale
                val y = p.y * scale
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
    }
    picture.regions.forEachIndexed { i, region ->
        fill.color = fillOf(region.id) ?: Color.WHITE
        canvas.drawPath(paths[i], fill)
    }
    paths.forEach { canvas.drawPath(it, stroke) }
    return bitmap
}

/**
 * Saves [bitmap] to the device gallery under Pictures/Coloring Fun. Uses scoped
 * MediaStore, so it needs no storage permission on API 29+. @return true on success.
 */
fun saveToGallery(context: Context, bitmap: Bitmap, displayName: String): Boolean {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "$displayName.png")
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Coloring Fun")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
    return runCatching {
        resolver.openOutputStream(uri)!!.use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        true
    }.getOrElse {
        resolver.delete(uri, null, null)
        false
    }
}
