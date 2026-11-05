package io.embodit.lowvision.app

/**
 * 「按住看原样」 (T14). User, headset v0.1.5: 「关掉滤镜窗口的时候，就不应该可以点击按住看原样，因为窗口没了」; after
 * v0.1.7: 「还是用 spatial ML 方案，但是按钮逻辑要对」. v0.1.8: the button is in the home window again and is live only
 * while the SpatialML lens picture is really up ([LensState.live]: the session ran, nothing failed since). Every way the
 * lens goes away (「关闭滤镜窗」, the picture's own ×, the frame window's ×, the service dying, a failed run) goes through
 * `LvStore.lensClosed` / `lensFailed`, which reset [LensState]; this is the one rule the button and the store share.
 */
object LensRules {
    /** The button can be pressed (and a press does anything) only while the lens picture is live. */
    fun canHold(lens: LensState): Boolean = lens.open && lens.live && lens.error == null

    /** 「看滤镜窗」: open, not live until the picture is up. */
    fun opened(source: LensSource, atMs: Long): LensState = LensState(open = true, source = source, openedAtMs = atMs)

    /** The picture is up: the button comes alive (null = nothing to change). */
    fun live(lens: LensState): LensState? = if (!lens.open || lens.live) null else lens.copy(live = true)

    /** Closed by any path (button, either window's ×, session lost); the source is kept for the next open. */
    fun closed(lens: LensState): LensState = LensState(source = lens.source)

    /** SpatialML failed to start or broke (S23). */
    fun failed(reason: String): LensState = LensState(error = reason)

    /** The lens after a press ([down] true) or release, or null when nothing changes (nothing goes to the camera). */
    fun hold(lens: LensState, down: Boolean): LensState? = if (!canHold(lens) || lens.comparing == down) null else lens.copy(comparing = down)
}
