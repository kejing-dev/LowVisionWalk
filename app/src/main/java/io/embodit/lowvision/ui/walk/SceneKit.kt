package io.embodit.lowvision.ui.walk

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import com.pico.spatial.core.ecs.BoundingBox
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.OpacityControllerComponent
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshModel
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.Resource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector2
import com.pico.spatial.core.math.Vector3
import io.embodit.lowvision.domain.Quat4
import io.embodit.lowvision.domain.Vec3
import io.embodit.lowvision.ui.theme.LvColors

// ---------------------------------------------------------------------------------------------
// domain <-> SDK types. The scene works in Stage metres: every entity hangs below the calibrated
// stage root, whose local space equals Stage space.
// ---------------------------------------------------------------------------------------------

internal fun Vec3.sdk(): Vector3 = Vector3(x, y, z)

internal fun Vector3.domain(): Vec3 = Vec3(x, y, z)

internal fun Quat4.sdk(): Quat = Quat(x, y, z, w)

internal fun Quat.domain(): Quat4 = Quat4(x, y, z, w)

/** Compose colours are gamma sRGB in 0..1, which is what `Color4` expects. */
internal fun Color.color4(): Color4 = Color4(red, green, blue, alpha)

internal val Entity.transformOrNull: TransformComponent?
    get() = components[TransformComponent::class.java]

internal fun Entity.setLocal(position: Vec3? = null, rotation: Quat4? = null, scale: Vec3? = null) {
    val t = transformOrNull ?: return
    if (position != null) t.setPosition(position.sdk())
    if (rotation != null) t.setQuaternion(rotation.sdk())
    if (scale != null) t.setScaleVector(scale.sdk())
}

internal fun Entity.setUniformScale(k: Float) {
    transformOrNull?.setScaleVector(Vector3(k, k, k))
}

/** Overall opacity of an entity and its children (fades only; no state depends on it). */
internal fun Entity.setOpacity(opacity: Float) {
    val existing = components[OpacityControllerComponent::class.java]
    if (existing != null) existing.opacity = opacity.coerceIn(0f, 1f) else components.set(OpacityControllerComponent(opacity.coerceIn(0f, 1f)))
}

internal fun Entity.named(name: String): Entity = also { runCatching { it.setName(name) } }

/** Metres per dp of an attachment panel at scale 1 (measured in the Spatial Ruler feasibility spike). */
internal const val DP_M = 0.0008f

/**
 * Resources shared by entities that come and go. The SDK releases a resource when its last user entity
 * is destroyed; shared ones are made global and closed with the scene (Spatial Ruler finding).
 */
internal class SharedResources {
    private val owned = ArrayList<Resource>()

    fun <T : Resource> keep(resource: T): T {
        resource.toGlobal()
        owned += resource
        return resource
    }

    fun close() {
        owned.forEach { runCatching { it.close() } }
        owned.clear()
    }
}

/**
 * Scene-lifetime materials and meshes. Domain colours are unlit with tone mapping off so that they keep
 * their exact value (Spatial Ruler: colours arrive dull with tone mapping on).
 */
internal class SceneKit(private val shared: SharedResources) {
    // v0.1.5 (q3-2): table, items, sign and shoe are Tripo models (WalkScene.propSlot) with their own materials
    val shoeHit: Material = unlit(LvColors.Danger)
    val door: Material = unlit(LvColors.Door, twoSided = true)
    val ghost: Material = sheer(LvColors.Door, 0.35f)

    /** Unit box (1 m cube), scaled per use: door dashes and the floor marker only. Created lazily ([warmUp]). */
    val box: MeshResource
        get() = MeshCache.box

    /** Unit quad in the XY plane facing +Z with v = 1 on the bitmap's top row (engine convention, Light Planner D10 fix). */
    val quad: MeshResource
        get() = MeshCache.quad

    /**
     * Mesh creation blocks the main thread until the render service answers: seconds on a loaded emulator, and an
     * input-dispatch ANR when one call passes 5 s while a focus event waits (v0.1.0 builds 5, 11, 17). The scene
     * therefore uses two meshes only (box, quad), each created in its own main-thread message ([warmUp]) and once per process ([MeshCache]); the props
     * (v0.1.5) are Tripo GLBs loaded one after the other by the scene.
     */
    fun warmUp(step: Int): Boolean = MeshCache.warmUp(step)


    fun unlit(color: Color, twoSided: Boolean = false): UnlitMaterial =
        shared.keep(
            UnlitMaterial.create(BlendingMode.OPAQUE).apply {
                setBaseColor(color.copy(alpha = 1f).color4())
                runCatching { setApplyToneMapping(false) }
                if (twoSided) setCullingMode(MaterialCullingMode.NONE)
            }
        )

    /** See-through unlit: the colour is opaque, the material's opacity carries the share. */
    fun sheer(color: Color, opacity: Float): UnlitMaterial =
        shared.keep(
            UnlitMaterial.create(BlendingMode.TRANSPARENT).apply {
                setBaseColor(color.copy(alpha = 1f).color4())
                setOpacity(opacity)
                runCatching { setApplyToneMapping(false) }
                setCullingMode(MaterialCullingMode.NONE)
                setDepthWrite(false)
            }
        )

