package com.gios.lightcontrol.color

import android.os.IBinder
import com.gios.lightcontrol.ColorRule
import java.util.concurrent.ConcurrentHashMap

/**
 * What other apps are asking for, right now.
 *
 * ## Why this exists
 *
 * Before it, an app that wanted to show a photograph had to hold `WRITE_SECURE_SETTINGS` and
 * write the daltonizer itself. Five apps did, which is five `pm grant` lines to run from a
 * computer, five grants that die on the next reinstall, and five writers on two settings — and
 * two writers with different opinions do not average out, they alternate. That is what
 * `ColorRule.Passthrough` was invented to stop: this app declining to have an opinion so the
 * other one could win.
 *
 * This is the other way round. The app says what it wants, this app writes it, and there is one
 * grant on the phone.
 *
 * ## Why nothing here checks who is in front
 *
 * It looks like the missing half of the security story and it is not. [ColorMode.applyFor] is
 * only ever called with the package the service believes is in front, so a rule is only ever
 * consulted for the foreground app — the gate is the shape of the caller, not a test in here. A
 * background app's request is stored and never read until that app comes forward, at which point
 * it is exactly what should happen.
 *
 * Worth stating plainly because the alternative is tempting: a `front` field in here, kept in
 * step with `ControlService.foreground` by hand. Two copies of the same fact, one of them a
 * static, updated from a different file. It would be wrong within a release.
 *
 * ## Keyed by token, not by package
 *
 * The obvious map is package to rule. The reason it is not is death: a request has to end when
 * the process that made it stops existing, and the only thing that can be linked to a death is
 * the binder the caller passed. Keying by that binder also makes two processes of one app behave
 * — each holds its own request, and one dying does not silently drop the other's.
 */
object ColorRequests {

    private data class Hold(val pkg: String, val rule: ColorRule?)

    private val holds = ConcurrentHashMap<IBinder, Hold>()

    /**
     * Called after any change, so the front app's rule is re-stated immediately rather than at
     * the next window event. Set by [com.gios.lightcontrol.keys.ControlService] while it is bound
     * and cleared on unbind, which is also what makes this whole feature inert with no service:
     * a request is still recorded, and nothing acts on it.
     */
    @Volatile
    var onChanged: (() -> Unit)? = null

    /**
     * Record what [pkg] is asking for. [rule] of null clears the request but keeps the token, so
     * an app can go quiet and speak again without rebinding.
     *
     * [pkg] comes from the kernel by way of the calling uid, never from anything the caller
     * wrote. See [ColorService].
     */
    fun set(token: IBinder, pkg: String, rule: ColorRule?) {
        val previous = holds.put(token, Hold(pkg, rule))
        if (previous == null) {
            // Linked once per token, not once per call. A second linkToDeath on the same binder
            // registers a second recipient, and an app that re-states its request on every screen
            // would accumulate them for the life of the process.
            runCatching { token.linkToDeath({ drop(token) }, 0) }
                // A binder that is already dead throws rather than calling back. Nothing to hold.
                .onFailure { holds.remove(token) }
        }
        if (previous?.rule != rule) onChanged?.invoke()
    }

    /** Forget a token: the app unbound tidily, or its process died. */
    fun drop(token: IBinder) {
        if (holds.remove(token) != null) onChanged?.invoke()
    }

    /**
     * What [pkg] is asking for, or null if it is not asking.
     *
     * A package with more than one live token is resolved to the most specific opinion rather
     * than to whichever entry the map happened to yield: any request beats no request. Two
     * processes of one app asking for *different* things is not a case worth arbitrating — it is
     * an app arguing with itself, and either answer is as defensible as the other.
     */
    fun ruleFor(pkg: String): ColorRule? =
        holds.values.firstNotNullOfOrNull { hold ->
            hold.rule.takeIf { hold.pkg == pkg }
        }

    /** Every package asking for something, for the readout on the Color screen. */
    fun asking(): Map<String, ColorRule> =
        holds.values.mapNotNull { hold -> hold.rule?.let { hold.pkg to it } }.toMap()

    /** Test seam. Nothing in the app clears these; only a process death does. */
    internal fun clearForTest() {
        holds.clear()
        onChanged = null
    }

    // ------------------------------------------------------------------ the wire

    /**
     * The int a caller sends, turned into a rule.
     *
     * Deliberately narrow. An unrecognised state is a clear rather than a refusal: the states are
     * the whole vocabulary, a caller sending something else is a newer library talking to an
     * older BrightControl, and the safe reading of "I do not understand what you want" is to want
     * nothing. Forcing a colour nobody asked for would be the worse guess.
     */
    fun ruleOf(state: Int): ColorRule? = when (state) {
        STATE_COLOUR -> ColorRule.Color
        STATE_MONO -> ColorRule.Mono
        else -> null
    }

    const val STATE_CLEAR = 0
    const val STATE_COLOUR = 1
    const val STATE_MONO = 2

    /** BrightControl is driving the screen. The caller must not write the settings. */
    const val SERVING = 1

    /**
     * Present and unable to act: no `WRITE_SECURE_SETTINGS`, or the user has left the colour
     * switch off. The request is kept, so this becomes [SERVING] the moment that changes without
     * the caller asking again.
     */
    const val INERT = 0

    /** Refused. The caller could not be identified. */
    const val REFUSED = -1

    /** The revision of `IColorProvider` this app speaks. */
    const val PROTOCOL = 1
}
