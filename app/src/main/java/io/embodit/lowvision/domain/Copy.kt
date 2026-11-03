package io.embodit.lowvision.domain

enum class Lang {
    ZH,
    EN,
}

/** The three main tasks and the optional challenge (design `rules.tasks`). */
enum class Task(val id: String) {
    CUP("cup"),
    SIGN("sign"),
    DOOR("door");

    companion object {
        fun of(id: String?): Task? = entries.firstOrNull { it.id == id?.lowercase() }
    }
}

/** One checklist item: what to do and why (design `rules.checklist`). */
data class CheckItem(val id: String, val what: String, val why: String)

/**
 * All copy of the app in both languages (design `copy`, `rules`, i18n: runtime switch, no restart).
 * The only file with user-visible text (besides the app name in res/values*).
 */
class Copy(val lang: Lang) {
    private val zh = lang == Lang.ZH

    private fun t(zh: String, en: String) = if (this.zh) zh else en

    // ---- home window
    val title = t("低视力体验", "Low Vision Walk")
    val subtitle = t("在你自己家里，用他们的眼睛看一遍你的家", "See your own home through their eyes")
    val walk = t("戴上体验", "Put it on")
    val lens = t("透过滤镜看", "Look through the lens")
    val lensCameraNote = t("需要头显摄像头", "Needs the headset camera")
    val pickFirst = t("先选一种情况", "Pick one first")
    val mute = t("静音", "Mute")
    val unmute = t("开声音", "Unmute")
    val settings = t("设置", "Settings")
    val levelLabel = t("严重程度（示意）", "Severity (illustrative)")
    val sideLabel = t("哪一侧看不见", "Which side is missing")
    val left = t("左", "Left")
    val right = t("右", "Right")
    val notBlack = t("真实的人多半不觉得是黑的，只是那里什么都没有；这里用暗色表示看不见", "Real patients rarely see black — that part is simply missing. Dark here means 'not seen'.")
    val turnHead = t("暗斑跟着头走，不跟眼睛——尽量转头去看", "The patch follows your head, not your eyes — turn your head to look")
    val disclaimer = t("体验示意，不是诊断，也不代表某一位病人的视野。如果你自己的视力有变化，请去看眼科。", "A simulation, not a diagnosis, and not any one patient's vision. If your own sight changes, see an eye doctor.")

    // ---- home window pages (system TabBar, v0.1.4)
    val tabLook = t("病种", "Conditions")
    val tabWalk = t("体验", "Walk")

    fun degrees(v: Vision): String {
        val d = VeilParams.degreesOf(v) ?: return ""
        return when (v.disease) {
            Disease.GLAUCOMA -> t("剩约 $d° 视野", "About $d° of view left")
            Disease.AMD -> t("暗斑约 $d°", "Patch about $d°")
            Disease.HEMIANOPIA -> ""
        }
    }

    fun sideName(s: Side) = if (s == Side.LEFT) t("左盲", "Left blind") else t("右盲", "Right blind")

    // ---- settings
    val language = t("语言", "Language")
    val safetyAgain = t("下次戴上时再显示安全提示", "Show the safety card next time")
    val about = t("关于", "About")
    val close = t("关闭", "Close")
    val version = t("版本", "Version")
    val quitApp = t("退出应用", "Quit app")
    val quitAsk = t("退出低视力体验？", "Quit Low Vision Walk?")
    val quitNo = t("取消", "Cancel")
    val quitYes = t("退出", "Quit")
    val references = t("参考", "References")
    val referenceLines =
        listOf(
            t("Through the Looking Glass（阿尔伯塔大学眼科 × KOVR）：先选一种视力丧失，再戴着做日常小事", "Through the Looking Glass (Univ. of Alberta Ophthalmology × KOVR): pick a vision loss, then do everyday tasks with it"),
            t("VisionSim（Braille Institute）：病种卡 + 严重程度 + 相机滤镜的形态", "VisionSim (Braille Institute): condition cards, severity and a camera filter"),
            t("Empath-D（arXiv 2503.12933）：换成他们的视角做真实任务，再给可做的改进", "Empath-D (arXiv 2503.12933): do real tasks from their view, then get changes you can make"),
            t("病种简介按美国国家眼科研究所（NEI）等公开科普整理，数值为常见描述的示意，未经眼科医生逐项校核", "Condition notes follow public eye-health pages such as the US National Eye Institute; the numbers illustrate common descriptions and were not checked item by item by an eye doctor"),
        )
    val honesty = t("戴上体验里只有遮挡和灰纱（应用拿不到透视画面，做不了模糊）；模糊和褪色只在滤镜窗里。", "The walk only darkens and greys the view (the app cannot read the passthrough, so it cannot blur it); blur and fading are in the lens only.")

