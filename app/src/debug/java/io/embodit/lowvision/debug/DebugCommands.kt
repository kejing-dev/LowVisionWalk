package io.embodit.lowvision.debug

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.embodit.lowvision.app.HintKind
import io.embodit.lowvision.app.LensSource
import io.embodit.lowvision.app.LvStore
import io.embodit.lowvision.app.WalkPhase
import io.embodit.lowvision.domain.Disease
import io.embodit.lowvision.domain.HeadPose
import io.embodit.lowvision.domain.Lang
import io.embodit.lowvision.domain.Quat4
import io.embodit.lowvision.domain.Side
import io.embodit.lowvision.domain.Task
import io.embodit.lowvision.domain.Vec3
import io.embodit.lowvision.domain.Vision
import io.embodit.lowvision.platform.ControllerInput
import io.embodit.lowvision.platform.LvLog
import io.embodit.lowvision.ui.walk.ActiveScene

/**
 * Debug commands (design qaHooks). Every command logs `CMD ok ...` or `CMD fail ...`; a `state` jump logs
 * `CMD reached <id>` when it gets there (it opens the Stage, confirms the safety card, waits for the scan
 * and marks the door 2.5 m ahead as needed). See AGENTS.md for the list.
 */
object DebugCommands {
    private val main = Handler(Looper.getMainLooper())
    private var job: Runnable? = null

    fun run(cmd: String, a: Map<String, String>) {
        val ok = runCatching { exec(cmd, a) }.onFailure { LvLog.e("CMD fail $cmd: $it", it) }.getOrDefault(false)
        LvLog.i("CMD ${if (ok) "ok" else "fail"} $cmd ${a.values.joinToString(" ")}")
    }

    private fun s() = LvStore.state.value

    private fun scene() = ActiveScene.scene

    private fun vision(a: Map<String, String>, base: Vision?): Vision? {
        val d = Disease.of(a["disease"]) ?: return base
        val level = a["level"]?.toFloatOrNull()?.toInt() ?: base?.takeIf { it.disease == d }?.level ?: Vision.DEFAULT_LEVEL
        return Vision(d, level.coerceIn(1, Vision.LEVELS), Side.of(a["side"] ?: base?.side?.name))
    }

