package com.gios.lightcontrol

import com.gios.lightcontrol.adb.LibadbTls
import java.lang.reflect.Modifier
import javax.net.ssl.SSLContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * libadb's one stored TLS context, matched to the key in use.
 *
 * libadb 3.1.1 builds a single `SSLContext` from the first key pair it is given and returns it for
 * every later key pair. After START OVER AND PAIR made a new key, every handshake still presented
 * the old certificate, and the phone refused the connection right after it accepted the pairing
 * (light-reports#700). These tests run against the real libadb class: the first one fails the
 * build if a libadb update moves the field the repair depends on.
 */
class LibadbTlsTest {

    private val keyA = byteArrayOf(1, 2, 3, 4)
    private val keyB = byteArrayOf(9, 8, 7, 6)

    @Before
    fun clean() = LibadbTls.clearForTest()

    @After
    fun tidy() = LibadbTls.clearForTest()

    /** What libadb does on a handshake: build a context and keep it in its static field. */
    private fun libadbBuildsOne() {
        LibadbTls.field().set(null, SSLContext.getInstance("TLS"))
    }

    private fun stored(): Any? = LibadbTls.field().get(null)

    @Test
    fun `libadb still keeps its TLS context where the repair looks for it`() {
        val field = LibadbTls.field()
        assertEquals("sslContext", field.name)
        assertEquals("io.github.muntashirakon.adb.SslUtils", field.declaringClass.name)
        assertTrue(Modifier.isStatic(field.modifiers))
        assertEquals(SSLContext::class.java, field.type)
    }

    @Test
    fun `the first key finds nothing stored and changes nothing`() {
        assertEquals(LibadbTls.Outcome.Fresh, LibadbTls.match(keyA))
        assertNull(stored())
    }

    @Test
    fun `the same key keeps the context libadb built for it`() {
        LibadbTls.match(keyA)
        libadbBuildsOne()
        val built = stored()
        assertNotNull(built)
        // A new manager reads the same key off disk into a new array. Same bytes, same context.
        assertEquals(LibadbTls.Outcome.Kept, LibadbTls.match(keyA.copyOf()))
        assertSame(built, stored())
    }

    @Test
    fun `a new key clears the context built for the old one`() {
        LibadbTls.match(keyA)
        libadbBuildsOne()
        assertEquals(LibadbTls.Outcome.Cleared(knownOther = true), LibadbTls.match(keyB))
        assertNull(stored())
    }

    @Test
    fun `forgetting the pairing clears the context, and the next key is told so once`() {
        LibadbTls.match(keyA)
        libadbBuildsOne()
        LibadbTls.forget()
        assertNull(stored())
        // The line that proves the old certificate would have been presented without the repair.
        assertEquals(LibadbTls.Outcome.Cleared(knownOther = true), LibadbTls.match(keyB))
        libadbBuildsOne()
        assertEquals(LibadbTls.Outcome.Kept, LibadbTls.match(keyB))
    }

    @Test
    fun `forgetting with nothing stored leaves the next key fresh`() {
        LibadbTls.forget()
        assertEquals(LibadbTls.Outcome.Fresh, LibadbTls.match(keyA))
    }

    @Test
    fun `a context stored before anything was matched is cleared too`() {
        libadbBuildsOne()
        assertEquals(LibadbTls.Outcome.Cleared(knownOther = false), LibadbTls.match(keyA))
        assertNull(stored())
    }

    @Test
    fun `only a clear that changed something reaches the trail`() {
        assertNull(LibadbTls.note(null))
        assertNull(LibadbTls.note(LibadbTls.Outcome.Kept))
        assertNull(LibadbTls.note(LibadbTls.Outcome.Fresh))
        assertTrue(LibadbTls.note(LibadbTls.Outcome.Cleared(knownOther = true))!!.contains("old key"))
        assertTrue(LibadbTls.note(LibadbTls.Outcome.Cleared(knownOther = false))!!.contains("Rebuilt"))
        assertTrue(LibadbTls.note(LibadbTls.Outcome.Failed("x"))!!.contains("could not reset"))
    }
}
