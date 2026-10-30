package io.embodit.lowvision.domain

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/** The scene objects the user can move, hide and turn (design `sceneObjects.list`). */
enum class SceneObject(val id: String) {
    TABLE("table"),
    SIGN("sign"),
    DOOR("door");

    /** Horizontal clearance from the head (design D14: ≥ 0.6 m, table / door ≥ 0.8 m). */
    val clearanceM: Float
        get() = if (this == SIGN) 0.6f else 0.8f

    companion object {
        fun of(id: String?): SceneObject? = entries.firstOrNull { it.id == id?.lowercase() }
    }
}

/** A detected surface reduced to what the walk needs (Stage metres). */
data class Surface(val kind: Kind, val center: Vec3, val normal: Vec3, val halfU: Float, val halfV: Float, val axisU: Vec3) {
    enum class Kind {
        FLOOR,
        TABLE,
        WALL,
        OTHER,
    }
}

/** Where an object stands: centre on its base (floor / table top / wall), yaw about the vertical (radians, 0 = facing +Z). */
data class Placement(val at: Vec3, val yaw: Float, val virtual: Boolean = false)

/**
 * The scene of one walk: floor height, the table with the three items, the sign, the door and (in the
 * challenge) the shoe. Positions in Stage metres. [hidden] objects are not drawn and cannot be picked.
 */
data class WalkLayout(
    val floorY: Float,
    val table: Placement,
    val sign: Placement,
    val door: Placement? = null,
    val shoe: Vec3? = null,
    val cupSlot: Int = 0,
    val hidden: Set<SceneObject> = emptySet(),
) {
    fun of(o: SceneObject): Placement? =
        when (o) {
            SceneObject.TABLE -> table
            SceneObject.SIGN -> sign
            SceneObject.DOOR -> door
        }

    fun with(o: SceneObject, p: Placement): WalkLayout =
        when (o) {
            SceneObject.TABLE -> copy(table = p)
            SceneObject.SIGN -> copy(sign = p)
            SceneObject.DOOR -> copy(door = p)
        }
}

/** Pure rules of the walk: placement, the safety monitor, the checklist order, the stick. */
object WalkRules {
    const val TABLE_W = 0.8f
    const val TABLE_D = 0.5f
    const val TABLE_H = 0.75f
    const val TABLE_AHEAD_M = 1.2f
    const val SIGN_AHEAD_M = 2.5f
    const val SIGN_CENTER_H = 1.5f
    const val SIGN_W = 0.30f
    const val SIGN_H = 0.20f
    const val DOOR_W = 0.9f
    const val DOOR_H = 2.0f
    const val DOOR_MAX_M = 4f
    const val DOOR_SKIP_MAX_M = 3f
    const val DOOR_SKIP_MIN_M = 1.5f
    const val DOOR_REACHED_M = 0.7f
    const val SHOE_HIT_M = 0.3f
    const val FALLBACK_FLOOR_BELOW_HEAD = 1.6f
    const val TABLE_MIN_H = 0.6f
    const val TABLE_MAX_H = 1.1f
    const val ITEM_SPACING_M = 0.27f
    const val SCAN_TIMEOUT_MS = 5_000L

    /** Unit vector of a yaw (0 = +Z) in the floor plane. */
    fun dirOf(yaw: Float) = Vec3(sin(yaw), 0f, cos(yaw))

    fun yawOf(dir: Vec3): Float = atan2(dir.x, dir.z)

    /** Horizontal unit direction the head looks along (yaw only). */
    fun headYawDir(head: HeadPose): Vec3 {
        val f = head.forward
        val flat = Vec3(f.x, 0f, f.z)
        return if (flat.length < 1e-3f) Vec3.AHEAD else flat.normalized()
    }

    /** Yaw that turns an object's +Z towards the head. */
    fun yawTowards(from: Vec3, head: Vec3): Float = atan2(head.x - from.x, head.z - from.z)

