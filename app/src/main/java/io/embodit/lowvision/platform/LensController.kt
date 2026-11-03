package io.embodit.lowvision.platform

import android.content.Context
import android.os.SharedMemory
import android.os.SystemClock
import com.pico.spatial.ml.securemr.GlobalTensor
import com.pico.spatial.ml.securemr.Pipeline
import com.pico.spatial.ml.securemr.SceneGraphProperty
import com.pico.spatial.ml.securemr.SpatialMLInstance
import com.pico.spatial.ml.securemr.SpatialMLSession
import com.pico.spatial.ml.securemr.Tensor
import io.embodit.lowvision.app.LensSource
import io.embodit.lowvision.app.LvStore
import io.embodit.lowvision.domain.LensMasks
import io.embodit.lowvision.domain.Vision
import java.nio.ByteOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The lens window (design P9 / E6): a SpatialML session in secure mode whose container (Shared Space only: SpatialML
 * containers are hidden in the Full Space) shows a 0.59 x 0.4425 m panel; the panel's base colour texture is a dynamic
 * texture the pipeline writes. Nothing is read back to the app, so no camera permission is needed.
 *
 * Graph (operator-only filter, host prototype tools/spatialml/lens_filter.py):
 *   src RGB u8 [480,640]  (rectifiedVSTAccess right eye, or a sample photo uploaded by the app)
 *   blur = 3 x half-size applyAffine + 3 x double-size applyAffine
 *   grey = convertColor RGB->GRAY->RGB of blur
 *   copy src / blur / grey into float [480, 1920] (copy keeps the element order, single-channel 2-D for arithmetic)
 *   out = keep * (src + blurMix * (blur - src)) + greyW * grey + add      (masks: LensMasks, uploaded per vision)
 *   copy out -> RGB u8, convertColor RGB->RGBA into the dynamic texture.
 *
 * Camera: the frame pipeline is submitted every [FRAME_MS] (15 Hz), each run waiting for the last. Sample photos
 * (v0.1.3, QA #2 q2-1): submitted every [PHOTO_MS] (4 Hz) as long as a photo is the source, also chained. Up to v0.1.2
 * the photo was submitted once, without waiting for the init pipeline, and the panel stayed black for good: that run
 * wrote the dynamic texture before the init pipeline had bound it to the panel, and a write from before the binding is
 * never shown (emulator 2026-11-02: unchained single run black after 25 s; a second submit, or one run chained after
 * the init task, shows at once). Now every run waits for the one before, the first for the init task ([initTask]), and
 * the repeat keeps the picture up whatever the order (fed back to PICO, factory `feedback/pico-cli-feedback.md`).
 * All SpatialML calls run on the main thread scope, except the
 * session's destroy (v0.1.2): on the emulator it never returned once a camera frame had been asked for (OpenMR
 * `endSession` binder call), which froze the app on 「关闭滤镜窗」 (ANR). It now runs on its own thread and the app does
 * not wait for it. Without a camera ([hasCamera] false, the emulator) the camera source draws nothing new: the window
 * keeps the last photo and the home window says there is no camera picture.
 */
class LensController(private val appContext: Context, private val hasCamera: Boolean) : LvStore.LensHost {
    private val scope: CoroutineScope = MainScope()
    private var job: Job? = null
    private var loop: Job? = null

    private var instance: SpatialMLInstance? = null
    private var session: SpatialMLSession? = null
    private var scene: GlobalTensor? = null
    private var tex: GlobalTensor? = null
    private var down: GlobalTensor? = null
    private var up: GlobalTensor? = null
    private var photo: GlobalTensor? = null
    private var masks: Array<GlobalTensor>? = null
    private var photoName: String? = null
    private var maskKey: String? = null
    private var source = LensSource.KITCHEN
    private var lastTask: Pipeline.RunTask? = null
    private var frames = 0L
    private var framesAtMs = 0L
    private var initTask: Pipeline.RunTask? = null
    private var photoLoop = false

    override fun open(source: LensSource, vision: Vision, compare: Boolean) {
        close()
        job =
            scope.launch {
                val started = SystemClock.elapsedRealtime()
                val s = runCatching { createSession() }.getOrElse {
                    // closed while opening: not a failure (no S23 card)
                    if (it is CancellationException) return@launch
                    LvLog.e("LENS session failed: $it", it)
                    null
                }
                if (s == null) {
                    LvStore.lensFailed(if (instance?.ready == true) "createSession returned null" else "SpatialML not ready after 8 s")
                    return@launch
                }
                session = s
                val prepared = runCatching { prepare(s) }
                if (prepared.exceptionOrNull() is CancellationException) return@launch
                prepared.onFailure { LvLog.e("LENS prepare failed: $it", it) }
                if (prepared.isFailure) {
                    LvStore.lensFailed("SpatialML: scene or tensors could not be created")
                    close()
                    return@launch
                }
                LvLog.i("LENS ready in ${SystemClock.elapsedRealtime() - started} ms")
                apply(source, vision, compare)
            }
    }

