package io.embodit.lowvision.domain

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionRulesTest {
    private val ahead = HeadPose(Vec3(0f, 1.6f, 0f), Quat4.IDENTITY)

    @Test
    fun glaucomaClearCentreDarkEdge() {
        val p = Vision(Disease.GLAUCOMA, 3).params // rClear 20, edge 8
        assertEquals(0f, VeilMath.alpha(p, 5f, 0f), 1e-4f)
        assertEquals(1f, VeilMath.alpha(p, 40f, 0f), 1e-4f)
        assertTrue(VeilMath.isVisible(p, 23f, 0f))
        assertFalse(VeilMath.isVisible(p, 25f, 0f))
    }

    @Test
    fun amdHidesCentreOnly() {
        val p = Vision(Disease.AMD, 3).params // rScotoma 8, edge 4
        assertEquals(1f, VeilMath.alpha(p, 0f, 0f), 1e-4f)
        assertEquals(0f, VeilMath.alpha(p, 30f, 0f), 1e-4f)
        assertFalse(VeilMath.isVisible(p, 9f, 0f))
        assertTrue(VeilMath.isVisible(p, 11f, 0f))
    }

    @Test
    fun hemianopiaLeftBlindKeepsTwoDegreesOfCentre() {
        val left = Vision(Disease.HEMIANOPIA, side = Side.LEFT).params
        assertEquals(1f, VeilMath.alpha(left, 20f, -20f), 1e-4f)
        assertEquals(0f, VeilMath.alpha(left, 20f, 20f), 1e-4f)
        assertTrue(VeilMath.isVisible(left, 0.5f, -0.5f)) // macular sparing
        assertFalse(VeilMath.isVisible(left, 5f, -5f))
        val right = Vision(Disease.HEMIANOPIA, side = Side.RIGHT).params
        assertFalse(VeilMath.isVisible(right, 5f, 5f))
        assertTrue(VeilMath.isVisible(right, 5f, -5f))
    }

    @Test
    fun veilColourIsContinuousGreyToTint() {
        val p = Vision(Disease.GLAUCOMA, 5).params // veil 0.2
        val centre = VeilMath.argb(p, 0f, 0f)
        assertEquals(51, centre ushr 24) // a = veil = 0.2
        assertEquals(0x80, (centre shr 16) and 0xFF) // pure grey where nothing is hidden
        val edge = VeilMath.argb(p, 60f, 0f)
        assertEquals(255, edge ushr 24)
        assertEquals(0x12, (edge shr 16) and 0xFF)
    }

    @Test
    fun textureLeftColumnsDarkForLeftBlind() {
        val p = Vision(Disease.HEMIANOPIA, side = Side.LEFT).params
        val n = 64
        val t = VeilMath.texture(p, n)
        val mid = n / 2
        assertTrue((t[mid * n + 2] ushr 24) > 240) // column 2 = viewer's left
        assertTrue((t[mid * n + n - 3] ushr 24) < 20)
    }

    @Test
    fun visibilityFromHeadFrame() {
        val p = Vision(Disease.HEMIANOPIA, side = Side.LEFT).params
        // 1 m to the left and 2 m ahead: left of centre -> not seen
        assertFalse(VeilMath.isVisible(p, ahead, Vec3(-1f, 1.6f, -2f)))
        assertTrue(VeilMath.isVisible(p, ahead, Vec3(1f, 1.6f, -2f)))
        // behind the head is never seen
        assertFalse(VeilMath.isVisible(Vision(Disease.AMD).params, ahead, Vec3(0f, 1.6f, 2f)))
    }

    @Test
    fun degreesLabels() {
        assertEquals(20, VeilParams.degreesOf(Vision(Disease.GLAUCOMA, 3)))
        assertEquals(8, VeilParams.degreesOf(Vision(Disease.AMD, 3)))
        assertNull(VeilParams.degreesOf(Vision(Disease.HEMIANOPIA)))
    }

    @Test
    fun virtualRoomWhenNothingIsFound() {
        val l = WalkRules.layout(emptyList(), ahead, Random(7))
        assertEquals(0f, l.floorY, 1e-4f) // 1.6 m under the head
        assertTrue(l.table.virtual)
        assertTrue(l.sign.virtual)
        assertEquals(-1.2f, l.table.at.z, 1e-3f)
        assertEquals(0.75f, l.table.at.y, 1e-3f)
        assertTrue(WalkRules.horizontal(l.table.at, ahead.position) >= 0.8f)
    }

    @Test
    fun realTableAndWallAreUsed() {
        val floor = Surface(Surface.Kind.FLOOR, Vec3(0f, 0f, 0f), Vec3.UP, 3f, 3f, Vec3.RIGHT)
        val table = Surface(Surface.Kind.TABLE, Vec3(0.3f, 0.74f, -1.5f), Vec3.UP, 0.5f, 0.3f, Vec3.RIGHT)
        val wall = Surface(Surface.Kind.WALL, Vec3(0f, 1.3f, -3f), Vec3(0f, 0f, 1f), 2f, 1.3f, Vec3.RIGHT)
        val l = WalkRules.layout(listOf(floor, table, wall), ahead, Random(1))
        assertFalse(l.table.virtual)
        assertFalse(l.sign.virtual)
        assertEquals(1.5f, l.sign.at.y, 1e-3f)
        assertEquals(-2.99f, l.sign.at.z, 1e-3f)
        assertEquals(0f, l.sign.yaw, 1e-3f) // faces +Z, into the room
    }

    @Test
    fun doorBeyondFourMetresIsRefused() {
        assertNull(WalkRules.markDoor(Vec3(0f, 0f, -4.5f), ahead, 0f, null))
        assertNotNull(WalkRules.markDoor(Vec3(0f, 0f, -3.5f), ahead, 0f, null))
    }

    @Test
    fun narrowWallDoesNotThrow() {
        // emulator room: a 0.46 m wide wall piece (half 0.23 m) crashed v0.1.0 build #4
        val w = Surface(Surface.Kind.WALL, Vec3(0f, 1.7f, -3f), Vec3(0f, 0f, 1f), 0.15f, 0.8f, Vec3.RIGHT)
        assertEquals(0f, WalkRules.closestOnWall(w, Vec3(2f, 1.6f, 0f)).x, 1e-4f)
        assertNotNull(WalkRules.pickWall(listOf(w), ahead))
    }

    @Test
    fun signPrefersTheWallInFront() {
        val behind = Surface(Surface.Kind.WALL, Vec3(0f, 1.3f, 2.2f), Vec3(0f, 0f, -1f), 2f, 1.3f, Vec3.RIGHT)
        val front = Surface(Surface.Kind.WALL, Vec3(0f, 1.3f, -3.5f), Vec3(0f, 0f, 1f), 2f, 1.3f, Vec3.RIGHT)
        assertEquals(front, WalkRules.pickWall(listOf(behind, front), ahead)!!.first)
        assertEquals(behind, WalkRules.pickWall(listOf(behind), ahead)!!.first)
    }

    @Test
    fun clearancePushesOut() {
        val p = WalkRules.clear(Vec3(0.2f, 0f, 0f), Vec3.ZERO, 0.8f)
        assertEquals(0.8f, p.x, 1e-4f)
    }

    @Test
    fun checklistOrder() {
        assertEquals(listOf("amd", "light", "path", "fixed", "contrast", "labels"), WalkRules.checklist(Disease.AMD, 0, 0, 0))
        assertEquals(listOf("glaucoma", "path", "labels", "contrast", "fixed", "light"), WalkRules.checklist(Disease.GLAUCOMA, 1, 1, 1))
    }

    @Test
    fun shoeOnTheBlindSide() {
        val start = Vec3(0f, 1.6f, 0f)
        val door = Vec3(0f, 0f, -3f)
        val s = WalkRules.shoe(start, door, Vision(Disease.HEMIANOPIA, side = Side.LEFT), Random(3), 0f)
        assertEquals(-0.25f, s.x, 1e-3f) // walking along -Z, the left is -X
        assertTrue(s.z < -1.1f && s.z > -1.9f)
        val m = WalkRules.shoe(start, door, Vision(Disease.AMD), Random(3), 0f)
        assertEquals(0f, m.x, 1e-3f)
    }

    @Test
    fun itemsSitOnTheTable() {
        val t = Placement(Vec3(0f, 0.75f, -1.2f), 0f)
        val mug = WalkRules.itemSpot(t, 1, true)
        assertEquals(-1.3f, mug.z, 1e-3f) // 0.1 m further from the viewer
        assertEquals(0.27f, WalkRules.itemSpot(t, 2, false).x, 1e-3f)
        for (cup in 0 until 3) for (item in 0 until 3) assertEquals(item, WalkRules.itemInSlot(cup, WalkRules.slotOfItem(cup, item)))
    }

    @Test
    fun safetyMonitorSpeedAndDrop() {
        val m = SafetyMonitor()
        var t = 0L
        var y = 1.6f
        for (i in 0 until 10) {
            assertNull(m.feed(t, Vec3(i * 0.005f, y, 0f)))
            t += 50
        }
        // 1.2 m/s for 0.3 s
        var hit: String? = null
        for (i in 0 until 8) {
            hit = hit ?: m.feed(t, Vec3(0.05f + i * 0.06f, y, 0f))
            t += 50
        }
        assertNotNull(hit)
        m.reset()
        hit = null
        for (i in 0 until 10) {
            y -= 0.04f
            hit = hit ?: m.feed(t, Vec3(0f, y, 0f))
            t += 50
        }
        assertNotNull(hit)
    }

    @Test
    fun stickRateDeadZoneAndDirection() {
        assertEquals(0f, WalkRules.stickRate(0.1f), 0f)
        assertTrue(WalkRules.stickRate(1f) < 0f) // right = clockwise from above = negative yaw
        assertEquals((Math.PI / 3).toFloat(), WalkRules.stickRate(-1f), 1e-4f)
    }
}