    /** Point [ahead] metres in front of the head (yaw only) at height [y] (design D9). */
    fun ahead(head: HeadPose, ahead: Float, y: Float, side: Float = 0f): Vec3 {
        val d = headYawDir(head)
        val r = Vec3(-d.z, 0f, d.x)
        return Vec3(head.position.x + d.x * ahead + r.x * side, y, head.position.z + d.z * ahead + r.z * side)
    }

    /** Pushes [p] out of the head's clearance circle (design D14), keeping its height. */
    fun clear(p: Vec3, head: Vec3, minM: Float): Vec3 {
        val dx = p.x - head.x
        val dz = p.z - head.z
        val d = hypot(dx, dz)
        if (d >= minM) return p
        if (d < 1e-3f) return Vec3(head.x, p.y, head.z - minM)
        return Vec3(head.x + dx / d * minM, p.y, head.z + dz / d * minM)
    }

    fun horizontal(a: Vec3, b: Vec3) = hypot(a.x - b.x, a.z - b.z)

    /** Floor height: the lowest FLOOR surface, else 1.6 m under the head (design virtualRoom). */
    fun floorY(surfaces: Collection<Surface>, head: HeadPose): Float =
        surfaces.filter { it.kind == Surface.Kind.FLOOR }.minOfOrNull { it.center.y } ?: (head.position.y - FALLBACK_FLOOR_BELOW_HEAD)

    /** A table top 0.6 – 1.1 m above the floor, 0.8 – 3 m away, the nearest one. */
    fun pickTable(surfaces: Collection<Surface>, floorY: Float, head: HeadPose): Surface? =
        surfaces
            .filter { (it.kind == Surface.Kind.TABLE || it.kind == Surface.Kind.OTHER) && it.normal.y > 0.9f }
            .filter { it.center.y - floorY in TABLE_MIN_H..TABLE_MAX_H && minOf(it.halfU, it.halfV) > 0.12f }
            .filter { horizontal(it.center, head.position) in 0.8f..3f }
            .minByOrNull { horizontal(it.center, head.position) + behindPenalty(it.center, head) }

    /** The nearest wall 2 – 4 m away, measured to its closest point (design E3). */
    fun pickWall(surfaces: Collection<Surface>, head: HeadPose): Pair<Surface, Vec3>? =
        surfaces
            .filter { it.kind == Surface.Kind.WALL && abs(it.normal.y) < 0.3f }
            .mapNotNull { w ->
                val p = closestOnWall(w, head.position)
                val d = horizontal(p, head.position)
                if (d in 2f..4f) Triple(w, p, d) else null
            }
            .minByOrNull { it.third + behindPenalty(it.second, head) }
            ?.let { it.first to it.second }

    /**
     * Placing prefers what is in front: a surface more than 60° off the head's heading counts 10 m further
     * (it is still used when nothing is in front). The tasks are done in place; the sign should not start
     * behind the viewer.
     */
    fun behindPenalty(p: Vec3, head: HeadPose): Float {
        val to = Vec3(p.x - head.position.x, 0f, p.z - head.position.z)
        if (to.length < 1e-3f) return 0f
        return if (to.normalized().dot(headYawDir(head)) < 0.5f) 10f else 0f
    }

    /** Closest point of a wall rectangle to [to], projected horizontally. */
    fun closestOnWall(w: Surface, to: Vec3): Vec3 {
        val rel = to - w.center
        // keep 0.2 m from the wall's ends; a wall narrower than 0.4 m gets its middle
        val margin = w.halfU - 0.2f
        val u = if (margin <= 0f) 0f else rel.dot(w.axisU).coerceIn(-margin, margin)
        val p = w.center + w.axisU * u
        return Vec3(p.x, w.center.y, p.z)
    }

    /** Wall normal pointing into the room, towards [viewer]. */
    fun inward(w: Surface, viewer: Vec3): Vec3 {
        val n = Vec3(w.normal.x, 0f, w.normal.z).let { if (it.length < 1e-3f) Vec3.AHEAD else it.normalized() }
        return if ((viewer - w.center).dot(n) < 0f) -n else n
    }

