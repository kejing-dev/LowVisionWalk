package io.embodit.lowvision.app

import android.content.Context
import android.os.Handler
import com.pico.spatial.ui.platform.containers.OpenStageResult
import com.pico.spatial.ui.platform.containers.StageStyle
import com.pico.spatial.ui.platform.containers.openStage
import io.embodit.lowvision.ui.walk.WALK_STAGE_ID
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import android.os.Looper
import android.os.SystemClock
import io.embodit.lowvision.domain.Disease
import io.embodit.lowvision.domain.HeadPose
import io.embodit.lowvision.domain.Lang
import io.embodit.lowvision.domain.LevelGate
import io.embodit.lowvision.domain.Placement
import io.embodit.lowvision.domain.SceneObject
import io.embodit.lowvision.domain.Side
import io.embodit.lowvision.domain.Task
import io.embodit.lowvision.domain.Vec3
import io.embodit.lowvision.domain.Vision
import io.embodit.lowvision.domain.WalkLayout
import io.embodit.lowvision.domain.WalkRules
import io.embodit.lowvision.platform.LvLog
import io.embodit.lowvision.platform.Prefs
import kotlin.random.Random
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The one state holder of the process: the home window, the Stage and the debug receiver all talk to
 * it on the main thread. Per-frame 3D work never goes through here; the scene reports discrete events
 * (scan done, picked, stepped on the shoe, reached the door, auto take-off, tracking lost).
 */
object LvStore {
    /** Opens / closes the walk Stage; lent by the home window (it owns the navigator). */
    interface StageHost {
        fun openStage()

        fun closeStage()
    }

    /** What the store needs from the 3D scene while the Stage is open. */
    interface SceneHost {
        fun head(): HeadPose?

        fun skipDoor(): Placement?
    }

    enum class Sound {
        FOUND,
        WRONG,
        STEPPED,
    }

    private val main = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(LvState())
    val state: StateFlow<LvState> = _state.asStateFlow()

    var stageHost: StageHost? = null

    /** Closes the Stage from inside it (the home window is closed while the walk is on). */
    var stageCloser: (() -> Unit)? = null

    /** The home window (id, tag) closed itself for the walk; the Stage opens the home window again when it ends. */
    @Volatile var hiddenHome: Pair<String, String?>? = null
    var sceneHost: SceneHost? = null
    var soundHost: ((Sound) -> Unit)? = null
    var lensHost: LensHost? = null

    /**
     * Opens / closes the lens spot window beside the home window (v0.1.3, QA #2 q2-2). The system puts the SpatialML
     * container in the middle of whichever window is focused when the session is created, 5 cm in front of it, and has no
     * placement parameter: in front of the home window it covered the controls, and a press on the home window raised the
     * window over it. So the session is created only once the spot window (to the right of the home window) is focused.
     */
    var spotHost: SpotHost? = null

    interface SpotHost {
        /** [gen] goes into the window's tag, so every open is a new window (a closing one is never reused). */
        fun openSpot(gen: Int)

        fun closeSpot()
    }

    private var lensWaitingForSpot = false

    /** Which spot window is the current one: a spot window closed just before the next open reports with an older number. */
    @Volatile var spotGen = 0
        private set

    /** Lens window side (SpatialML); null while the lens is not built in. */
    interface LensHost {
        fun open(source: LensSource, vision: Vision, compare: Boolean)

        fun update(source: LensSource, vision: Vision, compare: Boolean)

        fun close()
    }

    private var prefs: Prefs? = null
    private var appContext: Context? = null
    private val scope = MainScope()
    private var random = Random(SystemClock.elapsedRealtime())

    private val s: LvState
        get() = _state.value

    private fun set(next: LvState, why: String) {
        val before = _state.value.stateId
        _state.value = next
        val after = next.stateId
        if (before != after) LvLog.i("STATE $before -> $after ($why)")
    }

    private fun walk(why: String, f: (WalkState) -> WalkState) {
        val w = s.walk ?: return
        val n = f(w)
        // a new step of the walk: the home window follows the step again (shown, or aside while a task runs)
        set(s.copy(walk = if (n.phase != w.phase && n.window != WindowAsk.AUTO) n.copy(window = WindowAsk.AUTO) else n), why)
    }

    fun now() = SystemClock.elapsedRealtime()

