package io.embodit.lowvision.domain

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.1.5 (QA #3 q3-1, profile D9): the home window reopens only with a level head. */
class LevelGateTest {
    private val eye = Vec3(0.3f, 1.6f, -0.4f)

    private fun rad(deg: Float) = Math.toRadians(deg.toDouble()).toFloat()

    private fun head(yawDeg: Float, pitchDeg: Float, rollDeg: Float = 0f): HeadPose {
        val q = Quat4.axisAngle(Vec3.UP, rad(yawDeg)) * Quat4.axisAngle(Vec3.RIGHT, rad(pitchDeg)) * Quat4.axisAngle(Vec3(0f, 0f, -1f), rad(rollDeg))
        return HeadPose(eye, q.normalized())
    }

    /** Frames every 16 ms from [from] to [to] at a constant pitch; the first "open" reason, or null. */
    private fun run(gate: LevelGate, from: Long, to: Long, pitch: Float?): Pair<Long, String>? {
        var t = from
        while (t <= to) {
            gate.step(t, pitch)?.let { return t to it }
            t += 16
        }
        return null
    }

    @Test
    fun headDown30_doesNotOpen_thenLevel_opens() {
        val gate = LevelGate()
        gate.ask("reopen RESULT", 0L)
        val pitch = LevelGate.pitchDeg(head(0f, -30f))!!
        assertEquals(-30f, pitch, 0.1f)
        assertNull("head down 30° for 10 s: no open", run(gate, 0L, 10_000L, pitch))
        assertTrue(gate.waiting)
        assertEquals("asks to look up", -1, gate.hint(pitch))
        // head comes level: opens after the 300 ms dwell, not before
        val level = LevelGate.pitchDeg(head(0f, -2f))!!
        val open = run(gate, 10_016L, 12_000L, level)!!
        assertEquals("reopen RESULT", open.second)
        assertTrue(open.first - 10_016L in 300L..320L)
        assertTrue(!gate.waiting)
        assertEquals(0, gate.hint(level))
    }

    @Test
    fun headUp30_doesNotOpen_andAsksToLookDown() {
        val gate = LevelGate()
        gate.ask("recall", 0L)
        val pitch = LevelGate.pitchDeg(head(45f, 30f))!!
        assertNull(run(gate, 0L, 6_000L, pitch))
        assertEquals(1, gate.hint(pitch))
    }

    @Test
    fun aGlanceLevelShorterThanTheDwell_doesNotOpen() {
        val gate = LevelGate()
        gate.ask("reopen", 0L)
        assertNull(run(gate, 0L, 200L, 0f))
        assertNull(run(gate, 216L, 1_000L, -25f))
        assertNull(run(gate, 1_016L, 1_200L, 0f))
        assertTrue(gate.waiting)
    }

    @Test
    fun yawAndRoll_doNotCount_onlyPitch() {
        for (yaw in listOf(0f, 90f, 180f, -90f)) {
            val p = LevelGate.pitchDeg(head(yaw, 0f, 20f))!!
            assertTrue("yaw $yaw roll 20: pitch $p", abs(p) < 0.5f)
        }
    }

    @Test
    fun noHead_opensAfterFiveSecondsFromTheFirstHeadlessFrame() {
        val gate = LevelGate()
        gate.ask("reopen", 0L)
        // the identity pose at the origin (tracker not up yet) is no head
        assertNull(LevelGate.pitchDeg(HeadPose(Vec3.ZERO, Quat4.IDENTITY)))
        assertNull(LevelGate.pitchDeg(null))
        val open = run(gate, 2_000L, 10_000L, null)!!
        assertEquals("reopen/nohead", open.second)
        assertTrue(open.first - 2_000L in 5_000L..5_020L)
    }

    @Test
    fun hintSpot_followsTheViewPitch_clampedTo40() {
        val base = PanelSpot(0.9f, -25f, 600f, 96f, follows = true)
        val above = LevelGate.HINT_ABOVE_DEG
        assertEquals(30f - above, LevelGate.hintSpot(-30f, base).dropDeg, 1e-4f)
        assertEquals(-30f - above, LevelGate.hintSpot(30f, base).dropDeg, 1e-4f)
        assertEquals(40f, LevelGate.hintSpot(-70f, base).dropDeg, 1e-4f)
        // looking down 30°, the hint's lower edge (96 dp ≈ 4.4°) clears the pill strip's upper edge (31° - 2°)
        val pill = PanelSpots.getValue(PanelIds.PILL)
        assertTrue(LevelGate.hintSpot(-30f, base).dropDeg + 2.2f < pill.dropDeg - 2f)
        assertEquals(LevelGate.HINT_DIST_M, LevelGate.hintSpot(-30f, base).distM, 1e-4f)
        // upright (D9): the hint pose has yaw only, its centre on the line of sight when looking down 30°
        val h = head(30f, -30f)
        val pose = PanelPlace.placeUpright(h, LevelGate.hintSpot(-30f, base), PanelPlace.heading(h))
        val toHint = (pose.position - h.position).normalized()
        val off = Math.toDegrees(kotlin.math.acos(toHint.dot(h.forward).coerceIn(-1f, 1f).toDouble())).toFloat()
        assertTrue("hint just above the line of sight: $off°", abs(off - above) < 0.5f)
        val up = pose.rotation.rotate(Vec3.UP)
        assertTrue("hint upright", up.y > 0.9999f)
    }
}
