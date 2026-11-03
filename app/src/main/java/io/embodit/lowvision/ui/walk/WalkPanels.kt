package io.embodit.lowvision.ui.walk

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.CircularProgressDefaults
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import io.embodit.lowvision.R
import io.embodit.lowvision.app.HintKind
import io.embodit.lowvision.app.LvState
import io.embodit.lowvision.app.LvStore
import io.embodit.lowvision.app.WalkPhase
import io.embodit.lowvision.domain.Copy
import io.embodit.lowvision.domain.Disease
import io.embodit.lowvision.domain.SceneObject
import io.embodit.lowvision.domain.Side
import io.embodit.lowvision.domain.Task
import io.embodit.lowvision.domain.Vision
import io.embodit.lowvision.ui.home.HoldButton
import io.embodit.lowvision.domain.PanelIds
import io.embodit.lowvision.domain.PanelSpots
import io.embodit.lowvision.ui.home.colorOf
import io.embodit.lowvision.ui.home.iconOf
import io.embodit.lowvision.ui.theme.LvColors
import io.embodit.lowvision.ui.theme.LvType
import kotlinx.coroutines.delay

// Stage panels sit on an opaque plateInk base, not glass (attachment panels draw black bands with glass
// in a Stage on this emulator: Qianliyan / Life-size finding). Text uses the on-plate colours (≥ 9 : 1).

private val PanelShape = RoundedCornerShape(24.dp)

/** Fixed-size root of a pooled panel: it keeps its size also when empty (an unsized panel lays out huge). */
@Composable
internal fun PanelBox(id: String, content: @Composable () -> Unit) {
    val spot = PanelSpots.getValue(id)
    Box(Modifier.size(spot.widthDp.dp, spot.heightDp.dp)) { content() }
}

@Composable
private fun Plate(modifier: Modifier = Modifier.fillMaxSize(), padding: Dp = 20.dp, content: @Composable () -> Unit) {
    Box(modifier.background(LvColors.PlateInk, PanelShape).border(1.dp, LvColors.PlateRaised, PanelShape).padding(padding)) { content() }
}

@Composable
private fun PlateButton(label: String, modifier: Modifier = Modifier, primary: Boolean = false, enabled: Boolean = true, icon: Int? = null, onClick: () -> Unit) {
    val container = if (primary) LvColors.Accent else LvColors.PlateRaised
    val ink = if (primary) LvColors.OnAccent else LvColors.OnPlate
    Button(onClick = onClick, enabled = enabled, modifier = modifier.height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = ink)) {
        if (icon != null) {
            Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp), tint = ink)
            Spacer(Modifier.width(6.dp))
        }
        Text(label, color = ink, maxLines = 1, softWrap = false)
    }
}

// ---------------------------------------------------------------------------------------------
// P3 safety card (S06)
// ---------------------------------------------------------------------------------------------

