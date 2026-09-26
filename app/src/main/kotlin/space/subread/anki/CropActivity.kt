package space.subread.anki

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import space.subread.anki.core.Box
import space.subread.anki.core.Crop
import space.subread.anki.core.Fit
import space.subread.anki.core.Handle
import java.io.File
import kotlin.concurrent.thread

/**
 * The crop screen: the picture of a card, fit to the screen, with a rectangle on it. A drag at
 * a corner or an edge moves that corner or edge; a drag inside moves the rectangle. "Done"
 * writes the part in the rectangle as a new JPEG, and gives its path back.
 *
 * It always crops the picture as it came, so "Whole picture" and "Done" undo an earlier crop.
 */
class CropActivity : Activity() {

    private lateinit var picture: File
    private lateinit var bitmap: Bitmap
    private lateinit var view: CropView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PICTURE)
        val loaded = path?.let { BitmapFactory.decodeFile(it) }
        if (path == null || loaded == null) {
            Toast.makeText(this, R.string.crop_no_picture, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        picture = File(path)
        bitmap = loaded
        view = CropView(this, bitmap)
        intent.getFloatArrayExtra(EXTRA_BOX)?.takeIf { it.size == 4 }?.let {
            view.box = Crop.clamp(Box(it[0], it[1], it[2], it[3]), bitmap.width.toFloat(), bitmap.height.toFloat())
        }
        val bar = LinearLayout(this).apply {
            gravity = Gravity.END
            addView(slimButton(this@CropActivity, getString(R.string.cancel)) { finish() })
            addView(slimButton(this@CropActivity, getString(R.string.crop_whole)) { view.box = Crop.whole(bitmap.width.toFloat(), bitmap.height.toFloat()) })
            addView(slimButton(this@CropActivity, getString(R.string.crop_done)) { done() })
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.WHITE)
                fitsSystemWindows = true
                addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
                addView(bar, LinearLayout.LayoutParams(-1, -2))
            },
        )
    }

    /** Writes the crop, then gives it back. The whole picture gives the picture as it came. */
    private fun done() {
        val box = view.box
        val (left, top, right, bottom) = Crop.pixels(box, bitmap.width, bitmap.height).toList()
        val whole = left == 0 && top == 0 && right == bitmap.width && bottom == bitmap.height
        thread {
            val out = if (whole) picture else {
                val part = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
                Media.writeJpeg(part, File(picture.parentFile, "${picture.nameWithoutExtension}_crop_${System.currentTimeMillis()}.jpg"))
            }
            runOnUiThread {
                if (out == null) {
                    Toast.makeText(this, R.string.crop_failed, Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                setResult(
                    RESULT_OK,
                    Intent().putExtra(EXTRA_PICTURE, out.path).putExtra(EXTRA_BOX, floatArrayOf(box.left, box.top, box.right, box.bottom)),
                )
                finish()
            }
        }
    }

    companion object {
        /** In: the path of the picture as it came. Out: the path of the picture for the card. */
        const val EXTRA_PICTURE = "space.subread.anki.extra.CROP_PICTURE"

        /** In and out: the rectangle, left, top, right, bottom, in pixels of the picture as it came. */
        const val EXTRA_BOX = "space.subread.anki.extra.CROP_BOX"
    }
}

/** The picture, fit to the view, and the crop rectangle on it. Outside the rectangle, the picture is pale. */
// The view is built in code only, so it needs no constructor for a layout file.
@SuppressLint("ViewConstructor")
private class CropView(context: Context, private val bitmap: Bitmap) : View(context) {

    private val pictureWidth = bitmap.width.toFloat()
    private val pictureHeight = bitmap.height.toFloat()
    private val density = resources.displayMetrics.density

    /** The margin around the picture, so that a finger can take an edge at the side of the screen. */
    private val margin = 16 * density
    private val reach = 24 * density
    private var fit = Fit(1f, 0f, 0f)

    var box: Box = Crop.whole(pictureWidth, pictureHeight)
        set(value) {
            field = value
            invalidate()
        }

    private var handle: Handle? = null
    private var startBox = box
    private var startX = 0f
    private var startY = 0f

    private val veil = Paint().apply { color = 0xB3FFFFFF.toInt() }
    private val line = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
    }
    private val knob = Paint().apply { color = Color.BLACK }
    private val shown = RectF()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val inner = Crop.fit(w - 2 * margin, h - 2 * margin, pictureWidth, pictureHeight)
        fit = inner.copy(dx = inner.dx + margin, dy = inner.dy + margin)
    }

    override fun onDraw(canvas: Canvas) {
        shown.set(fit.toViewX(0f), fit.toViewY(0f), fit.toViewX(pictureWidth), fit.toViewY(pictureHeight))
        canvas.drawBitmap(bitmap, null, shown, null)
        val left = fit.toViewX(box.left)
        val top = fit.toViewY(box.top)
        val right = fit.toViewX(box.right)
        val bottom = fit.toViewY(box.bottom)
        canvas.drawRect(shown.left, shown.top, shown.right, top, veil)
        canvas.drawRect(shown.left, bottom, shown.right, shown.bottom, veil)
        canvas.drawRect(shown.left, top, left, bottom, veil)
        canvas.drawRect(right, top, shown.right, bottom, veil)
        canvas.drawRect(left, top, right, bottom, line)
        val size = 6 * density
        for (x in floatArrayOf(left, right)) for (y in floatArrayOf(top, bottom)) canvas.drawRect(x - size, y - size, x + size, y + size, knob)
    }

    // A drag has no click. The end of a touch calls performClick, for accessibility.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                handle = Crop.handleAt(box, fit.toPictureX(event.x), fit.toPictureY(event.y), reach / fit.scale)
                startBox = box
                startX = event.x
                startY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                val held = handle ?: return true
                box = Crop.drag(startBox, held, (event.x - startX) / fit.scale, (event.y - startY) / fit.scale, pictureWidth, pictureHeight)
            }
            MotionEvent.ACTION_UP -> {
                handle = null
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> handle = null
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
