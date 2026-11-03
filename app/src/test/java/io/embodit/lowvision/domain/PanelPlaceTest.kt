package io.embodit.lowvision.domain

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Profile D9 / D21 / D29: every panel pose comes from [PanelPlace.placeUpright], whatever the head's pitch and roll. */
class PanelPlaceTest {
    private val eye = Vec3(0.3f, 1.6f, -0.4f)

    private fun deg(rad: Float) = Math.toDegrees(rad.toDouble()).toFloat()

    private fun rad(deg: Float) = Math.toRadians(deg.toDouble()).toFloat()

    /** Head turned [yawDeg] about the vertical, then pitched (+ up) and rolled, in that order (head frame). */
    private fun head(yawDeg: Float, pitchDeg: Float = 0f, rollDeg: Float = 0f): HeadPose {
        val q = Quat4.axisAngle(Vec3.UP, rad(yawDeg)) * Quat4.axisAngle(Vec3.RIGHT, rad(pitchDeg)) * Quat4.axisAngle(Vec3(0f, 0f, -1f), rad(rollDeg))
        return HeadPose(eye, q.normalized())
    }

    /** The level bearing (rad) the head looks along when it is not pitched. */
    private fun levelBearing(yawDeg: Float) = WalkRules.yawOf(Quat4.axisAngle(Vec3.UP, rad(yawDeg)).rotate(Vec3.AHEAD))

    private fun angleDiffDeg(a: Float, b: Float): Float {
        var d = deg(a - b)
        while (d > 180f) d -= 360f
        while (d < -180f) d += 360f
        return abs(d)
    }

    private val poses = listOf("down 30" to head(40f, pitchDeg = -30f), "up 30" to head(40f, pitchDeg = 30f), "roll 20" to head(40f, rollDeg = 20f))

    private val cards = PanelSpots.filterValues { !it.follows }

    @Test
    fun cardsStandUprightBelowTheEyeAtComfortDistance() {
        for ((label, h) in poses) {
            for ((id, spot) in cards) {
                val p = PanelPlace.placeUpright(h, spot)
                val up = p.rotation.rotate(Vec3.UP)
                assertTrue("$label/$id up tilt", deg(acos(up.dot(Vec3.UP).coerceIn(-1f, 1f))) < 1f)
                val to = p.position - h.position
                val dist = to.length
                assertTrue("$label/$id distance $dist", dist in 0.8f..1.2f)
                val below = -deg(asin(to.y / dist))
                assertTrue("$label/$id centre $below° below the eye", below in 10f..20f)
                // the top edge is not above the eye (D21 default placement)
                val halfHeight = spot.heightDp * PANEL_DP_M * spot.distM / 2f
                assertTrue("$label/$id top edge above the eye", p.position.y + halfHeight <= h.position.y + 0.01f)
            }
        }
    }

    @Test
    fun cardsFaceTheEyeAboutTheVerticalOnly() {
        for ((label, h) in poses) {
            for ((id, spot) in cards) {
                val p = PanelPlace.placeUpright(h, spot)
                val normal = p.rotation.rotate(Vec3(0f, 0f, 1f))
                assertEquals("$label/$id normal level", 0f, normal.y, 1e-4f)
                val toEye = h.position - p.position
                val bearingToEye = atan2(toEye.x, toEye.z)
                assertTrue("$label/$id facing", angleDiffDeg(atan2(normal.x, normal.z), bearingToEye) < 1f)
            }
        }
    }

    /** D29: pressed with the head at the button's yaw, the content lands straight ahead (< 2°), for any yaw. */
    @Test
    fun placedStraightAheadOfTheHeadsYaw() {
        for (yaw in listOf(0f, 37f, 90f, 180f, -135f)) {
            for (h in listOf(head(yaw), head(yaw, pitchDeg = -30f), head(yaw, pitchDeg = 30f), head(yaw, rollDeg = 20f))) {
                val p = PanelPlace.placeUpright(h, PanelSpots.getValue(PanelIds.TASKS))
                val to = p.position - h.position
                val bearing = atan2(to.x, to.z)
                assertTrue("yaw $yaw", angleDiffDeg(bearing, levelBearing(yaw)) < 2f)
                assertTrue("yaw $yaw level distance", hypot(to.x, to.z) > 0.5f)
            }
        }
    }

    @Test
    fun lookingStraightDownStillPlacesAhead() {
        val h = head(60f, pitchDeg = -89f)
        val p = PanelPlace.placeUpright(h, PanelSpots.getValue(PanelIds.SAFETY))
        val to = p.position - h.position
        assertTrue(angleDiffDeg(atan2(to.x, to.z), levelBearing(60f)) < 2f)
    }

    /** Cards and the strips that show with them do not overlap in the view (D8): angular ranges below the eye. */
    @Test
    fun stripsClearOfTheCards() {
        fun range(id: String): ClosedFloatingPointRange<Float> {
            val s = PanelSpots.getValue(id)
            val half = deg(s.heightDp * PANEL_DP_M / 2f)
            return (s.dropDeg - half)..(s.dropDeg + half)
        }
        val pill = range(PanelIds.PILL)
        for (card in listOf(PanelIds.TASKS, PanelIds.RESULT, PanelIds.MENU)) {
            assertTrue("$card vs pill", range(card).endInclusive < pill.start)
        }
        assertTrue("hint above the cards", range(PanelIds.HINT).endInclusive < 0f)
        assertTrue("choices above the pill", range(PanelIds.CHOICES).endInclusive < pill.start)
    }

    private companion object {
        /** Panel metres per dp at scale 1 (ui.walk.DP_M). */
        const val PANEL_DP_M = 0.0008f
    }
}