@Composable
internal fun SafetyCard(state: LvState?) {
    state ?: return
    val c = Copy(state.lang)
    Plate {
        Column {
            DragHint(c)
            Text(c.safetyTitle, style = LvType.title, color = LvColors.OnPlate)
            Spacer(Modifier.height(12.dp))
            c.safetyLines.forEachIndexed { i, line ->
                Row(Modifier.padding(vertical = 5.dp)) {
                    Text("${i + 1}", style = LvType.cardName, color = LvColors.Accent, modifier = Modifier.width(24.dp))
                    Text(line, style = LvType.body, color = LvColors.OnPlate)
                }
            }
            Spacer(Modifier.weight(1f))
            PlateButton(c.gotIt, Modifier.fillMaxWidth(), primary = true, icon = R.drawable.ic_check) { LvStore.safetyOk() }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// P6 hint bar
// ---------------------------------------------------------------------------------------------

@Composable
internal fun HintBar(state: LvState?) {
    val w = state?.walk ?: return
    val hint = w.hint ?: return
    val c = Copy(state.lang)
    val text =
        when (hint.kind) {
            HintKind.SCANNING -> c.hintScanning
            HintKind.MARK_DOOR -> c.hintMarkDoor
            HintKind.TOO_NEAR -> c.hintTooNear
            HintKind.DOOR_FAR -> c.hintDoorFar
            HintKind.VIRTUAL -> c.hintVirtual(w.virtualTable, w.virtualSign)
            HintKind.WRONG_SALT -> c.hintWrongItem(1)
            HintKind.WRONG_CASE -> c.hintWrongItem(2)
            HintKind.NOT_VISIBLE -> c.hintNotVisible
            HintKind.SIGN_WRONG -> c.hintSignWrong
            HintKind.STEPPED -> c.hintStepped
            HintKind.PAUSED -> c.hintPaused
            HintKind.TRACKING_LOST -> c.hintTrackingLost
            HintKind.AUTO_OFF -> c.hintAutoOff
            HintKind.CHALLENGE -> c.hintChallenge
            HintKind.LOCKED -> c.hintTaskLocked
        }
    val alert = hint.kind in setOf(HintKind.WRONG_SALT, HintKind.WRONG_CASE, HintKind.NOT_VISIBLE, HintKind.SIGN_WRONG, HintKind.STEPPED, HintKind.TRACKING_LOST)
    Plate(padding = 14.dp) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            when {
                hint.kind == HintKind.SCANNING ->
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = LvColors.Accent, progressSize = CircularProgressDefaults.circleProgressSize(24.dp))
                alert -> Box(Modifier.size(14.dp).background(LvColors.Danger, CircleShape))
                else -> Box(Modifier.size(14.dp).background(LvColors.Accent, CircleShape))
            }
            Spacer(Modifier.width(14.dp))
            Text(text, style = LvType.body, color = LvColors.OnPlate, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (hint.kind == HintKind.MARK_DOOR || hint.kind == HintKind.TOO_NEAR || hint.kind == HintKind.DOOR_FAR) {
                Spacer(Modifier.width(10.dp))
                PlateButton(c.skip) { LvStore.skipClicked() }
            }
            if (hint.kind == HintKind.PAUSED || hint.kind == HintKind.AUTO_OFF) {
                Spacer(Modifier.width(10.dp))
                PlateButton(c.putOn, primary = true) { LvStore.takeOffClicked() }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// P4 pill (always there while the veil can be on)
// ---------------------------------------------------------------------------------------------

@Composable
private fun ticker(): Long {
    var now by remember { mutableLongStateOf(LvStore.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LvStore.now()
            delay(500)
        }
    }
    return now
}

@Composable
internal fun Pill(state: LvState?) {
    val w = state?.walk ?: return
    val c = Copy(state.lang)
    val now = ticker()
    Plate(padding = 10.dp, modifier = Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Icon(painter = painterResource(iconOf(w.vision.disease)), contentDescription = null, modifier = Modifier.size(26.dp), tint = colorOf(w.vision.disease))
            Spacer(Modifier.width(6.dp))
            Text(levelMark(w.vision, c), style = LvType.small, color = LvColors.OnPlateDim, maxLines = 1)
            Spacer(Modifier.width(10.dp))
            val run = w.run
            Column(Modifier.weight(1f)) {
                Text(run?.let { r -> r.task?.let { c.taskName(it) } ?: c.challengeName } ?: c.shortName(w.vision.disease), style = LvType.small, color = LvColors.OnPlate, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (run != null) Text(Copy.clock(now - run.startMs), style = LvType.clock, color = LvColors.OnPlate, maxLines = 1)
            }
            PlateButton(if (w.veilOn) c.takeOff else c.putOn, primary = !w.veilOn) { LvStore.takeOffClicked() }
            Spacer(Modifier.width(6.dp))
            CaptionButton(R.drawable.ic_recall, c.recall) { ActiveScene.scene?.recallPanels() }
            Spacer(Modifier.width(4.dp))
            CaptionButton(if (state.muted) R.drawable.ic_sound_off else R.drawable.ic_sound_on, if (state.muted) c.unmute else c.mute) { LvStore.muteClicked() }
            Spacer(Modifier.width(4.dp))
            CaptionButton(R.drawable.ic_more, c.more, active = w.menuOpen) { LvStore.menuToggle() }
        }
    }
}

private fun levelMark(v: Vision, c: Copy): String = if (v.disease == Disease.HEMIANOPIA) c.sideName(v.side) else (1..5).joinToString("") { if (it <= v.level) "●" else "○" }

/** An icon button with its word under it (B19: plain icons carry text). */
@Composable
private fun CaptionButton(icon: Int, label: String, active: Boolean = false, onClick: () -> Unit) {
    val ink = if (active) LvColors.OnAccent else LvColors.OnPlate
    Button(onClick = onClick, modifier = Modifier.size(width = 72.dp, height = 64.dp), colors = ButtonDefaults.buttonColors(containerColor = if (active) LvColors.Accent else LvColors.PlateRaised, contentColor = ink)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp), tint = ink)
            Text(label, style = LvType.tiny, color = ink, maxLines = 1, softWrap = false)
        }
    }
}

/**
 * Where to grab a card (D21): the card itself has no 2D drag. The bar under it is a 3D grip that the Stage's spatial
 * drag moves in world space (WalkScene.buildGrips); 「叫回」 on the pill brings the cards back in front.
 */
@Composable
private fun DragHint(c: Copy) {
    Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Text(c.dragHere, style = LvType.tiny, color = LvColors.OnPlateDim, maxLines = 1)
    }
}

// ---------------------------------------------------------------------------------------------
// S24 pill menu
// ---------------------------------------------------------------------------------------------

@Composable
internal fun PillMenu(state: LvState?) {
    val w = state?.walk ?: return
    val c = Copy(state.lang)
    val v = w.vision
    Plate(padding = 16.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (w.run != null) Text(c.menuNote, style = LvType.small, color = LvColors.OnPlateDim, maxLines = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlateButton(c.taskList, Modifier.weight(1f), icon = R.drawable.ic_list) { LvStore.menuTaskList() }
                PlateButton(c.home, Modifier.weight(1f), icon = R.drawable.ic_home) { LvStore.homeClicked() }
            }
            PlateButton(c.lensFromWalk, Modifier.fillMaxWidth(), icon = R.drawable.ic_eye) { LvStore.lensFromWalk() }
            Text(c.changeDisease, style = LvType.small, color = LvColors.OnPlateDim)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Disease.entries.forEach { d ->
                    val sel = v.disease == d
                    Button(
                        onClick = { LvStore.visionChanged(if (sel) v else Vision(d, v.level, v.side)) },
                        modifier = Modifier.weight(1f).height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = if (sel) LvColors.Accent else LvColors.PlateRaised, contentColor = if (sel) LvColors.OnAccent else LvColors.OnPlate),
                    ) {
                        Icon(painter = painterResource(iconOf(d)), contentDescription = null, modifier = Modifier.size(20.dp), tint = if (sel) LvColors.OnAccent else colorOf(d))
                        Spacer(Modifier.width(4.dp))
                        Text(c.shortName(d), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, color = if (sel) LvColors.OnAccent else LvColors.OnPlate)
                    }
                }
            }
            if (v.disease == Disease.HEMIANOPIA) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(Side.LEFT, Side.RIGHT).forEach { s ->
                        PlateButton(c.sideName(s), Modifier.weight(1f), primary = v.side == s) { LvStore.visionChanged(v.copy(side = s)) }
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlateButton("−", Modifier.width(64.dp), enabled = v.level > 1) { LvStore.visionChanged(v.withLevel(v.level - 1)) }
                    Text("${levelMark(v, c)}  ${c.degrees(v)}", style = LvType.body, color = LvColors.OnPlate, modifier = Modifier.weight(1f), maxLines = 1)
                    PlateButton("+", Modifier.width(64.dp), enabled = v.level < Vision.LEVELS) { LvStore.visionChanged(v.withLevel(v.level + 1)) }
                }
            }
            if (w.phase == WalkPhase.CHALLENGE) PlateButton(c.giveUp, Modifier.fillMaxWidth()) { LvStore.giveUpChallenge() }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// P5 task list (S09)
