package com.pyqcr.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.pyqcr.ui.util.MediaStoreUtils
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Pure-bitmap engine behind the Image Edit screen. Everything here works on
 * [Bitmap]s only — no Compose — so the same code renders the on-screen preview
 * and the final saved file.
 *
 * The document is a flattened base bitmap with optional overlay layers (photo
 * B stacked on top of photo A) plus pending pen/pixelate strokes and text.
 * Overlay [EditOverlay.cutout] strokes punch holes in the overlay, so the base
 * photo shows through the painted area (free-shape layer selection).
 */
data class EditStrokePoint(val x: Float, val y: Float)

/** One pen / pixelate / cutout stroke. Coordinates are normalized 0f..1f. */
data class EditMark(
    val points: List<EditStrokePoint>,
    val radius: Float,
    val color: Int = Color.RED,
    val pixelate: Boolean = false
)

/** A text label anchored at a normalized position ([y] is the text baseline). */
data class EditTextItem(
    val text: String,
    val x: Float,
    val y: Float,
    val sizeFraction: Float,
    val color: Int
)

/** A stacked photo. When [cutout] is non-empty, the painted areas show the base. */
data class EditOverlay(
    val bitmap: Bitmap,
    val cutout: List<EditMark> = emptyList()
)

data class ImageEditDoc(
    val base: Bitmap,
    val overlays: List<EditOverlay> = emptyList(),
    val marks: List<EditMark> = emptyList(),
    val texts: List<EditTextItem> = emptyList()
)

object ImageEditEngine {

    private const val MAX_DECODE_DIM = 2048
    private const val PIXELATE_DIVISOR = 22

    // ---------------------------------------------------------------- decoding

