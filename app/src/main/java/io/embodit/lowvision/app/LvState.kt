package io.embodit.lowvision.app

import io.embodit.lowvision.domain.Lang
import io.embodit.lowvision.domain.SceneObject
import io.embodit.lowvision.domain.Task
import io.embodit.lowvision.domain.Vec3
import io.embodit.lowvision.domain.Vision
import io.embodit.lowvision.domain.WalkLayout

/** Steps of the walk in the Stage (design states S06 – S19, S24, S26, S27). */
enum class WalkPhase {
    OPENING,
    SAFETY,
    SCANNING,
    MARK_DOOR,
    READY,
    TASK,
    RESULT,
    CHALLENGE,
    CHECKLIST,
}

/** What the hint bar (P6) says; the text comes from `Copy`. */
enum class HintKind {
    SCANNING,
    MARK_DOOR,
    TOO_NEAR,
    DOOR_FAR,
    VIRTUAL,
    WRONG_SALT,
    WRONG_CASE,
    NOT_VISIBLE,
    SIGN_WRONG,
    STEPPED,
    PAUSED,
    TRACKING_LOST,
    AUTO_OFF,
    CHALLENGE,
    LOCKED,
    PLACE_MARKER,
}

data class Hint(val kind: HintKind, val untilMs: Long? = null)

/**
 * The home window during the walk (v0.1.4, profile D21 / D8): the walk's cards are pages of the one system window.
 * [AUTO]: shown, except while a task runs or the scene is being scanned / the door marked (it would stand in front of
 * the table, the sign and the door), [RECALLED]: brought back by the pill's 「叫回」, [DISMISSED]: closed with the system
 * window bar's ×. Back to [AUTO] whenever the phase changes.
 */
enum class WindowAsk {
    AUTO,
    RECALLED,
    DISMISSED,
}

/** Pages of the home window (system TabBar items); [SETTINGS] is the template's last page. */
object Pages {
    const val LOOK = "look"
    const val LENS = "lens"
    const val WALK = "walk"
    const val MENU = "menu"
    const val SETTINGS = "settings"
}

/** A task being timed. [task] null = the optional walk challenge. */
data class TaskRun(val task: Task?, val startMs: Long, val mistakes: Int = 0, val takeOffs: Int = 0)

/** A finished task. [best] is the best clean time (no take-off) before this one, when there is one. */
data class TaskResult(val task: Task?, val ms: Long, val mistakes: Int, val takeOffs: Int, val best: Long? = null, val newBest: Boolean = false)

data class WalkState(
    val vision: Vision,
    val phase: WalkPhase = WalkPhase.OPENING,
    val veilOn: Boolean = false,
    val peeking: Boolean = false,
    val run: TaskRun? = null,
    val results: Map<Task, TaskResult> = emptyMap(),
    val challenge: TaskResult? = null,
    val shown: TaskResult? = null,
    val hint: Hint? = null,
    val menuOpen: Boolean = false,
    val taskListOpen: Boolean = true,
    val layout: WalkLayout? = null,
    val virtualTable: Boolean = false,
    val virtualSign: Boolean = false,
    val signWord: Int = 0,
    val signChoices: List<Int> = emptyList(),
    val wrongChoice: Int? = null,
    val selected: SceneObject = SceneObject.TABLE,
    /** The floor marker (T17, profile D1): put down by controller ray + trigger or gaze + pinch; 「移到标记处」 uses it. */
    val marker: Vec3? = null,
    val placingMarker: Boolean = false,
    val autoOffAtMs: Long = 0L,
    val trackingLost: Boolean = false,
    val misses: Map<String, Int> = emptyMap(),
    val checklist: List<String> = emptyList(),
    val window: WindowAsk = WindowAsk.AUTO,
) {
    val allMainDone: Boolean
        get() = Task.entries.all { results.containsKey(it) }

    /** The veil is drawn: worn, not peeking, and not taken off by tracking loss. */
    val veilVisible: Boolean
        get() = veilOn && !peeking && !trackingLost && phase in VEIL_PHASES

    companion object {
        val VEIL_PHASES = setOf(WalkPhase.READY, WalkPhase.TASK, WalkPhase.RESULT, WalkPhase.CHALLENGE)

        /** Phases in which the home window steps aside by itself ([WindowAsk.AUTO]). */
        val WINDOW_ASIDE = setOf(WalkPhase.SCANNING, WalkPhase.MARK_DOOR, WalkPhase.TASK, WalkPhase.CHALLENGE)
    }
}

