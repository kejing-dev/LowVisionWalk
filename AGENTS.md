# Low Vision Walk（低视力体验）

PICO OS 6 spatial app (v0.1.3), Kotlin, PICO Spatial SDK 6.1.9 (BOM), package `io.embodit.lowvision`. Factory app
`low-vision`, skeleton D. Design package (binding):
`C:\Users\Admin\Downloads\spatial-app-factory\apps\low-vision\design-package.json` (states S01–S28, panels P1–P10,
entities E1–E6, interactions T1–T18). Built from Life-size Creatures / Spatial Ruler pieces (window + secondary Mixed
Stage, panel pool, head / plane / controller trackers). All 2D UI is SpatialUI + `PicoTheme` (`LvTheme`); no Material
anywhere (factory gate `bin/material-grep.txt`).

The home window is a planar window in the Shared Space (720 × 600 dp, system glass): three condition cards
(glaucoma, macular degeneration, hemianopia), the field diagram, the intro and the severity (5 illustrative steps, shown
as degrees; hemianopia has left / right instead). 「戴上体验」 opens a secondary Mixed Stage: safety card → scan (≤ 5 s,
virtual stand-ins for what is missing) → mark the door → three in-place tasks (find the mug, read the sign, find the
door) with the veil on → optional challenge (walk to the door, a shoe on the side you can't see) → 「家里怎么改」 checklist.

## Structure

```
app/src/main/java/io/embodit/lowvision/
  Main.kt                DefaultWindowContainer (home) + Stage("WalkStage") (walk)
  domain/                pure Kotlin: Vision (diseases, severity table, VeilMath: veil alpha / colour / texture,
                         visibility check), WalkRules (placement, virtual room, door skip, shoe, checklist order,
                         stick rate, SafetyMonitor), Copy (all zh / en copy), Geometry
  app/                   LvState + LvStore: the one state holder (home window, Stage, debug receiver share it)
  platform/              LvLog, Prefs, HeadTracker, PlaneTracker (→ domain Surface), ControllerInput (stick, B / Y, ray)
  ui/home/HomeWindow.kt  home window, field diagram (Compose Canvas), settings overlay, lens controls / S23 card
  ui/walk/               WalkScreen (Stage composable + panel pool), WalkScene (ECS: veil, table + items, sign, door,
                         shoe, colliders, per-frame system), WalkPanels (pill, menu, task list, result, checklist,
                         safety, hint bar, word choices), PanelRig (ported pool), SceneKit (materials, unit quad)
app/src/debug/           DebugCommandReceiver + DebugCommands (debug builds only)
tools/                   dev.sh, make_icons.py (vector drawables + 512 px icon), subset_cjk_font.py, harness/
```

Data flows one way: window taps / Stage gestures / tracker events → `LvStore` → `LvState` → window recomposes,
`WalkScene.render` applies entities. Per-frame work (head pose, panel placement, safety monitor, challenge distances,
thumbstick turn, drag, door ghost) runs in `WalkFrameSystem` → `WalkScene.onFrame`, never through Compose.

## The veil (design E1 / LV_Veil) — how it is drawn

- A 10 × 10 m quad 2 m ahead, locked to the camera by the engine (`AnchorComponent(AnchorTarget.createCameraTarget())`,
  no app-side per-frame transform → no frame of lag), `UnlitMaterial` TRANSPARENT, `setDepthTest(false)`,
  `setDepthWrite(false)`, `SortAsUIElementComponent(-1.0)` so all panels (0.5–0.9 m) draw over it.
- The texture is computed at run time from the design's math (`VeilMath.texture`, 1024², θ = atan(r / D)) per
  disease × level / side and cached — fallback F1 of the design, but continuous math instead of pre-baked files
  (deviation logged: no Shader Graph, see below).
- UV convention: the engine samples v = 1 at the bitmap's top row (Light Planner / Sky Dome finding); `SceneKit.quad`
  is built for it, so the veil and the sign show upright and unmirrored. `testtex 1` puts an asymmetric 「F↑ L R」
  texture on the veil to check (profile D10).

## Build, install, run

```bash
tools/dev.sh build                       # inline JAVA_HOME = primer JDK 21
DEV=emulator-55xx AVD=<avd> tools/dev.sh install
```
Use only the emulator assigned to `low-vision` in the factory `state.json` (never start one yourself, never 5554 /
5556 / 5558). Headset (Swan B3110 `PB324XJGL2090081G`): when the factory `device.note` says the user is using it, only
`adb install -r`, never launch.

## Debug broadcast (QA)

`adb -s <dev> shell am broadcast -a io.embodit.lowvision.DEBUG -p io.embodit.lowvision --es cmd <name> [--es s <arg>] [extras]`
(or `tools/dev.sh cmd <name> [arg]`, `tools/dev.sh am <extras…>`). Every command logs `CMD ok|fail <name>` under tag
`LowVision`; `state` jumps log `CMD reached <id>` when there (opening the Stage, confirming the safety card, waiting
for the scan and marking the door 2.5 m ahead on the way).

| command | effect |
|---|---|
| `state --es id S10 [--es disease glaucoma\|amd\|hemi --ei level 1..5 --es side left\|right]` | jump to a design state |
| `head --ef x 0 --ef z -1.5 [--ef yaw 30]` / `head --es s off` | judge (visibility, shoe, door) with this head position; drawing unchanged |
| `veil --es disease amd --ei level 3` / `veil --es disease hemi --es side right` | change the veil only |
| `seed --ei n 7` | fixed random seed (mug slot, sign word, choices, shoe side) — send before `state` |
| `aim --es target cup\|sign\|door --ef offsetDeg 4 [--ef dirDeg 0]` | move a target to that angle off the current view axis (dirDeg 0 = right, 90 = up) |
| `tap --es s 0\|1\|2\|door` | pick the mug / salt / glasses case / door as if pinched |
| `choose --es s right\|wrong` | pick a word on the choice strip |
| `takeoff` `peek --es s 1\|0` `menu` `next` `checklist` `home` `interrupt` `autooff` | the matching buttons / events |
| `lost --es s 1\|0` | hold / release tracking lost (S27) |
| `stick --es s 0.8,1500` | thumbstick X for ms: turns the selected scene object |
| `ghost --es s 2.5` / `ghost --es s off` | door ghost as if a controller ray hit the floor 2.5 m ahead (S08) |
| `marker --es s 2.0` | the floor marker put down as a trigger / pinch on the floor 2.0 m ahead would (starts placing first, same `handleTap` path) |
| `bring --es s table\|sign\|door` / `hide --es s …` | the task card's scene-object rows (「移到标记处」: onto the marker; without one it starts placing it) |
| `testtex --es s 1\|0` | 「F↑」 orientation test texture on the veil |
| `lang --es s zh\|en` `mute` `lensSource --es s kitchen\|pills\|stairs\|camera` | |
| `lensRepeat --es s 0\|1` | 0 = one filter run per photo (chained after the init pipeline), 1 = every 250 ms (default); next open |
| `quit` / `quit --es s confirm\|cancel` | settings' 「退出应用」 (opens settings + the confirm) / the two answers (D26; confirm ends the app) |
| `grip --es s tasks\|result\|checklist\|safety\|menu --ef dx 0.2 --ef dy 0 --ef dz 0` | a card's grip bar held and moved by (dx, dy, dz) m (same begin / step / end as the spatial drag) |
| `recall` | the pill's 「叫回」: every shown card back in front |
| `dump` `pose` `panels` | `DUMP` / `SCENE` / `ITEM` (visible + angles) / `POSE` / `PANEL` lines |

## QA screenshot list (design states)

Home: S01 (nothing picked), S02 / S03 / S04 (each condition), S05 (settings), S05q (settings with the quit confirm), S25 (English), S23 (lens unavailable).
Walk (send `seed --ei n 7` first): S06, S07, S08 (+ `ghost 2.5`), S19 (emulator room without a table → virtual table),
S09, S10 (amd level 3 + `aim --es target cup --ef offsetDeg 6`: mug half under the patch), S10w, S10v, S11, S12, S13,
S12d, S14 (hemi left: door on the blind side), S16, S14c, S15, S16c, S17, S18, S24, S26, S27.
The veil is a large transparent surface: shoot each state twice and keep the second.

## Panels (v0.1.2, profile D9 / D21 / B19)

- Every panel pose comes from `domain/PanelLayout.kt` `PanelPlace.placeUpright(head, spot)`: head position + level
  heading only, upright, turned about the vertical towards the eye (`FacePanel` has no pitch any more). Spots
  (`PanelSpots`): cards 0.8–0.9 m, centre 10–16° below the level eye line, top edge not above the eye; strips at the
  edge of the view (hint 16° above, word choices 22° and the pill 30° below). `PanelPlaceTest` checks head down 30° /
  up 30° / roll 20°.
- Cards (safety, task list, result, checklist, menu) are placed when they appear and then stay in the room. Each has a
  3D grip bar under it (`WalkScene.buildGrips`, entity `grip:<id>`): the Stage's `detectSpatialDragGesture` moves the card
  in world space, the facing is frozen while held and turns to the viewer on release. No Compose `detectDragGestures`.
  「叫回」 on the pill puts them back in front. The strips (pill, choices, hint) follow the heading lazily (> 25°).
- Controls ≥ 48 dp everywhere (segments 52 / 48, small buttons 48, the settings switch row 52 and clickable).
- Settings end with 「退出应用」 → 「退出低视力体验？」 [取消] [退出]: `LvStore.quitConfirmed` closes the lens and the
  Stage, `finishAndRemoveTask()` on every app task, then the process ends (D26).
- D24: no colliders on detected planes. Marking the door on a wall = pinch the door ghost the controller ray puts there;
  the floor catcher (app-owned virtual floor) is on only while marking. See factory `apps/low-vision/build/reality-audit.md`.

## Lens window (P9, S20–S22, S28) — v0.1.3

- SpatialML secure session (`platform/LensController.kt`), operator-only filter (blur / contrast / fade / patch masks from
  `domain/LensMasks.kt`). Container 1500 × 1125 × 100 units = 0.6 × 0.45 m (1 unit = 0.4 mm, measured); the panel glb
  (`tools/spatialml/make_lens_assets.py`) is 0.59 × 0.4425 m. v0.1.2's 1.2 × 0.9 m panel in a 0.48 × 0.36 m container
  showed only the middle 40 % of the picture.
- Placement (QA #2 q2-2): the system puts the container in the middle of the window that has focus when the session is
  created, 5.7 cm in front of it, and raises a window over it when the window gets focus. So 「透过滤镜看」 first opens the
  lens spot window (`ui/home/LensSpot.kt`, `WindowContainer("LensSpot", placement = Placement.right(home))`, a new tag
  per open) and creates the session once that window is focused (`LvStore.lensSpotReady`, 3 s fallback). The container
  stands beside the home window; all lens controls stay in the home window. Closing the lens closes the spot window;
  closing the spot window from its bar closes the lens.
- Frames (QA #2 q2-1): every pipeline run waits for the one before, the first for the init pipeline (which binds the
  dynamic texture to the panel). v0.1.2 submitted the photo once without waiting, the write came before the binding and
  the window stayed black. Photos now run every 250 ms (`LENS picture up <photo>: 2 runs in … ms`), the camera at 15 Hz
  (`LENS picture up camera`, `LENS camera … Hz`).
- Emulator: the camera source only shows 「没有摄像头画面」 (no camera pipeline without a camera: a session that asked for
  camera frames never finished destroying, see factory `feedback/pico-cli-feedback.md`). Real camera: headset only.

## Floor marker (T17, v0.1.3, QA #2 q2-3 / profile D1)

The task card's scene-object section starts with the marker row: 「放标记」 → the app's floor catcher comes on, a ghost
cross follows the controller ray's floor hit; the trigger (or a gaze pinch) on the floor puts the green marker down
(`MARKER at=…`). 「移到标记处」 on a row moves that object onto the marker (table / sign at working height, out of the head
clearance, turned to the viewer); without a marker it starts placing one. 「跳过」 while marking the door puts a virtual door
1.5 m behind the table (scan-time layout), not along the head's heading. Tests: `MarkerPlacementTest` (four sides, three
head headings). Nothing is placed along the head's heading any more except the first layout at scan time (D9).

## Text overflow (D13, v0.1.3)

Every text with a fixed number of lines is `ui/theme/FitText` and logs `OVERFLOW <where> lines=<n> "<text>"` (tag
`LowVision`, level W) when cut or ellipsised. A tour of all states in zh and en should log none.

## Not built yet / device only

- Lens window with the real camera (S21): look, frame rate (`LENS camera … Hz`), filter strength.
- Device only: veil lag on fast head turns (N1), plane detection quality / TABLE labels (N3), auto take-off thresholds
  (N10), B / Y on the real controller (N9), walking feel.