// ---------------------------------------------------------------------------------------------

fun taskIcon(t: Task) =
    when (t) {
        Task.CUP -> R.drawable.ic_mug
        Task.SIGN -> R.drawable.ic_sign
        Task.DOOR -> R.drawable.ic_door
    }

@Composable
internal fun TaskList(state: LvState?) {
    val w = state?.walk ?: return
    val c = Copy(state.lang)
    Plate(padding = 18.dp) {
        Column {
            DragHint(c)
            Task.entries.forEach { t ->
                val r = w.results[t]
                Row(Modifier.fillMaxWidth().height(76.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painter = painterResource(taskIcon(t)), contentDescription = null, modifier = Modifier.size(30.dp), tint = LvColors.OnPlate)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(c.taskName(t), style = LvType.cardName, color = LvColors.OnPlate, maxLines = 1)
                            Spacer(Modifier.width(10.dp))
                            Text(if (r != null) c.used(r.ms) else c.notDone, style = LvType.small, color = if (r != null) LvColors.Door else LvColors.OnPlateDim, maxLines = 1)
                        }
                        Text(c.taskHow(t), style = LvType.small, color = LvColors.OnPlateDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(10.dp))
                    PlateButton(c.start, primary = r == null) { LvStore.startTask(t) }
                }
            }
            Spacer(Modifier.height(8.dp))
            PlateButton(c.seeChecklist, Modifier.fillMaxWidth(), primary = w.allMainDone, icon = R.drawable.ic_list) { LvStore.checklistClicked() }
            Spacer(Modifier.height(10.dp))
            Text(c.sceneObjects, style = LvType.small, color = LvColors.OnPlateDim)
            SceneObject.entries.forEach { o ->
                val hidden = w.layout?.hidden?.contains(o) == true
                val sel = w.selected == o
                Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(if (sel) LvColors.Accent else LvColors.PlateRaised, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(c.objectName(o), style = LvType.body, color = LvColors.OnPlate, modifier = Modifier.weight(1f), maxLines = 1)
                    SmallPlateButton(c.bringHere) { LvStore.bringHere(o) }
                    Spacer(Modifier.width(6.dp))
                    SmallPlateButton(if (hidden) c.show else c.hide) { LvStore.toggleHidden(o) }
                }
            }
            Text(c.stickHint, style = LvType.small, color = LvColors.OnPlateDim, maxLines = 1)
        }
    }
}