    fun init(context: Context, prefs: Prefs, isEmulator: Boolean) {
        if (this.prefs != null) return
        this.prefs = prefs
        appContext = context.applicationContext
        set(
            s.copy(
                lang = prefs.lang(),
                muted = prefs.muted(),
                selected = prefs.lastVision(),
                showSafety = !prefs.safetySeen() || prefs.showSafetyNext(),
                best = prefs.bestTimes(),
                isEmulator = isEmulator,
            ),
            "init",
        )
    }

    fun seed(n: Int) {
        random = Random(n)
        LvLog.i("SEED $n")
    }

    // =========================================================================================
    // home window
    // =========================================================================================

    fun diseaseClicked(d: Disease) {
        val keep = s.selected
        val v = if (keep?.disease == d) keep else Vision(d, prefs?.lastLevel(d) ?: Vision.DEFAULT_LEVEL, prefs?.lastSide() ?: Side.LEFT)
        choose(v, "disease ${d.id}")
    }

    fun levelChanged(level: Int) {
        val v = s.selected ?: return
        if (v.level == level.coerceIn(1, Vision.LEVELS)) return
        choose(v.withLevel(level), "level $level")
    }

    fun sideChanged(side: Side) {
        val v = s.selected ?: return
        choose(v.copy(side = side), "side $side")
    }

    private fun choose(v: Vision, why: String) {
        prefs?.saveVision(v)
        set(s.copy(selected = v), why)
        lensHost?.takeIf { s.lens.open }?.update(s.lens.source, v, s.lens.comparing)
    }

    /** Nothing picked (S01); the stored pick stays for the next start. */
    fun clearSelection() = set(s.copy(selected = null), "clear selection")

    fun muteClicked() {
        val m = !s.muted
        prefs?.setMuted(m)
        set(s.copy(muted = m), "mute $m")
    }

    fun settingsOpen(open: Boolean) = set(s.copy(settingsOpen = open, quitAsked = false), "settings $open")

    // =========================================================================================
    // the home window (v0.1.4, profile D21 / D8): one system window, pages on the system TabBar, moved and closed with
    // the system window bar. During the walk it carries the walk's cards and steps aside while a task runs.
    // =========================================================================================

    /** A TabBar item (or the debug `page` command): settings, a home page, or the walk's card / menu page. */
    fun pageSelected(page: String) {
        val w = s.walk
        when {
            page == Pages.SETTINGS -> settingsOpen(true)
            s.inStage && w != null -> set(s.copy(settingsOpen = false, quitAsked = false, walk = w.copy(menuOpen = page == Pages.MENU)), "page $page")
            page == Pages.LOOK || page == Pages.LENS -> set(s.copy(settingsOpen = false, quitAsked = false, homePage = page), "page $page")
            else -> LvLog.w("PAGE unknown $page")
        }
    }

    /** Lifecycle of the home window as it reports it (ON_CREATE … ON_DESTROY, "never"); the Stage reopens it from this. */
    val homeLife = MutableStateFlow("never")

    /** The window closes itself once to come back in front ([recallWindow]); it sets this, the window acts on it. */
    val homeRecall = MutableStateFlow(0)

    /** The pill's 「叫回」 (D21 「叫回面前」): the home window comes back in front of the head (close, then open again). */
    fun recallWindow() {
        LvLog.i("WINDOW recall life=${homeLife.value}")
        walk("window recall") { it.copy(window = WindowAsk.RECALLED) }
        if (homeLife.value != "ON_DESTROY" && homeLife.value != "never") homeRecall.value++
    }

    // ---- v0.1.5 (QA #3 q3-1, profile D9): the home window opens again only while the head is level

    /** The Stage feeds it every frame ([stepWindowGate]); every reopen during the walk asks through [openWhenLevel]. */
    private val windowGate = LevelGate()
    private var windowOpen: (() -> Unit)? = null
    private var gateLastStepMs = 0L
    private var gateLastLogMs = 0L

    /** Runs [open] once the head has been level for a moment (or the Stage gives no head / no frames for a while). */
    fun openWhenLevel(why: String, open: () -> Unit) {
        val now = SystemClock.elapsedRealtime()
        windowGate.ask(why, now)
        windowOpen = open
        gateLastStepMs = 0L
        LvLog.i("LEVEL ask why=$why")
        main.removeCallbacks(gateWatchdog)
        main.postDelayed(gateWatchdog, GATE_WATCHDOG_MS)
    }

