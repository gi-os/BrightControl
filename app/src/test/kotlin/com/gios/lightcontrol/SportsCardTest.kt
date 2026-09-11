package com.gios.lightcontrol

import com.gios.lightcontrol.notify.SportsCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The card, as the poster describes it.
 *
 * The face reads five strings off a notification and draws them. There is no phone in any of
 * this, which is the point: what makes a card a card, and which half of a score is behind, are
 * both decisions, and a decision is worth pinning.
 */
class SportsCardTest {

    @Test
    fun `a card needs a kind`() {
        assertNull(SportsCard.of(kind = null, team = "SEA", value = "NE 7 · SEA 14"))
        assertNull(SportsCard.of(kind = "   ", value = "NE 7 · SEA 14"))
    }

    @Test
    fun `the kind alone is a card`() {
        val card = SportsCard.of(kind = "TD")
        assertEquals("TD", card?.kind)
        assertNull(card?.team)
    }

    @Test
    fun `blank fields come back null rather than empty`() {
        val card = SportsCard.of(kind = " TD ", team = " SEA ", value = "", detail = "  ", foot = null)
        assertEquals("TD", card?.kind)
        assertEquals("SEA", card?.team)
        assertNull(card?.value)
        assertNull(card?.detail)
        assertNull(card?.foot)
    }

    @Test
    fun `the trailing side dims when it is behind`() {
        val value = "NE 7 · SEA 14"
        assertEquals(0 until value.indexOf(" · "), SportsCard.dimRange(value))
    }

    @Test
    fun `the leading side dims when it is behind`() {
        val value = "NE 21 · SEA 14"
        val sep = value.indexOf(" · ")
        assertEquals((sep + 3) until value.length, SportsCard.dimRange(value))
    }

    @Test
    fun `a tie dims neither half`() {
        assertNull(SportsCard.dimRange("NE 14 · SEA 14"))
    }

    @Test
    fun `anything that is not two scores dims nothing`() {
        assertNull(SportsCard.dimRange("SEA"))
        assertNull(SportsCard.dimRange("24–20"))
        assertNull(SportsCard.dimRange("NE · SEA"))
    }
}