enum class LensSource(val id: String) {
    CAMERA("camera"),
    KITCHEN("kitchen"),
    PILLS("pills"),
    STAIRS("stairs");

    companion object {
        fun of(id: String?): LensSource? = entries.firstOrNull { it.id == id?.lowercase() }
    }
}

/** The lens window (P9, Shared Space). [error] non-null = S23. */
data class LensState(val open: Boolean = false, val source: LensSource = LensSource.KITCHEN, val comparing: Boolean = false, val error: String? = null, val openedAtMs: Long = 0L)

data class LvState(
    val lang: Lang = Lang.ZH,
    val muted: Boolean = false,
    val selected: Vision? = null,
    val settingsOpen: Boolean = false,
    /** Settings' 「退出应用」 asked once, waiting for the confirm (profile D26). */
    val quitAsked: Boolean = false,
    val showSafety: Boolean = true,
    val inStage: Boolean = false,
    val walk: WalkState? = null,
    val lens: LensState = LensState(),
    val best: Map<String, Long> = emptyMap(),
    val isEmulator: Boolean = false,
    /** The home page shown outside the walk ([Pages.LOOK] or [Pages.LENS]). */
    val homePage: String = Pages.LOOK,
) {
    /** The home window's page (its TabBar selection). */
    val page: String
        get() {
            if (settingsOpen) return Pages.SETTINGS
            val w = walk
            if (inStage && w != null) return if (w.menuOpen) Pages.MENU else Pages.WALK
            return homePage
        }

    /** Whether the home window should be open now (v0.1.4: it carries the walk's cards, see [WindowAsk]). */
    val windowWanted: Boolean
        get() {
            val w = walk ?: return true
            if (!inStage) return true
            return when (w.window) {
                WindowAsk.RECALLED -> true
                WindowAsk.DISMISSED -> false
                WindowAsk.AUTO -> settingsOpen || w.menuOpen || w.phase !in WalkState.WINDOW_ASIDE
            }
        }

    /** Design state id, for the log and QA. */
    val stateId: String
        get() {
            val w = walk
            if (w != null && inStage) {
                if (w.trackingLost) return "S27"
                if (w.menuOpen) return "S24"
                return when (w.phase) {
                    WalkPhase.OPENING -> "S07"
                    WalkPhase.SAFETY -> "S06"
                    WalkPhase.SCANNING -> "S07"
                    WalkPhase.MARK_DOOR -> if (w.virtualTable || w.virtualSign) "S19" else "S08"
                    WalkPhase.READY -> if (w.hint?.kind == HintKind.PAUSED) "S26" else if (!w.veilOn) "S17" else "S09"
                    WalkPhase.TASK ->
                        when {
                            !w.veilOn -> "S17"
                            w.hint?.kind == HintKind.WRONG_SALT || w.hint?.kind == HintKind.WRONG_CASE -> "S10w"
                            w.hint?.kind == HintKind.NOT_VISIBLE -> "S10v"
                            w.hint?.kind == HintKind.SIGN_WRONG -> "S13"
                            w.run?.task == Task.CUP -> "S10"
                            w.run?.task == Task.SIGN -> "S12"
                            else -> "S14"
                        }
                    WalkPhase.RESULT ->
                        when (w.shown?.task) {
                            Task.CUP -> "S11"
                            Task.SIGN -> "S12d"
                            Task.DOOR -> "S16"
                            null -> "S16c"
                        }
                    WalkPhase.CHALLENGE -> if (w.hint?.kind == HintKind.STEPPED) "S15" else if (!w.veilOn) "S17" else "S14c"
                    WalkPhase.CHECKLIST -> "S18"
                }
            }
            if (lens.error != null) return "S23"
            if (lens.open) return if (lens.comparing) "S22" else if (lens.source == LensSource.CAMERA) "S21" else "S20"
            if (settingsOpen) return if (quitAsked) "S05q" else "S05"
            val v = selected ?: return "S01"
            if (lang == Lang.EN) return "S25"
            return when (v.disease) {
                io.embodit.lowvision.domain.Disease.GLAUCOMA -> "S02"
                io.embodit.lowvision.domain.Disease.AMD -> "S03"
                io.embodit.lowvision.domain.Disease.HEMIANOPIA -> "S04"
            }
        }
}