    /**
     * Decode an image URI/file path to a bitmap no larger than [maxDim] on its
     * long side, applying EXIF orientation so the pixels match the gallery.
     */
    fun decodeBitmap(context: Context, uriStr: String, maxDim: Int = MAX_DECODE_DIM): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            if (uriStr.startsWith("content://") || uriStr.startsWith("file://")) {
                val uri = Uri.parse(uriStr)
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
            } else {
                BitmapFactory.decodeFile(uriStr, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            while (bounds.outWidth / (sample shl 1) >= maxDim ||
                bounds.outHeight / (sample shl 1) >= maxDim
            ) {
                sample = sample shl 1
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val raw = if (uriStr.startsWith("content://") || uriStr.startsWith("file://")) {
                val uri = Uri.parse(uriStr)
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, opts)
                }
            } else {
                BitmapFactory.decodeFile(uriStr, opts)
            } ?: return null

            rotateForExif(context, uriStr, raw)
        } catch (e: Exception) {
            null
        }
    }

    private fun readExifOrientation(context: Context, uriStr: String): Int {
        return try {
            val exif = if (uriStr.startsWith("content://") || uriStr.startsWith("file://")) {
                val uri = Uri.parse(uriStr)
                context.contentResolver.openInputStream(uri)?.use { ExifInterface(it) }
            } else {
                ExifInterface(uriStr)
            }
            exif?.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            ) ?: ExifInterface.ORIENTATION_NORMAL
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
    }

    private fun rotateForExif(context: Context, uriStr: String, src: Bitmap): Bitmap {
        val orientation = readExifOrientation(context, uriStr)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return src
        }
        return try {
            val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
            if (rotated !== src) src.recycle()
            rotated
        } catch (e: Exception) {
            src
        }
    }

    // -------------------------------------------------------------- rendering

    /** Flatten the whole document (base + layers + strokes + text) into a new bitmap. */
    fun render(doc: ImageEditDoc): Bitmap {
        val w = doc.base.width
        val h = doc.base.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(doc.base, 0f, 0f, null)

        // Overlay layers, bottom-most first.
        for (overlay in doc.overlays) {
            if (overlay.bitmap.isRecycled) continue
            if (overlay.cutout.isEmpty()) {
                val src = Rect(0, 0, overlay.bitmap.width, overlay.bitmap.height)
                val dst = Rect(0, 0, w, h)
                canvas.drawBitmap(overlay.bitmap, src, dst, null)
            } else {
                // Render overlay into a scratch layer, punch the cutout strokes
                // (CLEAR), then composite the result over the base.
                val layer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val lc = Canvas(layer)
                val src = Rect(0, 0, overlay.bitmap.width, overlay.bitmap.height)
                val dst = Rect(0, 0, w, h)
                lc.drawBitmap(overlay.bitmap, src, dst, null)
                val clear = Paint().apply {
                    isAntiAlias = true
                    style = Paint.Style.STROKE
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                }
                for (mark in overlay.cutout) {
                    drawStroke(lc, mark, w, h, clear)
                }
                canvas.drawBitmap(layer, 0f, 0f, null)
                layer.recycle()
            }
        }

        // Pen / pixelate strokes.
        for (mark in doc.marks) {
            if (mark.pixelate) {
                drawPixelatedStroke(canvas, out, mark)
            } else {
                val paint = Paint().apply {
                    isAntiAlias = true
                    style = Paint.Style.STROKE
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                    color = mark.color
                }
                drawStroke(canvas, mark, w, h, paint)
            }
        }

        // Text labels.
        for (item in doc.texts) {
            val paint = Paint().apply {
                isAntiAlias = true
                color = item.color
                textSize = max(8f, item.sizeFraction * min(w, h))
                isFakeBoldText = true
            }
            canvas.drawText(item.text, item.x * w, item.y * h, paint)
        }
        return out
    }

    private fun strokeWidth(mark: EditMark, w: Int, h: Int): Float {
        return max(1f, mark.radius * min(w, h))
    }

    private fun buildPath(mark: EditMark, w: Int, h: Int): Path {
        val path = Path()
        val pts = mark.points
        if (pts.isEmpty()) return path
        if (pts.size == 1) {
            val stroke = strokeWidth(mark, w, h) / 2f
            path.addCircle(pts[0].x * w, pts[0].y * h, max(0.5f, stroke), Path.Direction.CW)
            return path
        }
        pts.forEachIndexed { index, p ->
            val x = p.x * w
            val y = p.y * h
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        return path
    }

    private fun drawStroke(canvas: Canvas, mark: EditMark, w: Int, h: Int, paint: Paint) {
        if (mark.points.isEmpty()) return
        paint.strokeWidth = strokeWidth(mark, w, h)
        if (mark.points.size == 1) {
            val p = mark.points[0]
            canvas.drawCircle(p.x * w, p.y * h, max(0.5f, paint.strokeWidth / 2f), paint)
        } else {
            canvas.drawPath(buildPath(mark, w, h), paint)
        }
    }

    /** Pixelate the area under the stroke: downscale the current canvas and mask it. */
    private fun drawPixelatedStroke(canvas: Canvas, current: Bitmap, mark: EditMark) {
        val w = current.width
        val h = current.height
        if (mark.points.isEmpty()) return
        val path = buildPath(mark, w, h)

        // Mask = the stroked area painted white.
        val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val maskCanvas = Canvas(mask)
        val maskPaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = Color.WHITE
            strokeWidth = strokeWidth(mark, w, h)
        }
        if (mark.points.size == 1) {
            val p = mark.points[0]
            maskCanvas.drawCircle(p.x * w, p.y * h, max(0.5f, mark.radius * min(w, h) / 2f), maskPaint)
        } else {
            maskCanvas.drawPath(path, maskPaint)
        }

        // Mosaic copy of the pixels currently on the canvas.
        val smallW = max(1, w / PIXELATE_DIVISOR)
        val smallH = max(1, h / PIXELATE_DIVISOR)
        val small = Bitmap.createScaledBitmap(current, smallW, smallH, false)
        val mosaic = Bitmap.createScaledBitmap(small, w, h, false)
        if (small !== mosaic) small.recycle()

        val layer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val lc = Canvas(layer)
        lc.drawBitmap(mosaic, 0f, 0f, null)
        val dstIn = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        lc.drawBitmap(mask, 0f, 0f, dstIn)
        canvas.drawBitmap(layer, 0f, 0f, null)

        mosaic.recycle()
        mask.recycle()
        layer.recycle()
    }

    // ----------------------------------------------------------- destructive ops

    /**
     * Crop with normalized 0f..1f bounds. The current document is flattened
     * first, so strokes/text/layers stay where the user put them.
     */
    fun crop(doc: ImageEditDoc, left: Float, top: Float, right: Float, bottom: Float): ImageEditDoc {
        val flat = render(doc)
        val w = flat.width
        val h = flat.height
        val l = (min(left, right) * w).toInt().coerceIn(0, w - 1)
        val t = (min(top, bottom) * h).toInt().coerceIn(0, h - 1)
        val r = (max(left, right) * w).toInt().coerceIn(l + 1, w)
        val b = (max(top, bottom) * h).toInt().coerceIn(t + 1, h)
        val cropped = Bitmap.createBitmap(flat, l, t, r - l, b - t)
        if (cropped !== flat) flat.recycle()
        return ImageEditDoc(base = cropped)
    }

    /**
     * Resize the flattened document to [targetWidth] pixels wide, keeping the
     * aspect ratio. [padSquare] centers it on a [targetWidth]² canvas filled
     * with [padColor] — same behavior as the existing batch resize.
     */
    fun resize(
        doc: ImageEditDoc,
        targetWidth: Int,
        padSquare: Boolean,
        padColor: Int
    ): ImageEditDoc {
        val flat = render(doc)
        val width = targetWidth.coerceAtLeast(1)
        val fh = (flat.height.toFloat() * width / flat.width.toFloat()).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(flat, width, fh, true)
        flat.recycle()
        val result = if (padSquare) {
            val side = width
            val square = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(square)
            canvas.drawColor(padColor)
            val fit = min(side.toFloat() / scaled.width, side.toFloat() / scaled.height)
            val drawW = (scaled.width * fit).toInt().coerceAtLeast(1)
            val drawH = (scaled.height * fit).toInt().coerceAtLeast(1)
            val fitted = if (drawW == scaled.width && drawH == scaled.height) null
            else Bitmap.createScaledBitmap(scaled, drawW, drawH, true)
            canvas.drawBitmap(
                fitted ?: scaled,
                (side - drawW) / 2f,
                (side - drawH) / 2f,
                null
            )
            fitted?.recycle()
            scaled.recycle()
            square
        } else {
            scaled
        }
        return ImageEditDoc(base = result)
    }

    /**
     * Rotate the flattened document by [degrees] (clockwise, any value).
     *
     * Arbitrary angles cannot be expressed in the overlay model — every mark is
     * stored in normalized image space — so the document is flattened first,
     * exactly like crop and resize. The canvas grows to the rotated bounding box
     * and the empty corners are filled white, which is what a printed photo
     * looks like on a sheet.
     */
    fun rotate(doc: ImageEditDoc, degrees: Float): ImageEditDoc {
        val flat = render(doc)
        val angle = ((degrees % 360f) + 360f) % 360f
        if (angle == 0f) return ImageEditDoc(base = flat)
        val rad = Math.toRadians(angle.toDouble())
        val sin = kotlin.math.abs(kotlin.math.sin(rad)).toFloat()
        val cos = kotlin.math.abs(kotlin.math.cos(rad)).toFloat()
        val w = flat.width.toFloat()
        val h = flat.height.toFloat()
        val outW = (w * cos + h * sin).toInt().coerceAtLeast(1)
        val outH = (w * sin + h * cos).toInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(android.graphics.Color.WHITE)
        val paint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
        }
        canvas.save()
        canvas.rotate(angle, outW / 2f, outH / 2f)
        canvas.drawBitmap(flat, (outW - w) / 2f, (outH - h) / 2f, paint)
        canvas.restore()
        flat.recycle()
        return ImageEditDoc(base = out)
    }

    /** Scale an overlay photo to exactly the base dimensions (same-position stacking). */
    fun fitOverlayToBase(overlay: Bitmap, base: Bitmap): Bitmap {
        if (overlay.width == base.width && overlay.height == base.height) return overlay
        val scaled = Bitmap.createScaledBitmap(overlay, base.width, base.height, true)
        if (scaled !== overlay) overlay.recycle()
        return scaled
    }

    // ------------------------------------------------------------------- saving

    /**
     * Save the flattened document as a new JPEG in
     * Pictures/PYQAlbum and publish it to the MediaStore so the gallery and
     * WeChat's picker see it. Returns the new URI/path, or null on failure.
     */
    fun saveDocument(context: Context, doc: ImageEditDoc): String? {
        val flat = render(doc)
        val name = "pyq_edit_${System.currentTimeMillis()}.jpg"
        val saved = saveBitmap(context, flat, name)
        flat.recycle()
        return saved
    }

    fun saveBitmap(context: Context, bitmap: Bitmap, fileName: String): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/PYQAlbum"
                    )
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    values
                ) ?: return null
                val written = context.contentResolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                } ?: false
                if (!written) {
                    context.contentResolver.delete(uri, null, null)
                    return null
                }
                val done = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                context.contentResolver.update(uri, done, null, null)
                MediaStoreUtils.publishImage(context, uri)
                uri.toString()
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "PYQAlbum"
                )
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, fileName)
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                }
                MediaStoreUtils.scanFile(context, file.absolutePath)
                file.absolutePath
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Compose two photos with a free-shape cutout so the base shows through. */
    fun compositeWithCutout(base: Bitmap, top: Bitmap, cutout: List<EditMark>): Bitmap {
        val fitted = fitOverlayToBase(top, base)
        return render(
            ImageEditDoc(
                base = base,
                overlays = listOf(EditOverlay(fitted, cutout))
            )
        )
    }

    /** Map a normalized point to pixels (helper for previews). */
    fun toPixels(point: EditStrokePoint, w: Int, h: Int): Pair<Float, Float> =
        Pair(point.x * w, point.y * h)

    /** RectF covering the source image inside a ContentScale.Fit container. */
    fun fitRect(
        containerWidth: Float,
        containerHeight: Float,
        imageWidth: Int,
        imageHeight: Int
    ): RectF {
        if (containerWidth <= 0f || containerHeight <= 0f ||
            imageWidth <= 0 || imageHeight <= 0
        ) {
            return RectF(0f, 0f, containerWidth, containerHeight)
        }
        val scale = min(containerWidth / imageWidth, containerHeight / imageHeight)
        val dw = imageWidth * scale
        val dh = imageHeight * scale
        val dx = (containerWidth - dw) / 2f
        val dy = (containerHeight - dh) / 2f
        return RectF(dx, dy, dx + dw, dy + dh)
    }
}