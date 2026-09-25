package space.subread.anki

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.core.net.toUri
import java.io.File
import java.nio.ByteOrder

/**
 * Cuts a clip out of an audio file: the decoder gives the PCM of the range, [AacEncoder]
 * writes it. The file can be a `content://` Uri, an `http(s)://` URL (the extractor reads
 * the range alone, with range requests), or a `data:` Uri.
 */
object AudioCut {

    private const val TIMEOUT_US = 10_000L

    /** The clip from [startMs] to [endMs] of [source], as AAC in [out]. Null when nothing could be decoded. */
    fun cut(context: Context, source: String, startMs: Long, endMs: Long, out: File): File? {
        if (endMs <= startMs) return null
        val extractor = MediaExtractor()
        var temp: File? = null
        try {
            val uri = source.toUri()
            when (uri.scheme?.lowercase()) {
                "http", "https" -> extractor.setDataSource(source)
                "content" -> extractor.setDataSource(context, uri, null)
                "data" -> {
                    val bytes = Media.bytes(context, source) ?: return null
                    temp = File.createTempFile("cut", ".bin", context.cacheDir).apply { writeBytes(bytes) }
                    extractor.setDataSource(temp.path)
                }
                else -> return null
            }
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            val format = extractor.getTrackFormat(track)
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            extractor.selectTrack(track)
            extractor.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val decoded = decode(extractor, format, startMs * 1000, endMs * 1000) ?: return null
            val (pcm, sampleRate, channels) = decoded
            // Less than a tenth of a second is no sentence: the range was outside the file.
            if (pcm.size < sampleRate / 10 * channels) return null
            AacEncoder.encode(pcm, sampleRate, channels, out)
            return out
        } catch (e: Exception) {
            return null
        } finally {
            extractor.release()
            temp?.delete()
        }
    }

    /** The PCM of the range, with the sample rate and the channels that the decoder gave. */
    private fun decode(extractor: MediaExtractor, format: MediaFormat, startUs: Long, endUs: Long): Triple<ShortArray, Int, Int>? {
        val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(format, null, null, 0)
        decoder.start()
        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val pcm = Pcm()
        var inputDone = false
        var outputDone = false
        val info = MediaCodec.BufferInfo()
        try {
            while (!outputDone) {
                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = decoder.getInputBuffer(index)!!
                        val size = extractor.readSampleData(buffer, 0)
                        val time = extractor.sampleTime
                        if (size < 0 || time > endUs) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size, time, 0)
                            extractor.advance()
                        }
                    }
                }
                val index = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val changed = decoder.outputFormat
                        sampleRate = changed.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = changed.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    index >= 0 -> {
                        if (info.size > 0) {
                            val buffer = decoder.getOutputBuffer(index)!!
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val shorts = buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            val frames = shorts.remaining() / channels
                            // The part of this buffer that is inside the clip.
                            val bufferStartUs = info.presentationTimeUs
                            val bufferEndUs = bufferStartUs + frames * 1_000_000L / sampleRate
                            val first = if (bufferStartUs >= startUs) 0 else ((startUs - bufferStartUs) * sampleRate / 1_000_000L).toInt().coerceIn(0, frames)
                            val last = if (bufferEndUs <= endUs) frames else ((endUs - bufferStartUs) * sampleRate / 1_000_000L).toInt().coerceIn(0, frames)
                            if (last > first) {
                                shorts.position(first * channels)
                                val chunk = ShortArray((last - first) * channels)
                                shorts.get(chunk)
                                pcm.add(chunk)
                            }
                        }
                        decoder.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } finally {
            runCatching { decoder.stop() }
            decoder.release()
        }
        return Triple(pcm.toArray(), sampleRate, channels)
    }

    /** A growing array of samples. */
    private class Pcm {
        private var data = ShortArray(1 shl 16)
        private var size = 0

        fun add(chunk: ShortArray) {
            if (size + chunk.size > data.size) data = data.copyOf(maxOf(data.size * 2, size + chunk.size))
            System.arraycopy(chunk, 0, data, size, chunk.size)
            size += chunk.size
        }

        fun toArray(): ShortArray = data.copyOf(size)
    }
}