    // ---- diseases
    fun name(d: Disease) =
        when (d) {
            Disease.GLAUCOMA -> t("青光眼", "Glaucoma")
            Disease.AMD -> t("黄斑变性", "Macular degeneration")
            Disease.HEMIANOPIA -> t("偏盲", "Hemianopia")
        }

    fun shortName(d: Disease) = if (d == Disease.AMD && !zh) "AMD" else name(d)

    fun line(d: Disease) =
        when (d) {
            Disease.GLAUCOMA -> t("视野从四周往里缩，像从管子里看", "Side vision closes in, like looking through a tube")
            Disease.AMD -> t("正中间一块看不清，四周还在", "A patch right where you look; the sides remain")
            Disease.HEMIANOPIA -> t("两只眼都丢了同一侧的一半", "Both eyes lose the same half")
        }

    fun intro(d: Disease) =
        when (d) {
            Disease.GLAUCOMA ->
                t(
                    "眼压等原因慢慢损伤视神经，最先丢的是周边视野，中心往往到晚期还清楚，所以很多人很久才发现，是常见的致盲眼病之一。最难的事：上下楼梯、过马路、在人群里不撞人——因为看不见旁边和脚下。",
                    "Raised eye pressure and other causes slowly damage the optic nerve. Side vision goes first; the centre often stays sharp until late, so many people don't notice for years. Hardest everyday tasks: stairs, crossing streets, not bumping into people — you can't see what's beside or below you.",
                )
            Disease.AMD ->
                t(
                    "视网膜中心的黄斑区退化，多见于 50 岁以上。正对着看的地方出现模糊发暗的一块，四周反而还能看见。最难的事：认人脸、看药盒和手机上的字、找正前方的东西——东西「就在眼前却看不见」。",
                    "The macula — the centre of the retina — degenerates, mostly after 50. A blurry dark patch sits exactly where you look, while side vision remains. Hardest: faces, medicine labels, phone text, finding things right in front of you.",
                )
            Disease.HEMIANOPIA ->
                t(
                    "常见于中风或脑外伤后，两只眼睛的同一侧视野都看不见（同侧偏盲），分界线在正中附近，有的人正中还保留一小块。很多人不知道自己少了一半，会只吃盘子一侧的饭、撞到盲侧的门框。最难的事：读书时找不到下一行、走路撞盲侧的东西。",
                    "Often after a stroke or brain injury: the same half of the visual field is lost in both eyes, split right down the middle. Many people don't realise half is missing — they eat from one side of the plate and walk into door frames on the blind side.",
                )
        }

    // ---- Stage: safety card (P3)
    val safetyTitle = t("戴上之前", "Before you put it on")
    val safetyLines =
        listOf(
            t("罩会把真实的东西一起遮住——地上清空，别在楼梯边做", "The veil hides real things too — clear the floor and stay away from stairs"),
            t("最好有人在旁边", "Best with someone beside you"),
            t("慢慢走；走快了或弯腰会自动摘下；随时低头点药丸「摘下」，手柄按 B / Y", "Move slowly; walking fast or bending down takes it off; tap 「Take off」 on the pill any time, or press B / Y"),
            t("这是示意，不代表某位病人的视野", "This is an illustration, not any one patient's vision"),
        )
    val gotIt = t("我知道了", "Got it")

    // ---- hint bar (P6)
    val hintScanning = t("看看四周，我来找地面、桌子和墙（最多 5 秒）", "Look around — finding the floor, a table and walls (5 s at most)")
    val hintMarkDoor = t("看向你家的门（或任何一个出口），捏一下标记；手柄指着它扣扳机", "Look at a door or way out and pinch, or point the controller and pull the trigger")
    val skip = t("跳过", "Skip")
    val cancel = t("取消", "Cancel")
    val hintTooNear = t("前面太近，请标一个出口", "Too close ahead — please mark a way out")
    val hintDoorFar = t("选近一点的出口（4 米以内）", "Pick a closer way out (within 4 m)")