    /** Drops a waiting reopen (the Stage is going; its own dispose path takes over). */
    fun cancelWindowGate(why: String, onlyIf: String? = null) {
        if (!windowGate.waiting) return
        if (onlyIf != null && windowGate.pending?.startsWith(onlyIf) != true) return
        LvLog.i("LEVEL cancel pending=${windowGate.pending} ($why)")
        windowGate.cancel()
        windowOpen = null
        main.removeCallbacks(gateWatchdog)
        setLevelHint(0)
    }

    val windowGateWaiting: Boolean
        get() = windowGate.waiting

    /** Per Stage frame: [pitchDeg] of the head (+ up), null without a valid head pose. */
    fun stepWindowGate(now: Long, pitchDeg: Float?) {
        if (!windowGate.waiting) return
        gateLastStepMs = now
        setLevelHint(windowGate.hint(pitchDeg))
        if (now - gateLastLogMs > 1_000) {
            gateLastLogMs = now
            LvLog.i("LEVEL wait pitch=${pitchDeg?.let { "%.1f".format(it) }} hint=${s.levelHint} waited=${windowGate.waitedMs(now)}")
        }
        windowGate.step(now, pitchDeg)?.let { fireWindowOpen(it, pitchDeg) }
    }

    private fun fireWindowOpen(why: String, pitchDeg: Float?) {
        LvLog.i("LEVEL ok pitch=${pitchDeg?.let { "%.1f".format(it) }} -> open why=$why")
        main.removeCallbacks(gateWatchdog)
        setLevelHint(0)
        val open = windowOpen
        windowOpen = null
        open?.invoke()
    }

    private fun setLevelHint(hint: Int) {
        if (s.levelHint != hint) set(s.copy(levelHint = hint), "level hint $hint")
    }

    /** No Stage frames: the window is not held back for ever (15 s before the first frame, 3 s after the last one). */
    private val gateWatchdog =
        object : Runnable {
            override fun run() {
                if (!windowGate.waiting) return
                val now = SystemClock.elapsedRealtime()
                val stalled = if (gateLastStepMs == 0L) windowGate.waitedMs(now) > GATE_NO_FRAMES_FIRST_MS else now - gateLastStepMs > GATE_WATCHDOG_MS
                if (stalled) {
                    val why = windowGate.pending ?: return
                    windowGate.cancel()
                    fireWindowOpen("$why/noframes", null)
                } else main.postDelayed(this, GATE_WATCHDOG_MS)
            }
        }

    private const val GATE_WATCHDOG_MS = 3_000L
    private const val GATE_NO_FRAMES_FIRST_MS = 15_000L

    /** The user closed the home window with the system window bar's × during the walk: it stays closed for this step. */
    fun windowDismissed() {
        LvLog.i("WINDOW dismissed by the user")
        walk("window dismissed") { it.copy(window = WindowAsk.DISMISSED) }
    }

    // ---- 「退出应用」 (profile D26): ask once, then end every task of the app and the process

    fun quitClicked() = set(s.copy(quitAsked = true), "quit asked")

    fun quitCancelled() = set(s.copy(quitAsked = false), "quit cancelled")

    fun quitConfirmed() {
        LvLog.i("QUIT confirmed")
        lensHost?.close()
        stageCloser?.invoke()
        val ctx = appContext
        val tasks = ctx?.getSystemService(android.app.ActivityManager::class.java)?.appTasks.orEmpty()
        tasks.forEach { t -> runCatching { t.finishAndRemoveTask() }.onFailure { LvLog.w("QUIT finishAndRemoveTask: $it") } }
        LvLog.i("QUIT removed ${tasks.size} task(s), process ends")
        // a moment for the Stage and the windows to go; the process then ends, which frees audio, trackers and SpatialML
        main.postDelayed({
            android.os.Process.killProcess(android.os.Process.myPid())
            kotlin.system.exitProcess(0)
        }, QUIT_EXIT_DELAY_MS)
    }

    fun langSelected(lang: Lang) {
        prefs?.setLang(lang)
        val w = s.walk
        // the sign word list is per language: keep the index, the word follows the language
        set(s.copy(lang = lang, walk = w), "lang $lang")
    }

