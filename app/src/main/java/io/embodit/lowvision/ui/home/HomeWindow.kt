package io.embodit.lowvision.ui.home

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SegmentControl
import com.pico.spatial.ui.design.SegmentControlDefaults
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.design.Slider
import com.pico.spatial.ui.design.SliderDefaults
import com.pico.spatial.ui.design.Switch
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.haptic.controllerHapticFeedback
import com.pico.spatial.ui.foundation.hover.spatialHoverEffect
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import com.pico.spatial.ui.platform.containers.OpenStageResult
import com.pico.spatial.ui.platform.containers.StageStyle
import io.embodit.lowvision.HOME_WINDOW_ID
import io.embodit.lowvision.BuildConfig
import io.embodit.lowvision.R
import io.embodit.lowvision.app.LensSource
import io.embodit.lowvision.app.LvState
import io.embodit.lowvision.app.LvStore
import io.embodit.lowvision.domain.Copy
import io.embodit.lowvision.domain.Disease
import io.embodit.lowvision.domain.Lang
import io.embodit.lowvision.domain.Side
import io.embodit.lowvision.domain.VeilMath
import io.embodit.lowvision.domain.Vision
import io.embodit.lowvision.platform.LvLog
import io.embodit.lowvision.ui.theme.FitText
import io.embodit.lowvision.ui.theme.LvColors
import io.embodit.lowvision.ui.theme.LvType
import io.embodit.lowvision.ui.walk.WALK_STAGE_ID
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The home window (design P1): Shared Space, 720 x 600 dp, system glass. Three disease cards; once
 * one is picked, the field diagram, the intro and the severity (or the blind side) open below; the two
 * entries at the bottom. While the lens is open the detail area holds the lens controls (P9). The
 * settings (P2) and the "lens unavailable" card (S23) are drawn over the window. Text on the glass uses labelPrimary
 * only (v0.1.2: labelSecondary measured 3.3 : 1 on the emulator glass, below 4.5 : 1, profile D13).
 */
@Composable
fun HomeWindow(windowId: String = HOME_WINDOW_ID, windowTag: String? = null) {
    val state by LvStore.state.collectAsStateWithLifecycle()
    val c = Copy(state.lang)
    StageBridge(windowId, windowTag)
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 18.dp)) {
            TopBar(state, c)
            Spacer(Modifier.height(14.dp))
            DiseaseCards(state, c)
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth().weight(1f)) {
                val v = state.selected
                when {
                    state.inStage -> InStageNote(c)
                    state.lens.open && v != null -> LensControls(state, v, c)
                    v != null -> Detail(v, c)
                    else -> Text(c.pickFirst, style = LvType.body, color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.align(Alignment.Center))
                }
            }
            Spacer(Modifier.height(12.dp))
            if (!state.inStage && !state.lens.open) BottomButtons(state, c)
        }
        if (state.settingsOpen) SettingsCard(state, c, Modifier.align(Alignment.Center))
        state.lens.error?.let { LensErrorCard(c, it, Modifier.align(Alignment.Center)) }
    }
}