    fun hintVirtual(table: Boolean, wall: Boolean): String =
        when {
            table && wall -> t("没找到桌子和墙，先用虚拟的，可以在任务卡「场景物体」里挪", "No table or wall found — using virtual ones; move them under 「Scene objects」 on the task card")
            table -> t("没找到桌子，先用虚拟的，可以在任务卡「场景物体」里挪", "No table found — using a virtual one; move it under 「Scene objects」 on the task card")
            else -> t("没找到墙，先用虚拟立牌，可以在任务卡「场景物体」里挪", "No wall found — using a virtual stand; move it under 「Scene objects」 on the task card")
        }

    fun hintWrongItem(item: Int) = if (item == 1) t("这是盐瓶", "That's the salt shaker") else t("这是眼镜盒", "That's a glasses case")

    val hintNotVisible = t("你其实看不见它——转头把它放进看得见的地方再试", "You can't actually see it — turn your head to bring it into view, then try again")
    val hintSignWrong = t("不对，再仔细看看", "Not quite — look again")
    val hintStepped = t("踩到鞋了——它就在你看不见的那一侧", "You stepped on a shoe — it was on the side you can't see")
    val hintPaused = t("已暂停，点戴上继续", "Paused — tap Put on to continue")
    val hintTrackingLost = t("定位丢了，站着别动", "Tracking lost — stand still")
    val hintAutoOff = t("先摘下了，慢慢来", "Taken off for now — take it slowly")
    val hintChallenge = t("慢慢走，随时可以摘下或放弃", "Walk slowly — you can take it off or give up any time")
    val hintTaskLocked = t("任务进行中，场景物体先锁住", "Scene objects are locked during a task")

    // ---- pill (P4) + menu (S24)
    val takeOff = t("摘下", "Take off")
    val putOn = t("戴上", "Put on")
    val more = t("更多", "More")
    val recall = t("叫回", "Recall")
    val taskList = t("任务列表", "Tasks")
    val changeDisease = t("换病种", "Change")
    val home = t("回首页", "Home")
    val giveUp = t("放弃挑战", "Give up")
    val menuNote = t("任务进行中改档位 / 换病种 / 切任务 = 放弃本任务", "Changing level, condition or task now abandons the current task")
    val challengeName = t("挑战", "Challenge")

    fun taskName(task: Task) =
        when (task) {
            Task.CUP -> t("找杯子", "Find the mug")
            Task.SIGN -> t("读标识", "Read the sign")
            Task.DOOR -> t("找到门口", "Find the door")
        }

    fun taskHow(task: Task) =
        when (task) {
            Task.CUP -> t("桌上三样东西，找到白色马克杯，看着它捏合或手柄指着扣扳机", "Three things on the table — find the white mug: look and pinch, or point and pull the trigger")
            Task.SIGN -> t("墙上有块小牌子，读出上面的字，从三个词里选", "A small sign on the wall — read it and pick the word")
            Task.DOOR -> t("原地转头找到你标的门，指着它捏合或扣扳机", "Turn your head to find the door you marked, then point at it")
        }

    // ---- task list (P5)
    val start = t("开始", "Start")
    val notDone = t("未做", "Not done")
    val current = t("当前", "Now")
    val seeChecklist = t("看清单", "Checklist")
    val sceneObjects = t("场景物体", "Scene objects")
    val stickHint = t("↔ 手柄摇杆左右：转动选中的物体", "↔ Thumbstick left / right: turn the selected object")
    val dragHint = t("任务外可以捏住或扣住扳机拖动物体", "Outside a task, pinch or hold the trigger to drag an object")
    val bringHere = t("移到标记处", "To marker")
    val placeMarker = t("放标记", "Place marker")
    val moveMarker = t("重放标记", "Move marker")
    val markerNone = t("地上还没有标记", "No marker on the floor")
    val markerDown = t("标记已放好", "Marker is down")
    val markerPlacing = t("正在放标记…", "Placing the marker…")
    val hintPlaceMarker = t("手柄指着地面扣扳机，或看着地面捏合，放下标记", "Point at the floor and pull the trigger, or look there and pinch")
    val hide = t("隐藏", "Hide")
    val show = t("显示", "Show")

