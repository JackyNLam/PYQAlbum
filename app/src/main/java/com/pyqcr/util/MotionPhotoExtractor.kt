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
     *
     * Every "ftyp" signature found is validated (plausible MP4 box size) and,
     * once extracted, probed with MediaMetadataRetriever — false positives
     * (e.g. "ftyp" bytes inside EXIF metadata) are skipped and the next
     * candidate is tried. When none verify, the first candidate is returned
     * anyway so an exotic-but-playable video is not hidden from the user.
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
                val offsets = findFtypOffsets(input)
                var lastCandidate: File? = null
                for (offset in offsets) {
                    // Every MP4 box starts with its 4-byte size field, so the
                    // appended video actually begins 4 bytes BEFORE the "ftyp"
                    // signature. Copying from the "ftyp" bytes themselves drops
                    // that header and MediaPlayer refuses to parse the file.
                    if (offset < 4) continue
                    val extracted = extractFrom(context, uri, offset - 4)
                    if (extracted == null) continue
                    // Keep the LAST plausible candidate (largest offset) as the
                    // fallback — the real appended video starts right after the
                    // JPEG ends, i.e. at the largest ftyp offset; EXIF false
                    // positives appear earlier and are filtered by the size check.
                    lastCandidate?.delete()
                    lastCandidate = extracted
                    if (isPlayableVideo(extracted)) {
                        return@use extracted
                    }
                }
                lastCandidate
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Byte offsets of every plausible "ftyp" MP4 signature in the stream (max [SCAN_LIMIT] bytes). */
    private fun findFtypOffsets(input: java.io.InputStream): List<Long> {
        val offsets = mutableListOf<Long>()
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

            var from = 0
            while (true) {
                val idx = indexOfFtyp(joined, from)
                if (idx < 0) break
                if (isPlausibleFtyp(joined, idx)) {
                    offsets.add((totalRead - tail.size) + idx)
                }
                from = idx + 1
                if (from > joined.size - 4) break
            }
            // Keep the last WINDOW bytes so a split signature still matches
            val keep = minOf(joined.size, WINDOW)
            tail = joined.copyOfRange(joined.size - keep, joined.size)
            totalRead += n
        }
        return offsets
    }

    /**
     * The 4 bytes before "ftyp" must encode a plausible MP4 box size
     * (>= 8 for the mandatory box header, and no larger than the scanned
     * data). Random "ftyp" byte sequences inside EXIF/JPEG metadata fail this.
     */
    private fun isPlausibleFtyp(data: ByteArray, idx: Int): Boolean {
        if (idx < 4) return false
        val size = ((data[idx - 4].toInt() and 0xFF) shl 24) or
            ((data[idx - 3].toInt() and 0xFF) shl 16) or
            ((data[idx - 2].toInt() and 0xFF) shl 8) or
            (data[idx - 1].toInt() and 0xFF)
        val remaining = data.size - (idx - 4)
        return size >= 8 && size <= remaining
    }

    /** First index of the literal "ftyp" sequence at/after [from], or -1. */
    private fun indexOfFtyp(data: ByteArray, from: Int): Int {
        if (data.size - from < 4) return -1
        val last = data.size - 4
        var i = from
        while (i <= last) {
            if (data[i] == FTYP[0] &&
                data[i + 1] == FTYP[1] &&
                data[i + 2] == FTYP[2] &&
                data[i + 3] == FTYP[3]
            ) {
                return i
            }
            i++
        }
        return -1
    }

    /** True when [file] looks like a decodable video — probed via MediaMetadataRetriever. */
    private fun isPlayableVideo(file: File): Boolean {
        return try {
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                val hasVideo = retriever.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO
                )
                hasVideo == "yes"
            } finally {
                try {
                    retriever.release()
                } catch (e: Exception) {
                }
            }
        } catch (e: Exception) {
            false
        }
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