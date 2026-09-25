package space.subread.anki.core

/**
 * What a player said about its position: the position, the wall-clock moment of that
 * report, and the speed. The same three numbers as the media session of Android.
 *
 * The report maps media time to wall-clock time in both directions, also when the player
 * is paused: the pause is the anchor, and the media before it ran at [speed].
 */
data class PlayerReport(
    /** The position in the media at [atNanos], in milliseconds. */
    val positionMs: Long,
    /** The wall clock of the report, `System.nanoTime()`. */
    val atNanos: Long,
    /** The playback speed. 1.0 is normal. Must be above zero. */
    val speed: Double = 1.0,
    /** True while the player plays. */
    val playing: Boolean = true,
) {
    init {
        require(speed > 0.0) { "speed must be above zero, was $speed" }
    }

    /** The wall-clock moment when the media was, or will be, at [mediaMs]. */
    fun wallAt(mediaMs: Long): Long =
        atNanos + Math.round((mediaMs - positionMs) * NANOS_PER_MILLI / speed)

    /** The position of the media at the wall-clock moment [nanos]. */
    fun mediaAt(nanos: Long): Long =
        positionMs + Math.round((nanos - atNanos) * speed / NANOS_PER_MILLI)

    /**
     * The wall-clock range in which the media from [startMs] to [endMs] played, with
     * [padMs] of media added on each side.
     */
    fun wallRange(startMs: Long, endMs: Long, padMs: Long = 0): LongRange {
        require(endMs >= startMs) { "end $endMs is before start $startMs" }
        return wallAt(startMs - padMs)..wallAt(endMs + padMs)
    }

    companion object {
        const val NANOS_PER_MILLI = 1_000_000.0

        /**
         * Reads the `state` row of the SubRead Overlay player provider:
         * `playing=1;position=96153;speed=1.0;package=de.ph1b.audiobook`. Null for an error
         * row (`error=no_player`) or a row without a position.
         */
        fun parseOverlayState(state: String?, atNanos: Long): PlayerReport? {
            if (state.isNullOrBlank()) return null
            val pairs = state.split(';').mapNotNull { pair ->
                val eq = pair.indexOf('=')
                if (eq < 0) null else pair.substring(0, eq).trim() to pair.substring(eq + 1).trim()
            }.toMap()
            if (pairs.containsKey("error")) return null
            val position = pairs["position"]?.toLongOrNull() ?: return null
            val speed = pairs["speed"]?.toDoubleOrNull()?.takeIf { it > 0.0 } ?: 1.0
            val playing = pairs["playing"] == "1" || pairs["playing"] == "true"
            return PlayerReport(position, atNanos, speed, playing)
        }

        /** The package of the player in an overlay `state` row, or null. */
        fun overlayPackage(state: String?): String? =
            state?.split(';')?.firstOrNull { it.startsWith("package=") }?.substringAfter('=')?.takeIf { it.isNotBlank() }
    }
}