    private fun exec(cmd: String, a: Map<String, String>): Boolean {
        val arg = a["s"]
        when (cmd) {
            "dump" -> dump()
            "pose" -> scene()?.lines("pose")?.forEach { LvLog.i(it) }
            "panels" -> scene()?.lines("panels")?.forEach { LvLog.i(it) }
            "state" -> return jump(a["id"] ?: arg ?: return false, a)
            "head" -> {
                val sc = scene() ?: return false
                if (arg == "off") sc.judgeOverride = null
                else {
                    val x = a["x"]?.toFloatOrNull() ?: return false
                    val z = a["z"]?.toFloatOrNull() ?: return false
                    val yaw = Math.toRadians((a["yaw"]?.toFloatOrNull() ?: 0f).toDouble()).toFloat()
                    val y = sc.head()?.position?.y ?: 1.6f
                    sc.judgeOverride = HeadPose(Vec3(x, y, z), Quat4.axisAngle(Vec3.UP, yaw))
                }
            }
            "veil" -> {
                val v = vision(a, s().walk?.vision ?: s().selected) ?: return false
                if (s().walk != null) LvStore.visionChanged(v)
                else {
                    LvStore.diseaseClicked(v.disease)
                    LvStore.levelChanged(v.level)
                    LvStore.sideChanged(v.side)
                }
            }
            "seed" -> LvStore.seed((a["n"] ?: arg)?.toFloatOrNull()?.toInt() ?: return false)
            "aim" -> return scene()?.aim(a["target"] ?: arg ?: return false, a["offsetDeg"]?.toFloatOrNull() ?: 0f, a["dirDeg"]?.toFloatOrNull() ?: 0f) ?: false
            "tap" -> {
                val sc = scene() ?: return false
                if (arg == "door") sc.tapDoor() else sc.tapItem(arg?.toIntOrNull() ?: return false)
            }
            "choose" -> {
                val w = s().walk ?: return false
                LvStore.signChosen(if (arg == "wrong") w.signChoices.first { it != w.signWord } else w.signWord)
            }
            "lensSource" -> LvStore.lensSource(LensSource.of(arg ?: a["photo"]) ?: return false)
            // D26: the same store events as the settings' buttons; no arg = 「退出应用」, confirm / cancel = the two answers
            "quit" ->
                when (arg) {
                    "confirm" -> LvStore.quitConfirmed()
                    "cancel" -> LvStore.quitCancelled()
                    else -> {
                        if (!s().settingsOpen) LvStore.settingsOpen(true)
                        LvStore.quitClicked()
                    }
                }
            "recall" -> scene()?.recallPanels() ?: return false
            // D21: a held grip bar under a card, hand travel in Stage metres (same begin / step / end as the real drag)
            "grip" -> return scene()?.debugGripDrag(arg ?: return false, Vec3(a["dx"]?.toFloatOrNull() ?: 0f, a["dy"]?.toFloatOrNull() ?: 0f, a["dz"]?.toFloatOrNull() ?: 0f)) ?: false
            "lost" -> {
                scene()?.debugLost = arg == "1"
                LvStore.trackingChanged(arg == "1")
            }
            "stick" -> {
                val sc = scene() ?: return false
                val parts = (arg ?: return false).split(',')
                sc.debugStick = parts[0].toFloat()
                sc.debugStickUntil = SystemClock.elapsedRealtime() + (parts.getOrNull(1)?.toLongOrNull() ?: 1500L)
            }
            "ghost" -> {
                val sc = scene() ?: return false
                val head = sc.head() ?: return false
                sc.debugGhostRay =
                    if (arg == "off") null
                    else {
                        val ahead = arg?.toFloatOrNull() ?: 2.5f
                        val f = Vec3(head.forward.x, 0f, head.forward.z).normalized()
                        val origin = head.position + Vec3(0.15f, -0.4f, 0f)
                        val target = Vec3(head.position.x + f.x * ahead, head.position.y - 1.55f, head.position.z + f.z * ahead)
                        ControllerInput.Ray(origin, (target - origin).normalized())
                    }
            }
            "testtex" -> {
                scene()?.overrideTest = arg == "1"
                scene()?.render(s())
            }
            "lang" -> LvStore.langSelected(if (arg == "en") Lang.EN else Lang.ZH)
            "mute" -> LvStore.muteClicked()
            "takeoff" -> LvStore.takeOffClicked()
            "peek" -> LvStore.peek(arg == "1")
            "menu" -> LvStore.menuToggle()
            "home" -> LvStore.homeClicked()
            "next" -> LvStore.nextClicked()
            "checklist" -> LvStore.checklistClicked()
            "interrupt" -> LvStore.interrupted()
            "autooff" -> LvStore.autoOff("debug")
            "bring" -> LvStore.bringHere(io.embodit.lowvision.domain.SceneObject.of(arg) ?: return false)
            "hide" -> LvStore.toggleHidden(io.embodit.lowvision.domain.SceneObject.of(arg) ?: return false)
            else -> return false
        }
        return true
    }

    private fun dump() {
        val st = s()
        val w = st.walk
        LvLog.i("DUMP state=${st.stateId} lang=${st.lang} muted=${st.muted} selected=${st.selected} inStage=${st.inStage} lens=${st.lens} phase=${w?.phase} veilOn=${w?.veilOn} visible=${w?.veilVisible} run=${w?.run} results=${w?.results?.keys} hint=${w?.hint} menu=${w?.menuOpen} tasks=${w?.taskListOpen} word=${w?.signWord} choices=${w?.signChoices} selectedObj=${w?.selected}")
        scene()?.lines("scene")?.forEach { LvLog.i(it) }
    }

