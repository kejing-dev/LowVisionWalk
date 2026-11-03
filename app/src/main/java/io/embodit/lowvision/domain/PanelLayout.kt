package io.embodit.lowvision.domain

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Where a walk strip sits relative to the head (profile D9 / D21): [distM] ahead along the head's yaw, [dropDeg] below
 * the level eye line (+ down), size in dp. [follows]: the strip keeps up with the view's yaw (pill, word choices, hint;
 * profile D21 allows small status strips at the edge of the view). v0.1.4: the cards (safety, task list, result,
 * checklist, menu) are pages of the home window, a system window moved with the system's own window bar (D21 / D8).
 */
data class PanelSpot(val distM: Float, val dropDeg: Float, val widthDp: Float, val heightDp: Float, val follows: Boolean = false)

/** Attachment strips of the Stage (design P4, P6, P10). */
object PanelIds {
    const val HINT = "hint"
    const val PILL = "pill"
    const val CHOICES = "choices"
    val pool = listOf(HINT, PILL, CHOICES)
}

/**
 * v0.1.2 (profile D9 / D21): the strips sit at the edge of the view; v0.1.4: clear of the home window that the system
 * opens straight ahead, its TabBar and its window bar (hint above, word choices and the pill below). A panel's angular size is set by its dp size only
 * (scale = distance).
 */
val PanelSpots =
    mapOf(
        // v0.1.4: the home window spans about ±17° around the eye line, its TabBar up to 21.5° above, its window bar
        // down to 21° below (emulator measurement): the hint goes above the TabBar, the word choices below the window bar
        PanelIds.HINT to PanelSpot(0.9f, -25f, 600f, 96f, follows = true),
        PanelIds.PILL to PanelSpot(0.8f, 31f, 580f, 88f, follows = true),
        PanelIds.CHOICES to PanelSpot(0.8f, 24.5f, 600f, 76f, follows = true), // v0.1.3: 440 cut "TWICE DAILY" (D13 tour)
    )

/** A panel's place in Stage metres and its turn about the vertical (+Z of the panel towards the viewer). No pitch, no roll. */
data class UprightPose(val position: Vec3, val yaw: Float) {
    val rotation: Quat4
        get() = Quat4.axisAngle(Vec3.UP, yaw)
}

/**
 * The one way panels get a pose (profile D9): only the head's position and level heading count, pitch and roll of the
 * head are ignored, the panel stands upright and turns about the vertical towards the eye.
 */
object PanelPlace {
    /** Level heading of the head (radians, [WalkRules.yawOf] convention). Looking straight down / up, the head's up decides. */
    fun heading(head: HeadPose): Float {
        val f = head.forward
        var flat = Vec3(f.x, 0f, f.z)
        if (flat.length < 0.2f) {
            // nearly vertical view: the top of the head points the way the user faces (down) or away from it (up)
            val u = head.up
            flat = if (f.y < 0f) Vec3(u.x, 0f, u.z) else Vec3(-u.x, 0f, -u.z)
        }
        return if (flat.length < 1e-3f) WalkRules.yawOf(Vec3.AHEAD) else WalkRules.yawOf(flat.normalized())
    }

    /** [spot] in front of [head] along [heading] (default: the head's own), upright and facing the eye. */
    fun placeUpright(head: HeadPose, spot: PanelSpot, heading: Float = heading(head)): UprightPose {
        val drop = Math.toRadians(spot.dropDeg.toDouble()).toFloat()
        val dir = Vec3(sin(heading) * cos(drop), -sin(drop), cos(heading) * cos(drop))
        val at = head.position + dir * spot.distM
        return UprightPose(at, faceYaw(at, head.position, heading))
    }

    /** Yaw that turns a panel at [at] towards [eye] about the vertical; straight above / below, the panel faces back along [fallback]. */
    fun faceYaw(at: Vec3, eye: Vec3, fallback: Float): Float {
        val dx = eye.x - at.x
        val dz = eye.z - at.z
        return if (dx * dx + dz * dz < 1e-6f) fallback + Math.PI.toFloat() else atan2(dx, dz)
    }
}
