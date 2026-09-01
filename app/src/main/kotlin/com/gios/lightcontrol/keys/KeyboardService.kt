package com.gios.lightcontrol.keys

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.gios.lightcontrol.Prefs

/**
 * The keyboard-replace service: when a LightOS app puts its own keyboard up, this service puts
 * *ours* over it and types into the field the user actually tapped.
 *
 * ### What problem this solves
 *
 * LightOS's built-in tools draw their own keyboard in-app — they never route through the Android
 * input method, which is why "set your keyboard as default" does nothing there. There is nothing
 * to *select*: the app paints its own keys. The only window in from outside is the accessibility
 * tree, and this service is that window.
 *
 * ### The two modes (and the fallback that was asked for)
 *
 *  - **Inject (default).** On an editable field gaining focus inside a LightOS tool, the overlay
 *    band appears. Every key press types into the focused field through
 *    `ACTION_SET_SELECTION` + `ACTION_APPEND_TEXT` (API 32+) with an `ACTION_SET_TEXT` fallback
 *    for older Android. No screenshots, no OCR — the field announces itself as editable and we
 *    write to it.
 *
 *  - **Simulate (fallback).** Some fields refuse text actions (password prompts, custom editors).
 *    Instead of injecting, the service finds the *underlying* LightOS keyboard's key node — the
 *    same node the app draws and reads its taps from — and `ACTION_CLICK`s it, i.e. simulates the
 *    button press below to activate the keyboard. `Prefs.keyboardReplaceSimulate` switches modes;
 *    on a miss in inject mode it falls through to simulate automatically.
 *
 * Both can also be driven by hand: `Action.Keyboard` (bindable to any key, see
 * [toggleFromButton]) pops the band up over whatever LightOS app is in front, so the feature
 * works even when focus detection misses the field.
 *
 * ### Why a second service
 *
 * [ControlService] keeps `canRetrieveWindowContent="false"` as a hard privacy line — it never
 * reads a word of what's on screen. This feature *requires* reading which field is focused, so it
 * gets its own service with `canRetrieveWindowContent="true"`, exactly as the ADB pairing reader
 * does. They share a process and neither can take the other down.
 *
 * ### Scope
 *
 * LightOS tools only. The gate is a prefix check on the window's package (`com.lightos`,
 * `com.thelightphone.`), the same prefixes [com.gios.lightcontrol.Policy] treats as Light's own
 * software. The service never reads a node whose window belongs to anything else, and everything
 * it does is gated on `Prefs.keyboardReplace`.
 */
class KeyboardService : AccessibilityService() {