    /** An opaque textured unlit material (the sign face). */
    fun texturedOpaque(bitmap: Bitmap): UnlitMaterial =
        shared.keep(
            UnlitMaterial.create(BlendingMode.OPAQUE).apply {
                setBaseColor(Color4(1f, 1f, 1f, 1f))
                setBaseColorTexture(shared.keep(TextureResource.create(bitmap)))
                runCatching { setApplyToneMapping(false) }
            }
        )

    /**
     * A bar of an abstract indicator (profile B12: geometry only for invisible colliders, floor marks and abstract
     * indicators): the door's dashed frame and the floor marker cross. Scene props are Tripo models, never this.
     */
    fun indicatorBar(material: Material, name: String): Entity = ModelEntity(box, material).named(name)

    fun quadEntity(material: Material, name: String): Entity = ModelEntity(quad, material).named(name)
}

/** Programmatic meshes (`MeshModel`). */
internal object MeshFactory {
    private val UP = Vector3(0f, 1f, 0f)
    private val FRONT = Vector3(0f, 0f, 1f)

    /**
     * Unit quad in the local XY plane, front +Z: corners bottom-left, bottom-right, top-right, top-left with
     * UVs (0,0) (1,0) (1,1) (0,1). The engine samples v = 1 at the bitmap's top row and u = 0 at its left
     * column (Light Planner / Sky Dome finding), so a bitmap drawn the normal way shows upright, unmirrored,
     * seen from +Z.
     */
    fun unitQuad(name: String): MeshResource {
        val positions = listOf(Vector3(-0.5f, -0.5f, 0f), Vector3(0.5f, -0.5f, 0f), Vector3(0.5f, 0.5f, 0f), Vector3(-0.5f, 0.5f, 0f))
        val uv = listOf(Vector2(0f, 0f), Vector2(1f, 0f), Vector2(1f, 1f), Vector2(0f, 1f))
        val model = MeshModel(positions = positions, triangleIndices = listOf(0, 1, 2, 0, 2, 3), normals = List(4) { FRONT }, uv0 = uv)
        return MeshResource.createWithMeshModel(model = model, bounds = BoundingBox(center = Vector3(0f, 0f, 0f), halfExtent = Vector3(0.5f, 0.5f, 0.001f)), name = name)
    }

    /**
     * A dashed rectangle outline in the XZ plane (y = 0), centred on the origin: [lengthX] x [widthZ],
     * stroke [stroke] wide, dashes [dash] long with [gap] between them. Facing up; draw it two-sided.
     */
    fun dashedRect(lengthX: Float, widthZ: Float, stroke: Float, dash: Float, gap: Float, name: String): MeshResource {
        val positions = ArrayList<Vector3>()
        val normals = ArrayList<Vector3>()
        val indices = ArrayList<Int>()
        fun quad(x0: Float, z0: Float, x1: Float, z1: Float) {
            val base = positions.size
            positions += Vector3(x0, 0f, z0)
            positions += Vector3(x1, 0f, z0)
            positions += Vector3(x1, 0f, z1)
            positions += Vector3(x0, 0f, z1)
            repeat(4) { normals += UP }
            indices += listOf(base, base + 2, base + 1, base, base + 3, base + 2)
        }
        val hx = lengthX / 2f
        val hz = widthZ / 2f
        val h = stroke / 2f
        fun dashesX(z: Float) {
            var x = -hx
            while (x < hx) {
                quad(x, z - h, minOf(x + dash, hx), z + h)
                x += dash + gap
            }
        }
        fun dashesZ(x: Float) {
            var z = -hz
            while (z < hz) {
                quad(x - h, z, x + h, minOf(z + dash, hz))
                z += dash + gap
            }
        }
        dashesX(-hz)
        dashesX(hz)
        dashesZ(-hx)
        dashesZ(hx)
        val model = MeshModel(positions = positions, triangleIndices = indices, normals = normals)
        val bounds = BoundingBox(center = Vector3(0f, 0f, 0f), halfExtent = Vector3(hx + h, 0.001f, hz + h))
        return MeshResource.createWithMeshModel(model = model, bounds = bounds, name = name)
    }
}

/**
 * The two meshes, made once per process and kept global (never closed): a walk after the first one creates no
 * mesh at all, so the Stage opens without a main-thread wait on the render service.
 */
internal object MeshCache {
    val box: MeshResource by lazy { timed("box") { MeshResource.createBox(Vector3(1f, 1f, 1f)).also { it.toGlobal() } } } // gate:allow geometry 地面标记十字 / 门框虚线
    val quad: MeshResource by lazy { timed("quad") { MeshFactory.unitQuad("quad").also { it.toGlobal() } } }

    private fun <T> timed(what: String, f: () -> T): T {
        val t0 = android.os.SystemClock.elapsedRealtime()
        val r = f()
        io.embodit.lowvision.platform.LvLog.i("MESH $what ${android.os.SystemClock.elapsedRealtime() - t0} ms")
        return r
    }

    fun warmUp(step: Int): Boolean =
        when (step) {
            0 -> { quad; true }
            1 -> { box; true }
            else -> false
        }
}
