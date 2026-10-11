package com.gios.lightcontrol

import com.gios.lightcontrol.portal.BindRetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bind retry schedule behind light-reports #672: spaced out, growing, finite, and short
 * enough that a network that will never bind is still reported quickly.
 */
class BindRetryTest {

    @Test
    fun retriesAreSpacedOutAndGrow() {
        var last = 0L
        for (attempt in 1..BindRetry.ATTEMPTS) {
            val d = BindRetry.delayBefore(attempt)!!
            assertTrue("retry $attempt waits ${d}ms", d >= 200)
            assertTrue("retry $attempt waits longer than the one before", d > last)
            last = d
        }
    }

    @Test
    fun retriesEnd() {
        assertNull(BindRetry.delayBefore(0))
        assertNull(BindRetry.delayBefore(BindRetry.ATTEMPTS + 1))
    }

    @Test
    fun theWholeWaitStaysUnderTenSeconds() {
        val total = (1..BindRetry.ATTEMPTS).sumOf { BindRetry.delayBefore(it)!! }
        assertEquals(BindRetry.TOTAL_MS, total)
        assertTrue(total in 3_000L..10_000L)
    }
}
