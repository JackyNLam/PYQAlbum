package com.pyqcr.util

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/**
 * Detects and extracts "motion photos" (also called live/moving photos):
 * JPEG files that carry a playable MP4 video appended right after the JPEG
 * bytes (Google Pixel, Samsung, Huawei, …). Works by scanning the stream for
 * the MP4 "ftyp" box signature — everything from that offset to EOF is the
 * embedded video, which is copied into a cache file for playback.
 */
object MotionPhotoExtractor {

    private const val SCAN_LIMIT = 64L * 1024 * 1024   // scan at most 64 MB in
    private const val WINDOW = 64 * 1024               // cross-chunk search window
    private const val MAX_VIDEO_BYTES = 256L * 1024 * 1024

    /** The exact 4-byte pattern that starts every MP4 box header. */
    private val FTYP = byteArrayOf('f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte())

    /**
     * If [uriStr] is a JPEG motion photo, extract the embedded video into a
     * cache file and return it; otherwise return null.
     */
    fun extractToCache(context: Context, uriStr: String): File? {
        return try {
            val uri = Uri.parse(uriStr)
            context.contentResolver.openInputStream(uri)?.use { input ->
                // Only JPEG streams carry the appended-video layout
                val first = ByteArray(2)
                val firstN = input.read(first)
                if (firstN < 2 || first[0] != 0xFF.toByte() || first[1] != 0xD8.toByte()) {
                    return@use null
                }
                // Scan for "ftyp" across chunk boundaries
                val buffer = ByteArray(64 * 1024)
                var totalRead = 0L
                var tail = byteArrayOf()
                while (totalRead < SCAN_LIMIT) {
                    val n = input.read(buffer)
                    if (n <= 0) break
                    val chunk = buffer.copyOfRange(0, n)
                    val joined = ByteArray(tail.size + chunk.size)
                    System.arraycopy(tail, 0, joined, 0, tail.size)
                    System.arraycopy(chunk, 0, joined, tail.size, chunk.size)

                    val idx = indexOfFtyp(joined)
                    if (idx >= 0) {
                        val absOffset = (totalRead - tail.size) + idx
                        return@use extractFrom(context, uri, absOffset)
                    }
                    // Keep the last WINDOW bytes so a split signature still matches
                    val keep = minOf(joined.size, WINDOW)
                    tail = joined.copyOfRange(joined.size - keep, joined.size)
                    totalRead += n
                }
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** First index of the literal "ftyp" sequence, or -1. */
    private fun indexOfFtyp(data: ByteArray): Int {
        if (data.size < 4) return -1
        val last = data.size - 4
        for (i in 0..last) {
            if (data[i] == FTYP[0] &&
                data[i + 1] == FTYP[1] &&
                data[i + 2] == FTYP[2] &&
                data[i + 3] == FTYP[3]
            ) {
                return i
            }
        }
        return -1
    }

    /** Copy everything from [offset] (start of the MP4) to EOF into a cache file. */
    private fun extractFrom(context: Context, uri: Uri, offset: Long): File? {
        val dir = File(context.cacheDir, "motion_photos").apply { mkdirs() }
        val out = File(dir, "motion_${System.currentTimeMillis()}.mp4")
        context.contentResolver.openInputStream(uri)?.use { input ->
            var skipped = 0L
            while (skipped < offset) {
                val s = input.skip(offset - skipped)
                if (s <= 0) break
                skipped += s
            }
            if (skipped >= offset) {
                val fos = FileOutputStream(out)
                try {
                    input.copyTo(fos, bufferSize = 64 * 1024)
                } finally {
                    try { fos.close() } catch (e: Exception) {}
                }
            }
        }
        return if (out.length() in 1..MAX_VIDEO_BYTES) out
        else {
            out.delete()
            null
        }
    }
}