    override fun update(source: LensSource, vision: Vision, compare: Boolean) {
        if (session == null) return
        scope.launch { apply(source, vision, compare) }
    }

    override fun close() {
        loop?.cancel()
        loop = null
        job?.cancel()
        job = null
        lastTask = null
        initTask = null
        val s = session
        session = null
        scene = null
        tex = null
        down = null
        up = null
        photo = null
        masks = null
        photoName = null
        maskKey = null
        if (s != null) {
            LvLog.i("LENS closed")
            Thread({
                val t0 = SystemClock.elapsedRealtime()
                runCatching { s.destructor.destroy() }.onFailure { LvLog.w("LENS session destroy: $it") }
                LvLog.i("LENS session destroyed in ${SystemClock.elapsedRealtime() - t0} ms")
            }, "lens-destroy").apply { isDaemon = true }.start()
        }
    }

    // ---------------------------------------------------------------------------------------------

    private suspend fun createSession(): SpatialMLSession? {
        val inst = instance ?: SpatialMLInstance.create(appContext).also { instance = it }
        val ready = withTimeoutOrNull(READY_TIMEOUT_MS) {
            while (!inst.ready) delay(100)
            true
        }
        if (ready != true) return null
        // design N5, measured v0.1.3: a container unit is 0.4 mm (1200 x 900 came out 0.48 x 0.36 m in the system's window
        // list), so 1500 x 1125 = 0.6 x 0.45 m holds the 0.59 x 0.4425 m panel glb (the 1.2 x 0.9 m one was clipped to its middle)
        return inst.createSession(SpatialMLSession.InitInfo(LensMasks.W, LensMasks.H, CONTAINER_W, CONTAINER_H, CONTAINER_D, SpatialMLSession.ContainerType.VOLUMETRIC))
    }

    private suspend fun prepare(s: SpatialMLSession) {
        val sc = s.newSceneFromGLTFSuspend("lens/lens_panel.glb")
        scene = sc
        val t = s.newGlobalTensor(Tensor.MultiDimensionalInitInfo(Tensor.DataType.Image.R8G8B8A8_U_DYNAMIC, intArrayOf(LensMasks.H, LensMasks.W)))
        tex = t
        down = s.newGlobalTensor(affineInfo()).also { it.tensorResource = floats("lv_down", affine(0.5f)) }
        up = s.newGlobalTensor(affineInfo()).also { it.tensorResource = floats("lv_up", affine(2f)) }
        photo = s.newGlobalTensor(Tensor.MultiDimensionalInitInfo(Tensor.DataType.UINT8, intArrayOf(LensMasks.H, LensMasks.W), 3, false))
        masks = Array(4) { s.newGlobalTensor(maskInfo()) }
        // init pipeline (once): the panel shows the dynamic texture
        s.newPipeline().run {
            val scenePh = newPlaceholderLike(sc)
            val texPh = newPlaceholderLike(t)
            updateSceneGraphProperty(scenePh, "/", SceneGraphProperty.PBRMaterials[0].BaseColorTexture, texPh)
            switchSceneVisibility(scenePh, newLocalTensor(1))
            initTask = submit(mapOf(scenePh to sc, texPh to t), null, null)
        }
        lastTask = initTask
    }

    private suspend fun apply(source: LensSource, vision: Vision, compare: Boolean) {
        val s = session ?: return
        val key = if (compare) "identity" else vision.key
        if (key != maskKey) {
            val started = SystemClock.elapsedRealtime()
            val m = withContext(Dispatchers.Default) { LensMasks.of(if (compare) null else vision) }
            val g = masks ?: return
            g[0].tensorResource = floats("lv_blur", m.blurMix)
            g[1].tensorResource = floats("lv_keep", m.keep)
            g[2].tensorResource = floats("lv_grey", m.grey)
            g[3].tensorResource = floats("lv_add", m.add)
            maskKey = key
            LvLog.i("LENS masks $key in ${SystemClock.elapsedRealtime() - started} ms")
        }
        if (source != LensSource.CAMERA && source.id != photoName) {
            val bytes = withContext(Dispatchers.IO) { appContext.assets.open("lens/${source.id}.rgb").use { it.readBytes() } }
            photo?.tensorResource = bytesMem("lv_photo", bytes)
            photoName = source.id
        }
        this.source = source
        if (source == LensSource.CAMERA && !hasCamera) {
            loop?.cancel()
            loop = null
            LvLog.i("LENS camera source without a camera: keeping the last picture")
        } else if (source == LensSource.CAMERA) startCamera(s) else startPhoto(s)
    }

