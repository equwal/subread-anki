package space.subread.anki.core

/**
 * The last seconds of captured sound, 16-bit PCM, with a wall clock. The capture writes
 * what it records; a request reads the frames of a wall-clock range back out.
 *
 * The clock: each write says when its last frame was recorded. Frames before it are
 * spaced by the sample rate. The capture is continuous, so the end of one write is the
 * start of the next.
 *
 * Not thread-safe: the caller locks.
 */
class PcmRing(val sampleRate: Int, val channels: Int, capacityFrames: Int) {
    init {
        require(sampleRate > 0) { "sampleRate must be above zero" }
        require(channels > 0) { "channels must be above zero" }
        require(capacityFrames > 0) { "capacityFrames must be above zero" }
    }

    /** How many frames the ring holds. */
    val capacityFrames: Int = capacityFrames

    private val data = ShortArray(capacityFrames * channels)

    /** Frames written since the start, in total. */
    var framesWritten: Long = 0
        private set

    /** The wall clock (nanoseconds) of the end of the last frame written. */
    var endNanos: Long = 0
        private set

    /** Frames in the ring now. */
    val framesHeld: Long get() = minOf(framesWritten, capacityFrames.toLong())

    /** The wall clock of the start of the oldest frame held. */
    val startNanos: Long get() = endNanos - framesToNanos(framesHeld)

    /** True when nothing was written yet. */
    val isEmpty: Boolean get() = framesWritten == 0L

    /**
     * Appends [frames] frames from [samples] (interleaved, `frames * channels` values).
     * [endNanos] is the wall clock at the end of the last of them.
     */
    fun write(samples: ShortArray, frames: Int, endNanos: Long) {
        require(frames >= 0 && frames * channels <= samples.size) { "frames $frames do not fit the samples" }
        var offset = 0
        var count = frames
        if (count > capacityFrames) {
            // More than the ring holds: the head is as good as written and gone.
            val skip = count - capacityFrames
            offset = skip * channels
            framesWritten += skip
            count = capacityFrames
        }
        val values = count * channels
        val at = ((framesWritten % capacityFrames) * channels).toInt()
        val first = minOf(values, data.size - at)
        System.arraycopy(samples, offset, data, at, first)
        if (values > first) System.arraycopy(samples, offset + first, data, 0, values - first)
        framesWritten += count
        this.endNanos = endNanos
    }

    /**
     * The frames recorded from [fromNanos] to [toNanos], interleaved. The range is cut to
     * what the ring holds. An empty array when nothing of the range is held.
     */
    fun read(fromNanos: Long, toNanos: Long): ShortArray {
        if (isEmpty || toNanos <= fromNanos) return ShortArray(0)
        val from = maxOf(fromNanos, startNanos)
        val to = minOf(toNanos, endNanos)
        if (to <= from) return ShortArray(0)
        val firstFrame = framesWritten - nanosToFrames(endNanos - from)
        val lastFrame = framesWritten - nanosToFrames(endNanos - to)
        val count = (lastFrame - firstFrame).toInt()
        if (count <= 0) return ShortArray(0)
        val out = ShortArray(count * channels)
        val at = ((firstFrame % capacityFrames) * channels).toInt()
        val first = minOf(out.size, data.size - at)
        System.arraycopy(data, at, out, 0, first)
        if (out.size > first) System.arraycopy(data, 0, out, first, out.size - first)
        return out
    }

    /** Nanoseconds for a count of frames. */
    fun framesToNanos(frames: Long): Long = frames * 1_000_000_000L / sampleRate

    /** Frames for a count of nanoseconds, rounded down. */
    fun nanosToFrames(nanos: Long): Long = nanos * sampleRate / 1_000_000_000L
}