/** Opens / closes the walk Stage for the store; closes it when the window goes away, unless it stepped aside for the walk. */
@Composable
private fun StageBridge(windowId: String, windowTag: String?) {
    val context = LocalContext.current
    val navigator = LocalSpatialNavigator.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(navigator, lifecycleOwner) {
        LvStore.stageHost =
            object : LvStore.StageHost {
                override fun openStage() {
                    scope.launch(Dispatchers.Main.immediate) {
                        val result = runCatching { navigator.openStage(WALK_STAGE_ID, StageStyle.Mixed) }
                        when (val r = result.getOrNull()) {
                            is OpenStageResult.Allowed -> {
                                LvStore.stageResult(true, "Allowed")
                                // the window draws over Stage content (life-size finding): it closes while the walk is on and
                                // the Stage opens it again when it ends. Not minimised: the system keeps a minimised window
                                // minimised across launches (a force-stop mid-walk started the app invisible), and restoring
                                // needs an open Stage. The close waits until the Stage has settled.
                                delay(HOME_CLOSE_DELAY_MS)
                                if (LvStore.state.value.inStage || LvStore.state.value.walk != null) {
                                    val ok = runCatching { navigator.closeWindowContainer() }
                                    LvLog.i("HOME closed for the walk: ${ok.exceptionOrNull() ?: "ok"}")
                                    if (ok.isSuccess) LvStore.hiddenHome = windowId to windowTag
                                }
                            }
                            is OpenStageResult.NotAllowed -> LvStore.stageResult(false, "NotAllowed")
                            is OpenStageResult.Error -> LvStore.stageResult(false, "Error ${r.code} ${r.reason}")
                            null -> LvStore.stageResult(false, "threw ${result.exceptionOrNull()}")
                        }
                    }
                }

                override fun closeStage() {
                    scope.launch(Dispatchers.Main.immediate) { runCatching { navigator.closeStage() }.onFailure { LvLog.w("STAGE close failed: $it") } }
                }
            }
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_DESTROY && LvStore.hiddenHome == null) {
                    scope.launch(Dispatchers.Main.immediate) { runCatching { navigator.closeStage() } }
                    LvStore.stageClosed()
                    LvStore.lensClosed("home destroyed")
                }
            }
        LvStore.spotHost =
            object : LvStore.SpotHost {
                override fun openSpot(gen: Int) = navigator.openWindowContainer(LENS_SPOT_ID, "spot$gen")

                override fun closeSpot() {
                    runCatching { navigator.closeWindowContainer(LENS_SPOT_ID) }.onFailure { LvLog.w("LENS spot close: $it") }
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            LvStore.stageHost = null
            LvStore.spotHost = null
        }
    }
}

// ---------------------------------------------------------------------------------------------
// top bar
// ---------------------------------------------------------------------------------------------

@Composable
private fun TopBar(state: LvState, c: Copy) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            FitText(c.title, where = "home/c.title", style = LvType.title, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1)
            FitText(c.subtitle, where = "home/c.subtitle", style = LvType.body, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        LabeledIcon(if (state.muted) R.drawable.ic_sound_off else R.drawable.ic_sound_on, if (state.muted) c.unmute else c.mute) { LvStore.muteClicked() }
        Spacer(Modifier.width(8.dp))
        LabeledIcon(R.drawable.ic_settings, c.settings) { LvStore.settingsOpen(true) }
    }
}

/** An icon button with its word under it (B19: plain icons carry text). */
@Composable
private fun LabeledIcon(icon: Int, label: String, onClick: () -> Unit) {
    Button(onClick = onClick, size = ButtonDefaults.Small, modifier = Modifier.height(48.dp), colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.fillTertiary, contentColor = PicoTheme.colorScheme.labelPrimary)) {
        Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp), tint = PicoTheme.colorScheme.labelPrimary)
        Spacer(Modifier.width(6.dp))
        FitText(label, where = "home/label", maxLines = 1, softWrap = false)
    }
}

// ---------------------------------------------------------------------------------------------
// disease cards
// ---------------------------------------------------------------------------------------------

fun iconOf(d: Disease) =
    when (d) {
        Disease.GLAUCOMA -> R.drawable.ic_glaucoma
        Disease.AMD -> R.drawable.ic_amd
        Disease.HEMIANOPIA -> R.drawable.ic_hemianopia
    }

fun colorOf(d: Disease) =
    when (d) {
        Disease.GLAUCOMA -> LvColors.Glaucoma
        Disease.AMD -> LvColors.Amd
        Disease.HEMIANOPIA -> LvColors.Hemianopia
    }