    fun objectName(o: SceneObject) =
        when (o) {
            SceneObject.TABLE -> t("桌子", "Table")
            SceneObject.SIGN -> t("标识牌", "Sign")
            SceneObject.DOOR -> t("门框", "Door frame")
        }

    fun used(ms: Long) = t("用时 ${clock(ms)}", "Took ${clock(ms)}")

    // ---- result card (P7)
    val peek = t("摘下看看", "Peek without")
    val next = t("下一个", "Next")
    val tryChallenge = t("挑战：戴着走过去", "Challenge: walk there")

    fun mistakes(task: Task?, n: Int): String =
        when (task) {
            Task.SIGN -> t("读错 $n 次", "Misread $n×")
            null -> t("踩到鞋 $n 次", "Stepped on the shoe $n×")
            else -> t("点错 $n 次", "Missed $n×")
        }

    fun takeOffs(n: Int) = t("摘下 $n 次", "Taken off $n×")

    fun best(ms: Long) = t("最好 ${clock(ms)}", "Best ${clock(ms)}")

    val challengeDone = t("走到门口了", "You made it to the door")

    fun insight(d: Disease, task: Task): String =
        when (d) {
            Disease.GLAUCOMA ->
                when (task) {
                    Task.CUP -> t("杯子不在正前方就得一点点转头扫——管状视野里，东西不是「找不到」，是「扫不到」", "Unless the mug is straight ahead you sweep for it — with tunnel vision things aren't lost, they're out of the sweep")
                    Task.SIGN -> t("字本身看得清，难的是先找到牌子在哪", "The letters are sharp; finding the sign at all is the hard part")
                    Task.DOOR -> t("门很大，可视野只有一小圈——病人会觉得门框「突然冒出来」", "The door is big but the view is a small circle — door frames seem to appear out of nowhere")
                }
            Disease.AMD ->
                when (task) {
                    Task.CUP -> t("杯子就在正前方，暗斑正好把它盖住——病人会觉得东西「突然出现」", "The mug was right ahead and the patch covered it — patients feel things suddenly appear")
                    Task.SIGN -> t("越想盯着字看，字越被暗斑盖住——要学会用余光去读", "The harder you stare at the word, the more the patch hides it — they learn to read off-centre")
                    Task.DOOR -> t("大的东西还能用四周看到，所以找门不难，难的是门把手和细节", "Big things still show at the edges, so the door is easy; the handle and details are not")
                }
            Disease.HEMIANOPIA ->
                when (task) {
                    Task.CUP -> t("盲侧的东西不会提醒你它在那儿——不转头就像不存在", "Things on the blind side don't announce themselves — without turning they don't exist")
                    Task.SIGN -> t("读到一半就断了——偏盲的人读书常找不到下一行", "Words get cut in half — people with hemianopia often lose the next line when reading")
                    Task.DOOR -> t("门在盲侧时，你得先知道要转头——很多人撞的就是这一侧的门框", "With the door on the blind side you have to know to turn — that's the frame people walk into")
                }
        }

    val challengeInsight = t("鞋就在你看不见的那一侧——家里地上的东西，对他们就是陷阱", "The shoe was on the side you can't see — anything on the floor is a trap for them")

    // ---- checklist (P8)
    val checklistTitle = t("家里怎么改", "What to change at home")

    fun checklistSub(v: Vision): String =
        if (v.disease == Disease.HEMIANOPIA) t("按你刚体验的 ${name(v.disease)} · ${sideName(v.side)} 排序", "Ordered for the ${name(v.disease)} · ${sideName(v.side)} you just tried")
        else t("按你刚体验的 ${name(v.disease)} · ${degrees(v)} 排序", "Ordered for the ${name(v.disease)} · ${degrees(v)} you just tried")

    val againOther = t("换一种病再走", "Try another")

