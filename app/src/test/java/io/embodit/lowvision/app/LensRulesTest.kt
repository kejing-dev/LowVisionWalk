package io.embodit.lowvision.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.1.6 (user, headset v0.1.5): with the lens window closed, 「按住看原样」 is gone and a press reaches nothing. */
class LensRulesTest {
    private val open = LensState(open = true, source = LensSource.CAMERA)

    @Test
    fun closedLensHasNoHoldButton() {
        assertFalse(LensRules.canHold(LensState()))
        assertFalse(LensRules.canHold(LensState(source = LensSource.CAMERA)))
        assertFalse(LensRules.canHold(LensState(error = "SpatialML not ready after 8 s")))
        assertTrue(LensRules.canHold(open))
    }

    @Test
    fun pressWithTheLensClosedChangesNothing() {
        // null = the store sets nothing and sends nothing to the camera
        assertNull(LensRules.hold(LensState(source = LensSource.CAMERA), down = true))
        assertNull(LensRules.hold(LensState(source = LensSource.CAMERA), down = false))
        assertNull(LensRules.hold(LensState(error = "x"), down = true))
    }

    @Test
    fun closingTheLensEndsAHold() {
        // what LvStore.lensClosed leaves behind: a release that comes after it is ignored too
        val pressed = LensRules.hold(open, down = true)!!
        assertTrue(pressed.comparing)
        val closed = LensState(source = pressed.source)
        assertFalse(closed.comparing)
        assertNull(LensRules.hold(closed, down = false))
    }

    @Test
    fun openLensPressAndRelease() {
        assertEquals(true, LensRules.hold(open, down = true)?.comparing)
        assertNull(LensRules.hold(open, down = false))
        assertEquals(false, LensRules.hold(open.copy(comparing = true), down = false)?.comparing)
    }
}
