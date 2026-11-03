package io.embodit.lowvision.ui.walk

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.core.ecs.AnchorComponent
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.SortAsUIElementComponent
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.ViewCoordinateSpace
import com.pico.spatial.core.ecs.anchor.AnchorTarget
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.ui.foundation.content.SpatialViewAttachments
import com.pico.spatial.ui.foundation.gesture.SpatialDragValue
import com.pico.spatial.ui.foundation.gesture.SpatialTapValue
import io.embodit.lowvision.app.LvState
import io.embodit.lowvision.app.LvStore
import io.embodit.lowvision.app.WalkPhase
import io.embodit.lowvision.app.WalkState
import io.embodit.lowvision.domain.Copy
import io.embodit.lowvision.domain.HeadPose
import io.embodit.lowvision.domain.PanelIds
import io.embodit.lowvision.domain.PanelPlace
import io.embodit.lowvision.domain.PanelSpot
import io.embodit.lowvision.domain.PanelSpots
import io.embodit.lowvision.domain.Placement
import io.embodit.lowvision.domain.Quat4
import io.embodit.lowvision.domain.SafetyMonitor
import io.embodit.lowvision.domain.SceneObject
import io.embodit.lowvision.domain.Surface
import io.embodit.lowvision.domain.Task
import io.embodit.lowvision.domain.Vec3
import io.embodit.lowvision.domain.VeilMath
import io.embodit.lowvision.domain.VeilParams
import io.embodit.lowvision.domain.Vision
import io.embodit.lowvision.domain.WalkLayout
import io.embodit.lowvision.domain.WalkRules
import io.embodit.lowvision.platform.ControllerInput
import io.embodit.lowvision.platform.HeadTracker
import io.embodit.lowvision.platform.LvLog
import io.embodit.lowvision.platform.PlaneTracker
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal object ActiveScene {
    @Volatile var scene: WalkScene? = null
}

/** The one per-frame ECS system of the walk. */
class WalkFrameSystem : System() {
    override fun update(context: SceneUpdateContext) {
        // a bad frame must not take the app (and the user's veil) down: log it and keep going
        try {
            ActiveScene.scene?.onFrame(context)
        } catch (t: Throwable) {
            if (frameErrors++ % 120 == 0) LvLog.e("FRAME error: $t", t)
        }
    }

    private companion object {
        var frameErrors = 0
    }
}

/**
 * The 3D side of the walk inside the Mixed Stage (design container `STAGE_MIXED`): the head-locked veil,
 * the table with the mug and two decoys, the sign, the door frame, the shoe of the challenge, the
 * door-marking colliders and the panel pool. Per-frame work (head pose, lazy panel follow, the safety
 * monitor, the challenge distances, thumbstick turn, drags) happens in [onFrame] and never goes through
 * Compose; [render] applies a new state. Main thread only.
 */
class WalkScene : LvStore.SceneHost {
    val panelShown = mutableStateMapOf<String, Boolean>()
    val bindTick = mutableIntStateOf(0)

    private val main = Handler(Looper.getMainLooper())
    private val scope = MainScope()
    private val headTracker = HeadTracker()
    private val planes = PlaneTracker()
    private val controller = ControllerInput()
    private val safety = SafetyMonitor()
    private val random = Random(SystemClock.elapsedRealtime())

    private var content: SpatialViewContent? = null
    private var stageRoot: Entity? = null
    private var contentProbe: Entity? = null
    private var rig: PanelRig? = null
    private var shared: SharedResources? = null
    private var kit: SceneKit? = null
    private var attached = false

    /** Scene objects are built once the scan is over (mesh creation blocks the main thread; see SceneKit.warmUp). */
    private var objectsPending: Pair<Entity, SceneKit>? = null

    private var head = HeadPose(Vec3.ZERO, Quat4.IDENTITY)
    private var hasHead = false
    private var frames = 0L
    private var lastLogMs = 0L
    private var lastFrameMs = 0L
    private var state = LvState()

    /** Debug: the head used for judging (visibility, shoe, door), not for drawing. */
    var judgeOverride: HeadPose? = null

    // ---- veil
    private var veilAnchor: Entity? = null
    private var veilModel: Entity? = null
    private var veilMaterial: UnlitMaterial? = null
    private val veilTextures = HashMap<String, TextureResource>()
    private var veilKey: String? = null
    private var veilWanted: String? = null
    private var veilBuilding: String? = null

    // ---- scene objects
    private var table: Entity? = null
    private var tableMesh: Entity? = null
    private val items = arrayOfNulls<Entity>(3)
    private var sign: Entity? = null
    private var signStand: Entity? = null
    private var standPole: Entity? = null
    private var standFoot: Entity? = null
    private var signFace: Entity? = null
    private var signFaceKey: String? = null
    private var signMaterials = HashMap<String, UnlitMaterial>()
    private var door: Entity? = null
    private var doorGhost: Entity? = null
    private var shoe: Entity? = null
    private var shoeParts: List<Entity> = emptyList()
    private var floorCatcher: Entity? = null
    /** The floor marker (T17) and its see-through twin on the controller ray's floor hit while it is being put down. */
    private var marker: Entity? = null
    private var markerGhost: Entity? = null
    /** Where the door ghost stands now (T7); a pinch / trigger on the ghost marks the door there. */
    private var ghostAt: Placement? = null

    // ---- per-walk runtime
    private var scanStartMs = 0L
    private var lastPhase: WalkPhase? = null
    private var steppedOnce = false
    private var reached = false
    private var followYaw: Float? = null
    private var unsupportedSinceMs = 0L
    private var wasSupported = false
    private var stickYaw = 0f
    private var stickObject: SceneObject? = null
    private var stickRestMs = 0L
    private var drag: Drag? = null
    private var lastDragEndMs = 0L

    private class Drag(val obj: SceneObject, val start: Placement) {
        var totalPx = Vec3.ZERO
        var moved = false
        var at: Vec3 = start.at
    }

    // =========================================================================================
    // lifecycle
    // =========================================================================================

