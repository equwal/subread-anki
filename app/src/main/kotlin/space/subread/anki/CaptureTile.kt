package space.subread.anki

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** The tile in the quick settings: one tap starts the capture, one tap stops it. */
class CaptureTile : TileService() {

    override fun onStartListening() {
        val tile = qsTile ?: return
        tile.state = if (CaptureService.instance != null) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    // The Intent form is the only one before Android 14; the version check keeps it there.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        if (CaptureService.instance != null) {
            CaptureService.stop(this)
            onStartListening()
            return
        }
        val intent = Intent(this, CaptureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
