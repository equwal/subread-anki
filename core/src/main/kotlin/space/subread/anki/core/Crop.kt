package space.subread.anki.core

import kotlin.math.abs
import kotlin.math.roundToInt

/** A rectangle on a picture, in pixels of the picture. [right] and [bottom] are the far edges. */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/**
 * Where a picture shows in a view: each pixel of the picture is [scale] pixels of the view,
 * and the picture starts at [dx], [dy] of the view.
 */
data class Fit(val scale: Float, val dx: Float, val dy: Float) {
    fun toPictureX(viewX: Float): Float = (viewX - dx) / scale
    fun toPictureY(viewY: Float): Float = (viewY - dy) / scale
    fun toViewX(pictureX: Float): Float = pictureX * scale + dx
    fun toViewY(pictureY: Float): Float = pictureY * scale + dy
}

/** What a finger moves on the crop rectangle. */
enum class Handle { MOVE, LEFT, TOP, RIGHT, BOTTOM, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

/**
 * The math of the crop screen. The rectangle is in pixels of the picture, so the size of the
 * screen does not change it. Any aspect ratio is allowed.
 */
object Crop {

    /** The smallest side of a crop, in pixels of the picture. A smaller picture gives its own side. */
    const val MIN_SIZE = 32f

    /** The whole picture fits in the view, in the center, as large as the view allows. */
    fun fit(viewWidth: Float, viewHeight: Float, pictureWidth: Float, pictureHeight: Float): Fit {
        val scale = minOf(viewWidth / pictureWidth, viewHeight / pictureHeight)
        return Fit(scale, (viewWidth - pictureWidth * scale) / 2, (viewHeight - pictureHeight * scale) / 2)
    }

    fun whole(width: Float, height: Float): Box = Box(0f, 0f, width, height)

    /** The smallest side of a crop of a picture of [width] x [height]. */
    fun minSize(width: Float, height: Float): Float = minOf(MIN_SIZE, width, height)

    /** The box moved into the picture, and made at least [minSize] on each side. */
    fun clamp(box: Box, width: Float, height: Float): Box {
        val min = minSize(width, height)
        val left = bound(box.left, 0f, width - min)
        val top = bound(box.top, 0f, height - min)
        return Box(left, top, bound(box.right, left + min, width), bound(box.bottom, top + min, height))
    }

    /**
     * The part of the box that a finger at [x], [y] holds, in pixels of the picture. A corner
     * or an edge within [reach] wins over the inside. Null when the finger is outside.
     */
    fun handleAt(box: Box, x: Float, y: Float, reach: Float): Handle? {
        if (x < box.left - reach || x > box.right + reach || y < box.top - reach || y > box.bottom + reach) return null
        val toLeft = abs(x - box.left)
        val toRight = abs(x - box.right)
        val toTop = abs(y - box.top)
        val toBottom = abs(y - box.bottom)
        // -1: the left or top edge; 1: the right or bottom edge; 0: neither. The nearer edge wins.
        val side = if (minOf(toLeft, toRight) > reach) 0 else if (toLeft <= toRight) -1 else 1
        val end = if (minOf(toTop, toBottom) > reach) 0 else if (toTop <= toBottom) -1 else 1
        return when {
            side == -1 && end == -1 -> Handle.TOP_LEFT
            side == 1 && end == -1 -> Handle.TOP_RIGHT
            side == -1 && end == 1 -> Handle.BOTTOM_LEFT
            side == 1 && end == 1 -> Handle.BOTTOM_RIGHT
            side == -1 -> Handle.LEFT
            side == 1 -> Handle.RIGHT
            end == -1 -> Handle.TOP
            end == 1 -> Handle.BOTTOM
            x in box.left..box.right && y in box.top..box.bottom -> Handle.MOVE
            else -> null
        }
    }

    /**
     * The box after a finger moved [handle] by [dx], [dy] pixels of the picture from [start].
     * An edge stops at the picture and at [minSize] from the opposite edge. A corner moves
     * two edges; the opposite corner stays. [Handle.MOVE] keeps the size.
     */
    fun drag(start: Box, handle: Handle, dx: Float, dy: Float, width: Float, height: Float): Box {
        val s = clamp(start, width, height)
        val min = minSize(width, height)
        val left = bound(s.left + dx, 0f, s.right - min)
        val right = bound(s.right + dx, s.left + min, width)
        val top = bound(s.top + dy, 0f, s.bottom - min)
        val bottom = bound(s.bottom + dy, s.top + min, height)
        return when (handle) {
            Handle.MOVE -> {
                val x = bound(s.left + dx, 0f, width - s.width)
                val y = bound(s.top + dy, 0f, height - s.height)
                Box(x, y, minOf(x + s.width, width), minOf(y + s.height, height))
            }
            Handle.LEFT -> s.copy(left = left)
            Handle.RIGHT -> s.copy(right = right)
            Handle.TOP -> s.copy(top = top)
            Handle.BOTTOM -> s.copy(bottom = bottom)
            Handle.TOP_LEFT -> s.copy(left = left, top = top)
            Handle.TOP_RIGHT -> s.copy(right = right, top = top)
            Handle.BOTTOM_LEFT -> s.copy(left = left, bottom = bottom)
            Handle.BOTTOM_RIGHT -> s.copy(right = right, bottom = bottom)
        }
    }

    /**
     * The box in whole pixels: left, top, right, bottom. Each is in the picture, and the
     * crop is at least one pixel on each side.
     */
    fun pixels(box: Box, width: Int, height: Int): IntArray {
        val left = box.left.roundToInt().coerceIn(0, width - 1)
        val top = box.top.roundToInt().coerceIn(0, height - 1)
        return intArrayOf(left, top, box.right.roundToInt().coerceIn(left + 1, width), box.bottom.roundToInt().coerceIn(top + 1, height))
    }

    /** [value] between [low] and [high]; [low] wins when [high] is below it. Never throws. */
    private fun bound(value: Float, low: Float, high: Float): Float = maxOf(low, minOf(value, high))
}