    fun attach(content: SpatialViewContent, attachments: SpatialViewAttachments) {
        if (attached || stageRoot != null) detach()
        this.content = content
        val root = Entity().named("stageRoot")
        content.addEntity(root)
        val probe = Entity().named("contentProbe")
        content.addEntity(probe)
        contentProbe = probe
        // an entity added to the SpatialView is not at the Stage origin: move the root once (Spatial Ruler)
        val offset = root.convertPositionFrom(Vector3.ZERO, null)
        val rotation = root.convertRotationFrom(Quat.identity(), null)
        root.components[TransformComponent::class.java]?.apply {
            setPosition(offset)
            setQuaternion(rotation)
        }
        stageRoot = root
        val res = SharedResources()
        shared = res
        val k = SceneKit(res)
        kit = k
        // meshes one per main-thread message (see SceneKit.warmUp): the veil's quad now, the box a moment later
        k.warmUp(0)
        buildVeil(root, res)
        main.postDelayed({ if (stageRoot === root) k.warmUp(1) }, BOX_WARMUP_MS)
        // the objects wait a moment: mesh creation blocks the main thread, and the Stage's first frames and
        // panels come first (ANR on the emulator when everything was built inside attach)
        objectsPending = root to k
        val rig = PanelRig(root) { id, shown -> panelShown[id] = shown }
        this.rig = rig
        for (id in PanelIds.pool) {
            val spot = PanelSpots.getValue(id)
            rig.world(id, spot.widthDp, spot.heightDp, spot.distM)
        }
        headTracker.start()
        controller.start()
        planes.start()
        ActiveScene.scene = this
        LvStore.sceneHost = this
        attached = true
        bindPanels(attachments)
        render(LvStore.state.value)
        LvLog.i("SCENE attached root_offset=${LvLog.pos(offset.domain())}")
    }

    fun bindPanels(attachments: SpatialViewAttachments) {
        val rig = rig ?: return
        if (!rig.bind(attachments, PanelIds.pool, SystemClock.elapsedRealtime())) {
            main.removeCallbacks(rebind)
            main.postDelayed(rebind, 250)
        }
    }

    private val rebind = Runnable { if (stageRoot != null) bindTick.intValue++ }

    /** Stops what [attach] started and destroys every entity (lifecycle rule). */
    fun detach() {
        if (!attached && stageRoot == null) return
        attached = false
        if (ActiveScene.scene === this) ActiveScene.scene = null
        if (LvStore.sceneHost === this) LvStore.sceneHost = null
        main.removeCallbacksAndMessages(null)
        scope.cancel()
        headTracker.stop()
        controller.stop()
        planes.stop()
        panelShown.clear()
        runCatching { rig?.release() }
        runCatching { stageRoot?.destroy(true) }
        runCatching { contentProbe?.destroy(true) }
        veilTextures.clear()
        signMaterials.clear()
        shared?.close()
        stageRoot = null
        contentProbe = null
        rig = null
        kit = null
        shared = null
        content = null
        veilAnchor = null
        veilModel = null
        veilMaterial = null
        hasHead = false
        LvLog.i("SCENE detached")
    }

    // =========================================================================================
    // building
    // =========================================================================================

    /**
     * The veil (design E1 / LV_Veil): a 10 x 10 m quad 2 m ahead, locked to the camera by the engine
     * (AnchorComponent camera target: no frame of lag from the app), unlit and transparent, depth test off
     * so it covers the virtual objects too, sorted as the farthest UI element so that every panel (0.5 –
     * 0.9 m) draws over it. The texture comes from [VeilMath] (fallback F1, computed at run time).
     */
    private fun buildVeil(root: Entity, res: SharedResources) {
        val k = kit ?: return
        val anchor = Entity().named("veilAnchor")
        anchor.components.set(AnchorComponent(AnchorTarget.createCameraTarget()).apply { positionOffset = Vector3(0f, 0f, -VeilMath.VEIL_DIST_M) })
        root.addChild(anchor)
        val material =
            res.keep(
                UnlitMaterial.create(BlendingMode.TRANSPARENT).apply {
                    setBaseColor(Color4(1f, 1f, 1f, 1f))
                    runCatching { setApplyToneMapping(false) }
                    setCullingMode(MaterialCullingMode.NONE)
                    setDepthWrite(false)
                    setDepthTest(false)
                }
            )
        val model = ModelEntity(k.quad, material).named("veil")
        model.components.set(SortAsUIElementComponent(VEIL_SORT_BIAS))
        anchor.addChild(model)
        model.setLocal(scale = Vec3(VeilMath.VEIL_HALF_M * 2f, VeilMath.VEIL_HALF_M * 2f, 1f))
        model.enabled = false
        veilAnchor = anchor
        veilModel = model
        veilMaterial = material
    }

