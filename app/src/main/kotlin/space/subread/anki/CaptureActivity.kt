package space.subread.anki

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast

/**
 * Asks Android for the capture and starts [CaptureService] with the answer. It shows nothing
 * of its own: the dialog of Android is the whole screen.
 */
class CaptureActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val wanted = ArrayList<String>()
        if (CaptureService.canCaptureAudio && !granted(android.Manifest.permission.RECORD_AUDIO)) wanted += android.Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !granted(android.Manifest.permission.POST_NOTIFICATIONS)) {
            wanted += android.Manifest.permission.POST_NOTIFICATIONS
        }
        if (wanted.isEmpty()) ask() else requestPermissions(wanted.toTypedArray(), PERMISSIONS)
    }

    private fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Without the microphone permission the capture still takes pictures.
        if (CaptureService.canCaptureAudio && !granted(android.Manifest.permission.RECORD_AUDIO)) {
            Toast.makeText(this, R.string.capture_no_record, Toast.LENGTH_LONG).show()
        }
        ask()
    }

    @Suppress("DEPRECATION") // The result API of AndroidX would bring AppCompat in for one dialog.
    private fun ask() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(manager.createScreenCaptureIntent(), CONSENT)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CONSENT) {
            if (resultCode == RESULT_OK && data != null) {
                CaptureService.start(this, resultCode, data)
            } else {
                Toast.makeText(this, R.string.capture_denied, Toast.LENGTH_LONG).show()
            }
            finish()
        }
    }

    companion object {
        private const val PERMISSIONS = 1
        private const val CONSENT = 2
    }
}