@Composable
private fun DiseaseCards(state: LvState, c: Copy) {
    val enabled = !state.inStage
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Disease.entries.forEach { d ->
            val selected = state.selected?.disease == d
            val interaction = remember { MutableInteractionSource() }
            val shape = RoundedCornerShape(20.dp)
            Column(
                modifier =
                    Modifier.weight(1f)
                        .height(if (state.selected == null) 176.dp else 112.dp)
                        .alpha(if (enabled || selected) 1f else 0.4f)
                        .then(if (selected) Modifier.border(3.dp, PicoTheme.colorScheme.interaction, shape) else Modifier)
                        .background(PicoTheme.colorScheme.fillTertiary, shape)
                        .clip(shape)
                        .then(if (enabled) Modifier.spatialHoverEffect() else Modifier)
                        .clickable(interactionSource = interaction, indication = LocalIndication.current, enabled = enabled) { LvStore.diseaseClicked(d) }
                        .controllerHapticFeedback(interactionSource = interaction)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(painter = painterResource(iconOf(d)), contentDescription = null, modifier = Modifier.size(32.dp), tint = colorOf(d))
                    Spacer(Modifier.width(10.dp))
                    FitText(c.name(d), where = "home/c.name", style = LvType.cardName, color = PicoTheme.colorScheme.labelPrimary, maxLines = 2)
                }
                Spacer(Modifier.height(8.dp))
                FitText(c.line(d), where = "home/c.line", style = LvType.body, color = PicoTheme.colorScheme.labelPrimary, maxLines = if (state.selected == null) 3 else 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// detail (S02 – S04)
// ---------------------------------------------------------------------------------------------

@Composable
private fun Detail(v: Vision, c: Copy) {
    Row(Modifier.fillMaxSize()) {
        FieldDiagram(v, Modifier.size(220.dp).align(Alignment.CenterVertically))
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f).fillMaxHeight()) {
            FitText(c.intro(v.disease), where = "home/c.intro", style = LvType.body, color = PicoTheme.colorScheme.labelPrimary, maxLines = 6, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.weight(1f))
            SeverityControl(v, c)
            Spacer(Modifier.height(6.dp))
            FitText(if (v.disease == Disease.AMD) c.turnHead else c.notBlack, where = "home/if", style = LvType.small, color = PicoTheme.colorScheme.labelPrimary, maxLines = 2)
        }
    }
}

@Composable
private fun SeverityControl(v: Vision, c: Copy) {
    if (v.disease == Disease.HEMIANOPIA) {
        Text(c.sideLabel, style = LvType.small, color = PicoTheme.colorScheme.labelPrimary)
        Spacer(Modifier.height(6.dp))
        SideSegment(v.side, c) { LvStore.sideChanged(it) }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(c.levelLabel, style = LvType.small, color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.weight(1f))
        FitText(c.degrees(v), where = "home/c.degrees", style = LvType.cardName, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1)
    }
    Slider(
        value = v.level.toFloat(),
        onValueChange = { LvStore.levelChanged(it.roundToInt()) },
        modifier = Modifier.fillMaxWidth(),
        valueRange = 1f..5f,
        onValueChangeFinished = {},
        colors = SliderDefaults.sliderColors(trackColor = PicoTheme.colorScheme.fillPrimary, progressColor = PicoTheme.colorScheme.interaction, progressHighColor = PicoTheme.colorScheme.interaction),
    )
    Row(Modifier.fillMaxWidth()) {
        (1..5).forEach { i ->
            Text(
                text = if (i == v.level) "●" else "○",
                style = LvType.small,
                color = if (i == v.level) PicoTheme.colorScheme.labelPrimary else PicoTheme.colorScheme.labelSecondary,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
fun SideSegment(side: Side, c: Copy, onSide: (Side) -> Unit) {
    SegmentControl(modifier = Modifier.width(224.dp).height(52.dp), backgroundColor = PicoTheme.colorScheme.fillTertiary) {
        listOf(Side.LEFT to c.left, Side.RIGHT to c.right).forEach { (s, label) ->
            SegmentItem(
                selected = side == s,
                onClick = { onSide(s) },
                modifier = Modifier.width(108.dp).height(48.dp),
                textStyle = PicoTheme.typography.labelLarge,
                colors =
                    SegmentControlDefaults.colors(
                        itemBackgroundColor = Color.Transparent,
                        itemContentColor = PicoTheme.colorScheme.labelPrimary,
                        selectedItemBackgroundColor = PicoTheme.colorScheme.fillSecondary,
                        selectedItemContentColor = PicoTheme.colorScheme.labelPrimary,
                    ),
                title = { FitText(text = label, where = "home/label", maxLines = 1, softWrap = false) },
            )
        }
    }
}

/**
 * The field diagram (design P1): a round field out to 60° from the centre, the seen part light, the
 * veil of the current disease and level drawn with the same math as the Stage veil. Compose
 * foundation Canvas, no Material.
 */
@Composable
fun FieldDiagram(v: Vision, modifier: Modifier) {
    val bitmap = remember(v) { fieldBitmap(v, 220) }
    val ring = colorOf(v.disease)
    Canvas(modifier = modifier) {
        val r = size.minDimension / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        drawCircle(LvColors.FieldSeen, r, center)
        drawImage(bitmap.asImageBitmap(), dstSize = IntSize(size.width.toInt(), size.height.toInt()))
        drawCircle(ring, r - 2f, center, style = Stroke(width = 4f))
        drawLine(ring.copy(alpha = 0.6f), Offset(center.x - 8f, center.y), Offset(center.x + 8f, center.y), strokeWidth = 2f)
        drawLine(ring.copy(alpha = 0.6f), Offset(center.x, center.y - 8f), Offset(center.x, center.y + 8f), strokeWidth = 2f)
    }
}

private const val FIELD_DEG = 60f
private const val HOME_CLOSE_DELAY_MS = 2_000L

private fun fieldBitmap(v: Vision, px: Int): Bitmap {
    val p = v.params
    val out = IntArray(px * px)
    val half = px / 2f
    for (row in 0 until px) {
        for (col in 0 until px) {
            val dx = (col + 0.5f - half) / half
            val dy = (half - row - 0.5f) / half
            val rr = kotlin.math.hypot(dx, dy)
            if (rr > 1f) continue
            out[row * px + col] = VeilMath.argb(p, rr * FIELD_DEG, dx * FIELD_DEG)
        }
    }
    return Bitmap.createBitmap(out, px, px, Bitmap.Config.ARGB_8888)
}

@Composable
private fun BottomButtons(state: LvState, c: Copy) {
    val enabled = state.selected != null
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            onClick = { LvStore.walkClicked() },
            enabled = enabled,
            modifier = Modifier.weight(1f).height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = LvColors.Accent, contentColor = LvColors.OnAccent),
        ) {
            Icon(painter = painterResource(R.drawable.ic_eye), contentDescription = null, modifier = Modifier.size(22.dp), tint = LvColors.OnAccent)
            Spacer(Modifier.width(8.dp))
            FitText(c.walk, where = "home/c.walk", maxLines = 1, color = LvColors.OnAccent)
        }
        Button(
            onClick = { LvStore.lensClicked() },
            enabled = enabled,
            modifier = Modifier.weight(1f).height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.fillSecondary, contentColor = PicoTheme.colorScheme.labelPrimary),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                FitText(c.lens, where = "home/c.lens", maxLines = 1)
                FitText(c.lensCameraNote, where = "home/c.lensCameraNote", style = LvType.small, maxLines = 1)
            }
        }
    }
}