    /**
     * The scene after scanning: real table / wall when found, a virtual stand-in otherwise (design S19:
     * table 1.2 m ahead, stand 2.5 m ahead, floor 1.6 m below the head). The mug takes a random slot of
     * three, on the far half of the top.
     */
    fun layout(surfaces: Collection<Surface>, head: HeadPose, random: Random): WalkLayout {
        val floorY = floorY(surfaces, head)
        val tableSurface = pickTable(surfaces, floorY, head)
        val table =
            if (tableSurface != null) {
                val towards = Vec3(head.position.x - tableSurface.center.x, 0f, head.position.z - tableSurface.center.z).normalized()
                Placement(tableSurface.center, yawOf(towards))
            } else {
                val at = clear(ahead(head, TABLE_AHEAD_M, floorY + TABLE_H), head.position, SceneObject.TABLE.clearanceM)
                Placement(at, yawTowards(at, head.position), virtual = true)
            }
        val wall = pickWall(surfaces, head)
        val sign =
            if (wall != null) {
                val n = inward(wall.first, head.position)
                Placement(Vec3(wall.second.x + n.x * 0.01f, floorY + SIGN_CENTER_H, wall.second.z + n.z * 0.01f), yawOf(n))
            } else {
                val at = ahead(head, SIGN_AHEAD_M, floorY + SIGN_CENTER_H, side = 0.6f)
                Placement(at, yawTowards(at, head.position), virtual = true)
            }
        return WalkLayout(floorY = floorY, table = table, sign = sign, cupSlot = random.nextInt(3))
    }

    /** Positions of the three items on the table top: slot 0..2 across the width; the mug sits 0.1 m further back. */
    fun itemSpot(table: Placement, slot: Int, isMug: Boolean): Vec3 {
        val front = dirOf(table.yaw) // towards the viewer at placing time
        val across = Vec3(front.z, 0f, -front.x)
        val x = (slot - 1) * ITEM_SPACING_M
        val back = if (isMug) -0.1f else 0.04f
        return table.at + across * x + front * back
    }

    /** Which item sits in a slot: the mug (0), the salt (1), the glasses case (2). */
    fun itemInSlot(cupSlot: Int, slot: Int): Int = if (slot == cupSlot) 0 else if (slot == (cupSlot + 1) % 3) 1 else 2

    fun slotOfItem(cupSlot: Int, item: Int): Int = (0 until 3).first { itemInSlot(cupSlot, it) == item }

    /**
     * Skip the door marking (design S08 / safety.skipDoor): the frame stands min(3 m, nearest surface
     * ahead − 0.6 m) along the head's horizontal heading; under 1.5 m skipping is refused (null).
     * This is the design's fixed fallback spot, not aiming.
     */
    fun skipDoor(surfaces: Collection<Surface>, head: HeadPose, floorY: Float): Placement? {
        val dir = headYawDir(head)
        var nearest = Float.MAX_VALUE
        for (s in surfaces) {
            if (s.kind != Surface.Kind.WALL) continue
            val n = s.normal
            val denom = dir.dot(n)
            if (abs(denom) < 1e-3f) continue
            val t = (s.center - head.position).dot(n) / denom
            if (t <= 0f) continue
            val hit = head.position + dir * t
            val rel = hit - s.center
            if (abs(rel.dot(s.axisU)) <= s.halfU + 0.05f) nearest = minOf(nearest, t)
        }
        val d = minOf(DOOR_SKIP_MAX_M, nearest - 0.6f)
        if (d < DOOR_SKIP_MIN_M) return null
        val at = ahead(head, d, floorY)
        return Placement(at, yawTowards(at, head.position), virtual = true)
    }

