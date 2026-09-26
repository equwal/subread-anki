package space.subread.anki

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.StateListDrawable
import android.widget.Button
import androidx.core.graphics.drawable.toDrawable

/**
 * A slim button with no frame: black text on white, light grey while it is pressed. No
 * ripple and no shadow, so an e-ink screen has nothing to redraw.
 */
fun slimButton(context: Context, label: String, onClick: () -> Unit): Button = Button(context).apply {
    text = label
    isAllCaps = false
    textSize = 14f
    setTextColor(
        ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(Color.GRAY, Color.BLACK),
        ),
    )
    background = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), Color.LTGRAY.toDrawable())
        addState(intArrayOf(), Color.TRANSPARENT.toDrawable())
    }
    stateListAnimator = null
    minWidth = 0
    minimumWidth = 0
    minHeight = 0
    minimumHeight = 0
    val density = context.resources.displayMetrics.density
    setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
    setOnClickListener { onClick() }
}