@Composable
private fun InStageNote(c: Copy) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(c.inWalk, style = LvType.cardName, color = PicoTheme.colorScheme.labelPrimary)
        Spacer(Modifier.height(16.dp))
        Button(onClick = { LvStore.homeClicked() }, colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.fillSecondary, contentColor = PicoTheme.colorScheme.labelPrimary)) {
            Icon(painter = painterResource(R.drawable.ic_home), contentDescription = null, modifier = Modifier.size(20.dp), tint = PicoTheme.colorScheme.labelPrimary)
            Spacer(Modifier.width(8.dp))
            FitText(c.endWalk, where = "home/c.endWalk", maxLines = 1)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// lens controls (P9 / S20 – S22)
// ---------------------------------------------------------------------------------------------

@Composable
private fun LensControls(state: LvState, v: Vision, c: Copy) {
    var now by remember { mutableLongStateOf(LvStore.now()) }
    LaunchedEffect(state.lens.openedAtMs) {
        while (true) {
            now = LvStore.now()
            delay(1_000)
        }
    }
    Row(Modifier.fillMaxSize()) {
        FieldDiagram(v, Modifier.size(160.dp))
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(c.lensSource, style = LvType.small, color = PicoTheme.colorScheme.labelPrimary)
            Spacer(Modifier.height(6.dp))
            SegmentControl(modifier = Modifier.fillMaxWidth().height(52.dp), backgroundColor = PicoTheme.colorScheme.fillTertiary) {
                listOf(LensSource.CAMERA to c.sourceCamera, LensSource.KITCHEN to c.photoKitchen, LensSource.PILLS to c.photoPills, LensSource.STAIRS to c.photoStairs).forEach { (s, label) ->
                    SegmentItem(
                        selected = state.lens.source == s,
                        onClick = { LvStore.lensSource(s) },
                        modifier = Modifier.width(104.dp).height(48.dp),
                        textStyle = PicoTheme.typography.labelLarge,
                        colors =
                            SegmentControlDefaults.colors(
                                itemBackgroundColor = Color.Transparent,
                                itemContentColor = PicoTheme.colorScheme.labelPrimary,
                                selectedItemBackgroundColor = PicoTheme.colorScheme.fillSecondary,
                                selectedItemContentColor = PicoTheme.colorScheme.labelPrimary,
                            ),
                        title = { FitText(text = label, where = "home/label", maxLines = 1, softWrap = false) },
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            SeverityControl(v, c)
            Spacer(Modifier.height(8.dp))
            if (state.lens.source == LensSource.CAMERA) {
                val line = if (state.isEmulator) c.lensNoCamera else if (now - state.lens.openedAtMs > 5_000) c.lensBlackHint else null
                if (line != null) FitText(line, where = "home/line", style = LvType.body, color = PicoTheme.colorScheme.labelPrimary, maxLines = 2)
            }
            FitText(c.lensNote, where = "home/c.lensNote", style = LvType.small, color = PicoTheme.colorScheme.labelPrimary, maxLines = 2)
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HoldButton(c.compare, Modifier.weight(1f)) { LvStore.lensCompare(it) }
                Button(onClick = { LvStore.lensCloseClicked() }, modifier = Modifier.weight(1f).height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.fillSecondary, contentColor = PicoTheme.colorScheme.labelPrimary)) {
                    FitText(c.closeLens, where = "home/c.closeLens", maxLines = 1)
                }
            }
        }
    }
}

/** A press-and-hold button: [onHold] true on press, false on release or cancel (T14 / P7 peek). */
@Composable
fun HoldButton(label: String, modifier: Modifier, container: Color = PicoTheme.colorScheme.fillSecondary, content: Color = PicoTheme.colorScheme.labelPrimary, onHold: (Boolean) -> Unit) {
    val shape = RoundedCornerShape(26.dp)
    Box(
        modifier =
            modifier.height(52.dp)
                .clip(shape)
                .background(container, shape)
                .spatialHoverEffect()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        onHold(true)
                        waitForUpOrCancellation()
                        onHold(false)
                    }
                },
        contentAlignment = Alignment.Center,
    ) {
        FitText(label, where = "home/label", style = PicoTheme.typography.labelLarge, color = content, maxLines = 1)
    }
}

