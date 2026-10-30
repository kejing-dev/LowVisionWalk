package io.embodit.lowvision.platform

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.pico.spatial.core.lifecycle.Cancellable
import com.pico.spatial.sense.base.AnchorUpdate
import com.pico.spatial.sense.plane.PlaneAnchor
import com.pico.spatial.sense.plane.PlaneTrackingManager
import io.embodit.lowvision.domain.Surface
import io.embodit.lowvision.domain.Vec3
import kotlin.math.abs
import kotlin.math.max

/**
 * Plane detection (ported from Spatial Ruler), reduced to [Surface]s: centre, normal, an in-plane axis
 * and half extents, in Stage metres. Semantic labels: FLOOR, TABLE, WALL (door / window / curtain
 * planes count as wall: you can mark a door on them); everything else is OTHER.
 * Main thread only; started and stopped by the walk scene.
 */
class PlaneTracker {
    private val main = Handler(Looper.getMainLooper())
    private val surfaces = LinkedHashMap<String, Surface>()
    private var subscription: Cancellable? = null

    var startedAtMs = 0L
        private set

    val all: Collection<Surface>
        get() = surfaces.values

    fun start() {
        if (subscription != null) return
        startedAtMs = SystemClock.elapsedRealtime()
        subscription =
            PlaneTrackingManager.subscribeAnchorUpdate { update ->
                if (Looper.myLooper() == Looper.getMainLooper()) handle(update) else main.post { handle(update) }
            }
        runCatching { PlaneTrackingManager.start() }.onFailure { LvLog.e("PLANES start failed: $it", it) }
        LvLog.i("PLANES started state=${runCatching { PlaneTrackingManager.state }.getOrNull()}")
    }

    fun stop() {
        runCatching { subscription?.cancel() }
        subscription = null
        runCatching { PlaneTrackingManager.stop() }
        main.removeCallbacksAndMessages(null)
        surfaces.clear()
    }

    fun summary(): String = surfaces.values.groupingBy { it.kind }.eachCount().entries.joinToString(" ") { "${it.key}=${it.value}" }

    private fun handle(update: AnchorUpdate<PlaneAnchor>) {
        if (subscription == null) return
        val id = update.anchor.anchorUUID.toString()
        when (update.event) {
            AnchorUpdate.Event.ADDED, AnchorUpdate.Event.LOADED, AnchorUpdate.Event.UPDATED -> {
                val s = runCatching { update.anchor.toSurface() }.getOrNull() ?: return
                val isNew = !surfaces.containsKey(id)
                surfaces[id] = s
                if (isNew) LvLog.i("PLANES + ${s.kind} c=${LvLog.pos(s.center)} n=${LvLog.pos(s.normal)} half=${"%.2f x %.2f".format(s.halfU, s.halfV)}")
            }
            AnchorUpdate.Event.REMOVED -> surfaces.remove(id)
            else -> Unit
        }
    }

    private fun PlaneAnchor.toSurface(): Surface? {
        val q = transform.quaternion
        val p = transform.position
        val pts = vertices.map { v -> (q.rotateVector(v) + p).let { Vec3(it.x, it.y, it.z) } }
        if (pts.size < 3) return null
        val c = pts.fold(Vec3.ZERO) { a, b -> a + b } / pts.size.toFloat()
        var n = Vec3.ZERO
        for (i in pts.indices) {
            val a = pts[i] - c
            val b = pts[(i + 1) % pts.size] - c
            n += a.cross(b)
        }
        if (n.length < 1e-6f) return null
        n = n.normalized()
        val kind =
            when (semantics.name) {
                "FLOOR" -> Surface.Kind.FLOOR
                "TABLE" -> Surface.Kind.TABLE
                "WALL", "VIRTUAL_WALL", "DOOR", "WINDOW", "CURTAIN" -> Surface.Kind.WALL
                else -> if (abs(n.y) < 0.3f) Surface.Kind.WALL else Surface.Kind.OTHER
            }
        if (n.y < -0.9f) n = -n
        val axisU =
            if (abs(n.y) < 0.7f) Vec3.UP.cross(n).normalized()
            else (pts[1] - pts[0]).let { e -> (e - n * e.dot(n)).normalized() }
        val axisV = n.cross(axisU).normalized()
        var hu = 0f
        var hv = 0f
        for (pt in pts) {
            val r = pt - c
            hu = max(hu, abs(r.dot(axisU)))
            hv = max(hv, abs(r.dot(axisV)))
        }
        return Surface(kind, c, n, hu, hv, axisU)
    }
}
