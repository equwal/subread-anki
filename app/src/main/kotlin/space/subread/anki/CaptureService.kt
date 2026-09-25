package space.subread.anki

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import androidx.core.content.IntentCompat
import androidx.core.graphics.createBitmap
import space.subread.anki.core.Loudness
import space.subread.anki.core.PcmRing
import java.io.File
import kotlin.concurrent.thread

/**
 * Keeps the last minute and a half of the sound of the device and the last picture of the
 * screen, while the user has the capture on. A card takes its clip and its picture from here.
 *
 * Android gives the sound of other apps to a media projection alone, and only since Android
 * 10. The projection needs the consent of the user each time it starts, and Android shows it
 * in the status bar. The service is a foreground service, as Android demands for a projection.
 *
 * The clock of the ring is `SystemClock.elapsedRealtimeNanos()`, the clock of the media
 * sessions: a report of the player maps its media times straight onto the ring.
 */
class CaptureService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var record: AudioRecord? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null

    /** The newest picture of the screen. Kept open until a newer one comes. */
    private var latest: Image? = null
    private val imageLock = Any()

    private val ring = PcmRing(SAMPLE_RATE, 1, SAMPLE_RATE * SECONDS)
    private val ringLock = Any()

    @Volatile
    private var running = false

    /** True when the sound of the player is captured; false on Android 9 or without the permission. */
    var hasAudio = false
        private set

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val code = intent.getIntExtra(EXTRA_CODE, 0)
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
        if (data == null || projection != null) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        val manager = getSystemService(MediaProjectionManager::class.java)
        val projection = runCatching { manager.getMediaProjection(code, data) }.getOrNull()
        if (projection == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        this.projection = projection
        // Android 14 demands the callback before the display, and stops the projection from
        // the status bar or the lock screen: the service goes with it.
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() = stopSelf()
        }, handler)
        running = true
        instance = this
        startAudio(projection)
        startScreen(projection)
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val channel = NotificationChannel(CHANNEL, getString(R.string.capture_channel), NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val stop = PendingIntent.getService(
            this, 0, Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.icon)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.capture_notification))
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, getString(R.string.capture_stop), stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startAudio(projection: MediaProjection) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = runCatching {
            AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minimum, SAMPLE_RATE) * 2)
                .build()
        }.getOrNull() ?: return
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return
        }
        this.record = record
        hasAudio = true
        record.startRecording()
        thread(name = "capture-audio") {
            val buffer = ShortArray(SAMPLE_RATE / 10)
            while (running) {
                val n = record.read(buffer, 0, buffer.size)
                if (n <= 0) continue
                val now = SystemClock.elapsedRealtimeNanos()
                synchronized(ringLock) { ring.write(buffer, n, now) }
            }
        }
    }

    private fun startScreen(projection: MediaProjection) {
        val metrics = DisplayMetrics()
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY) ?: return
        @Suppress("DEPRECATION") // The size of the whole screen: a service has no window to measure.
        display.getRealMetrics(metrics)
        val reader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 3)
        reader.setOnImageAvailableListener({ r ->
            val image = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            synchronized(imageLock) {
                latest?.close()
                latest = image
            }
        }, handler)
        this.reader = reader
        this.display = runCatching {
            projection.createVirtualDisplay(
                "SubReadAnki", metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, handler,
            )
        }.getOrNull()
    }

    /**
     * The sound between two moments of the ring clock, as AAC in [out]. Null when the ring
     * has none of it, less than a fifth of a second, or only silence: a reader app plays no
     * sound, and a card with a silent clip is worse than a card without one.
     */
    fun clip(fromNanos: Long, toNanos: Long, out: File): File? {
        val pcm = synchronized(ringLock) { ring.read(fromNanos, toNanos) }
        if (pcm.size < SAMPLE_RATE / 5 || Loudness.isSilent(pcm)) return null
        return runCatching { AacEncoder.encode(pcm, SAMPLE_RATE, 1, out); out }.getOrNull()
    }

    /**
     * The newest picture of the screen. Called when a text comes in, before the pop-up draws,
     * so the picture shows the app that sent the text. Null when there is none yet.
     */
    fun snapshot(): Bitmap? = synchronized(imageLock) {
        val image = latest ?: return null
        val plane = image.planes[0]
        val stride = plane.rowStride / plane.pixelStride
        val full = createBitmap(stride, image.height)
        full.copyPixelsFromBuffer(plane.buffer.also { it.rewind() })
        if (stride == image.width) full else Bitmap.createBitmap(full, 0, 0, image.width, image.height)
    }

    override fun onDestroy() {
        running = false
        instance = null
        record?.let { runCatching { it.stop() }; it.release() }
        record = null
        display?.release()
        display = null
        reader?.close()
        reader = null
        synchronized(imageLock) {
            latest?.close()
            latest = null
        }
        projection?.stop()
        projection = null
        super.onDestroy()
    }

    companion object {
        const val SAMPLE_RATE = 44_100
        /** How much sound the ring keeps. */
        const val SECONDS = 90
        const val CHANNEL = "capture"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "space.subread.anki.action.STOP_CAPTURE"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"

        /** The service while it runs; null when the capture is off. */
        @Volatile
        var instance: CaptureService? = null
            private set

        /** True when this Android can capture the sound of other apps. */
        val canCaptureAudio: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

        /** Starts the capture with the consent that Android gave [CaptureActivity]. */
        fun start(context: Context, code: Int, data: Intent) {
            val intent = Intent(context, CaptureService::class.java).putExtra(EXTRA_CODE, code).putExtra(EXTRA_DATA, data)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, CaptureService::class.java).setAction(ACTION_STOP))
        }
    }
}
