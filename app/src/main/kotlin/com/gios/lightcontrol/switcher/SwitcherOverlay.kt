package com.gios.lightcontrol.switcher

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.gios.lightcontrol.Action
import com.gios.lightcontrol.R
import com.gios.lightcontrol.lock.LightType

/**
 * The app switcher LightOS does not have, drawn by the service that owns the home button.
 *
 * ### Why a window rather than an activity
 *
 * The same reason the lock face is one (see `lock/LockOverlay`), plus one of its own. A
 * `TYPE_ACCESSIBILITY_OVERLAY` sits at layer 31, above anything an app or LightOS can put on
 * screen, so this appears over whatever you were looking at without starting anything, without
 * the background-activity-start appop, and with no task of its own to get stuck in. It also
 * cannot disturb the list it is showing: an activity would push itself onto the recents order it
 * exists to display, so the switcher would always be the last thing you used.
 *
 * `performGlobalAction(GLOBAL_ACTION_RECENTS)` is deliberately not what the home button does.
 * That asks SystemUI for a recents screen and this phone appears to ship no such screen: the call
 * returns true for "injected" and nothing appears, which is the worst answer available — a gesture
 * that reports success and does nothing.
 *
 * It is on the screen anyway, as **a button at the bottom rather than the gesture itself**, because
 * "appears to ship no such screen" is a conclusion drawn from one firmware and the cost of being
 * wrong about it is a phone that hides a working switcher. The difference is that a button can be
 * held to its answer: the service asks, waits, and if nothing came forward it puts this list back
 * with a line saying so. A dead button that admits it is dead costs a tap; a missing one costs the
 * feature.
 *
 * ### What it takes
 *
 * Keys, while it is up — the wheel moves the selection, a wheel click opens what is under it, a
 * held click opens that app's page in Settings, home puts it away. Those are read by the service (`ControlService.onSwitcherKey`) and not
 * here: this window is `FLAG_NOT_FOCUSABLE`, so it never holds key focus and can never be the
 * reason a key stops arriving somewhere. Touches it does take: a row opens that app, anywhere
 * else closes it.
 *
 * It also closes itself after [IDLE_MS] with nothing pressed. A full-screen window that can
 * outlive the user's interest in it is one you cannot get out of, and this one is bound to the
 * home button, which is the key that must never be the way a phone gets stuck.
 */
class SwitcherOverlay(private val context: Context) {

    /**
     * One row: the package, the name a person recognizes it by, and whether it is the pinned Home
     * rather than a recent app.
     *
     * [home] and [action] are both decided by `Recents`, which holds the preference and the
     * binding — this window draws the answer and does not have an opinion about it. A home row
     * carries the [Action] to perform instead of a package to launch, because home is not always a
     * package: `DefaultHome` is a global action and reaches whatever the system's choice is. [pkg]
     * on a home row is only what its App info hold points at, and may be blank when nothing
     * resolved.
     */
    data class Entry(
        val pkg: String,
        val label: String,
        val home: Boolean = false,
        val action: Action? = null,
    )

    private val handler = Handler(Looper.getMainLooper())
    private val type = LightType(context)

    private var root: FrameLayout? = null
    private var content: View? = null
    private var column: LinearLayout? = null
    private var rows: List<TextView> = emptyList()
    private var status: TextView? = null
    private var entries: List<Entry> = emptyList()
    private var index = 0

    /**
     * Each app's own icon, by package, kept across shows.
     *
     * Loading one is a resource read, and this list is built on the main thread in the moment
     * between deciding to leave an app and leaving it -- the one place in this app where a few
     * milliseconds are the whole feature. So they are read once and held. The map is dropped
     * wholesale rather than aged out: an entry is a small bitmap, [CACHE] of them is nothing, and
     * an eviction policy would be more code than the thing it manages.
     */
    private val icons = HashMap<String, Drawable?>()

    /**
     * Told which row was chosen. The service does the launching — it owns the throttle.
     *
     * The whole [Entry] rather than a package, because the pinned Home row is an [Action] and not
     * always an app: handing back a package would mean this window deciding what "home" means, and
     * that decision belongs to the home button's own binding.
     */
    var onPick: ((Entry) -> Unit)? = null

    /** The bottom button was tapped: ask the system for its own recents. */
    var onSystem: (() -> Unit)? = null

    /**
     * Told which package was held down on: the system's App info page, for that app.
     *
     * This is where the hold used to force stop the app over adb. App info has AOSP's own Force
     * stop button in it, which needs no shell, no pairing and no permission — so the hold that
     * could only sometimes do the real thing was replaced by the one that always can. The service
     * starts the activity; this window only says which app the thumb was on.
     */
    var onAppInfo: ((String) -> Unit)? = null