    // ---- state jumps: a list of steps, each polled until it reports done (300 ms period, 30 s cap)

    private fun jump(id: String, a: Map<String, String>): Boolean {
        job?.let { main.removeCallbacks(it) }
        val v = vision(a, s().walk?.vision ?: s().selected) ?: Vision(Disease.GLAUCOMA)
        val steps = ArrayList<() -> Boolean>()
        // a head override from an earlier jump (S15 / S16c) must not judge this one
        scene()?.judgeOverride = null
        fun once(f: () -> Unit) {
            steps += {
                f()
                true
            }
        }
        fun waitMs(ms: Long) {
            var until = 0L
            steps += {
                val now = SystemClock.elapsedRealtime()
                if (until == 0L) until = now + ms
                now >= until
            }
        }
        fun home() {
            steps += {
                if (s().inStage) LvStore.homeClicked()
                if (s().lens.open) LvStore.lensClosed("debug")
                if (s().lens.error != null) LvStore.lensErrorOk()
                if (s().settingsOpen) LvStore.settingsOpen(false)
                !s().inStage
            }
        }
        fun select(vv: Vision) =
            once {
                if (s().inStage) LvStore.visionChanged(vv)
                else {
                    LvStore.diseaseClicked(vv.disease)
                    LvStore.levelChanged(vv.level)
                    LvStore.sideChanged(vv.side)
                }
            }
        fun walkTo(target: WalkPhase) {
            steps += {
                val st = s()
                val w = st.walk
                if (!st.inStage) {
                    if (w == null) LvStore.walkClicked()
                    false
                } else
                    when {
                        w == null -> false
                        w.phase == target -> true
                        w.phase == WalkPhase.SAFETY -> {
                            LvStore.safetyOk()
                            false
                        }
                        w.phase == WalkPhase.MARK_DOOR && target != WalkPhase.MARK_DOOR -> {
                            if (w.hint?.kind != HintKind.VIRTUAL) scene()?.markAhead(2.5f)
                            false
                        }
                        target == WalkPhase.READY && w.phase in setOf(WalkPhase.TASK, WalkPhase.RESULT, WalkPhase.CHALLENGE, WalkPhase.CHECKLIST) -> {
                            LvStore.menuTaskList()
                            false
                        }
                        else -> false
                    }
            }
        }
        fun task(t: Task) {
            walkTo(WalkPhase.READY)
            once { if (s().walk?.veilOn == false) LvStore.takeOffClicked() }
            once { LvStore.startTask(t) }
            waitMs(400)
        }
        val up = id.uppercase()
        when (up) {
            "S01" -> {
                home()
                once { LvStore.langSelected(Lang.ZH) }
                once { LvStore.clearSelection() }
            }
            "S02" -> { home(); select(v.copy(disease = Disease.GLAUCOMA)) }
            "S03" -> { home(); select(v.copy(disease = Disease.AMD)) }
            "S04" -> { home(); select(v.copy(disease = Disease.HEMIANOPIA)) }
            "S05" -> { home(); select(v); once { LvStore.settingsOpen(true) } }
            "S05Q" -> { home(); select(v); once { LvStore.settingsOpen(true) }; once { LvStore.quitClicked() } }
            "S25" -> { home(); select(v); once { LvStore.langSelected(Lang.EN) } }
            "S20", "S21", "S22" -> {
                home()
                select(v)
                once { LvStore.lensClicked() }
                once { LvStore.lensSource(if (up == "S21") LensSource.CAMERA else LensSource.of(a["photo"]) ?: LensSource.KITCHEN) }
                if (up == "S22") once { LvStore.lensCompare(true) }
            }
            "S23" -> { home(); select(v); once { LvStore.lensFailed("debug: SpatialML ready timed out after 8 s") } }
            "S28" -> { home(); select(v); once { LvStore.lensClicked() }; waitMs(800); once { LvStore.lensClosed("system close (debug)") } }
            "S06" -> { home(); select(v); once { LvStore.showSafetyChanged(true) }; walkTo(WalkPhase.SAFETY) }
            "S07" -> { home(); select(v); walkTo(WalkPhase.SCANNING) }
            "S08", "S19" -> { home(); select(v); walkTo(WalkPhase.MARK_DOOR) }
            "S09" -> { select(v); walkTo(WalkPhase.READY) }
            "S10" -> { select(v); task(Task.CUP) }
            "S10W" -> { select(v); task(Task.CUP); once { scene()?.tapItem(1) } }
            "S10V" -> { select(v); task(Task.CUP); once { aim("cup", visible = false) }; waitMs(300); once { scene()?.tapItem(0) } }
            "S11" -> { select(v); task(Task.CUP); once { aim("cup", visible = true) }; waitMs(300); once { scene()?.tapItem(0) } }
            "S12" -> { select(v); task(Task.SIGN) }
            "S13" -> { select(v); task(Task.SIGN); once { exec("choose", mapOf("s" to "wrong")) } }
            "S12D" -> { select(v); task(Task.SIGN); once { exec("choose", mapOf("s" to "right")) } }
            "S14" -> { select(v); task(Task.DOOR) }
            "S16", "S14C", "S15", "S16C" -> {
                select(v)
                task(Task.DOOR)
                once { aim("door", visible = true) }
                waitMs(300)
                once { scene()?.tapDoor() }
                waitMs(300)
                if (up != "S16") {
                    once { LvStore.challengeClicked() }
                    waitMs(400)
                }
                if (up == "S15") once { headAt(s().walk?.layout?.shoe) }
                if (up == "S16C") once { headAt(s().walk?.layout?.door?.at) }
            }
            "S17" -> { select(v); walkTo(WalkPhase.READY); once { if (s().walk?.veilOn == true) LvStore.takeOffClicked() } }
            "S18" -> { select(v); walkTo(WalkPhase.READY); once { LvStore.checklistClicked() } }
            "S24" -> { select(v); walkTo(WalkPhase.READY); once { if (s().walk?.menuOpen != true) LvStore.menuToggle() } }
            "S26" -> { select(v); task(Task.CUP); once { LvStore.interrupted() } }
            "S27" -> { select(v); walkTo(WalkPhase.READY); once { exec("lost", mapOf("s" to "1")) } }
            else -> return false
        }
        val started = SystemClock.elapsedRealtime()
        var i = 0
        val r =
            object : Runnable {
                override fun run() {
                    if (SystemClock.elapsedRealtime() - started > 30_000) {
                        LvLog.i("CMD timeout state $id at step $i (${s().stateId})")
                        return
                    }
                    while (i < steps.size && runCatching { steps[i]() }.onFailure { LvLog.e("CMD step $i: $it", it) }.getOrDefault(false)) i++
                    if (i >= steps.size) LvLog.i("CMD reached $id (now ${s().stateId})") else main.postDelayed(this, 300)
                }
            }
        job = r
        main.post(r)
        return true
    }

    /** Puts the target inside (or outside) the clear part of the field of the current disease. */
    private fun aim(target: String, visible: Boolean) {
        val v = s().walk?.vision ?: return
        val p = v.params
        val (off, dir) =
            when (v.disease) {
                Disease.GLAUCOMA -> (if (visible) 0f else p.rClear + p.edgeSoft + 4f) to 0f
                Disease.AMD -> (if (visible) p.rScotoma + p.edgeSoft + 6f else p.rScotoma * 0.5f) to 0f
                Disease.HEMIANOPIA -> 15f to (if ((v.side == Side.LEFT) == visible) 0f else 180f)
            }
        scene()?.aim(target, off, dir)
    }

    private fun headAt(p: Vec3?) {
        p ?: return
        exec("head", mapOf("x" to p.x.toString(), "z" to (p.z + 0.05f).toString()))
    }
}