    /** Sample photo: the same graph again every [PHOTO_MS] while a photo is the source (see the class comment). */
    private fun startPhoto(s: SpatialMLSession) {
        if (!repeatPhotos) {
            loop?.cancel()
            loop = null
            runOnce(s, camera = false)
            return
        }
        // masks and photo are global tensors rewritten in place: a running photo loop picks them up by itself
        if (loop?.isActive == true && photoLoop) return
        loop?.cancel()
        photoLoop = true
        loop =
            scope.launch {
                val pipeline = runCatching { framePipeline(s, camera = false) }.getOrElse {
                    LvLog.e("LENS photo pipeline: $it", it)
                    return@launch
                }
                var n = 0
                var errors = 0
                val t0 = SystemClock.elapsedRealtime()
                while (isActive && session === s && source != LensSource.CAMERA) {
                    val r = runCatching { pipeline.submit(lastTask) }
                    if (r.isSuccess) {
                        lastTask = r.getOrNull()
                        errors = 0
                        n++
                        if (n == 2) LvLog.i("LENS picture up $photoName: 2 runs in ${SystemClock.elapsedRealtime() - t0} ms")
                        if (n % 40 == 0) LvLog.i("LENS photo $photoName runs=$n")
                    } else if (++errors >= 5) {
                        LvLog.e("LENS photo stopped: ${r.exceptionOrNull()}")
                        break
                    }
                    delay(PHOTO_MS)
                }
            }
        LvLog.i("LENS run $photoName (every $PHOTO_MS ms)")
    }

    private fun startCamera(s: SpatialMLSession) {
        if (loop?.isActive == true && !photoLoop) return
        loop?.cancel()
        photoLoop = false
        frames = 0
        framesAtMs = SystemClock.elapsedRealtime()
        loop =
            scope.launch {
                val pipeline = runCatching { framePipeline(s, camera = true) }.getOrElse {
                    LvLog.e("LENS camera pipeline: $it", it)
                    return@launch
                }
                var errors = 0
                var total = 0
                while (isActive && session === s && source == LensSource.CAMERA) {
                    val t0 = SystemClock.elapsedRealtime()
                    val r = runCatching { pipeline.submit(lastTask) }
                    if (r.isSuccess) {
                        lastTask = r.getOrNull()
                        errors = 0
                        frames++
                        if (++total == 2) LvLog.i("LENS picture up camera: 2 runs")
                    } else if (++errors >= 5) {
                        LvLog.e("LENS camera stopped: ${r.exceptionOrNull()}")
                        break
                    }
                    val now = SystemClock.elapsedRealtime()
                    if (now - framesAtMs > 5_000) {
                        LvLog.i("LENS camera ${"%.1f".format(frames * 1000f / (now - framesAtMs))} Hz")
                        frames = 0
                        framesAtMs = now
                    }
                    delay((FRAME_MS - (now - t0)).coerceAtLeast(5))
                }
            }
    }

    private fun runOnce(s: SpatialMLSession, camera: Boolean) {
        runCatching {
            lastTask = framePipeline(s, camera).submit(lastTask)
            LvLog.i("LENS run ${if (camera) "camera" else photoName}")
        }.onFailure { LvLog.e("LENS run failed: $it", it) }
    }

    /** The filter graph with its placeholder bindings; [Bound.submit] runs it once. */
    private class Bound(val pipeline: Pipeline, val map: Map<com.pico.spatial.ml.securemr.PipelineTensorPlaceholder, GlobalTensor>) {
        fun submit(after: Pipeline.RunTask?): Pipeline.RunTask = pipeline.submit(map, null, after)
    }

