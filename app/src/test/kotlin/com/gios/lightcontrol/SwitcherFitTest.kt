package com.gios.lightcontrol

import com.gios.lightcontrol.switcher.SwitcherFit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The switcher's capacity, held against the LPIII's real panel.
 *
 * This is the test that was missing while the number rotted. Between v3.97 and v4.3 the screen's
 * furniture grew three times, each subtraction in the capacity arithmetic individually right, and
 * on the phone's 1080×1240 panel the room left for the list slid under three rows without any
 * signal anywhere: the FLOOR clamp absorbed the shortfall, the pinned Home row and the current-app
 * head were then paid out of the floor, and the gesture opened onto two apps (reported 2026-09-01,
 * visible from v4.7 on — that release fixed the wheel hold on LightOS's screens, light-reports#136,
 * and what came up was the list as it had quietly become). If a padding or a label makes the list
 * smaller than a screenful again, this file is what says so, at build time instead of on Gio's phone.
 */
class SwitcherFitTest {

    /** The Light Phone III's panel: 1080 × 1240. Density cancels out of the arithmetic. */
    private val W = 1080f
    private val H = 1240f

    @Test
    fun `the LPIII gets a full screen of rows with Home pinned`() {
        // Five rows: the current app, three recents, and the pinned Home. Against the two-app
        // screen this test exists because of, and it is exact on purpose -- a drop to four is
        // somebody spending a row and this line is where they are asked to say why.
        assertEquals(5, SwitcherFit.capacity(W, H, fontScale = 1f, pinned = true))
    }

    @Test
    fun `switching the pinned row off frees its heading but buys no extra row`() {
        // Unpinned, the HOME heading's air comes back but the panel still fits five -- the two
        // configurations may never be more than one row apart, or the setting reads as a trap.
        assertEquals(5, SwitcherFit.capacity(W, H, fontScale = 1f, pinned = false))
    }

    @Test
    fun `what capacity promises actually fits the panel`() {
        // The other half of the guarantee: the answer is not just "bigger than before" but
        // honest -- label, rows, heading, button and hint at that count stay inside the glass.
        val rows = SwitcherFit.capacity(W, H, fontScale = 1f, pinned = true)
        val grid = W / SwitcherFit.GRID_COLUMNS
        fun line(designPx: Float) =
            designPx * H / SwitcherFit.DESIGN_HEIGHT * SwitcherFit.SCALE * SwitcherFit.LINE_SPACING
        val drawn =
            grid * (SwitcherFit.TOP_PAD + SwitcherFit.BOTTOM_PAD) +
                line(SwitcherFit.SUPERFINE) + grid * SwitcherFit.LABEL_AIR +
                rows * (grid * SwitcherFit.ROW_AIR * 2f + line(SwitcherFit.COPY)) +
                line(SwitcherFit.SUPERFINE) + grid * (SwitcherFit.PIN_AIR_TOP + SwitcherFit.PIN_AIR_BOTTOM) +
                line(SwitcherFit.SUPERFINE) + grid * SwitcherFit.BUTTON_AIR * 2f +
                line(SwitcherFit.SUPERFINE) + grid * SwitcherFit.HINT_AIR
        assertTrue("drawn $drawn px overflows the ${H}px panel", drawn <= H)
    }

    @Test
    fun `bigger system text costs rows, never the floor`() {
        // LightOS lets text be enlarged and type scales on height times fontScale, so a bigger
        // setting must shed rows gracefully rather than overflow -- and can never go below the
        // floor that makes the gesture worth pressing.
        val big = SwitcherFit.capacity(W, H, fontScale = 1.3f, pinned = true)
        assertTrue("expected 3..4 rows at fontScale 1.3, got $big", big in SwitcherFit.FLOOR..4)
    }

    @Test
    fun `a short panel stops at the floor and a tall one never passes the ceiling`() {
        assertEquals(SwitcherFit.FLOOR, SwitcherFit.capacity(W, 500f, fontScale = 1f, pinned = true))
        // Type scales on height, so rows grow with the panel and the count climbs slowly -- a
        // 4000px panel fits eight of its own rows, not thirty of the LPIII's. The ceiling is a
        // guard rail, not a destination.
        val tall = SwitcherFit.capacity(W, 4000f, fontScale = 1f, pinned = true)
        assertTrue("expected a tall panel to fit more rows, got $tall", tall in 6..SwitcherFit.CEILING)
    }
}
