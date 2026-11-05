package io.embodit.lowvision.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.1.8 (user: 「关掉滤镜窗口的时候，就不应该可以点击按住看原样，因为窗口没了」): 「按住看原样」 is live only while the
 * SpatialML picture is up, and every way of closing the lens takes it away until the next open is up again.
 */
class LensRulesTest {
    private val up = LensRules.live(LensRules.opened(LensSource.CAMERA, 1_000L))!!

    @Test
    fun liveOnlyOnceThePictureIsUp() {
        val opening = LensRules.opened(LensSource.CAMERA, 1_000L)
        assertFalse("opening, no picture yet", LensRules.canHold(opening))
        assertNull("no press while opening", LensRules.hold(opening, down = true))
        assertTrue(LensRules.canHold(up))
        assertNull("already live", LensRules.live(up))
        assertNull("a closed lens never comes alive", LensRules.live(LensState()))
    }

    @Test
    fun everyClosePathDisablesTheButton() {
        // 「关闭滤镜窗」, the picture's ×, the frame window's ×, the service dying / a failed run: all LvStore.lensClosed
        val closed = LensRules.closed(up)
        assertFalse(LensRules.canHold(closed))
        assertEquals("source kept for the next open", LensSource.CAMERA, closed.source)
        // SpatialML failed to start or broke: LvStore.lensFailed (S23)
        val failed = LensRules.failed("SpatialML not ready after 8 s")
        assertFalse(LensRules.canHold(failed))
        for (gone in listOf(closed, failed, LensState())) {
            assertNull("press does nothing", LensRules.hold(gone, down = true))
            assertNull("release does nothing", LensRules.hold(gone, down = false))
        }
    }

    @Test
    fun closingWhileHeldThenReleasing() {
        val held = LensRules.hold(up, down = true)!!
        assertTrue(held.comparing)
        val closed = LensRules.closed(held)
        assertFalse(closed.comparing)
        assertNull(LensRules.hold(closed, down = false))
    }

    @Test
    fun reopenComesBackOnlyWhenUp() {
        val reopened = LensRules.opened(LensRules.closed(up).source, 5_000L)
        assertFalse(LensRules.canHold(reopened))
        assertTrue(LensRules.canHold(LensRules.live(reopened)!!))
    }

    @Test
    fun pressAndReleaseWhileUp() {
        assertEquals(true, LensRules.hold(up, down = true)?.comparing)
        assertNull("not held: nothing to release", LensRules.hold(up, down = false))
        assertEquals(false, LensRules.hold(up.copy(comparing = true), down = false)?.comparing)
    }
}
