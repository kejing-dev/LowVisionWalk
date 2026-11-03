package io.embodit.lowvision.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.1.3 (QA #2 q2-3, profile D1): 「移到标记处」 puts an object on the floor marker, not along the head's heading, and
 * 「跳过」 puts the door behind the table, whatever the head looks at. Four-sided placement (2026-10-28 rule): the user
 * stands east / south / west / north of the marker; the object faces the user within 10°.
 */
class MarkerPlacementTest {
    private val floorY = 0f

    private fun head(at: Vec3, yawDeg: Float) = HeadPose(at, Quat4.axisAngle(Vec3.UP, Math.toRadians(yawDeg.toDouble()).toFloat()))

    private fun angleDeg(a: Float, b: Float): Float {
        var d = (a - b) % (2 * PI).toFloat()
        if (d > PI) d -= (2 * PI).toFloat()
        if (d < -PI) d += (2 * PI).toFloat()
        return abs(Math.toDegrees(d.toDouble()).toFloat())
    }

    @Test
    fun toMarkerLandsOnTheMarkerFacingTheUserFromFourSides() {
        val marker = Vec3(1f, 0f, -2f)
        for (o in SceneObject.entries) {
            // east, south, west, north of the marker, 2 m away, looking somewhere else (head yaw must not matter)
            for ((dx, dz) in listOf(2f to 0f, 0f to 2f, -2f to 0f, 0f to -2f)) {
                for (lookDeg in listOf(0f, 90f, 200f)) {
                    val h = head(Vec3(marker.x + dx, 1.6f, marker.z + dz), lookDeg)
                    val p = WalkRules.toMarker(o, marker, floorY, h, virtual = true)
                    assertEquals("$o x", marker.x, p.at.x, 1e-4f)
                    assertEquals("$o z", marker.z, p.at.z, 1e-4f)
                    val towardsUser = atan2(h.position.x - p.at.x, h.position.z - p.at.z)
                    assertTrue("$o faces the user", angleDeg(p.yaw, towardsUser) < 10f)
                }
            }
        }
        val h = head(Vec3(0f, 1.6f, 0f), 0f)
        assertEquals(WalkRules.TABLE_H, WalkRules.toMarker(SceneObject.TABLE, marker, floorY, h, true).at.y, 1e-4f)
        assertEquals(WalkRules.SIGN_CENTER_H, WalkRules.toMarker(SceneObject.SIGN, marker, floorY, h, true).at.y, 1e-4f)
        assertEquals(0f, WalkRules.toMarker(SceneObject.DOOR, marker, floorY, h, true).at.y, 1e-4f)
    }

    @Test
    fun toMarkerKeepsTheHeadClearance() {
        val h = head(Vec3(0f, 1.6f, 0f), 0f)
        val p = WalkRules.toMarker(SceneObject.TABLE, Vec3(0.1f, 0f, -0.1f), floorY, h, true)
        assertTrue(WalkRules.horizontal(p.at, h.position) >= SceneObject.TABLE.clearanceM - 1e-4f)
    }

    @Test
    fun skipDoorStandsBehindTheTableWhereverTheHeadLooks() {
        // laid out with the viewer at the origin: table 1.2 m ahead (-Z), facing the viewer (+Z)
        val layout = WalkLayout(floorY = floorY, table = Placement(Vec3(0f, WalkRules.TABLE_H, -1.2f), 0f), sign = Placement(Vec3(0.6f, 1.5f, -2.5f), 0f))
        val doors = listOf(0f, 90f, 180f, 270f).map { WalkRules.skipDoor(layout, head(Vec3(0f, 1.6f, 0f), it)) }
        for (d in doors) {
            assertEquals(0f, d.at.x, 1e-4f)
            assertEquals(-1.2f - WalkRules.DOOR_BEHIND_TABLE_M, d.at.z, 1e-4f)
            assertEquals(floorY, d.at.y, 1e-4f)
            assertTrue(d.virtual)
            assertTrue(angleDeg(d.yaw, 0f) < 1f) // faces the viewer at the origin
        }
    }
}
