package com.gios.lightcontrol.adb

import java.lang.reflect.Field
import java.lang.reflect.Modifier
import javax.net.ssl.SSLContext

/**
 * Keeps libadb's one TLS context matched to the key this app is using now.
 *
 * ## The fault
 *
 * libadb 3.1.1 builds its `SSLContext` in `SslUtils.getSslContext(keyPair)`, stores it in a private
 * static field, and returns that same object on every later call. It never looks at the key pair
 * it is given after the first call. Both pairing (`PairingConnectionCtx`) and connecting
 * (`AdbConnection`, on `STLS`) get their context there. So the first key pair that reaches a TLS
 * handshake decides which certificate this process presents, for as long as the process lives.
 *
 * This app replaces its key pair while it runs. FORGET THE PAIRING, START OVER AND PAIR, and the
 * automatic recovery from a refused key all delete the key files, and the next [AdbManager] makes a
 * new pair. The ADB screen calls [AdbManager.ensureAlive] when it opens, and when wireless debugging
 * is on that is already a TLS handshake. So the order on the phone was:
 *
 * 1. The screen opens and connects with key A. libadb stores a context for A.
 * 2. START OVER AND PAIR deletes A. Pairing sends the public half of the new key B, and the phone
 *    accepts it.
 * 3. The connect presents A's certificate, because the stored context is A's. The phone does not
 *    trust A, so it refuses the handshake within milliseconds.
 *
 * That is the trail in light-reports#700: "pairing accepted", then the mDNS connect and the port
 * fallback both fail in the same second, with wireless debugging on. Every retry in the same
 * process fails the same way, and only a new process clears the field. The family has about fifty
 * reports since August 26, and triage never found a cause on the phone side, because the phone
 * was right to refuse: the certificate it was shown was not the key it had just paired.
 *
 * ## The repair
 *
 * Before anything opens a TLS socket, [match] compares the certificate the stored context was
 * made for with the certificate in use now. When they differ, it clears the field, and libadb
 * builds a new context from the key pair of the next handshake. libadb has no public way to do
 * this, so it is done by reflection. `LibadbTlsTest` pins the field's name and type, so a libadb
 * update that changes them fails the unit tests in CI and not on a phone.
 *
 * No Android imports, so the test can run on the JVM.
 */
object LibadbTls {

    /** What [match] found, for the pairing trail. */
    sealed interface Outcome {
        /** The stored context was already made for this certificate. Nothing changed. */
        data object Kept : Outcome

        /** Nothing was stored yet. libadb builds the context from this key on its first use. */
        data object Fresh : Outcome

        /**
         * A context was stored for a different certificate, and it was cleared.
         *
         * [knownOther] is true when this object recorded that other certificate itself. That is
         * the fault this file exists for: without the clear, this handshake presents the old key.
         */
        data class Cleared(val knownOther: Boolean) : Outcome

        /** The field could not be read or written. libadb has probably changed. */
        data class Failed(val why: String) : Outcome
    }

    private const val CLASS = "io.github.muntashirakon.adb.SslUtils"
    private const val FIELD = "sslContext"

    /** The certificate the stored context was last matched to, as encoded bytes. */
    private var matchedTo: ByteArray? = null

    /**
     * True when [forget] threw away a context that was built for the deleted key.
     *
     * The next [match] reports that as [Outcome.Cleared], so the pairing trail shows the line
     * that says the old certificate would have been presented. [forget] has already done the
     * clearing by then, so without this flag the trail would only say [Outcome.Fresh].
     */
    private var forgotHeld = false

    /**
     * Make sure the next TLS handshake presents [certificate].
     *
     * Cheap when nothing changed: one array comparison.
     */
    @Synchronized
    fun match(certificate: ByteArray): Outcome {
        val previous = matchedTo
        if (previous != null && previous.contentEquals(certificate)) return Outcome.Kept
        val field = runCatching { field() }.getOrElse { return Outcome.Failed(AdbFailure.describe(it)) }
        val held = runCatching { field.get(null) }.getOrElse { return Outcome.Failed(AdbFailure.describe(it)) }
        if (held != null) {
            runCatching { field.set(null, null) }.getOrElse { return Outcome.Failed(AdbFailure.describe(it)) }
        }
        matchedTo = certificate.copyOf()
        val forgot = forgotHeld
        forgotHeld = false
        return when {
            held != null -> Outcome.Cleared(knownOther = previous != null || forgot)
            forgot -> Outcome.Cleared(knownOther = true)
            else -> Outcome.Fresh
        }
    }

    /**
     * Drop the stored context and the record of what it was made for.
     *
     * Called when the key files are deleted. The next handshake builds a context from the new key.
     */
    @Synchronized
    fun forget(): Outcome {
        val outcome = runCatching {
            val field = field()
            val held = field.get(null)
            field.set(null, null)
            if (held != null) forgotHeld = true
            Outcome.Fresh
        }.getOrElse { Outcome.Failed(AdbFailure.describe(it)) }
        matchedTo = null
        return outcome
    }

    /** Back to a process that has made no TLS context at all. For the unit tests. */
    @Synchronized
    internal fun clearForTest() {
        matchedTo = null
        forgotHeld = false
        runCatching { field().set(null, null) }
    }

    /** A line for the pairing trail, or null when nothing worth saying happened. */
    fun note(outcome: Outcome?): String? = when (outcome) {
        null, Outcome.Kept, Outcome.Fresh -> null
        is Outcome.Cleared ->
            if (outcome.knownOther) {
                "TLS: libadb still held the old key's certificate. Rebuilt it for this key"
            } else {
                "TLS: libadb held a certificate from before. Rebuilt it for this key"
            }
        is Outcome.Failed -> "TLS: could not reset libadb's certificate (${outcome.why})"
    }

    /** The private static field, opened for writing. Throws when libadb no longer has it. */
    internal fun field(): Field {
        val field = Class.forName(CLASS).getDeclaredField(FIELD)
        check(Modifier.isStatic(field.modifiers)) { "$CLASS.$FIELD is no longer static" }
        check(field.type == SSLContext::class.java) { "$CLASS.$FIELD is no longer an SSLContext" }
        field.isAccessible = true
        return field
    }
}