    private fun buildObjects(root: Entity, k: SceneKit) {
        fun pickable(e: Entity, size: Vector3, center: Vector3 = Vector3(0f, 0f, 0f)) {
            val holder = Entity()
            e.addChild(holder)
            holder.setLocal(position = Vec3(center.x, center.y, center.z))
            holder.named(runCatching { e.getName() }.getOrNull() ?: "pick")
            holder.components.set(CollisionComponent(collisionShape = listOf(ShapeResource.createBox(size)), physicsMaterial = PhysicsMaterialResource()))
            holder.components.set(InteractableComponent())
        }
        // table: origin at the centre of the top surface; +Z faces the viewer at placing time
        val t = Entity().named("table").also { root.addChild(it); it.enabled = false }
        tableMesh =
            Entity().named("table").also { mesh ->
                t.addChild(mesh)
                val top = k.boxEntity(k.wood, "table").also { mesh.addChild(it) }
                top.setLocal(position = Vec3(0f, -0.02f, 0f), scale = Vec3(WalkRules.TABLE_W, 0.04f, WalkRules.TABLE_D))
                for ((sx, sz) in listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f)) {
                    val leg = k.boxEntity(k.wood, "table")
                    mesh.addChild(leg)
                    leg.setLocal(position = Vec3(sx * (WalkRules.TABLE_W / 2f - 0.05f), -WalkRules.TABLE_H / 2f, sz * (WalkRules.TABLE_D / 2f - 0.05f)), scale = Vec3(0.04f, WalkRules.TABLE_H - 0.04f, 0.04f))
                }
                pickable(mesh, Vector3(WalkRules.TABLE_W, WalkRules.TABLE_H, WalkRules.TABLE_D), Vector3(0f, -WalkRules.TABLE_H / 2f, 0f))
            }
        // the three items; their pick boxes are 1.5 x the model (design T8), and no hover effect (R1-4)
        items[0] =
            Entity().named("item:0").also { mug ->
                t.addChild(mug)
                k.boxEntity(k.mug, "item:0").also { mug.addChild(it); it.setLocal(position = Vec3(0f, 0.0475f, 0f), scale = Vec3(0.08f, 0.095f, 0.08f)) }
                // the handle: a "C" of three bars on the +X side
                k.boxEntity(k.mug, "item:0").also { mug.addChild(it); it.setLocal(position = Vec3(0.062f, 0.05f, 0f), scale = Vec3(0.012f, 0.055f, 0.014f)) }
                k.boxEntity(k.mug, "item:0").also { mug.addChild(it); it.setLocal(position = Vec3(0.05f, 0.0775f, 0f), scale = Vec3(0.03f, 0.012f, 0.014f)) }
                k.boxEntity(k.mug, "item:0").also { mug.addChild(it); it.setLocal(position = Vec3(0.05f, 0.0225f, 0f), scale = Vec3(0.03f, 0.012f, 0.014f)) }
                pickable(mug, Vector3(0.16f, 0.15f, 0.12f), Vector3(0.01f, 0.0475f, 0f))
            }
        items[1] =
            Entity().named("item:1").also { s ->
                t.addChild(s)
                k.boxEntity(k.salt, "item:1").also { s.addChild(it); it.setLocal(position = Vec3(0f, 0.045f, 0f), scale = Vec3(0.04f, 0.09f, 0.04f)) }
                pickable(s, Vector3(0.06f, 0.135f, 0.06f), Vector3(0f, 0.045f, 0f))
            }
        items[2] =
            Entity().named("item:2").also { c ->
                t.addChild(c)
                k.boxEntity(k.case, "item:2").also { c.addChild(it); it.setLocal(position = Vec3(0f, 0.02f, 0f), scale = Vec3(0.16f, 0.04f, 0.06f)) }
                pickable(c, Vector3(0.24f, 0.06f, 0.09f), Vector3(0f, 0.02f, 0f))
            }
        table = t
        // sign: origin at the centre of the board; +Z out of the wall
        sign =
            Entity().named("sign").also { s ->
                root.addChild(s)
                s.enabled = false
                k.boxEntity(k.stand, "sign").also { s.addChild(it); it.setLocal(position = Vec3(0f, 0f, -0.006f), scale = Vec3(WalkRules.SIGN_W + 0.02f, WalkRules.SIGN_H + 0.02f, 0.01f)) }
                pickable(s, Vector3(WalkRules.SIGN_W + 0.1f, WalkRules.SIGN_H + 0.1f, 0.06f))
                signStand =
                    Entity().named("sign").also { st ->
                        s.addChild(st)
                        standPole = k.boxEntity(k.stand, "sign").also { st.addChild(it) }
                        standFoot = k.boxEntity(k.stand, "sign").also { st.addChild(it) }
                    }
            }
        // door frame: origin on the floor at the middle of the threshold; +Z towards the room
        door =
            Entity().named("door").also { d ->
                root.addChild(d)
                d.enabled = false
                buildDoorFrame(d, k, k.door)
                pickable(d, Vector3(WalkRules.DOOR_W + 0.2f, WalkRules.DOOR_H + 0.1f, 0.2f), Vector3(0f, WalkRules.DOOR_H / 2f, 0f))
            }
        doorGhost =
            Entity().named("doorGhost").also { g ->
                root.addChild(g)
                g.enabled = false
                buildDoorFrame(g, k, k.ghost)
                // the ghost is the pick target on a wall (D24: the app's own collider, not one on the detected plane)
                Entity().named("doorGhost").also { hit ->
                    g.addChild(hit)
                    hit.setLocal(position = Vec3(0f, WalkRules.DOOR_H / 2f, 0f))
                    hit.components.set(CollisionComponent(collisionShape = listOf(ShapeResource.createBox(Vector3(WalkRules.DOOR_W, WalkRules.DOOR_H, 0.1f))), physicsMaterial = PhysicsMaterialResource()))
                    hit.components.set(InteractableComponent())
                }
            }
        // shoe of the challenge: two boxes, 28 x 10 x 10 cm; a red twin shows once it is stepped on (S15)
        shoe =
            Entity().named("shoe").also { s ->
                root.addChild(s)
                s.enabled = false
                fun parts(material: com.pico.spatial.core.ecs.resource.Material) =
                    Entity().also { g ->
                        s.addChild(g)
                        k.boxEntity(material, "shoe").also { g.addChild(it); it.setLocal(position = Vec3(0f, 0.03f, 0f), scale = Vec3(0.10f, 0.06f, 0.28f)) }
                        k.boxEntity(material, "shoe").also { g.addChild(it); it.setLocal(position = Vec3(0f, 0.08f, 0.07f), scale = Vec3(0.09f, 0.05f, 0.12f)) }
                    }
                shoeParts = listOf(parts(k.shoe), parts(k.shoeHit).also { it.enabled = false })
            }
        // door marking: the app's own virtual floor catcher, on only while marking (D24: no colliders on detected
        // planes; a wall is marked by pinching the door ghost that the controller ray puts on it)
        // floor marker: a flat cross 0.5 m wide with a small square in the middle, not pickable (the floor catcher is)
        fun markerShape(name: String, material: com.pico.spatial.core.ecs.resource.Material) =
            Entity().named(name).also { m ->
                root.addChild(m)
                m.enabled = false
                k.boxEntity(material, name).also { m.addChild(it); it.setLocal(position = Vec3(0f, 0.01f, 0f), scale = Vec3(0.5f, 0.012f, 0.04f)) }
                k.boxEntity(material, name).also { m.addChild(it); it.setLocal(position = Vec3(0f, 0.01f, 0f), scale = Vec3(0.04f, 0.012f, 0.5f)) }
                k.boxEntity(material, name).also { m.addChild(it); it.setLocal(position = Vec3(0f, 0.012f, 0f), scale = Vec3(0.12f, 0.016f, 0.12f)) }
            }
        marker = markerShape("marker", k.door)
        markerGhost = markerShape("markerGhost", k.ghost)
        floorCatcher =
            Entity().named("floor").also {
                it.components.set(CollisionComponent(collisionShape = listOf(ShapeResource.createBox(Vector3(16f, 0.02f, 16f))), physicsMaterial = PhysicsMaterialResource()))
                it.components.set(InteractableComponent())
                root.addChild(it)
                it.enabled = false
            }
    }

    /** Green dashed frame 0.9 x 2.0 m: two posts and a lintel cut into dashes. */
    private fun buildDoorFrame(parent: Entity, k: SceneKit, material: com.pico.spatial.core.ecs.resource.Material) {
        val stroke = 0.035f
        val dash = 0.16f
        val gap = 0.09f
        val name = runCatching { parent.getName() }.getOrNull() ?: "door"
        fun bar(x: Float, y: Float, sx: Float, sy: Float) = k.boxEntity(material, name).also { parent.addChild(it); it.setLocal(position = Vec3(x, y, 0f), scale = Vec3(sx, sy, stroke)) }
        for (side in listOf(-1f, 1f)) {
            var y = 0f
            while (y < WalkRules.DOOR_H) {
                val len = minOf(dash, WalkRules.DOOR_H - y)
                bar(side * WalkRules.DOOR_W / 2f, y + len / 2f, stroke, len)
                y += dash + gap
            }
        }
        var x = -WalkRules.DOOR_W / 2f
        while (x < WalkRules.DOOR_W / 2f) {
            val len = minOf(dash, WalkRules.DOOR_W / 2f - x)
            bar(x + len / 2f, WalkRules.DOOR_H, len, stroke)
            x += dash + gap
        }
    }

    // =========================================================================================
    // render (state -> entities)
    // =========================================================================================

    fun render(next: LvState) {
        state = next
        if (!attached) return
        val w = next.walk
        val phase = w?.phase
        if (phase != lastPhase) onPhase(lastPhase, phase)
        lastPhase = phase
        applyVeil(w)
        applyObjects(w, next)
        applyPanels(w)
        floorCatcher?.enabled = phase == WalkPhase.MARK_DOOR || w?.placingMarker == true
        // its top at the layout's floor (v0.1.3: it sat at Stage y = 0, so a pinch on a floor found 0.4 m up landed further away)
        w?.layout?.let { floorCatcher?.setLocal(position = Vec3(0f, it.floorY - 0.01f, 0f)) }
        val mk = w?.marker
        val markerShown = mk != null && phase in setOf(WalkPhase.READY, WalkPhase.TASK, WalkPhase.RESULT, WalkPhase.CHALLENGE)
        marker?.enabled = markerShown
        if (mk != null) marker?.setLocal(position = mk)
        if (w?.placingMarker != true) markerGhost?.enabled = false
    }

    private fun onPhase(from: WalkPhase?, to: WalkPhase?) {
        val now = SystemClock.elapsedRealtime()
        if (to != null && to != WalkPhase.SAFETY && to != WalkPhase.SCANNING && to != WalkPhase.OPENING) {
            objectsPending?.let { (root, k) ->
                objectsPending = null
                // one main-thread message, after this render: the frame that shows the new phase goes out first
                main.post { if (stageRoot === root) { buildObjects(root, k); render(LvStore.state.value) } }
            }
        }
        if (to == WalkPhase.SCANNING) scanStartMs = now
        if (to == WalkPhase.CHALLENGE) {
            steppedOnce = false
            reached = false
            shoeParts.getOrNull(0)?.enabled = true
            shoeParts.getOrNull(1)?.enabled = false
        }
        if (to in WalkState.VEIL_PHASES && from !in WalkState.VEIL_PHASES) safety.reset()
        followYaw = null // re-centre the panels on the next frame (D9: in front of the current head)
        LvLog.i("WALK phase $from -> $to")
    }

    private fun applyVeil(w: WalkState?) {
        val model = veilModel ?: return
        if (w == null) {
            model.enabled = false
            return
        }
        val key = veilKeyOf(w.vision)
        veilWanted = key
        val tex = veilTextures[key]
        if (tex != null && veilKey != key) {
            veilMaterial?.setBaseColorTexture(tex)
            veilKey = key
            LvLog.i("VEIL texture $key")
        } else if (tex == null) buildVeilTexture(w.vision)
        model.enabled = w.veilVisible && veilKey == key
    }

    private fun veilKeyOf(v: Vision) = if (overrideTest) "test" else v.key

    /** Debug `testtex`: an asymmetric 「F↑」 texture on the veil, to check up / down / left / right (profile D10). */
    var overrideTest = false

    private fun buildVeilTexture(v: Vision) {
        val key = veilKeyOf(v)
        if (veilBuilding == key) return
        veilBuilding = key
        val p = v.params
        val test = overrideTest
        scope.launch {
            val started = SystemClock.elapsedRealtime()
            val bitmap = withContext(Dispatchers.Default) { if (test) testBitmap() else Bitmap.createBitmap(VeilMath.texture(p, VEIL_PX), VEIL_PX, VEIL_PX, Bitmap.Config.ARGB_8888) }
            if (!attached) return@launch
            val tex = runCatching { TextureResource.create(bitmap) }.getOrElse {
                LvLog.e("VEIL texture failed: $it", it)
                null
            } ?: return@launch
            shared?.keep(tex)
            veilTextures[key] = tex
            if (veilBuilding == key) veilBuilding = null
            LvLog.i("VEIL built $key in ${SystemClock.elapsedRealtime() - started} ms")
            render(LvStore.state.value)
        }
    }

    private fun testBitmap(): Bitmap {
        val b = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(0x40000000)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF3030.toInt(); textSize = 120f; typeface = Typeface.DEFAULT_BOLD }
        c.drawText("F↑", 40f, 160f, paint)
        paint.color = 0xFF30C0FF.toInt()
        c.drawText("L", 40f, 470f, paint)
        c.drawText("R", 400f, 470f, paint)
        return b
    }

    private fun applyObjects(w: WalkState?, s: LvState) {
        val l = w?.layout
        val showScene = w != null && l != null && w.phase !in setOf(WalkPhase.SCANNING, WalkPhase.SAFETY, WalkPhase.OPENING)
        val t = table
        if (t != null) {
            t.enabled = showScene && SceneObject.TABLE !in l!!.hidden
            if (l != null) {
                val live = liveOf(SceneObject.TABLE, l.table)
                t.setLocal(position = live.at, rotation = Quat4.axisAngle(Vec3.UP, live.yaw))
                tableMesh?.enabled = l.table.virtual
                for (slot in 0 until 3) {
                    val item = WalkRules.itemInSlot(l.cupSlot, slot)
                    val local = WalkRules.itemSpot(Placement(Vec3.ZERO, 0f), slot, item == 0)
                    items[item]?.setLocal(position = local)
                }
            }
        }
        val sg = sign
        if (sg != null) {
            sg.enabled = showScene && SceneObject.SIGN !in l!!.hidden
            if (l != null) {
                val live = liveOf(SceneObject.SIGN, l.sign)
                sg.setLocal(position = live.at, rotation = Quat4.axisAngle(Vec3.UP, live.yaw))
                val standH = (live.at.y - l.floorY).coerceAtLeast(0.2f)
                signStand?.enabled = l.sign.virtual
                // pole from the floor to the board's lower edge, a flat foot on the floor
                standPole?.setLocal(position = Vec3(0f, -(standH + WalkRules.SIGN_H / 2f) / 2f, -0.02f), scale = Vec3(0.03f, standH - WalkRules.SIGN_H / 2f, 0.03f))
                standFoot?.setLocal(position = Vec3(0f, -standH, -0.02f), scale = Vec3(0.3f, 0.02f, 0.3f))
                updateSignFace(w, s)
            }
        }
        val d = door
        if (d != null) {
            val dp = l?.door
            d.enabled = showScene && dp != null && SceneObject.DOOR !in l.hidden
            if (dp != null) {
                val live = liveOf(SceneObject.DOOR, dp)
                d.setLocal(position = live.at, rotation = Quat4.axisAngle(Vec3.UP, live.yaw))
            }
        }
        val sh = shoe
        if (sh != null) {
            val at = l?.shoe
            sh.enabled = at != null && w.phase == WalkPhase.CHALLENGE
            if (at != null) {
                val dp = l.door?.at ?: at
                sh.setLocal(position = at, rotation = Quat4.axisAngle(Vec3.UP, WalkRules.yawTowards(at, dp)))
            }
        }
        if (w?.phase != WalkPhase.MARK_DOOR) doorGhost?.enabled = false
    }

    /** The placement drawn now: the drag or thumbstick turn in progress over the stored one. */
    private fun liveOf(o: SceneObject, p: Placement): Placement {
        var r = p
        drag?.let { if (it.obj == o && it.moved) r = r.copy(at = it.at) }
        if (stickObject == o) r = r.copy(yaw = r.yaw + stickYaw)
        return r
    }

    private fun updateSignFace(w: WalkState, s: LvState) {
        val k = kit ?: return
        val sg = sign ?: return
        val word = Copy(s.lang).signWords.getOrElse(w.signWord) { "" }
        if (word == signFaceKey) return
        signFaceKey = word
        signFace?.let { runCatching { it.destroy(true) } }
        val material = signMaterials.getOrPut(word) { k.texturedOpaque(signBitmap(word)) }
        signFace = k.quadEntity(material, "sign").also {
            sg.addChild(it)
            it.setLocal(position = Vec3(0f, 0f, 0.0005f), scale = Vec3(WalkRules.SIGN_W, WalkRules.SIGN_H, 1f))
        }
    }

    /** White board, black word, letters about 4 cm high on the 30 x 20 cm board (design E3). */
    private fun signBitmap(word: String): Bitmap {
        val w = 600
        val h = 400
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(0xFFFFFFFF.toInt())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF111111.toInt(); typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textAlign = Paint.Align.CENTER }
        // 4 cm of 20 cm = 80 px cap height; shrink long words to fit the width
        paint.textSize = 100f
        val width = paint.measureText(word)
        if (width > w * 0.9f) paint.textSize *= w * 0.9f / width
        val y = h / 2f - (paint.descent() + paint.ascent()) / 2f
        c.drawText(word, w / 2f, y, paint)
        return b
    }

    private fun applyPanels(w: WalkState?) {
        val rig = rig ?: return
        fun want(id: String, on: Boolean) {
            val slot = rig[id] ?: return
            if (on && !slot.shown) slot.show() else if (!on) slot.hide()
        }
        val p = w?.phase
        want(PanelIds.HINT, w?.hint != null && p != WalkPhase.SAFETY)
        want(PanelIds.PILL, p in WalkState.VEIL_PHASES)
        want(PanelIds.CHOICES, p == WalkPhase.TASK && w.run?.task == Task.SIGN && !w.menuOpen)
    }

    // =========================================================================================
    // per frame
    // =========================================================================================

    fun onFrame(context: SceneUpdateContext) {
        val root = stageRoot ?: return
        if (root.scene == null || context.scene != root.scene) return
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastFrameMs == 0L) 0f else ((now - lastFrameMs) / 1000f).coerceAtMost(0.5f)
        lastFrameMs = now
        frames++
        headTracker.pose()?.let {
            if (it.position.length > 1e-3f) {
                head = it
                hasHead = true
            }
        }
        val rig = rig ?: return
        rig.onFrame(now)
        rig.stowBehind(head)
        if (!attached || !hasHead) return
        val w = state.walk ?: return
        trackTracking(now)
        when (w.phase) {
            WalkPhase.SCANNING -> stepScan(now)
            WalkPhase.MARK_DOOR -> stepGhost(w)
            else -> if (w.placingMarker) stepMarkerGhost(w)
        }
        if (w.veilVisible) safety.feed(now, head.position)?.let { LvStore.autoOff(it) } else safety.reset()
        if (w.phase == WalkPhase.CHALLENGE) stepChallenge(w)
        repeat(controller.takeByPresses()) { if (w.phase in WalkState.VEIL_PHASES) LvStore.buttonToggle() }
        stepStick(w, now, dt)
        stepDrag(w)
        nearFade(w)
        placePanels()
        rig.faceViewer(head.position)
        if (now - lastLogMs > 2_000) {
            lastLogMs = now
            LvLog.i("FRAME n=$frames head=${LvLog.pos(head.position)} yaw=${"%.0f".format(Math.toDegrees(WalkRules.yawOf(WalkRules.headYawDir(head)).toDouble()))} state=${state.stateId} veil=${veilModel?.enabled} planes(${planes.summary()}) controller(${controller.status()})")
        }
    }

    private fun trackTracking(now: Long) {
        val support = headTracker.supportState()
        if (support == DataProvider.SupportState.SUPPORTED) {
            wasSupported = true
            unsupportedSinceMs = 0L
            if (state.walk?.trackingLost == true && !debugLost) LvStore.trackingChanged(false)
            return
        }
        if (!wasSupported) return
        if (unsupportedSinceMs == 0L) unsupportedSinceMs = now
        if (now - unsupportedSinceMs > 1_000 && state.walk?.trackingLost != true) LvStore.trackingChanged(true)
    }

    /** Debug `lost 1|0` holds the tracking-lost state (S27). */
    var debugLost = false

    private fun stepScan(now: Long) {
        val all = planes.all
        val floorY = WalkRules.floorY(all, head)
        val haveAll = all.any { it.kind == Surface.Kind.FLOOR } && WalkRules.pickTable(all, floorY, head) != null && WalkRules.pickWall(all, head) != null
        if (haveAll || now - scanStartMs >= WalkRules.SCAN_TIMEOUT_MS) {
            LvLog.i("SCAN done after ${now - scanStartMs} ms complete=$haveAll planes(${planes.summary()})")
            LvStore.scanDone(WalkRules.layout(all, head, random))
        }
    }

    /** T7: while marking, a see-through door follows the controller ray's hit on a wall or the floor (no head aiming). */
    private fun stepGhost(w: WalkState) {
        val g = doorGhost ?: return
        val ray = debugGhostRay ?: controller.ray()
        val floorY = w.layout?.floorY ?: return
        if (ray == null) {
            g.enabled = false
            ghostAt = null
            return
        }
        val hit = rayHit(ray, floorY)
        if (hit == null) {
            g.enabled = false
            ghostAt = null
            return
        }
        val p = WalkRules.markDoor(hit.first, head, floorY, hit.second)
        ghostAt = p
        g.enabled = p != null
        if (p != null) g.setLocal(position = p.at, rotation = Quat4.axisAngle(Vec3.UP, p.yaw))
    }

    var debugGhostRay: ControllerInput.Ray? = null

    /** T17: while the marker is being put down, its ghost follows the controller ray's hit on the floor (no head aiming). */
    private fun stepMarkerGhost(w: WalkState) {
        val g = markerGhost ?: return
        val floorY = w.layout?.floorY ?: return
        val ray = debugGhostRay ?: controller.ray()
        val hit = ray?.let { rayHit(it, floorY) }?.takeIf { it.second == null }?.first
        g.enabled = hit != null
        if (hit != null) g.setLocal(position = Vec3(hit.x, floorY, hit.z))
    }

    /** Nearest hit of a ray on a detected wall rectangle or the floor plane: point and wall normal (null on the floor). */
    private fun rayHit(ray: ControllerInput.Ray, floorY: Float): Pair<Vec3, Vec3?>? {
        var best: Pair<Vec3, Vec3?>? = null
        var bestT = Float.MAX_VALUE
        for (s in planes.all) {
            if (s.kind != Surface.Kind.WALL) continue
            val denom = ray.dir.dot(s.normal)
            if (abs(denom) < 1e-3f) continue
            val t = (s.center - ray.origin).dot(s.normal) / denom
            if (t <= 0f || t >= bestT) continue
            val hit = ray.origin + ray.dir * t
            val rel = hit - s.center
            val v = s.normal.cross(s.axisU).normalized()
            if (abs(rel.dot(s.axisU)) > s.halfU || abs(rel.dot(v)) > s.halfV) continue
            bestT = t
            best = hit to s.normal
        }
        if (ray.dir.y < -1e-3f) {
            val t = (floorY - ray.origin.y) / ray.dir.y
            if (t > 0f && t < bestT) best = (ray.origin + ray.dir * t) to null
        }
        return best
    }

    private fun judgeHead(): HeadPose = judgeOverride ?: head

    private fun stepChallenge(w: WalkState) {
        val l = w.layout ?: return
        val h = judgeHead().position
        val shoeAt = l.shoe
        if (shoeAt != null && !steppedOnce && WalkRules.horizontal(h, shoeAt) < WalkRules.SHOE_HIT_M) {
            steppedOnce = true
            shoeParts.getOrNull(0)?.enabled = false
            shoeParts.getOrNull(1)?.enabled = true
            LvLog.i("CHALLENGE stepped at=${LvLog.pos(h)}")
            LvStore.stepped()
        }
        val d = l.door ?: return
        val center = Vec3(d.at.x, d.at.y, d.at.z)
        if (!reached && WalkRules.horizontal(h, center) < WalkRules.DOOR_REACHED_M) {
            reached = true
            LvLog.i("CHALLENGE reached door at=${LvLog.pos(h)}")
            LvStore.doorReached()
        }
    }

    /** People who walk into an object see it at 30 % (D14). */
    private fun nearFade(w: WalkState) {
        val l = w.layout ?: return
        fun fade(e: Entity?, at: Vec3, r: Float) {
            e ?: return
            e.setOpacity(if (WalkRules.horizontal(head.position, at) < r) 0.3f else 1f)
        }
        fade(table, l.table.at, 0.45f)
        l.door?.let { fade(door, it.at, 0.45f) }
        fade(sign, l.sign.at, 0.3f)
    }

    // ---- thumbstick turn (D1): the selected object turns about its vertical axis

    private fun stepStick(w: WalkState, now: Long, dt: Float) {
        val x = debugStick?.takeIf { now < debugStickUntil } ?: controller.stick(now)
        val rate = WalkRules.stickRate(x)
        val o = w.selected
        if (rate != 0f && w.run == null && w.layout?.of(o) != null) {
            if (stickObject != o) {
                commitStick(w)
                stickObject = o
                stickYaw = 0f
            }
            stickYaw += rate * dt
            stickRestMs = now
            render(state)
        } else if (stickObject != null && now - stickRestMs > 300) commitStick(w)
    }

    private fun commitStick(w: WalkState) {
        val o = stickObject ?: return
        val p = w.layout?.of(o)
        val yaw = stickYaw
        stickObject = null
        stickYaw = 0f
        if (p != null && abs(yaw) > 1e-3f) LvStore.objectMoved(o, p.copy(yaw = p.yaw + yaw))
    }

    var debugStick: Float? = null
    var debugStickUntil = 0L

    // ---- strips (D9): every pose comes from PanelPlace.placeUpright (head position + level heading only). The small
    // strips (pill, word choices, hint) keep up with the heading lazily (design P4: > 25°). The cards are pages of the
    // home window since v0.1.4: the system window bar moves them (D21), nothing here drags a panel.

    private fun placePanels() {
        val rig = rig ?: return
        val headingNow = PanelPlace.heading(head)
        val fy = followYaw
        if (fy == null || VeilMath.yawDeltaDeg(headingNow, fy) > FOLLOW_DEG) followYaw = headingNow
        val heading = followYaw ?: headingNow
        for ((id, spot) in PanelSpots) {
            val slot = rig[id] ?: continue
            if (!slot.shown) continue
            val at = PanelPlace.placeUpright(head, spot, if (spot.follows) heading else headingNow).position
            if (abs(slot.scaleX - spot.distM) > 0.01f) slot.show(at = at, kx = spot.distM, ky = spot.distM) else slot.moveTo(at)
        }
    }

    // =========================================================================================
    // input
    // =========================================================================================

    fun onSpatialTap(value: SpatialTapValue) {
        val name = runCatching { value.targetEntity?.getName() }.getOrNull() ?: "none"
        val stage = toStage(value.position.x, value.position.y, value.position.z)
        LvLog.i("TAP target=$name kind=${value.interactionKind} stage=${LvLog.pos(stage)}")
        if (SystemClock.elapsedRealtime() - lastDragEndMs < 400) return
        handleTap(name, stage)
    }

    internal fun handleTap(name: String, stage: Vec3?) {
        // the store, not the last rendered state: a move a moment ago may not have been rendered yet
        val w = LvStore.state.value.walk ?: return
        val l = w.layout
        // putting the marker down (T17): the controller's trigger or a gaze pinch on the floor
        if (w.placingMarker && w.phase != WalkPhase.MARK_DOOR) {
            if (name == "floor" && stage != null && l != null) LvStore.markerPlaced(Vec3(stage.x, l.floorY, stage.z))
            return
        }
        when (w.phase) {
            WalkPhase.MARK_DOOR -> {
                if (stage == null || l == null) return
                if (name == "doorGhost") ghostAt?.let { LvStore.doorMarked(it) }
                else if (name == "floor") LvStore.doorMarked(WalkRules.markDoor(stage, head, l.floorY, null))
            }
            WalkPhase.TASK -> {
                l ?: return
                when {
                    name.startsWith("item:") -> {
                        val item = name.removePrefix("item:").toIntOrNull() ?: return
                        val center = itemCenter(l, item)
                        val visible = VeilMath.isVisible(w.vision.params, judgeHead(), center)
                        LvLog.i("PICK item=$item visible=$visible angles=${anglesLine(center)}")
                        LvStore.itemPicked(item, visible)
                    }
                    name == "door" -> {
                        val d = l.door ?: return
                        val center = Vec3(d.at.x, d.at.y + WalkRules.DOOR_H / 2f, d.at.z)
                        val visible = VeilMath.isVisible(w.vision.params, judgeHead(), center)
                        LvLog.i("PICK door visible=$visible angles=${anglesLine(center)}")
                        LvStore.doorPicked(visible)
                    }
                }
            }
            else ->
                when {
                    name == "table" || name.startsWith("item:") -> LvStore.objectSelected(SceneObject.TABLE)
                    name == "sign" -> LvStore.objectSelected(SceneObject.SIGN)
                    name == "door" -> LvStore.objectSelected(SceneObject.DOOR)
                }
        }
    }

    private fun anglesLine(target: Vec3): String {
        val h = judgeHead()
        val (t, tx) = VeilMath.anglesOf(h.rotation.conjugate().rotate(target - h.position))
        return String.format(Locale.US, "theta=%.1f thetaX=%.1f", t, tx)
    }

    /** Centre of an item in Stage metres (about half its height above the table top). */
    private fun itemCenter(l: WalkLayout, item: Int): Vec3 {
        val slot = WalkRules.slotOfItem(l.cupSlot, item)
        val spot = WalkRules.itemSpot(l.table, slot, item == 0)
        return Vec3(spot.x, spot.y + 0.045f, spot.z)
    }

    fun onSpatialDrag(phase: String, value: SpatialDragValue) {
        val name = runCatching { value.targetEntity?.getName() }.getOrNull() ?: "none"
        when (phase) {
            "start" -> {
                val w = state.walk
                val o =
                    when {
                        name == "table" || name.startsWith("item:") -> SceneObject.TABLE
                        name == "sign" -> SceneObject.SIGN
                        name == "door" -> SceneObject.DOOR
                        else -> null
                    }
                val p = o?.let { w?.layout?.of(it) }
                if (w == null || o == null || p == null || w.run != null || w.phase == WalkPhase.MARK_DOOR) {
                    drag = null
                    return
                }
                drag = Drag(o, p)
                LvLog.i("DRAG start ${o.id}")
            }
            "move" -> {
                val d = drag ?: return
                val a = value.dragAmount
                d.totalPx = Vec3(d.totalPx.x + a.x, d.totalPx.y + a.y, d.totalPx.z + a.z)
            }
            else -> endDrag()
        }
    }

    /**
     * T17: the object slides on the floor by the hand's horizontal travel, geared by its distance (life-size
     * rule; a pointing-line rule throws far objects), and keeps the head clearance (D14).
     */
    private fun stepDrag(w: WalkState) {
        val d = drag ?: return
        val hand = handDelta(d.totalPx) ?: return
        if (!d.moved) {
            if (hand.length < 0.02f) return
            d.moved = true
        }
        val dist = WalkRules.horizontal(d.start.at, head.position)
        val gain = (dist / 1.5f).coerceIn(1f, 4f)
        val f = WalkRules.headYawDir(head)
        val flat = Vec3(hand.x, 0f, hand.z) + f * hand.y
        val target = Vec3(d.start.at.x + flat.x * gain, d.start.at.y, d.start.at.z + flat.z * gain)
        d.at = WalkRules.clear(target, head.position, d.obj.clearanceM)
        render(state)
        if (w.run != null) drag = null
    }

    private fun endDrag() {
        val d = drag ?: return
        drag = null
        if (!d.moved) return
        lastDragEndMs = SystemClock.elapsedRealtime()
        LvStore.objectMoved(d.obj, d.start.copy(at = d.at))
    }

    private fun handDelta(px: Vec3): Vec3? {
        val moved = toStage(px.x, px.y, px.z) ?: return null
        val origin = toStage(0f, 0f, 0f) ?: return null
        return moved - origin
    }

    private fun toStage(x: Float, y: Float, z: Float): Vec3? {
        val c = content ?: return null
        val probe = contentProbe ?: return null
        return runCatching {
                val local = c.convertPosition(Vector3(x, y, z), ViewCoordinateSpace.Local, c.localSpatialCoordinateSpace)
                probe.convertPositionTo(local, null).domain()
            }
            .getOrNull()
    }

    // =========================================================================================
    // SceneHost
    // =========================================================================================

    override fun head(): HeadPose? = if (hasHead) head else null

    override fun skipDoor(): Placement? {
        val l = state.walk?.layout ?: return null
        return WalkRules.skipDoor(l, head)
    }

    /** Debug `marker`: puts the marker down as a floor tap [dist] m ahead would (starts placing first, like the button). */
    fun debugMarker(dist: Float): Boolean {
        val w = LvStore.state.value.walk ?: return false
        val l = w.layout ?: return false
        if (!w.placingMarker) LvStore.markerClicked()
        handleTap("floor", WalkRules.ahead(head, dist, l.floorY))
        return LvStore.state.value.walk?.marker != null
    }

    // =========================================================================================
    // debug
    // =========================================================================================

    /** `aim`: moves a target so that it sits [offsetDeg] off the current view axis, towards [dirDeg] (0 = right, 90 = up). */
    fun aim(target: String, offsetDeg: Float, dirDeg: Float): Boolean {
        val w = LvStore.state.value.walk ?: return false
        val l = w.layout ?: return false
        val h = judgeHead()
        val off = Math.toRadians(offsetDeg.toDouble()).toFloat()
        val dir = Math.toRadians(dirDeg.toDouble()).toFloat()
        val localDir = Vec3(sin(off) * cos(dir), sin(off) * sin(dir), -cos(off))
        fun spotAt(dist: Float) = h.position + h.rotation.rotate(localDir) * dist
        when (target) {
            "cup" -> {
                val c = itemCenter(l, 0)
                val want = spotAt((c - h.position).length)
                val delta = want - c
                LvStore.objectMoved(SceneObject.TABLE, l.table.copy(at = l.table.at + delta))
            }
            "sign" -> {
                val want = spotAt((l.sign.at - h.position).length)
                LvStore.objectMoved(SceneObject.SIGN, l.sign.copy(at = want, yaw = WalkRules.yawTowards(want, h.position)))
            }
            "door" -> {
                val d = l.door ?: return false
                val c = Vec3(d.at.x, d.at.y + WalkRules.DOOR_H / 2f, d.at.z)
                val want = spotAt((c - h.position).length)
                val at = Vec3(want.x, l.floorY, want.z)
                LvStore.objectMoved(SceneObject.DOOR, d.copy(at = at, yaw = WalkRules.yawTowards(at, h.position)))
            }
            else -> return false
        }
        return true
    }

    fun tapItem(item: Int) = handleTap("item:$item", null)

    fun tapDoor() = handleTap("door", null)

    /** Marks the door [ahead] metres in front of the viewer, as a tap on the floor there. */
    fun markAhead(ahead: Float) {
        val l = state.walk?.layout ?: return
        LvStore.doorMarked(WalkRules.markDoor(WalkRules.ahead(head, ahead, l.floorY), head, l.floorY, null))
    }

    fun lines(what: String): List<String> =
        when (what) {
            "pose" -> listOf("POSE hmd pos=${LvLog.pos(head.position)} fwd=${LvLog.pos(head.forward)} valid=$hasHead support=${headTracker.supportState()} frames=$frames override=${judgeOverride?.position?.let { LvLog.pos(it) }}")
            "panels" -> rig?.dump() ?: emptyList()
            else -> {
                val l = LvStore.state.value.walk?.layout
                listOf(
                    "SCENE veil=${veilModel?.enabled} key=$veilKey planes(${planes.summary()}) table=${l?.table} sign=${l?.sign} door=${l?.door} shoe=${l?.shoe} hidden=${l?.hidden}",
                ) + (0 until 3).map { i -> if (l == null) "ITEM $i none" else "ITEM $i center=${LvLog.pos(itemCenter(l, i))} visible=${VeilMath.isVisible(LvStore.state.value.walk!!.vision.params, judgeHead(), itemCenter(l, i))} ${anglesLine(itemCenter(l, i))}" }
            }
        }

    companion object {
        const val VEIL_PX = 1024
        const val VEIL_SORT_BIAS = -1.0f
        const val FOLLOW_DEG = 25f
        const val BOX_WARMUP_MS = 1_500L
    }
}

@Suppress("unused") private fun VeilParams.describe() = "$disease r=$rClear s=$rScotoma e=$edgeSoft h=$hemiSide v=$veil"