// ---------------------------------------------------------------------------------------------
// overlays: settings (P2 / S05), lens unavailable (S23)
// ---------------------------------------------------------------------------------------------

@Composable
private fun OverlayCard(modifier: Modifier, width: Int, content: @Composable () -> Unit) {
    Box(
        modifier
            .width(width.dp)
            .background(LvColors.PlateInk, RoundedCornerShape(24.dp))
            .border(1.dp, LvColors.PlateRaised, RoundedCornerShape(24.dp))
            .padding(20.dp)
    ) {
        content()
    }
}

@Composable
private fun SettingsCard(state: LvState, c: Copy, modifier: Modifier) {
    OverlayCard(modifier, 560) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.settings, style = LvType.title, color = LvColors.OnPlate, modifier = Modifier.weight(1f))
                Button(onClick = { LvStore.settingsOpen(false) }, size = ButtonDefaults.Small, modifier = Modifier.height(48.dp), colors = ButtonDefaults.buttonColors(containerColor = LvColors.PlateRaised, contentColor = LvColors.OnPlate)) {
                    Icon(painter = painterResource(R.drawable.ic_close), contentDescription = null, modifier = Modifier.size(18.dp), tint = LvColors.OnPlate)
                    Spacer(Modifier.width(6.dp))
                    FitText(c.close, where = "home/c.close", color = LvColors.OnPlate, maxLines = 1)
                }
            }
            Spacer(Modifier.height(14.dp))
            // the long part scrolls; 「退出应用」 stays at the bottom of the card (profile D26)
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.language, style = LvType.body, color = LvColors.OnPlate, modifier = Modifier.weight(1f))
                SegmentControl(modifier = Modifier.width(224.dp).height(52.dp), backgroundColor = LvColors.PlateRaised) {
                    listOf(Lang.ZH to "中文", Lang.EN to "English").forEach { (l, label) ->
                        SegmentItem(
                            selected = state.lang == l,
                            onClick = { LvStore.langSelected(l) },
                            modifier = Modifier.width(108.dp).height(48.dp),
                            textStyle = PicoTheme.typography.labelLarge,
                            colors =
                                SegmentControlDefaults.colors(
                                    itemBackgroundColor = Color.Transparent,
                                    itemContentColor = LvColors.OnPlate,
                                    selectedItemBackgroundColor = LvColors.Accent,
                                    selectedItemContentColor = LvColors.OnAccent,
                                ),
                            title = { FitText(text = label, where = "home/label", maxLines = 1, softWrap = false) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().height(52.dp).clickable { LvStore.showSafetyChanged(!state.showSafety) }, verticalAlignment = Alignment.CenterVertically) {
                Text(c.safetyAgain, style = LvType.body, color = LvColors.OnPlate, modifier = Modifier.weight(1f))
                Switch(checked = state.showSafety, onCheckedChange = { LvStore.showSafetyChanged(it) })
            }
            Spacer(Modifier.height(14.dp))
            Text(c.about, style = LvType.cardName, color = LvColors.OnPlate)
            Spacer(Modifier.height(4.dp))
            Text(c.disclaimer, style = LvType.body, color = LvColors.OnPlate)
            Spacer(Modifier.height(4.dp))
            Text(c.honesty, style = LvType.small, color = LvColors.OnPlateDim)
            Spacer(Modifier.height(8.dp))
            Text(c.references, style = LvType.small, color = LvColors.OnPlateDim)
            c.referenceLines.forEach { Text("· $it", style = LvType.small, color = LvColors.OnPlateDim) }
            Spacer(Modifier.height(6.dp))
            Text("${c.version} ${BuildConfig.VERSION_NAME}", style = LvType.small, color = LvColors.OnPlateDim)
            }
            Spacer(Modifier.height(12.dp))
            QuitRow(state, c)
        }
    }
}