    /** A marked door: on the floor below the picked point, facing the viewer; null beyond 4 m. */
    fun markDoor(point: Vec3, head: HeadPose, floorY: Float, wallNormal: Vec3?): Placement? {
        val base = Vec3(point.x, floorY, point.z)
        if (horizontal(base, head.position) > DOOR_MAX_M) return null
        val at = clear(base, head.position, SceneObject.DOOR.clearanceM)
        val yaw = if (wallNormal != null) yawOf(Vec3(wallNormal.x, 0f, wallNormal.z).let { n -> if ((head.position - at).dot(n) < 0f) -n else n }) else yawTowards(at, head.position)
        return Placement(at, yaw)
    }

    /**
     * The shoe of the challenge (design E4): 40 – 60 % of the way from [start] to the door, 0.25 m to
     * the side you can't see (hemianopia: the blind side; glaucoma: either; AMD: in the middle).
     */
    fun shoe(start: Vec3, door: Vec3, vision: Vision, random: Random, floorY: Float): Vec3 {
        val t = 0.4f + random.nextFloat() * 0.2f
        val line = Vec3(door.x - start.x, 0f, door.z - start.z)
        val dir = if (line.length < 1e-3f) Vec3.AHEAD else line.normalized()
        val right = Vec3(-dir.z, 0f, dir.x)
        val side =
            when (vision.disease) {
                Disease.HEMIANOPIA -> vision.side.sign.toFloat()
                Disease.GLAUCOMA -> if (random.nextBoolean()) 1f else -1f
                Disease.AMD -> 0f
            }
        val p = start + line * t + right * (0.25f * side)
        return Vec3(p.x, floorY, p.z)
    }

    /** Checklist ids, disease item first, the rest by what went wrong (design `rules.checklist.order`). */
    fun checklist(disease: Disease, cupMisses: Int, signMisses: Int, stepped: Int): List<String> {
        val first = disease.id
        val rest = mutableListOf("light", "path", "fixed", "contrast", "labels")
        fun lift(vararg ids: String) {
            for (id in ids.reversed()) {
                rest.remove(id)
                rest.add(0, id)
            }
        }
        if (cupMisses > 0) lift("contrast", "fixed")
        if (signMisses > 0) lift("labels")
        if (stepped > 0) lift("path")
        return listOf(first) + rest
    }

    /** Thumbstick turn rate, rad / s: dead zone 0.15, quadratic to 60°/s; right = clockwise from above (life-size v0.1.1). */
    fun stickRate(x: Float): Float {
        val a = abs(x)
        if (a < 0.15f) return 0f
        val k = ((a - 0.15f) / 0.85f).coerceIn(0f, 1f)
        val rate = (Math.PI / 3).toFloat() * k * k
        return if (x > 0) -rate else rate
    }
}

/**
 * Auto take-off (design `rules.safety.autoTakeOff`): horizontal head speed over 0.8 m/s (averaged over
 * 0.25 s), or a head drop of more than 0.3 m within 0.5 s. Feed one sample per frame.
 */
class SafetyMonitor(private val speedLimit: Float = 0.8f, private val dropLimit: Float = 0.3f) {
    private val samples = ArrayDeque<Pair<Long, Vec3>>()

    fun reset() = samples.clear()

    /** Returns the reason when the veil must come off now, else null. */
    fun feed(nowMs: Long, head: Vec3): String? {
        samples.addLast(nowMs to head)
        while (samples.size > 2 && nowMs - samples.first().first > 600) samples.removeFirst()
        val speedRef = samples.lastOrNull { nowMs - it.first >= 250 } ?: samples.first()
        val dt = (nowMs - speedRef.first) / 1000f
        if (dt >= 0.2f) {
            val v = WalkRules.horizontal(head, speedRef.second) / dt
            if (v > speedLimit) return "speed %.2f".format(v)
        }
        val maxY = samples.filter { nowMs - it.first <= 500 }.maxOfOrNull { it.second.y } ?: head.y
        if (maxY - head.y > dropLimit) return "drop %.2f".format(maxY - head.y)
        return null
    }
}
