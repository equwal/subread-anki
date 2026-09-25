package space.subread.anki

import android.content.Context
import android.database.Cursor
import android.os.SystemClock
import androidx.core.net.toUri
import space.subread.anki.core.PlayerReport

/**
 * Asks SubRead Overlay for the player and the subtitle line of now, through its content
 * provider `content://space.subread.overlay.player`.
 *
 * `/state` is in every version of the overlay. `/line` came with the Anki support: a version
 * without it answers the state row for every path, and then the line is unknown.
 */
object OverlayClient {

    const val PACKAGE = "space.subread.overlay"

    /** The release authority, then the one of a debug build, which installs next to the release. */
    val AUTHORITIES = listOf("space.subread.overlay.player", "space.subread.overlay.debug.player")

    /** The authority of the installed overlay, or null without one. */
    fun authority(context: Context): String? =
        AUTHORITIES.firstOrNull { context.packageManager.resolveContentProvider(it, 0) != null }

    /** The subtitle line of now. The times are on the clock of the subtitle file. */
    data class Line(
        val index: Int,
        val startMs: Long,
        val endMs: Long,
        val text: String,
        val before: String?,
        val after: String?,
        /** The shift the user set: a time of the subtitle file is `offsetMs` later than the player. */
        val offsetMs: Long,
    )

    /** What the overlay knows now. [report] is on the clock of `SystemClock.elapsedRealtimeNanos()`. */
    data class Now(val report: PlayerReport?, val player: String?, val line: Line?)

    fun installed(context: Context): Boolean = authority(context) != null

    /**
     * The line of now and the player. Null when the overlay is not installed or does not answer.
     * With a release and a debug overlay side by side, the one that shows a line wins, else the
     * one that sees a player.
     */
    fun now(context: Context): Now? {
        val answers = AUTHORITIES.mapNotNull { authority ->
            if (context.packageManager.resolveContentProvider(authority, 0) == null) null else now(context, authority)
        }
        return answers.firstOrNull { it.line != null } ?: answers.firstOrNull { it.report != null } ?: answers.firstOrNull()
    }

    private fun now(context: Context, authority: String): Now? {
        val cursor = runCatching {
            context.contentResolver.query("content://$authority/line".toUri(), null, null, null, null)
        }.getOrNull() ?: return null
        cursor.use { c ->
            if (!c.moveToFirst()) return null
            val state = c.text("state")
            val player = PlayerReport.overlayPackage(state)
            val report = report(c, state)
            val text = c.text("text")
            val line = if (text == null) null else Line(
                index = c.long("index")?.toInt() ?: -1,
                startMs = c.long("start") ?: 0,
                endMs = c.long("end") ?: 0,
                text = text,
                before = c.text("before"),
                after = c.text("after"),
                offsetMs = c.long("offset") ?: 0,
            )
            return Now(report, player, line)
        }
    }

    /**
     * The report of the player. The line row gives the raw report: the position at the moment
     * the player reported it. A pause reported a minute ago still maps its media times to the
     * right wall-clock times. The state row alone gives the position of now.
     */
    private fun report(c: Cursor, state: String?): PlayerReport? {
        val position = c.long("reported_position")
        val at = c.long("reported_at")
        if (position != null && position >= 0 && at != null) {
            val speed = c.text("speed")?.toDoubleOrNull()?.takeIf { it > 0 } ?: 1.0
            val playing = c.long("playing") == 1L
            return PlayerReport(position, at * 1_000_000L, speed, playing)
        }
        return PlayerReport.parseOverlayState(state, SystemClock.elapsedRealtimeNanos())?.takeIf { it.positionMs >= 0 }
    }

    private fun Cursor.text(column: String): String? {
        val i = getColumnIndex(column)
        return if (i < 0 || isNull(i)) null else getString(i)
    }

    private fun Cursor.long(column: String): Long? {
        val i = getColumnIndex(column)
        return if (i < 0 || isNull(i)) null else getLong(i)
    }
}