    fun showSafetyChanged(on: Boolean) {
        prefs?.setShowSafetyNext(on)
        set(s.copy(showSafety = on || !(prefs?.safetySeen() ?: false)), "safety next $on")
    }

    fun play(sound: Sound) {
        if (s.muted) return
        soundHost?.invoke(sound)
    }

    // =========================================================================================
    // walk: enter / leave (T4, T11)
    // =========================================================================================

    fun walkClicked() {
        val v = s.selected ?: return
        if (s.inStage) return
        if (s.lens.open) lensClosed("walk")
        set(s.copy(walk = WalkState(vision = v), settingsOpen = false), "walk clicked")
        val host = stageHost
        if (host != null) {
            host.openStage()
            return
        }
        // no home window holds a navigator right now (between close and reopen): open through the app context
        val ctx = appContext ?: return stageResult(false, "no host, no context")
        scope.launch {
            val result = runCatching { ctx.openStage(WALK_STAGE_ID, StageStyle.Mixed) }
            when (val r = result.getOrNull()) {
                is OpenStageResult.Allowed -> stageResult(true, "Allowed (app context)")
                is OpenStageResult.NotAllowed -> stageResult(false, "NotAllowed")
                is OpenStageResult.Error -> stageResult(false, "Error ${r.code} ${r.reason}")
                null -> stageResult(false, "threw ${result.exceptionOrNull()}")
            }
        }
    }

    fun stageResult(ok: Boolean, why: String) {
        LvLog.i("STAGE open ok=$ok $why")
        if (!ok) set(s.copy(walk = null, inStage = false), "stage refused")
    }

    /** The Stage composable is up: safety card first (S06) or straight to scanning (S07). */
    fun stageEntered() {
        val w = s.walk ?: WalkState(vision = s.selected ?: Vision(Disease.GLAUCOMA))
        val phase = if (s.showSafety) WalkPhase.SAFETY else WalkPhase.SCANNING
        set(s.copy(inStage = true, walk = w.copy(phase = phase, hint = if (phase == WalkPhase.SCANNING) Hint(HintKind.SCANNING) else null)), "stage entered")
    }

    fun stageClosed() {
        if (!s.inStage && s.walk == null) return
        main.removeCallbacksAndMessages(null)
        set(s.copy(inStage = false, walk = null), "stage closed")
        if (lensAfterStage) {
            // the SpatialML container is hidden in the Full Space: the lens opens once the home window is back
            lensAfterStage = false
            main.postDelayed({ lensClicked() }, LENS_AFTER_STAGE_MS)
        }
    }

    private var lensAfterStage = false
    private const val LENS_AFTER_STAGE_MS = 2_000L
    private const val QUIT_EXIT_DELAY_MS = 400L

    /** 「透过滤镜看」 from the walk's menu: leave the Full Space first, then open the lens in the Shared Space. */
    fun lensFromWalk() {
        LvLog.i("WALK -> lens")
        lensAfterStage = true
        homeClicked()
    }

    fun homeClicked() {
        LvLog.i("WALK home")
        val closer = stageCloser
        if (closer != null) closer() else {
            stageHost?.closeStage()
            stageClosed()
        }
    }

    fun safetyOk() {
        prefs?.setSafetySeen()
        prefs?.setShowSafetyNext(false)
        set(s.copy(showSafety = false), "safety seen")
        walk("safety ok") { it.copy(phase = WalkPhase.SCANNING, hint = Hint(HintKind.SCANNING)) }
    }

    /** Scanning is over (planes found or 5 s passed): the scene placed table and sign (S19 when virtual). */
    fun scanDone(layout: WalkLayout) {
        val w = s.walk ?: return
        if (w.phase != WalkPhase.SCANNING) return
        val word = random.nextInt(12)
        val virtualTable = layout.table.virtual
        val virtualSign = layout.sign.virtual
        LvLog.i("WALK scan done floor=${"%.2f".format(layout.floorY)} table=${LvLog.pos(layout.table.at)}${if (virtualTable) "(virtual)" else ""} sign=${LvLog.pos(layout.sign.at)}${if (virtualSign) "(virtual)" else ""} cupSlot=${layout.cupSlot} word=$word")
        val hint = if (virtualTable || virtualSign) Hint(HintKind.VIRTUAL, now() + 6_000) else Hint(HintKind.MARK_DOOR)
        walk("scan done") { it.copy(phase = WalkPhase.MARK_DOOR, layout = layout, virtualTable = virtualTable, virtualSign = virtualSign, signWord = word, signChoices = choicesFor(word), hint = hint) }
        if (hint.untilMs != null) expireHint(hint, then = Hint(HintKind.MARK_DOOR))
    }

