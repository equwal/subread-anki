package space.subread.anki

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteOrder

/**
 * Writes 16-bit PCM as AAC in an `.m4a`. AAC because every player has it, Anki on iOS
 * included, and because Android has the encoder on each device since API 16.
 */
object AacEncoder {

    private const val MIME = MediaFormat.MIMETYPE_AUDIO_AAC
    private const val TIMEOUT_US = 10_000L

    /** Encodes [pcm], interleaved with [channels] channels at [sampleRate], into [out]. */
    fun encode(pcm: ShortArray, sampleRate: Int, channels: Int, out: File) {
        require(pcm.isNotEmpty()) { "no sound to encode" }
        val format = MediaFormat.createAudioFormat(MIME, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 64_000 * channels)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
        }
        val codec = MediaCodec.createEncoderByType(MIME)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var fed = 0
        var inputDone = false
        var outputDone = false
        val info = MediaCodec.BufferInfo()
        try {
            while (!outputDone) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)!!
                        buffer.clear()
                        // A frame must stay whole: the count is a multiple of the channels.
                        val count = minOf(buffer.capacity() / 2, pcm.size - fed) / channels * channels
                        buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm, fed, count)
                        val timeUs = fed / channels * 1_000_000L / sampleRate
                        fed += count
                        val flags = if (fed >= pcm.size) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                        if (flags != 0) inputDone = true
                        codec.queueInputBuffer(index, 0, count * 2, timeUs, flags)
                    }
                }
                val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                    }
                    index >= 0 -> {
                        val buffer = codec.getOutputBuffer(index)!!
                        val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!config && info.size > 0 && track >= 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buffer, info)
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            runCatching { if (track >= 0) muxer.stop() }
            muxer.release()
        }
    }
}