    /**
     * Told whenever this window goes up or comes down.
     *
     * Added for the back strip, which is a *lower* window: this one is a full-screen
     * `TYPE_ACCESSIBILITY_OVERLAY` at layer 31, so a strip left up underneath it is an invisible
     * column of the screen that swallows touches aimed at this list. The service refuses the strip
     * while [showing], and until this existed it had no way to find out — the switcher goes up and
     * down on a key press, with no window-state event anywhere, because events from this package
     * are dropped before the service sees them.
     *
     * Nine call sites take this window down; one hook here is what stops that being nine places to
     * remember something in.
     */
    var onVisibilityChanged: (() -> Unit)? = null

    val showing: Boolean get() = root != null

    /** The row under the selection, or null when nothing is up. */
    val selectedEntry: Entry? get() = entries.getOrNull(index)

    /**
     * The package under the selection, or null when there is not one.
     *
     * Blank is folded into null on purpose: the pinned Home row can carry no package at all, and
     * "" is not something to open an App info page for.
     */
    val selected: String? get() = selectedEntry?.pkg?.takeIf { it.isNotBlank() }

    /**
     * The list as it stands, for a caller that is about to take the window down and may need to
     * put it back. [hide] clears the entries with the views, so anything meaning to return has to
     * hold its own copy — see `ControlService.openSystemSwitcher`.
     */
    fun snapshot(): List<Entry> = entries

    private val idle = Runnable { hide() }

    /**
     * How many rows fit on this screen, measured rather than assumed.
     *
     * It was eight, which is a number somebody typed while looking at one phone. Eight rows plus a
     * label plus the hint line is taller than the LPIII's panel at the type size this screen uses,
     * so the last app on the list — the one furthest back, and the one a long press is most likely
     * aiming at — was drawn below the fold on a list that deliberately cannot be scrolled by
     * finger.
     *
     * Worked out from the same numbers the view is built from: the panel's height, the grid units
     * of padding above and below, the header and the hint, one row of type plus its own padding,
     * and — when [pinned] — the HOME heading over the row at the bottom. [FLOOR] is the honest minimum — a switcher showing fewer than three apps is not
     * worth the gesture — and the ceiling stops a tablet-sized surface turning this into a
     * launcher.
     */
    fun capacity(pinned: Boolean): Int {
        val metrics = context.resources.displayMetrics
        // The icon is drawn exactly one line tall (see [iconPx]), so a row with one in it is the
        // same height as a row without -- which is why this arithmetic does not mention it.
        val rowPx = type.gridPx(0.75f) * 2f + sp(type.copy * SCALE) * LINE_SPACING
        val labelPx = sp(type.superfine * SCALE) * LINE_SPACING + type.gridPx(1f)
        val hintPx = sp(type.superfine * SCALE) * LINE_SPACING + type.gridPx(2f)
        // The way-out button, one line plus the air above it. Counted here for the same reason the
        // header and the hint are: a row this arithmetic forgets about is a row drawn below the
        // fold of a list that cannot be scrolled by finger, and it is always the app furthest back
        // -- the one a switcher is for.
        val buttonPx = sp(type.superfine * SCALE) * LINE_SPACING + type.gridPx(2f)
        // The HOME heading over the pinned row, and the air around it. Counted for the same reason
        // as everything else here: the row this arithmetic forgets is the app furthest back. Zero
        // when the row is switched off, because charging a screen for something it is not drawing
        // is a row of recents spent on nothing.
        val pinPx = if (pinned) sp(type.superfine * SCALE) * LINE_SPACING + type.gridPx(2f) else 0f
        val padding = type.gridPx(3f) + type.gridPx(3f)
        val room = metrics.heightPixels - padding - labelPx - hintPx - buttonPx - pinPx
        if (rowPx <= 0f) return FLOOR
        return (room / rowPx).toInt().coerceIn(FLOOR, CEILING)
    }