    private fun choicesFor(word: Int): List<Int> {
        val others = (0 until 12).filter { it != word }.shuffled(random).take(2)
        return (others + word).shuffled(random)
    }

    fun doorMarked(p: Placement?) {
        val w = s.walk ?: return
        if (w.phase != WalkPhase.MARK_DOOR) return
        if (p == null) {
            showHint(Hint(HintKind.DOOR_FAR, now() + 2_500), restore = Hint(HintKind.MARK_DOOR))
            return
        }
        LvLog.i("WALK door at=${LvLog.pos(p.at)} virtual=${p.virtual}")
        walk("door marked") { it.copy(phase = WalkPhase.READY, layout = it.layout?.copy(door = p), veilOn = true, taskListOpen = true, hint = null) }
    }

    fun skipClicked() {
        val p = sceneHost?.skipDoor()
        if (p == null) {
            showHint(Hint(HintKind.TOO_NEAR, now() + 2_500), restore = Hint(HintKind.MARK_DOOR))
            return
        }
        doorMarked(p)
    }

    // =========================================================================================
    // tasks (S09 – S16c)
    // =========================================================================================

    fun startTask(task: Task) {
        val w = s.walk ?: return
        if (w.phase !in setOf(WalkPhase.READY, WalkPhase.TASK, WalkPhase.RESULT)) return
        LvLog.i("TASK start ${task.id}")
        walk("start ${task.id}") { it.copy(phase = WalkPhase.TASK, run = TaskRun(task, now()), taskListOpen = false, menuOpen = false, hint = null, wrongChoice = null, shown = null, veilOn = true) }
    }

    /** Picked the mug (0), salt (1) or glasses case (2): [visible] is the scene's visibility check. */
    fun itemPicked(item: Int, visible: Boolean) {
        val w = s.walk ?: return
        val run = w.run ?: return
        if (w.phase != WalkPhase.TASK || run.task != Task.CUP) return
        when {
            item != 0 -> miss(if (item == 1) HintKind.WRONG_SALT else HintKind.WRONG_CASE)
            !visible && w.veilOn -> miss(HintKind.NOT_VISIBLE)
            else -> finish(run)
        }
    }

    fun doorPicked(visible: Boolean) {
        val w = s.walk ?: return
        val run = w.run ?: return
        if (w.phase != WalkPhase.TASK || run.task != Task.DOOR) return
        if (!visible && w.veilOn) miss(HintKind.NOT_VISIBLE) else finish(run)
    }

    fun signChosen(choice: Int) {
        val w = s.walk ?: return
        val run = w.run ?: return
        if (w.phase != WalkPhase.TASK || run.task != Task.SIGN) return
        if (choice == w.signWord) finish(run)
        else {
            walk("sign wrong") { it.copy(wrongChoice = choice) }
            miss(HintKind.SIGN_WRONG)
            main.postDelayed({ walk("sign flash off") { it.copy(wrongChoice = null) } }, 1_500)
        }
    }

    private fun miss(kind: HintKind) {
        play(Sound.WRONG)
        walk("miss $kind") { w ->
            val run = w.run ?: return@walk w
            val key = run.task?.id ?: "shoe"
            w.copy(run = run.copy(mistakes = run.mistakes + 1), misses = w.misses + (key to (w.misses[key] ?: 0) + 1))
        }
        showHint(Hint(kind, now() + 1_500), restore = null)
    }

    private fun finish(run: TaskRun) {
        val w = s.walk ?: return
        val ms = now() - run.startMs
        val key = "${w.vision.key}-${run.task?.id ?: "walk"}"
        val before = s.best[key]
        val clean = run.takeOffs == 0
        val newBest = clean && (before == null || ms < before)
        val result = TaskResult(run.task, ms, run.mistakes, run.takeOffs, before, newBest)
        LvLog.i("TASK done ${run.task?.id ?: "challenge"} ms=$ms mistakes=${run.mistakes} takeOffs=${run.takeOffs} newBest=$newBest")
        play(Sound.FOUND)
        if (newBest) {
            prefs?.saveBest(key, ms)
            set(s.copy(best = s.best + (key to ms)), "best")
        }
        walk("finish") {
            val results = if (run.task != null) it.results + (run.task to result) else it.results
            it.copy(phase = WalkPhase.RESULT, run = null, shown = result, results = results, challenge = if (run.task == null) result else it.challenge, hint = null, layout = if (run.task == null) it.layout?.copy(shoe = null) else it.layout)
        }
    }

