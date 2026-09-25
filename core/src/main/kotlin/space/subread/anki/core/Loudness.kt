package space.subread.anki.core

import kotlin.math.sqrt

/** How loud a clip of 16-bit sound is. A clip of silence does not go on a card. */
object Loudness {

    /** Below this root mean square, a clip is silence: a reader app plays no sound. */
    const val SILENCE = 150.0

    /** The root mean square of the samples, from 0 to 32768. 0 for no samples. */
    fun rms(samples: ShortArray): Double {
        if (samples.isEmpty()) return 0.0
        var sum = 0.0
        for (sample in samples) sum += sample.toDouble() * sample
        return sqrt(sum / samples.size)
    }

    /** True when the clip has nothing to hear. */
    fun isSilent(samples: ShortArray, threshold: Double = SILENCE): Boolean = rms(samples) < threshold
}
