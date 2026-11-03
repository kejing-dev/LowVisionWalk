package io.embodit.lowvision.domain

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Where a walk panel sits relative to the head (profile D9 / D21): [distM] ahead along the head's yaw, [dropDeg] below
 * the level eye line (+ down), size in dp. [follows]: a small strip that keeps up with the view's yaw (pill, word
 * choices, hint; profile D21 allows small status strips at the edge of the view); every other panel is a card that is
 * placed once and then stays in the room.
 */
data class PanelSpot(val distM: Float, val dropDeg: Float, val widthDp: Float, val heightDp: Float, val follows: Boolean = false)

/** Attachment panels of the pool (design P3 – P8, P10, S24). */
object PanelIds {
    const val SAFETY = "safety"
    const val HINT = "hint"
    const val PILL = "pill"
    const val MENU = "menu"
    const val TASKS = "tasks"
    const val RESULT = "result"
    const val CHECKLIST = "checklist"
    const val CHOICES = "choices"
    val pool = listOf(SAFETY, HINT, PILL, MENU, TASKS, RESULT, CHECKLIST, CHOICES)
}

/**
 * v0.1.2 (profile D9 / D21): cards stand 0.8 – 0.9 m ahead with their centre 10 – 16° below the level eye line and the
 * top edge not above the eye; the strips sit at the edge of the view (hint above, word choices and the pill below the
 * tallest card, so nothing overlaps). A panel's angular size is set by its dp size only (scale = distance).
 */
val PanelSpots =
    mapOf(
        PanelIds.SAFETY to PanelSpot(0.9f, 11f, 520f, 440f),
        PanelIds.HINT to PanelSpot(0.9f, -16f, 600f, 96f, follows = true),
        PanelIds.PILL to PanelSpot(0.8f, 30f, 580f, 88f, follows = true),
        PanelIds.MENU to PanelSpot(0.8f, 12f, 520f, 410f), // v0.1.3 q2-4: 400 cut 「黄斑变性」 / Glaucoma / Hemianopia
        PanelIds.TASKS to PanelSpot(0.9f, 14f, 560f, 600f),
        PanelIds.RESULT to PanelSpot(0.9f, 10f, 500f, 420f), // v0.1.3 q2-4: the challenge button has its own row
        PanelIds.CHECKLIST to PanelSpot(0.9f, 16f, 600f, 680f),
        PanelIds.CHOICES to PanelSpot(0.8f, 22f, 600f, 76f, follows = true), // v0.1.3: 440 cut "TWICE DAILY" (D13 tour)
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