    companion object {
        /** The live instance, so [com.gios.lightcontrol.Action.Keyboard] can reach it. */
        @Volatile
        private var instance: KeyboardService? = null

        /** Light's own software — the tools this feature replaces the keyboard in. */
        private val lightOsPrefixes = listOf("com.lightos", "com.thelightphone.")

        /**
         * Documented public constants the SDK stubs omit. API 32 added
         * `AccessibilityNodeInfo.ACTION_APPEND_TEXT = 0x00400000` and its argument key
         * `ACTION_ARGUMENT_APPEND_TEXT_CHARSEQUENCE`; both are hardcoded platform values.
         */
        private const val ACTION_APPEND_TEXT = 0x00400000
        private const val ACTION_ARGUMENT_APPEND_TEXT_CHARSEQUENCE = "ACTION_ARGUMENT_APPEND_TEXT_CHARSEQUENCE"

        fun isLightOs(pkg: String?): Boolean =
            pkg != null && lightOsPrefixes.any { pkg.startsWith(it) }

        /** Called from [com.gios.lightcontrol.Action.Keyboard]'s dispatch. */
        @JvmStatic
        fun toggleFromButton() {
            instance?.toggle()
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private var overlay: KeyboardOverlay? = null

    /** The app the window-state events belong to. Null until the first one arrives. */
    private var front: String? = null

    /** The editable node we are typing into, refreshed on every event and re-resolved on demand. */
    private var editable: AccessibilityNodeInfo? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        prefs = Prefs(this)
        overlay = KeyboardOverlay(this).apply {
            onKey = { ch -> press(ch) }
            onBackspace = { backspace() }
            onEnter = { enter() }
            onHide = { hide() }
        }
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        hide()
        overlay = null
        // Only if it is still this instance. A fast toggle of the service lands the old
        // instance's unbind after the new one's onServiceConnected, and clearing this
        // unconditionally here left the keyboard button reaching nothing.
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onInterrupt() = hide()

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!prefs.keyboardReplace) return
        when (event.eventType) {
            // The authoritative "which app is in front" signal, and the one place a keyboard
            // window shows up as a package of its own. Only LightOS tools get the band.
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                front = event.packageName?.toString()
                if (!isLightOs(front)) {
                    hide()
                    return
                }
                refresh()
            }
            // An editable field took focus inside a LightOS tool: that is the signal to type.
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                val pkg = event.packageName?.toString()
                if (!isLightOs(pkg)) {
                    hide()
                    return
                }
                val node = event.source
                if (node != null && isEditable(node)) {
                    editable?.recycle()
                    editable = node
                    show()
                } else {
                    refresh()
                }
            }
            // The keyboard itself is a window; when it comes or goes, re-ask what is focused.
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> refresh()
        }
    }

    // ------------------------------------------------------------------ show / hide

    private fun show() {
        if (!prefs.keyboardReplace) return
        overlay?.show()
    }

    private fun hide() {
        editable?.recycle()
        editable = null
        overlay?.hide()
    }

    /** The manual trigger — pop the band over whatever LightOS app is in front. */
    fun toggle() {
        if (!prefs.keyboardReplace) return
        if (overlay?.showing == true) {
            hide()
        } else if (isLightOs(front)) {
            refresh()
            show()
        }
    }

    /**
     * Re-resolve the focused field from the live tree. Called whenever focus may have moved —
     * a window change, a key that dismissed the keyboard, a tap elsewhere.
     */
    private fun refresh() {
        if (!isLightOs(front)) {
            hide()
            return
        }
        val focused = resolveEditable()
        editable?.recycle()
        editable = focused
        if (focused != null) {
            show()
        } else {
            // The keyboard window itself is in front (its own window-state event), so give the
            // field a beat to re-announce itself before taking the band down.
            handler.postDelayed({ if (resolveEditable() == null) hide() }, 120)
        }
    }

    private fun resolveEditable(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        // The field that has input focus is the one a keyboard would type into.
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && isEditable(focused)) return focused
        // Some in-app keyboards take focus for themselves; walk for the nearest editable child.
        return findEditable(root)
    }

    private fun findEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (isEditable(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val hit = findEditable(child)
            if (hit != null) return hit
            child.recycle()
        }
        return null
    }

    private fun isEditable(node: AccessibilityNodeInfo): Boolean =
        node.isEditable ||
            node.isPassword ||
            node.className?.toString()?.contains("EditText") == true

    // ------------------------------------------------------------------ input

    private fun currentEditable(): AccessibilityNodeInfo? {
        val cached = editable
        if (cached != null && cached.isVisibleToUser) return cached
        val resolved = resolveEditable()
        editable?.recycle()
        editable = resolved
        return resolved
    }

    /** One character, or a whole word, from the band. */
    private fun press(text: String) {
        val node = currentEditable()
        if (node != null && !prefs.keyboardReplaceSimulate) {
            if (inject(node, text)) return
        }
        // Inject refused or switched off: simulate the press on the underlying keyboard.
        simulate(text)
    }

    private fun inject(node: AccessibilityNodeInfo, text: String): Boolean {
        val current = node.text?.toString().orEmpty()
        val end = current.length
        node.performAction(
            AccessibilityNodeInfo.ACTION_SET_SELECTION,
            Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, end)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
            },
        )
        // ACTION_APPEND_TEXT is documented API 32 but the SDK stubs omit it, so the two values
        // (the action id and the argument key) are the platform constants written out.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            node.performAction(
                ACTION_APPEND_TEXT,
                Bundle().apply {
                    putCharSequence(ACTION_ARGUMENT_APPEND_TEXT_CHARSEQUENCE, text)
                },
            )
        } else {
            node.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        current + text,
                    )
                },
            )
        }
    }

    private fun backspace() {
        val node = currentEditable()
        if (node == null) {
            simulate("BACKSPACE")
            return
        }
        val current = node.text?.toString().orEmpty()
        if (current.isEmpty()) {
            simulate("BACKSPACE")
            return
        }
        val shortened = current.dropLast(1)
        node.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, shortened)
            },
        )
    }

    private fun enter() {
        val node = currentEditable()
        if (node == null || prefs.keyboardReplaceSimulate) {
            simulate("ENTER")
            return
        }
        inject(node, "\n")
    }

    /**
     * The fallback that was asked for: instead of writing text, find the underlying LightOS
     * keyboard's key for this press and click it. The app sees its own key being pressed, so it
     * goes through the same path as a finger — which is exactly what works when the field refuses
     * text actions. [mark] is the label we are looking for ("Q", "BACKSPACE", "ENTER", a space).
     */
    private fun simulate(mark: String) {
        val root = rootInActiveWindow ?: return
        val need = if (mark == " ") "SPACE" else mark
        val nodes = buildList {
            addAll(runCatching { root.findAccessibilityNodeInfosByText(need) }.getOrNull().orEmpty())
            if (mark.length == 1) {
                // One-letter keys sometimes render as themselves; try the raw character too.
                addAll(runCatching { root.findAccessibilityNodeInfosByText(mark) }.getOrNull().orEmpty())
            }
        }
        // Prefer a clickable node in the bottom half of the screen — that is where a keyboard's
        // keys live. First match of the label, clickable, low on the panel.
        val rect = Rect()
        val screenH = resources.displayMetrics.heightPixels
        val hit = nodes.firstOrNull { n ->
            n.isClickable && n.getBoundsInScreen(rect).let { rect.bottom > screenH / 2 }
        } ?: nodes.firstOrNull { it.isClickable }
        if (hit != null) {
            hit.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return
        }
        // No labelled key found — nothing more honest to do than leave the band and let the
        // LightOS keyboard under it take over, so the press is not silently eaten.
        hide()
    }
}
