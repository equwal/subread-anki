package space.subread.anki.core

import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.float
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The math of the crop screen. */
class CropTest {

    /** Runs a property test. JUnit wants a test method to return nothing. */
    private fun property(block: suspend () -> Unit) {
        runBlocking { block() }
    }

    /** A picture size: from a tiny picture to a large screenshot. */
    private val side: Arb<Int> = Arb.int(1..3000)

    /** A picture and a box on it that the crop screen can hold. */
    private val picture: Arb<Triple<Float, Float, Box>> = arbitrary {
        val width = side.bind().toFloat()
        val height = side.bind().toFloat()
        val raw = Box(
            Arb.float(-100f, 3100f).bind(), Arb.float(-100f, 3100f).bind(),
            Arb.float(-100f, 3100f).bind(), Arb.float(-100f, 3100f).bind(),
        )
        Triple(width, height, Crop.clamp(raw, width, height))
    }

    private val move: Arb<Float> = Arb.float(-5000f, 5000f)

    /** A tolerance for float sums, in pixels of the picture. */
    private val slack = 1e-2f

    private fun assertInside(box: Box, width: Float, height: Float) {
        assertTrue("$box is in $width x $height", box.left >= -slack && box.top >= -slack && box.right <= width + slack && box.bottom <= height + slack)
        val min = Crop.minSize(width, height)
        assertTrue("$box is at least $min", box.width >= min - slack && box.height >= min - slack)
    }

    @Test
    fun viewAndPictureRoundTrip() = property {
        checkAll(Arb.int(50..3000), Arb.int(50..3000), side, side, Arb.float(0f, 1f), Arb.float(0f, 1f)) { vw, vh, pw, ph, fx, fy ->
            val fit = Crop.fit(vw.toFloat(), vh.toFloat(), pw.toFloat(), ph.toFloat())
            val x = fx * pw
            val y = fy * ph
            assertEquals(x, fit.toPictureX(fit.toViewX(x)), pw * 1e-4f + 1e-3f)
            assertEquals(y, fit.toPictureY(fit.toViewY(y)), ph * 1e-4f + 1e-3f)
            // The whole picture is in the view, in the center.
            assertTrue(fit.toViewX(0f) >= -0.01f && fit.toViewX(pw.toFloat()) <= vw + 0.01f)
            assertTrue(fit.toViewY(0f) >= -0.01f && fit.toViewY(ph.toFloat()) <= vh + 0.01f)
            assertEquals(fit.toViewX(0f), vw - fit.toViewX(pw.toFloat()), 0.01f)
        }
    }

    @Test
    fun theClampIsInThePictureAndNotTooSmall() = property {
        checkAll(picture) { (width, height, box) -> assertInside(box, width, height) }
    }

    @Test
    fun aDragStaysInThePictureAndNotTooSmall() = property {
        checkAll(picture, Arb.enum<Handle>(), move, move) { (width, height, box), handle, dx, dy ->
            assertInside(Crop.drag(box, handle, dx, dy, width, height), width, height)
        }
    }

    @Test
    fun aCornerDragKeepsTheOppositeCorner() = property {
        checkAll(picture, move, move) { (width, height, box), dx, dy ->
            val topLeft = Crop.drag(box, Handle.TOP_LEFT, dx, dy, width, height)
            assertEquals(box.right, topLeft.right)
            assertEquals(box.bottom, topLeft.bottom)
            val topRight = Crop.drag(box, Handle.TOP_RIGHT, dx, dy, width, height)
            assertEquals(box.left, topRight.left)
            assertEquals(box.bottom, topRight.bottom)
            val bottomLeft = Crop.drag(box, Handle.BOTTOM_LEFT, dx, dy, width, height)
            assertEquals(box.right, bottomLeft.right)
            assertEquals(box.top, bottomLeft.top)
            val bottomRight = Crop.drag(box, Handle.BOTTOM_RIGHT, dx, dy, width, height)
            assertEquals(box.left, bottomRight.left)
            assertEquals(box.top, bottomRight.top)
        }
    }

    @Test
    fun anEdgeDragMovesThatEdgeAlone() = property {
        checkAll(picture, move, move) { (width, height, box), dx, dy ->
            val left = Crop.drag(box, Handle.LEFT, dx, dy, width, height)
            assertEquals(box.copy(left = left.left), left)
            val bottom = Crop.drag(box, Handle.BOTTOM, dx, dy, width, height)
            assertEquals(box.copy(bottom = bottom.bottom), bottom)
        }
    }

    @Test
    fun aMoveKeepsTheSize() = property {
        checkAll(picture, move, move) { (width, height, box), dx, dy ->
            val moved = Crop.drag(box, Handle.MOVE, dx, dy, width, height)
            assertEquals(box.width, moved.width, slack)
            assertEquals(box.height, moved.height, slack)
        }
    }

    @Test
    fun aSmallDragInsideMovesByTheDrag() {
        val moved = Crop.drag(Box(100f, 100f, 200f, 200f), Handle.MOVE, 10f, -20f, 1000f, 1000f)
        assertEquals(Box(110f, 80f, 210f, 180f), moved)
        // The minimum size stops a corner at 32 pixels from the opposite corner.
        assertEquals(Box(100f, 100f, 132f, 132f), Crop.drag(Box(100f, 100f, 200f, 200f), Handle.BOTTOM_RIGHT, -500f, -500f, 1000f, 1000f))
    }

    @Test
    fun theFingerTakesTheNearestPart() {
        val box = Box(100f, 100f, 300f, 200f)
        assertEquals(Handle.TOP_LEFT, Crop.handleAt(box, 104f, 96f, 10f))
        assertEquals(Handle.BOTTOM_RIGHT, Crop.handleAt(box, 305f, 195f, 10f))
        assertEquals(Handle.RIGHT, Crop.handleAt(box, 300f, 150f, 10f))
        assertEquals(Handle.TOP, Crop.handleAt(box, 200f, 108f, 10f))
        assertEquals(Handle.MOVE, Crop.handleAt(box, 200f, 150f, 10f))
        assertNull(Crop.handleAt(box, 50f, 150f, 10f))
    }

    @Test
    fun thePixelsAreInThePicture() = property {
        checkAll(picture) { (width, height, box) ->
            val (left, top, right, bottom) = Crop.pixels(box, width.toInt(), height.toInt()).toList()
            assertTrue(0 <= left && left < right && right <= width.toInt())
            assertTrue(0 <= top && top < bottom && bottom <= height.toInt())
            assertTrue(abs(right - left - box.width) <= 1f && abs(bottom - top - box.height) <= 1f)
        }
    }
}
