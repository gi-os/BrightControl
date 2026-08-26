package com.gios.lightcontrol.adb

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Reads the six-digit pairing code off the system's Wireless debugging dialog, so the user
 * never has to carry it anywhere.
 *
 * This exists because the pairing session dies the instant Settings pauses — see
 * [AdbPairSession] for the full list of routes that closes off. Reading the dialog in place is
 * the only one left, and it happens to be the least work for the user: one button, no typing.
 *
 * ## Deliberately not part of ControlService
 *
 * [com.gios.lightcontrol.keys.ControlService] is declared `canRetrieveWindowContent="false"` on
 * purpose, and that promise is worth keeping. This is a separate service, declared with
 * `packageNames="com.android.settings"`, so it is structurally incapable of seeing any other
 * app — not by policy, by registration. On top of that it does nothing at all unless
 * [AdbPairSession.armed] is true, which lasts for one 90-second window that the user starts by
 * tapping a button.
 *
 * Once pairing is done the service has no further purpose, and the ADB screen offers to switch
 * it back off.
 */
// recycle() is a no-op from API 33 and deprecated with it, but minSdk here is 29 and on those
// builds the node pool is real. Called, and the warning suppressed, rather than dropped.
@Suppress("DEPRECATION")
class AdbPairReader : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!AdbPairSession.armed) return
        // Events are the fast path, not the only one. See [sweep].
        scheduleSweep()

        read()
    }

    /**
     * One pass over every window, offering each to [AdbPairSession].
     *
     * Called from an accessibility event and from [sweep]; both are cheap and neither is trusted to
     * be the one that happens.
     */
    private fun read() {
        if (!AdbPairSession.armed) return
        // **Every window, not just the active one.**
        //
        // light-reports#65 and #68 carried the text this read, and it was the *Wireless debugging
        // list* — "Use wireless debugging", "Device name", "Pair device with QR code" — with no code
        // on it, because a dialog is its own window and `rootInActiveWindow` was handing back the
        // activity behind it. So the one window that has ever contained the six digits was the one
        // window never being looked at, and the failure was reported against the wrong screen.
        //
        // Each window is offered on its own rather than concatenated, because the strongest signal
        // is a line that is *exactly* six digits, and joining the dialog to the list behind it
        // surrounds those digits with a screenful of other numbers.
        val roots = mutableListOf<AccessibilityNodeInfo>()
        runCatching { windows.forEach { window -> window.root?.let { roots += it } } }
        rootInActiveWindow?.let { roots += it }
        if (roots.isEmpty()) return
        try {
            for (root in roots) {
                val text = StringBuilder()
                collect(root, text, 0)
                if (AdbPairSession.offerScreen(this, text.toString())) return
            }
            // Nothing carried a code. Best-effort: walk the user towards the dialog from whichever
            // window looks like the list. Never depended on — the read above works just as well
            // when they navigate by hand, and a forked Settings may label these rows differently
            // or not expose them as clickable nodes at all.
            for (root in roots) {
                val text = StringBuilder()
                collect(root, text, 0)
                val screen = text.toString()
                if (!AdbPairCode.looksLikePairingDialog(screen)) {
                    if (advance(root, screen)) return
                }
            }
        } finally {
            roots.forEach { runCatching { it.recycle() } }
        }
    }

    override fun onInterrupt() = Unit

    /**
     * Look at every window on a timer, as well as when an event arrives.
     *
     * ### Why events are not enough
     *
     * "Sometimes it never sees the pair with device code." Reading on events means reading when the
     * framework says something changed — and a dialog that arrives while the app is settling, or
     * whose window announces itself with an event type this service does not subscribe to, produces
     * no read at all. The window is sitting there with six digits on it and nobody looks again until
     * something else moves.
     *
     * So while armed, this sweeps every half second regardless. It costs a walk of the window list
     * on a phone that is doing nothing else, and it stops the moment a code is found or the ninety
     * seconds run out — the same two conditions that disarm the reader.
     */
    private fun scheduleSweep() {
        if (sweepScheduled) return
        sweepScheduled = true
        sweeper.post(sweep)
    }

    private val sweeper = android.os.Handler(android.os.Looper.getMainLooper())

    private var sweepScheduled = false

    private val sweep = object : Runnable {
        override fun run() {
            if (!AdbPairSession.armed) {
                sweepScheduled = false
                return
            }
            read()
            // Re-armed rather than looped forever: the check above is what ends it.
            sweeper.postDelayed(this, SWEEP_MS)
        }
    }

    private var lastTarget: String? = null
    private var lastTapAt = 0L

    /**
     * Flatten a window's text, one node per line. Line-per-node matters: [AdbPairSession] looks
     * for a line that is exactly six digits, which is a far stronger signal than six digits
     * loose in a paragraph.
     */
    private fun collect(node: AccessibilityNodeInfo?, out: StringBuilder, depth: Int) {
        node ?: return
        if (depth > 40) return
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { out.append(it).append('\n') }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let {
            out.append(it).append('\n')
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collect(child, out, depth + 1)
            runCatching { child.recycle() }
        }
    }

    /**
     * Tap the row that gets us one screen closer to the pairing dialog.
     *
     * Debounced, because `typeWindowContentChanged` arrives in bursts: if a tap does not
     * navigate — a fork that labels the row the same but handles it elsewhere — an undebounced
     * version would hammer it several times a second for the whole ninety-second window.
     */
    private fun advance(root: AccessibilityNodeInfo, screen: String): Boolean {
        val target = when {
            screen.contains("Pair device with pairing code", true) -> "Pair device with pairing code"
            screen.contains("Wireless debugging", true) -> "Wireless debugging"
            else -> return false
        }

        val now = android.os.SystemClock.elapsedRealtime()
        if (target == lastTarget && now - lastTapAt < TAP_DEBOUNCE_MS) return true
        lastTarget = target
        lastTapAt = now

        val tapped = root.findAccessibilityNodeInfosByText(target)
            ?.firstOrNull { it.isClickable || it.parent?.isClickable == true }
            ?.let { node ->
                val clickable = if (node.isClickable) node else node.parent
                clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        // True either way: this window was the one to act on, so no other window needs trying.
        return tapped != null
    }

    private companion object {
        const val TAP_DEBOUNCE_MS = 1_500L

        /**
         * Between sweeps of the window list while armed.
         *
         * Half a second: a pairing code sits on screen for as long as somebody leaves it there, so
         * this only has to be faster than a person's patience, not faster than the dialog.
         */
        const val SWEEP_MS = 500L
    }
}