/** Last item of the settings (profile D26): 「退出应用」, then a confirm in place, [取消] [退出]. */
@Composable
private fun QuitRow(state: LvState, c: Copy) {
    if (!state.quitAsked) {
        Button(onClick = { LvStore.quitClicked() }, modifier = Modifier.fillMaxWidth().height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = LvColors.PlateRaised, contentColor = LvColors.OnPlate)) {
            FitText(c.quitApp, where = "home/c.quitApp", color = LvColors.OnPlate, maxLines = 1)
        }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        FitText(c.quitAsk, where = "home/c.quitAsk", style = LvType.cardName, color = LvColors.OnPlate, modifier = Modifier.weight(1f), maxLines = 1)
        Button(onClick = { LvStore.quitCancelled() }, modifier = Modifier.width(112.dp).height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = LvColors.PlateRaised, contentColor = LvColors.OnPlate)) {
            FitText(c.quitNo, where = "home/c.quitNo", color = LvColors.OnPlate, maxLines = 1)
        }
        Button(onClick = { LvStore.quitConfirmed() }, modifier = Modifier.width(112.dp).height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = LvColors.Danger, contentColor = LvColors.OnDanger)) {
            FitText(c.quitYes, where = "home/c.quitYes", color = LvColors.OnDanger, maxLines = 1)
        }
    }
}

@Composable
private fun LensErrorCard(c: Copy, reason: String, modifier: Modifier) {
    OverlayCard(modifier, 440) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).background(LvColors.Danger, CircleShape))
                Spacer(Modifier.width(10.dp))
                Text(c.lensUnavailable, style = LvType.cardName, color = LvColors.OnPlate)
            }
            Spacer(Modifier.height(8.dp))
            FitText(reason, where = "home/reason", style = LvType.small, color = LvColors.OnPlateDim, maxLines = 3)
            Spacer(Modifier.height(14.dp))
            Button(onClick = { LvStore.lensErrorOk() }, modifier = Modifier.height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = LvColors.Accent, contentColor = LvColors.OnAccent)) { Text(c.ok, color = LvColors.OnAccent) }
        }
    }
}