    fun nextClicked() {
        val w = s.walk ?: return
        if (w.phase != WalkPhase.RESULT) return
        if (w.allMainDone && w.checklist.isEmpty()) openChecklist("all done") else walk("next") { it.copy(phase = WalkPhase.READY, shown = null, taskListOpen = true, peeking = false) }
    }

    fun challengeClicked() {
        val w = s.walk ?: return
        val door = w.layout?.door ?: return
        val head = sceneHost?.head() ?: return
        val shoe = WalkRules.shoe(head.position, door.at, w.vision, random, w.layout.floorY)
        LvLog.i("CHALLENGE start shoe=${LvLog.pos(shoe)}")
        walk("challenge") { it.copy(phase = WalkPhase.CHALLENGE, run = TaskRun(null, now()), shown = null, layout = it.layout?.copy(shoe = shoe), veilOn = true, peeking = false, hint = Hint(HintKind.CHALLENGE, now() + 4_000), taskListOpen = false) }
    }

    fun stepped() {
        val w = s.walk ?: return
        if (w.phase != WalkPhase.CHALLENGE) return
        play(Sound.STEPPED)
        walk("stepped") { it.copy(run = it.run?.let { r -> r.copy(mistakes = r.mistakes + 1) }, misses = it.misses + ("shoe" to (it.misses["shoe"] ?: 0) + 1)) }
        showHint(Hint(HintKind.STEPPED, now() + 2_500), restore = null)
    }

    fun doorReached() {
        val w = s.walk ?: return
        val run = w.run ?: return
        if (w.phase != WalkPhase.CHALLENGE) return
        finish(run)
    }

    fun giveUpChallenge() {
        walk("give up") { it.copy(phase = WalkPhase.READY, run = null, layout = it.layout?.copy(shoe = null), menuOpen = false, taskListOpen = true, hint = null) }
    }

    fun checklistClicked() = openChecklist("button")

    private fun openChecklist(why: String) {
        val w = s.walk ?: return
        val ids = WalkRules.checklist(w.vision.disease, w.misses["cup"] ?: 0, w.misses["sign"] ?: 0, w.misses["shoe"] ?: 0)
        walk("checklist $why") { it.copy(phase = WalkPhase.CHECKLIST, checklist = ids, run = null, shown = null, menuOpen = false, taskListOpen = false, veilOn = false, hint = null) }
    }

    /** 「换一种病再走」: stays in the Stage, keeps planes and the door, resets the tasks, opens the menu (S24). */
    fun againOther() {
        walk("again") { it.copy(phase = WalkPhase.READY, results = emptyMap(), challenge = null, checklist = emptyList(), misses = emptyMap(), veilOn = true, menuOpen = true, taskListOpen = true, shown = null) }
    }

    // ---- veil (T6, S17)

    fun takeOffClicked() {
        val w = s.walk ?: return
        if (!w.veilOn) {
            if (now() < w.autoOffAtMs) return
            walk("put on") { it.copy(veilOn = true, hint = if (it.hint?.kind in setOf(HintKind.PAUSED, HintKind.AUTO_OFF)) null else it.hint) }
        } else takeOff("button")
    }

    fun takeOff(why: String) {
        val w = s.walk ?: return
        if (!w.veilOn) return
        LvLog.i("VEIL off $why")
        walk("take off $why") { it.copy(veilOn = false, run = it.run?.let { r -> r.copy(takeOffs = r.takeOffs + 1) }) }
    }

    /** Controller B / Y toggles the veil. */
    fun buttonToggle() = takeOffClicked()

    fun peek(down: Boolean) {
        val w = s.walk ?: return
        if (w.peeking == down) return
        walk("peek $down") { it.copy(peeking = down, shown = if (down) it.shown?.copy(takeOffs = it.shown.takeOffs + 1) else it.shown) }
    }