@Composable
private fun SmallPlateButton(label: String, onClick: () -> Unit) {
    Button(onClick = onClick, size = ButtonDefaults.Small, modifier = Modifier.height(48.dp), colors = ButtonDefaults.buttonColors(containerColor = LvColors.PlateRaised, contentColor = LvColors.OnPlate)) {
        Text(label, color = LvColors.OnPlate, maxLines = 1, softWrap = false)
    }
}

// ---------------------------------------------------------------------------------------------
// P7 result card (S11 / S12d / S16 / S16c)
// ---------------------------------------------------------------------------------------------

@Composable
internal fun ResultCard(state: LvState?) {
    val w = state?.walk ?: return
    val r = w.shown ?: return
    val c = Copy(state.lang)
    Plate {
        Column {
            DragHint(c)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(Copy.clock(r.ms), style = LvType.bigClock, color = LvColors.OnPlate)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(r.task?.let { c.taskName(it) } ?: c.challengeDone, style = LvType.cardName, color = LvColors.OnPlate, maxLines = 1)
                    val best = if (r.newBest) c.best(r.ms) else r.best?.let { c.best(it) }
                    if (best != null) Text(best, style = LvType.small, color = LvColors.OnPlateDim)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(r.task?.let { c.insight(w.vision.disease, it) } ?: c.challengeInsight, style = LvType.body, color = LvColors.OnPlate, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Text("${c.mistakes(r.task, r.mistakes)} · ${c.takeOffs(r.takeOffs)}", style = LvType.small, color = LvColors.OnPlateDim)
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HoldButton(c.peek, Modifier.weight(1f), container = LvColors.PlateRaised, content = LvColors.OnPlate) { LvStore.peek(it) }
                if (r.task == Task.DOOR && w.challenge == null) PlateButton(c.tryChallenge, Modifier.weight(1.3f)) { LvStore.challengeClicked() }
                PlateButton(c.next, Modifier.weight(1f), primary = true) { LvStore.nextClicked() }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// P8 checklist (S18)
// ---------------------------------------------------------------------------------------------

@Composable
internal fun ChecklistCard(state: LvState?) {
    val w = state?.walk ?: return
    val c = Copy(state.lang)
    Plate {
        Column {
            DragHint(c)
            Text(c.checklistTitle, style = LvType.title, color = LvColors.OnPlate)
            Text(c.checklistSub(w.vision), style = LvType.small, color = LvColors.OnPlateDim)
            Spacer(Modifier.height(10.dp))
            w.checklist.forEachIndexed { i, id ->
                val item = c.checkItem(id)
                Row(Modifier.padding(vertical = 5.dp)) {
                    Box(Modifier.padding(top = 4.dp).size(22.dp).background(if (i == 0) colorOf(w.vision.disease) else LvColors.PlateRaised, CircleShape), contentAlignment = Alignment.Center) {
                        Text("${i + 1}", style = LvType.small, color = if (i == 0) LvColors.PlateInk else LvColors.OnPlate)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(item.what, style = LvType.body, color = LvColors.OnPlate, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("— ${item.why}", style = LvType.small, color = LvColors.OnPlateDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PlateButton(c.home, Modifier.weight(1f), icon = R.drawable.ic_home) { LvStore.homeClicked() }
                PlateButton(c.againOther, Modifier.weight(1f), primary = true) { LvStore.againOther() }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// P10 word choices (S12 / S13)
// ---------------------------------------------------------------------------------------------

@Composable
internal fun SignChoices(state: LvState?) {
    val w = state?.walk ?: return
    val c = Copy(state.lang)
    val words = c.signWords
    Plate(padding = 10.dp) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            w.signChoices.forEach { i ->
                val wrong = w.wrongChoice == i
                Button(
                    onClick = { LvStore.signChosen(i) },
                    modifier = Modifier.weight(1f).height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (wrong) LvColors.Danger else LvColors.PlateRaised, contentColor = if (wrong) LvColors.PlateInk else LvColors.OnPlate),
                ) {
                    Text(words.getOrElse(i) { "?" }, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, color = if (wrong) LvColors.PlateInk else LvColors.OnPlate)
                }
            }
        }
    }
}