    private fun sp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        value,
        context.resources.displayMetrics,
    )

    /**
     * Put it up. False means nothing was added, and the caller must not swallow keys for it.
     *
     * **An empty list still shows.** It says so, on the screen, with a line explaining that
     * nothing has been opened yet. The alternative — refusing to appear — is a gesture that
     * looks broken in exactly the state a new install is in, which is the state everybody who
     * has just updated the app is in. A feature that cannot show you it worked has not worked.
     */
    fun show(list: List<Entry>): Boolean {
        entries = list
        index = 0
        if (root != null) {
            column?.let { runCatching { fill(it) } }
            runCatching { enter() }
            arm()
            return true
        }
        val wm = context.getSystemService(WindowManager::class.java) ?: return false
        val view = build() ?: return false
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Not focusable, for the same reason the lock face is not: key focus belongs to
            // whatever was already holding it. The service reads our keys off the filter instead.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        val added = runCatching { wm.addView(view, params); true }.getOrDefault(false)
        if (!added) return false
        root = view
        runCatching { enter() }
        arm()
        runCatching { onVisibilityChanged?.invoke() }
        return true
    }

    /** Move the selection by [delta], wrapping — the wheel has no ends. */
    fun move(delta: Int) {
        if (entries.isEmpty()) return
        index = ((index + delta) % entries.size + entries.size) % entries.size
        paint()
        arm()
    }

    /**
     * Take the selection. **This no longer takes the window down** — the service does, once the
     * app is in front of it.
     *
     * It used to hide first, which is the obvious order and the wrong one: a start is not instant,
     * and for the few frames in between what is on screen is the app you were trying to leave. See
     * `ControlService.holdSwitcherThrough`. The idle timer is re-armed as the backstop for a
     * handover that somehow never finishes.
     */
    fun choose() {
        val entry = selectedEntry ?: return
        arm()
        onPick?.invoke(entry)
    }

    /**
     * Take it down.
     *
     * The reference is dropped only after the removal succeeds — a window this app has forgotten
     * about but the system still has is a black screen with nothing left to close it.
     */
    fun hide(): Boolean {
        handler.removeCallbacks(idle)
        val view = root ?: return true
        val wm = context.getSystemService(WindowManager::class.java) ?: return false
        val gone = runCatching { wm.removeView(view); true }
            .getOrElse { runCatching { wm.removeViewImmediate(view); true }.getOrDefault(false) }
        if (!gone) return false
        root = null
        content = null
        column = null
        rows = emptyList()
        entries = emptyList()
        index = 0
        runCatching { onVisibilityChanged?.invoke() }
        return true
    }

    /**
     * Show the list, at rest, on the first frame it exists.
     *
     * There is deliberately no animation left here. v3.16 dithered the background in and v3.20
     * had the list fade and rise into place; both are gone for the same reason. This window opens
     * on a double press of the home button, which is a gesture people make when they are already
     * moving — and every millisecond of arrival is time the row you are reaching for is not yet
     * where it is going to be. A switcher is not somewhere you look at, it is somewhere you pass
     * through.
     *
     * The reset is still worth doing: a window that is being re-shown may be carrying the
     * transform an earlier animation left on it.
     */
    private fun enter() {
        val view = content ?: return
        view.animate().cancel()
        view.alpha = 1f
        view.translationY = 0f
    }

    private fun arm() {
        handler.removeCallbacks(idle)
        handler.postDelayed(idle, IDLE_MS)
    }

    // ------------------------------------------------------------------------ the view

    private fun build(): FrameLayout? = runCatching {
        val frame = FrameLayout(context).apply {
            // Opaque from the first frame. Whatever is behind this window is an app the list is
            // there to replace, and a translucent switcher is two screens at once.
            background = ColorDrawable(Color.BLACK)
            // Anywhere that isn't a row means "no thanks".
            isClickable = true
            setOnClickListener { hide() }
        }
        // Scrollable by the wheel and by nothing else. A finger could drag this list a little,
        // which on a screen whose only job is "the selection is here" means the selection can be
        // dragged out from under itself — and the row you were about to click is somewhere else
        // by the time you click it. Touch is refused outright rather than damped: there is no
        // amount of finger scrolling this screen wants. `smoothScrollTo` is unaffected, which is
        // how the selection still brings itself into view.
        val scroll = object : ScrollView(context) {
            override fun onTouchEvent(ev: MotionEvent): Boolean = false
            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = false
        }.apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // The SDK's grid: one unit in from the sides, three of top bar. The same figures as
            // the lock face, so the two screens this app draws look like one phone.
            setPadding(type.gridPx(1f), type.gridPx(3f), type.gridPx(1f), type.gridPx(3f))
        }
        scroll.addView(
            col,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        frame.addView(scroll)
        content = scroll
        column = col
        fill(col)
        frame
    }.getOrNull()

    private fun fill(col: LinearLayout) {
        col.removeAllViews()
        col.addView(
            TextView(context).apply {
                text = "RECENT"
                setTextColor(DIM)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, type.superfine * SCALE)
                typeface = type.medium
                letterSpacing = type.buttonTracking
                setPadding(0, 0, 0, type.gridPx(1f))
            },
        )
        // Asked of the recents rather than of the whole list: Home is pinned whether or not
        // anything has been opened, so `entries.isEmpty()` stopped being the same question as
        // "have you been anywhere yet" the moment it was added. Without this the line that
        // explains an empty switcher would never appear again, on exactly the install that needs
        // it -- a fresh one, which is every phone the minute after an update.
        if (entries.none { !it.home }) {
            col.addView(
                TextView(context).apply {
                    text = "Nothing yet. Open an app and press home twice again."
                    setTextColor(DIM)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, type.copy * SCALE)
                    typeface = type.regular
                    setPadding(0, type.gridPx(0.75f), 0, type.gridPx(0.75f))
                },
            )
        }
        // Drawn in the order `Recents` handed them over, which puts Home last -- but with the
        // pinned row set below a gap and under its own label, so the bottom of this screen reads
        // as "and here is the way out" rather than as one more thing you happened to open.
        rows = entries.map { entry ->
            if (entry.home) col.addView(pinLabel())
            TextView(context).apply {
                text = entry.label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, type.copy * SCALE)
                typeface = type.regular
                setPadding(0, type.gridPx(0.75f), 0, type.gridPx(0.75f))
                // The app's own icon, inline, ahead of its name. A switcher is read at a glance
                // and a column of names is a column of things that have to be read; the icon is
                // the part of an app you already know by shape. It is a compound drawable rather
                // than a row of its own views because that is one View per app instead of three,
                // on a list rebuilt every time the window opens.
                gravity = Gravity.CENTER_VERTICAL
                compoundDrawablePadding = type.gridPx(0.75f)
                setCompoundDrawablesRelative(art(entry), null, null, null)
                isClickable = true
                setOnClickListener {
                    // No hide here either: the service takes the list off once the app it
                    // started is actually behind it. See [choose].
                    arm()
                    onPick?.invoke(entry)
                }
                // Hold for App info -- Settings' own page for this app, where Force stop,
                // Uninstall and storage are. Something you do *about* an app rather than instead
                // of switching to one, which is why it is the hold and not the tap.
                setOnLongClickListener {
                    // Nothing to show a page for when home resolved to no package at all. Silent
                    // rather than a note: a hold that does nothing on one row is a smaller
                    // surprise than a line of explanation nobody asked for.
                    if (entry.pkg.isNotBlank()) {
                        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        onAppInfo?.invoke(entry.pkg)
                    }
                    true
                }
                col.addView(this)
            }
        }
        col.addView(systemButton())
        col.addView(
            TextView(context).apply {
                text = "Wheel to move · click to open · hold for app info · home to close"
                setTextColor(DIM)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, type.superfine * SCALE)
                typeface = type.regular
                setPadding(0, type.gridPx(2f), 0, 0)
            },
        )
        status = TextView(context).apply {
            text = ""
            visibility = View.GONE
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, type.superfine * SCALE)
            typeface = type.medium
            letterSpacing = type.buttonTracking
            setPadding(0, type.gridPx(1f), 0, 0)
            col.addView(this)
        }
        paint()
    }

    /**
     * The rule above the pinned row.
     *
     * The gap is doing the work — this list has no colour and no boxes to separate anything with,
     * so a heading and some air is the whole vocabulary available for "the part below this is not
     * the part above it".
     */
    private fun pinLabel(): View = TextView(context).apply {
        text = "HOME"
        setTextColor(DIM)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, type.superfine * SCALE)
        typeface = type.medium
        letterSpacing = type.buttonTracking
        setPadding(0, type.gridPx(1.5f), 0, type.gridPx(0.5f))
    }

    /**
     * The way out to the platform's own switcher.
     *
     * Below the rows on purpose. Everything above it is this app's switcher, which is the thing
     * that works on this phone; this is the door to the version the platform may or may not have.
     *
     * It re-arms the idle timer rather than closing — asking for another screen is not the same as
     * being done with this one, and the answer to "did anything happen" has to be able to land
     * back here.
     */
    private fun systemButton(): View = TextView(context).apply {
        text = "SYSTEM SWITCHER"
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, type.superfine * SCALE)
        typeface = type.medium
        letterSpacing = type.buttonTracking
        setPadding(0, type.gridPx(2f), 0, 0)
        isClickable = true
        // Tap only. It briefly carried App info on a hold as well, which put two answers on the
        // one control furthest from the app they were about -- the app is the row, and the gesture
        // about an app belongs on it.
        setOnClickListener {
            arm()
            runCatching { onSystem?.invoke() }
        }
    }

    /**
     * Put a line on the bottom of the list, with no app attached to it.
     *
     * [stopped] is the same idea bound to a package and to a row that may have to go. This is for
     * an answer about the screen itself — that the system had no switcher to show, that a page
     * could not be opened — where there is nothing to remove and nothing to rebuild.
     */
    fun note(text: String) {
        status?.let {
            it.text = text
            it.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        }
        arm()
    }

    /**
     * One line tall, matching the type beside it.
     *
     * Deliberately not bigger. A compound drawable taller than the line it sits on makes the
     * TextView taller, which would change how many rows fit and quietly push the app furthest
     * back below a fold this list cannot be scrolled past — the exact bug [capacity] exists to
     * stop. Tied to the type rather than to the grid so the two move together.
     */
    private fun iconPx(): Int = (sp(type.copy * SCALE) * LINE_SPACING).toInt()

    /**
     * The icon for [entry], sized and ready to hang off a row.
     *
     * Never null: an app whose icon cannot be loaded gets an empty box of the same size, so its
     * name still lines up with every other name. A row that shuffles left because its icon was
     * missing is a worse answer than a gap.
     *
     * `mutate()` because the alpha is set per row — see [paint]. Without it, dimming one row's
     * icon dims every row that shares the same constant-state drawable.
     */
    private fun art(entry: Entry): Drawable {
        if (icons.size > CACHE) icons.clear()
        // The launcher shown as Home gets the drawn house instead of its own artwork. Cached
        // under a key of its own, so the same package still has its real icon available to any
        // row that wants it -- turning the setting off must not leave a house behind.
        val key = if (entry.home) HOME_KEY else entry.pkg
        val loaded = icons.getOrPut(key) {
            runCatching {
                if (entry.home) {
                    context.getDrawable(R.drawable.ic_switcher_home)?.mutate()
                } else {
                    val pm = context.packageManager
                    pm.getApplicationIcon(pm.getApplicationInfo(entry.pkg, 0)).mutate()
                }
            }.getOrNull()
        }
        val box = iconPx()
        val art = loaded ?: ColorDrawable(Color.TRANSPARENT)
        art.setBounds(0, 0, box, box)
        return art
    }

    /** The selection is drawn as brightness, not as a box — this phone has no color to spend. */
    private fun paint() {
        rows.forEachIndexed { i, row ->
            val on = i == index
            row.setTextColor(if (on) Color.WHITE else DIM)
            row.typeface = if (on) type.medium else type.regular
            // The icon dims with the name it belongs to. The selection on this screen is one
            // thing being brighter than the rest, and an icon that stayed at full strength on
            // every row would be eight bright things arguing with it.
            runCatching { row.compoundDrawablesRelative.firstOrNull()?.alpha = if (on) 255 else DIM_ALPHA }
        }
        val row = rows.getOrNull(index) ?: return
        val scroll = row.parent?.parent as? ScrollView ?: return
        row.post { runCatching { scroll.smoothScrollTo(0, row.top - row.height) } }
    }

    private companion object {
        /** LightOS's own secondary grey. The same value as the app's Compose `Dim`. */
        val DIM: Int = Color.rgb(0x9A, 0x9A, 0x9A)

        /** [DIM] as an alpha, for the icons — the same grey, arrived at the only way a bitmap can. */
        const val DIM_ALPHA = 0x9A

        /** Icons held before the map is dropped. Comfortably more than the list can show. */
        const val CACHE = 32

        /** The house's slot in the icon cache. Not a package, so it can never collide with one. */
        const val HOME_KEY = "\u0000home"


        /** Fewer than this and the gesture is not worth making. */
        const val FLOOR = 3

        /** More than this and it is a launcher, not a switcher. */
        const val CEILING = 12

        /** Roughly what a line of this type occupies, including its own leading. */
        const val LINE_SPACING = 1.3f

        /** How long it waits with nothing pressed before closing itself. */
        const val IDLE_MS = 6_000L

        /**
         * Every size on this screen, over the SDK's own scale.
         *
         * The one deliberate departure from `LightType` in this app. The switcher is read at
         * arm's length in the second between deciding to leave an app and leaving it, which is
         * not the reading distance the SDK's body scale is set for, and a list of eight rows has
         * the room to spend.
         */
        const val SCALE = 1.15f
    }
}