    fun checkItem(id: String): CheckItem =
        when (id) {
            "light" -> CheckItem(id, t("照明加亮、均匀：同一房间不留暗角，夜间走廊留小夜灯", "Bright, even light: no dark corners; a night light in the hallway"), t("视力差的人在暗处最先出事", "Low vision fails first in the dark"))
            "path" -> CheckItem(id, t("通道清空：门口、床边、去卫生间的路上不放鞋、电线、小凳", "Clear paths: no shoes, cables or stools by the door, the bed or the way to the bathroom"), t("你刚才差点踩到鞋", "You nearly stepped on a shoe just now"))
            "fixed" -> CheckItem(id, t("东西固定位置：杯子、药、钥匙放固定地方，用完放回", "Fixed places: mug, medicine, keys always in the same spot"), t("找东西靠记忆，不靠看", "Finding things by memory, not by sight"))
            "contrast" -> CheckItem(id, t("提高对比度：白杯子配深色桌垫、深色门把手配浅色门", "More contrast: a white mug on a dark mat, a dark handle on a light door"), t("对比强的东西在残余视野里也能跳出来", "High contrast pops out even in what vision is left"))
            "labels" -> CheckItem(id, t("大字高对比标签：药盒、开关、常用柜子贴大字（≥ 2 cm）", "Large high-contrast labels on medicine, switches and cupboards (letters ≥ 2 cm)"), t("黑底白字或白底黑字，比小字好认得多", "White on black or black on white beats small print"))
            "glaucoma" -> CheckItem(id, t("楼梯和门槛重点处理：边缘对比胶带 + 扶手连续不断", "Stairs and thresholds first: contrast tape on edges, a handrail with no gaps"), t("管状视野看不见脚下", "Tunnel vision can't see the feet"))
            "amd" -> CheckItem(id, t("放大镜和语音：药盒用大字标签 + 手机开大字 / 读屏；常用东西放在视线一侧", "Magnifier and voice: large-print medicine labels, large text or a screen reader on the phone; keep things to one side of the gaze"), t("正前方恰好是看不见的地方", "Straight ahead is exactly what's missing"))
            else -> CheckItem("hemianopia", t("东西和人都放在「看得见的那一侧」：餐盘转一转，门框盲侧贴醒目色条", "Keep things and people on the seeing side: turn the plate, mark the blind side of door frames with a bright strip"), t("盲侧的东西不会提醒你它在那儿", "Things on the blind side don't announce themselves"))
        }

    // ---- sign words (E3)
    val signWords: List<String> =
        if (zh) listOf("出口", "洗手间", "厨房", "小心台阶", "每日两次", "饭后服用", "热水", "储物间", "卧室", "请勿触碰", "电梯", "地滑")
        else listOf("EXIT", "TOILET", "KITCHEN", "MIND THE STEP", "TWICE DAILY", "AFTER MEALS", "HOT WATER", "STORAGE", "BEDROOM", "DO NOT TOUCH", "LIFT", "WET FLOOR")

    // ---- lens (P9)
    val lensTitle = t("滤镜窗", "Lens")
    val lensSource = t("画面来源", "Picture")
    val sourceCamera = t("摄像头", "Camera")
    val photoKitchen = t("厨房", "Kitchen")
    val photoPills = t("药盒", "Pills")
    val photoStairs = t("楼梯", "Stairs")
    val compare = t("按住看原样", "Hold to see normal")
    val closeLens = t("关闭滤镜窗", "Close the lens")
    val lensNote = t("滤镜窗像一块实时监视屏，显示你正前方被处理后的样子；不能戴着走", "The lens works like a live monitor showing what's in front of you, processed. You can't walk with it.")
    val lensBlackHint = t("窗里一直是黑的？换示例照片看看", "Lens stays black? Try a sample photo.")
    val lensNoCamera = t("这台设备没有摄像头画面，换示例照片看看", "No camera picture on this device — try a sample photo")
    val lensUnavailable = t("这台设备上滤镜窗开不了", "The lens can't open on this device")
    val ok = t("好", "OK")
    val lensFromWalk = t("透过滤镜看", "Lens")

    companion object {
        fun clock(ms: Long): String {
            val s = (ms / 1000).coerceAtLeast(0)
            return "%d:%02d".format(s / 60, s % 60)
        }
    }
}