    fun autoOff(reason: String) {
        val w = s.walk ?: return
        if (!w.veilOn || w.phase !in WalkState.VEIL_PHASES) return
        LvLog.i("SAFETY auto off $reason")
        takeOff("auto $reason")
        walk("auto off") { it.copy(autoOffAtMs = now() + 3_000) }
        showHint(Hint(HintKind.AUTO_OFF, now() + 3_000), restore = null)
    }

    fun trackingChanged(lost: Boolean) {
        val w = s.walk ?: return
        if (w.trackingLost == lost) return
        LvLog.i("TRACK lost=$lost")
        if (lost) walk("tracking lost") { it.copy(trackingLost = true, veilOn = false, hint = Hint(HintKind.TRACKING_LOST)) }
        else walk("tracking back") { it.copy(trackingLost = false, hint = null) }
    }

    /** Back from background / Home / headset off (S26): the veil is off, a running task is void. */
    fun interrupted() {
        val w = s.walk ?: return
        if (w.phase !in WalkState.VEIL_PHASES) return
        LvLog.i("WALK interrupted")
        walk("interrupted") { it.copy(veilOn = false, peeking = false, run = null, phase = if (it.phase == WalkPhase.RESULT) WalkPhase.RESULT else WalkPhase.READY, layout = it.layout?.copy(shoe = null), taskListOpen = true, hint = Hint(HintKind.PAUSED)) }
    }

    // ---- pill menu (S24, T16)

    fun menuToggle() = walk("menu") { it.copy(menuOpen = !it.menuOpen, window = if (!it.menuOpen) WindowAsk.AUTO else it.window) }

    fun menuTaskList() = walk("menu tasks") { abandon(it).copy(menuOpen = false, taskListOpen = true, phase = if (it.phase == WalkPhase.CHECKLIST) it.phase else WalkPhase.READY) }

    fun visionChanged(v: Vision) {
        prefs?.saveVision(v)
        set(s.copy(selected = v), "walk vision")
        walk("walk vision ${v.key}") { abandon(it).copy(vision = v, phase = if (it.phase in setOf(WalkPhase.TASK, WalkPhase.CHALLENGE, WalkPhase.RESULT, WalkPhase.CHECKLIST)) WalkPhase.READY else it.phase, veilOn = true, taskListOpen = true) }
    }

    private fun abandon(w: WalkState): WalkState = if (w.run != null) w.copy(run = null, layout = w.layout?.copy(shoe = null), hint = null) else w

    // ---- scene objects (T17, D14)

    fun objectSelected(o: SceneObject) = walk("select ${o.id}") { it.copy(selected = o) }

    fun objectMoved(o: SceneObject, p: Placement) {
        walk("moved ${o.id}") { it.copy(layout = it.layout?.with(o, p), selected = o) }
        LvLog.i("OBJECT ${o.id} at=${LvLog.pos(p.at)} yaw=${"%.0f".format(Math.toDegrees(p.yaw.toDouble()))}")
    }

    /** 「移到标记处」 (T17, profile D1): onto the floor marker; without one, this starts putting the marker down. */
    fun bringHere(o: SceneObject) {
        val w = s.walk ?: return
        if (w.run != null) {
            showHint(Hint(HintKind.LOCKED, now() + 2_000), restore = null)
            return
        }
        val l = w.layout ?: return
        val marker = w.marker
        val head = sceneHost?.head()
        if (marker == null || head == null) {
            if (!w.placingMarker) markerClicked()
            return
        }
        val virtual = l.of(o)?.virtual ?: true
        val p = WalkRules.toMarker(o, marker, l.floorY, head, virtual)
        LvLog.i("OBJECT ${o.id} to marker ${LvLog.pos(marker)} -> ${LvLog.pos(p.at)}")
        walk("bring ${o.id}") { it.copy(layout = it.layout?.with(o, p)?.let { ll -> ll.copy(hidden = ll.hidden - o) }, selected = o) }
    }

    /** 「放标记」 / 「重放标记」: the floor catcher comes on and the controller ray shows a ghost marker; again = cancel. */
    fun markerClicked() {
        val w = s.walk ?: return
        if (w.placingMarker) {
            walk("marker cancelled") { it.copy(placingMarker = false, hint = if (it.hint?.kind == HintKind.PLACE_MARKER) null else it.hint) }
            return
        }
        walk("placing marker") { it.copy(placingMarker = true, hint = Hint(HintKind.PLACE_MARKER)) }
    }

