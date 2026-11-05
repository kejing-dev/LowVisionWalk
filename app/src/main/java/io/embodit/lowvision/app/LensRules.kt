package io.embodit.lowvision.app

/**
 * 「按住看原样」 (T14, v0.1.6). User, headset v0.1.5: 「关掉滤镜窗口的时候，就不应该可以点击按住看原样，因为窗口没了」,
 * 「或者应该就在那个窗口里点」. The button lives in the lens spot window (the frame around the lens picture), so it
 * goes away with the window; this is the one rule the button and the store share.
 */
object LensRules {
    /** The button is shown (and a press does anything) only while the lens window is open. */
    fun canHold(lens: LensState): Boolean = lens.open && lens.error == null

    /** The lens after a press ([down] true) or release, or null when nothing changes (nothing goes to the camera). */
    fun hold(lens: LensState, down: Boolean): LensState? = if (!canHold(lens) || lens.comparing == down) null else lens.copy(comparing = down)
}
