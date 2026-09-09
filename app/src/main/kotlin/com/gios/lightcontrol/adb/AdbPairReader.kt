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
        if (!AdbPairSession.armed) {
            wasArmed = false
            return
        }
        // A fresh arming is a fresh scroll budget. Watched here rather than pushed from
        // [AdbPairSession.arm] so the session keeps knowing nothing about the walk.
        if (!wasArmed) {
            wasArmed = true
            scrolls = 0
            lastTarget = null
            lastActedAt = 0L
        }
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
        // **Only Settings windows, and only ever.**
        //
        // The service is declared `packageNames="com.android.settings"`, but that pin filters which
        // *events* reach [onAccessibilityEvent] — it does nothing to `windows` or
        // `rootInActiveWindow`, which return every window on screen. Without this filter the reader
        // offers the app's own ADB screen to [AdbPairSession], and that screen's help text says
        // "Pair device with pairing code" — which [AdbPairCode.looksLikePairingDialog] accepts,
        // finds no six digits, and files a false "could not read the pairing code" report
        // (light-reports#177).
        runCatching {
            windows.forEach { window ->
                window.root?.let { root ->
                    if (root.packageName?.toString() == SETTINGS_PACKAGE) roots += root
                }
            }
        }
        rootInActiveWindow?.let { root ->
            if (root.packageName?.toString() == SETTINGS_PACKAGE) roots += root
        }
        if (roots.isEmpty()) return
        try {
            for (root in roots) {
                val text = StringBuilder()
                collect(root, text, 0)
                if (AdbPairSession.offerScreen(this, text.toString())) return
            }
            // Nothing carried a code, so walk towards the dialog. This used to be described as
            // best-effort and never depended on — which was true of the read and false of the
            // user, who is standing in a Settings screen the app opened for them and reasonably
            // expects the button they pressed to finish the job. [AdbPairWalk] decides the step;
            // this only carries it out. A forked Settings that labels these rows differently, or
            // does not expose them as clickable nodes, still falls through to nothing happening —
            // and the read works just as well when they navigate by hand.
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
    private var lastActedAt = 0L

    /**
     * Scrolls spent in this armed window.
     *
     * Reset when [AdbPairSession.armed] goes up rather than when a screen changes, so the cap is a
     * budget for one attempt at pairing and not one per screen the user wanders through.
     */
    private var scrolls = 0

    private var wasArmed = false

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
     * Carry out one step of [AdbPairWalk] on this window.
     *
     * Returns true when this window was the one to act on, so no other window needs trying —
     * whether the step was a press, a scroll, or a press that found nothing to press.
     *
     * Debounced, because `typeWindowContentChanged` arrives in bursts and [sweep] adds two reads a
     * second on top: undebounced, a row that does not navigate would be hammered several times a
     * second for the whole ninety-second window, and a list would be scrolled off the bottom before
     * a single sweep got to look at what came into view.
     */
    private fun advance(root: AccessibilityNodeInfo, screen: String): Boolean {
        val step = AdbPairWalk.next(screen)
        if (step is AdbPairWalk.Step.Nothing) return false

        val now = android.os.SystemClock.elapsedRealtime()
        val key = step.toString()
        if (key == lastTarget && now - lastActedAt < TAP_DEBOUNCE_MS) return true
        lastTarget = key
        lastActedAt = now

        return when (step) {
            is AdbPairWalk.Step.Tap -> tap(root, step.label)
            AdbPairWalk.Step.Scroll -> scroll(root)
            AdbPairWalk.Step.Nothing -> false
        }
    }

    /**
     * Press the row carrying this label.
     *
     * ### Why the label's parent is not enough
     *
     * A Settings row is a `RecyclerView` item holding a frame, holding a column of title and
     * summary, holding the `TextView` that the text search actually matches. The clickable node is
     * the item — three levels up, not one — so the old `it.isClickable || it.parent?.isClickable`
     * test found the label, found nothing clickable, dispatched no click and reported success
     * anyway. That is the pair of reports that reached the right screen and stopped there
     * (light-reports#311 and #235): the row was on the glass, matched, and never pressed.
     *
     * So the climb goes up as far as [CLICKABLE_DEPTH] and takes the first ancestor that will
     * accept a click. It stops rather than climbing to the root, because the root of a preference
     * screen is itself clickable often enough, and clicking a whole screen presses whatever the
     * framework decides is under the middle of it.
     */
    private fun tap(root: AccessibilityNodeInfo, label: String): Boolean {
        val matches = root.findAccessibilityNodeInfosByText(label) ?: return false
        try {
            for (node in matches) {
                var candidate: AccessibilityNodeInfo? = node
                var climbed = 0
                while (candidate != null && climbed <= CLICKABLE_DEPTH) {
                    if (candidate.isClickable) {
                        return candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }
                    candidate = candidate.parent
                    climbed++
                }
            }
        } finally {
            matches.forEach { runCatching { it.recycle() } }
        }
        return false
    }

    /**
     * Scroll the list this window is built on, one page forward.
     *
     * ### Why looking further had to be a step
     *
     * The app drops the user on Developer options, and on a 360-pixel screen the "Wireless
     * debugging" row is below the fold. A row that is not laid out is not in the flattened text and
     * has no node to click, so the walk had nothing to match and nothing to do — eight reports whose
     * whole diagnostic reads `windows seen while waiting: Developer options`, ninety seconds spent
     * beside a row one swipe away.
     *
     * Bounded twice over: [AdbPairWalk.MAX_SCROLLS] per armed window, and the list itself refuses
     * once it is at the bottom, which ends it earlier on a short screen. The count resets with the
     * arming, not with the screen, so walking back and forth cannot buy more scrolls.
     */
    private fun scroll(root: AccessibilityNodeInfo): Boolean {
        if (scrolls >= AdbPairWalk.MAX_SCROLLS) return false
        val scrollable = findScrollable(root, 0) ?: return false
        return try {
            scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                .also { if (it) scrolls++ }
        } finally {
            if (scrollable != root) runCatching { scrollable.recycle() }
        }
    }

    /** The nearest node that says it can scroll. Depth-first, and the first one wins. */
    private fun findScrollable(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
        node ?: return null
        if (depth > 40) return null
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findScrollable(child, depth + 1)?.let { return it }
            runCatching { child.recycle() }
        }
        return null
    }

    private companion object {
        const val TAP_DEBOUNCE_MS = 1_500L

        /**
         * How far above a matched label to look for something clickable.
         *
         * Three: item, frame, column, label. Not further — the root of a preference screen is
         * clickable often enough, and clicking a screen presses whatever the framework puts under
         * the middle of it.
         */
        const val CLICKABLE_DEPTH = 3

        /**
         * Between sweeps of the window list while armed.
         *
         * Half a second: a pairing code sits on screen for as long as somebody leaves it there, so
         * this only has to be faster than a person's patience, not faster than the dialog.
         */
        const val SWEEP_MS = 500L

        /**
         * The one package this reader may look at, mirroring the service's `packageNames` pin in
         * adb_pair_service.xml. Not redundant with it: that attribute filters events, not the
         * windows [read] is offered, so this is what actually keeps the app's own screen out.
         */
        const val SETTINGS_PACKAGE = "com.android.settings"
    }
}
