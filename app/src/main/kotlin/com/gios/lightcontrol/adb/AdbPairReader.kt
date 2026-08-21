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

        val root = rootInActiveWindow ?: return
        try {
            val text = StringBuilder()
            collect(root, text, 0)
            val screen = text.toString()

            if (AdbPairSession.offerScreen(this, screen)) return

            // Not the dialog yet. Best-effort: walk the user there. Never depended on — the read
            // above works just as well when they navigate by hand, and a forked Settings may
            // label these rows differently or not expose them as clickable nodes at all.
            if (!AdbPairCode.looksLikePairingDialog(screen)) advance(root, screen)
        } finally {
            runCatching { root.recycle() }
        }
    }

    override fun onInterrupt() = Unit

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
    private fun advance(root: AccessibilityNodeInfo, screen: String) {
        val target = when {
            screen.contains("Pair device with pairing code", true) -> "Pair device with pairing code"
            screen.contains("Wireless debugging", true) -> "Wireless debugging"
            else -> return
        }

        val now = android.os.SystemClock.elapsedRealtime()
        if (target == lastTarget && now - lastTapAt < TAP_DEBOUNCE_MS) return
        lastTarget = target
        lastTapAt = now

        root.findAccessibilityNodeInfosByText(target)
            ?.firstOrNull { it.isClickable || it.parent?.isClickable == true }
            ?.let { node ->
                val clickable = if (node.isClickable) node else node.parent
                clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
    }

    private companion object {
        const val TAP_DEBOUNCE_MS = 1_500L
    }
}