    private fun framePipeline(s: SpatialMLSession, camera: Boolean): Bound {
        val t = tex!!
        val dn = down!!
        val upT = up!!
        val g = masks!!
        val p = s.newPipeline()
        val map = HashMap<com.pico.spatial.ml.securemr.PipelineTensorPlaceholder, GlobalTensor>()
        fun ph(gt: GlobalTensor) = p.newPlaceholderLike(gt).also { map[it] = gt }
        p.run {
            val src =
                if (camera) {
                    newLocalTensor(rgb(LensMasks.H, LensMasks.W)).also { rectifiedVSTAccess(it, null, null, null) }
                } else ph(photo!!)
            val dPh = ph(dn)
            val uPh = ph(upT)
            val s1 = newLocalTensor(rgb(240, 320)).also { applyAffine(dPh, src, it) }
            val s2 = newLocalTensor(rgb(120, 160)).also { applyAffine(dPh, s1, it) }
            val s3 = newLocalTensor(rgb(60, 80)).also { applyAffine(dPh, s2, it) }
            val u2 = newLocalTensor(rgb(120, 160)).also { applyAffine(uPh, s3, it) }
            val u1 = newLocalTensor(rgb(240, 320)).also { applyAffine(uPh, u2, it) }
            val blur = newLocalTensor(rgb(LensMasks.H, LensMasks.W)).also { applyAffine(uPh, u1, it) }
            val grey1 = newLocalTensor(Tensor.MultiDimensionalInitInfo(Tensor.DataType.UINT8, intArrayOf(LensMasks.H, LensMasks.W), 1, false))
            convertColor(Pipeline.ColorConversion.RGB_TO_GRAY, blur, grey1)
            val grey3 = newLocalTensor(rgb(LensMasks.H, LensMasks.W)).also { convertColor(Pipeline.ColorConversion.GRAY_TO_RGB, grey1, it) }
            fun flat() = newLocalTensor(maskInfo())
            val f = flat().also { copy(src, it) }
            val b = flat().also { copy(blur, it) }
            val gr = flat().also { copy(grey3, it) }
            val bm = ph(g[0])
            val keep = ph(g[1])
            val greyW = ph(g[2])
            val add = ph(g[3])
            val d = flat().also { arithmetic("{0} - {1}", arrayOf(b, f), it) }
            val tmix = flat().also { elementwiseMultiply(d, bm, it) }
            val base = flat().also { arithmetic("{0} + {1}", arrayOf(f, tmix), it) }
            val p1 = flat().also { elementwiseMultiply(base, keep, it) }
            val p2 = flat().also { elementwiseMultiply(gr, greyW, it) }
            val out = flat().also { arithmetic("{0} + {1} + {2}", arrayOf(p1, p2, add), it) }
            val outU8 = newLocalTensor(rgb(LensMasks.H, LensMasks.W)).also { copy(out, it) }
            convertColor(Pipeline.ColorConversion.RGB_TO_RGBA, outU8, ph(t))
        }
        return Bound(p, map)
    }

    // ---------------------------------------------------------------------------------------------

    companion object {
        private const val READY_TIMEOUT_MS = 8_000L
        private const val FRAME_MS = 66L
        private const val PHOTO_MS = 250L
        private const val CONTAINER_W = 1500
        private const val CONTAINER_H = 1125
        private const val CONTAINER_D = 100

        /** Debug only (`lensRepeat 0`): one run per photo (chained after the init task), to compare with v0.1.2. */
        @Volatile var repeatPhotos = true

        private fun rgb(h: Int, w: Int) = Tensor.MultiDimensionalInitInfo(Tensor.DataType.UINT8, intArrayOf(h, w), 3, false)

        private fun maskInfo() = Tensor.MultiDimensionalInitInfo(Tensor.DataType.FLOAT32, intArrayOf(LensMasks.H, LensMasks.W * 3), 1, false)

        private fun affineInfo() = Tensor.MultiDimensionalInitInfo(Tensor.DataType.FLOAT32, intArrayOf(2, 3), 1, false)

        /** dst = scale * src with pixel centres aligned (OpenCV warpAffine maps src -> dst). */
        private fun affine(scale: Float): FloatArray {
            val off = 0.5f * scale - 0.5f
            return floatArrayOf(scale, 0f, off, 0f, scale, off)
        }

        private fun floats(name: String, values: FloatArray): SharedMemory {
            val mem = SharedMemory.create(name, values.size * Float.SIZE_BYTES)
            val buf = mem.mapReadWrite()
            buf.order(ByteOrder.nativeOrder()).asFloatBuffer().put(values)
            SharedMemory.unmap(buf)
            return mem
        }

        private fun bytesMem(name: String, values: ByteArray): SharedMemory {
            val mem = SharedMemory.create(name, values.size)
            val buf = mem.mapReadWrite()
            buf.put(values)
            SharedMemory.unmap(buf)
            return mem
        }
    }
}