    /** A trigger / pinch on the floor while placing: the marker is down there (and stays until it is put down again). */
    fun markerPlaced(at: Vec3) {
        val w = s.walk ?: return
        if (!w.placingMarker) return
        LvLog.i("MARKER at=${LvLog.pos(at)}")
        walk("marker placed") { it.copy(marker = at, placingMarker = false, hint = if (it.hint?.kind == HintKind.PLACE_MARKER) null else it.hint) }
    }

    fun toggleHidden(o: SceneObject) {
        walk("hide ${o.id}") { w -> val l = w.layout ?: return@walk w; w.copy(layout = l.copy(hidden = if (o in l.hidden) l.hidden - o else l.hidden + o)) }
    }

    fun isLocked(): Boolean = s.walk?.run != null

    // ---- hints

    private fun showHint(h: Hint, restore: Hint?) {
        walk("hint ${h.kind}") { it.copy(hint = h) }
        expireHint(h, restore)
    }

    private fun expireHint(h: Hint, then: Hint?) {
        val until = h.untilMs ?: return
        main.postDelayed({ if (s.walk?.hint == h) walk("hint end ${h.kind}") { it.copy(hint = then) } }, (until - now()).coerceAtLeast(0))
    }

    // =========================================================================================
    // lens window (T12 – T14, S20 – S23, S28)
    // =========================================================================================

    fun lensClicked() {
        val v = s.selected ?: return
        if (s.inStage) return
        val source = if (s.isEmulator) LensSource.KITCHEN else LensSource.CAMERA
        val host = lensHost
        if (host == null) {
            set(s.copy(lens = LensState(open = false, error = "no SpatialML host")), "lens unavailable")
            return
        }
        set(s.copy(lens = LensRules.opened(source, now()), settingsOpen = false, homePage = Pages.LENS), "lens open")
        val spot = spotHost
        if (spot == null) {
            host.open(source, v, false)
            return
        }
        lensWaitingForSpot = true
        spot.openSpot(++spotGen)
        // the spot window reports focus within ~1 s; without it the lens still opens (where the system puts it)
        main.postDelayed({ lensSpotReady("no focus after $SPOT_WAIT_MS ms") }, SPOT_WAIT_MS)
    }

    /** The spot window is focused (or the wait is over): now the session, so its container lands in front of the spot. */
    fun lensSpotReady(why: String) {
        if (!lensWaitingForSpot || !s.lens.open) return
        lensWaitingForSpot = false
        val v = s.selected ?: return
        LvLog.i("LENS spot ready ($why)")
        lensHost?.open(s.lens.source, v, s.lens.comparing)
    }

    /** The spot window went away (closed by the app, or by the user from its window bar). */
    fun lensSpotGone(gen: Int) {
        if (gen == spotGen && s.lens.open) lensClosed("spot window closed")
    }

    private const val SPOT_WAIT_MS = 3_000L

    fun lensSource(source: LensSource) {
        val v = s.selected ?: return
        if (!s.lens.open) return
        set(s.copy(lens = s.lens.copy(source = source, openedAtMs = now())), "lens source ${source.id}")
        lensHost?.update(source, v, s.lens.comparing)
    }

    /** The lens picture is up (the session ran twice): 「按住看原样」 comes alive. */
    fun lensLive() {
        val lens = LensRules.live(s.lens) ?: return
        set(s.copy(lens = lens), "lens live")
    }

    fun lensCompare(down: Boolean) {
        val v = s.selected ?: return
        val lens = LensRules.hold(s.lens, down) ?: return
        set(s.copy(lens = lens), "lens compare $down")
        lensHost?.update(s.lens.source, v, down)
    }

    fun lensCloseClicked() = lensClosed("button")

    fun lensClosed(why: String) {
        if (!s.lens.open) return
        lensWaitingForSpot = false
        lensHost?.close()
        set(s.copy(lens = LensRules.closed(s.lens)), "lens closed $why")
        spotHost?.closeSpot()
    }

    fun lensFailed(reason: String) {
        LvLog.w("LENS failed $reason")
        lensWaitingForSpot = false
        lensHost?.close()
        set(s.copy(lens = LensRules.failed(reason)), "lens failed")
        spotHost?.closeSpot()
    }

    fun lensErrorOk() = set(s.copy(lens = LensState()), "lens error ok")
}
