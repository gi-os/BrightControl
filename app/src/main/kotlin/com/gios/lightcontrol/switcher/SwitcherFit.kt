package com.gios.lightcontrol.switcher

/**
 * How many rows the app switcher may put on a panel. Pure arithmetic, so a JVM test can hold it
 * against the LPIII's real numbers — which is the whole reason it is not a method on the view.
 *
 * This number rotted once, and nothing said so. The formula subtracts every piece of furniture
 * the screen draws around the list, and between v3.97 and v4.3 the furniture grew three times —
 * the HOME heading over the pinned row, then a wider way-out button, while the pinned row itself
 * also started costing a slot out of the answer (see `Recents.entries`). Each subtraction was
 * correct and defensible alone. Together, on a 1080×1240 panel, they left room for 2.9 rows: the
 * [FLOOR] clamp quietly became the capacity, the pinned Home and the current-app head came out of
 * *it*, and the switcher opened onto two apps. No line was wrong; the sum was.
 *
 * So the sum is pinned down here. [SwitcherFitTest] opens this on the LPIII's panel and fails the
 * build if the list ever again gets less than a screenful — the test is the person who notices.
 *
 * The scale rules are the SDK's, same as `LightType`: **type scales on height** (design px against
 * a 600px-tall reference, times the user's font scale), **units scale on width** (the grid is 27
 * columns). Density cancels out of both, which is why the panel's raw pixels are enough.
 */
object SwitcherFit {

    /**
     * Rows of the list that fit above the fold, [FLOOR]..[CEILING].
     *
     * "Rows of the list" is the number `Recents.entries` is handed as its limit: the pinned Home
     * row is one of them (it pays with a slot, see there), and so is the current-app head. What
     * is subtracted first is everything else the screen draws: the column's padding, the RECENT
     * label, the HOME heading when [pinned], the way-out button, and the hint line.
     */
    fun capacity(widthPx: Float, heightPx: Float, fontScale: Float, pinned: Boolean): Int {
        val grid = widthPx / GRID_COLUMNS
        fun line(designPx: Float) = designPx * heightPx / DESIGN_HEIGHT * fontScale * SCALE * LINE_SPACING
        // The icon is drawn exactly one line tall (see the view's iconPx), so a row with one in
        // it is the same height as a row without -- which is why rowPx does not mention it.
        val rowPx = grid * ROW_AIR * 2f + line(COPY)
        val labelPx = line(SUPERFINE) + grid * LABEL_AIR
        val hintPx = line(SUPERFINE) + grid * HINT_AIR
        val buttonPx = line(SUPERFINE) + grid * BUTTON_AIR * 2f
        val pinPx = if (pinned) line(SUPERFINE) + grid * (PIN_AIR_TOP + PIN_AIR_BOTTOM) else 0f
        val padding = grid * TOP_PAD + grid * BOTTOM_PAD
        val room = heightPx - padding - labelPx - hintPx - buttonPx - pinPx
        if (rowPx <= 0f) return FLOOR
        return (room / rowPx).toInt().coerceIn(FLOOR, CEILING)
    }

    /** The SDK's grid: 27 columns across whatever the panel is. */
    const val GRID_COLUMNS = 27f

    /** The SDK's vertical type reference: design pixels are measured against a 600px-tall screen. */
    const val DESIGN_HEIGHT = 600f

    /** `LightType.copy` and `LightType.superfine`, in design px. Duplicated knowingly: those live
     * on an instance that needs a Context, and this file's whole point is not needing one. */
    const val COPY = 30f
    const val SUPERFINE = 16f

    /** The switcher's deliberate 15% over the SDK scale. See the view's own note on SCALE. */
    const val SCALE = 1.15f

    /** Roughly what a line of this type occupies, including its own leading. */
    const val LINE_SPACING = 1.3f

    // ---- air, in grid units. The views take their padding from these same names, so the
    // arithmetic above and the layout it predicts cannot drift apart one number at a time.

    /** Above and below each row's text. Half a unit: the icon sets the height, the air frames it. */
    const val ROW_AIR = 0.5f

    /** Under the RECENT label. */
    const val LABEL_AIR = 1f

    /** Around the HOME heading over the pinned row. The gap is the vocabulary — see the view. */
    const val PIN_AIR_TOP = 0.75f
    const val PIN_AIR_BOTTOM = 0.25f

    /** Above and below the way-out button's text, inside the clickable. */
    const val BUTTON_AIR = 1f

    /** Above the hint line. */
    const val HINT_AIR = 1f

    /** The column's own padding: the SDK's three-unit top bar, and half a unit over the glass. */
    const val TOP_PAD = 3f
    const val BOTTOM_PAD = 0.5f

    /** Fewer than this and the gesture is not worth making. */
    const val FLOOR = 3

    /** More than this and it is a launcher, not a switcher. */
    const val CEILING = 12
}
