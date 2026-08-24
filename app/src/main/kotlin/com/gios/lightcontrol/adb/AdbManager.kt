package com.gios.lightcontrol.adb

import android.content.Context
import android.os.Build
import android.sun.misc.BASE64Encoder
import android.sun.security.provider.X509Factory
import android.sun.security.x509.AlgorithmId
import android.sun.security.x509.CertificateAlgorithmId
import android.sun.security.x509.CertificateExtensions
import android.sun.security.x509.CertificateIssuerName
import android.sun.security.x509.CertificateSerialNumber
import android.sun.security.x509.CertificateSubjectName
import android.sun.security.x509.CertificateValidity
import android.sun.security.x509.CertificateVersion
import android.sun.security.x509.CertificateX509Key
import android.sun.security.x509.KeyIdentifier
import android.sun.security.x509.PrivateKeyUsageExtension
import android.sun.security.x509.SubjectKeyIdentifierExtension
import android.sun.security.x509.X500Name
import android.sun.security.x509.X509CertImpl
import android.sun.security.x509.X509CertInfo
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.android.AdbMdns
import io.github.muntashirakon.adb.android.AndroidUtils
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.Random
import java.util.concurrent.TimeUnit

/**
 * The phone talking ADB to itself.
 *
 * LightOS grants nothing this app needs through any on-screen setting — the accessibility
 * service, `WRITE_SETTINGS`, `SYSTEM_ALERT_WINDOW`, `WRITE_SECURE_SETTINGS`, the notification
 * listener — so every one of them normally means plugging into a computer. And a reinstall
 * drops the appops and the secure-settings grants, so it means plugging in *again*, which is
 * the single most common way this app arrives on the phone looking broken.
 *
 * This removes the computer. Android's own wireless-debugging daemon listens on a TCP port on
 * the device; [libadb][io.github.muntashirakon.adb] connects to it over loopback with a client
 * certificate, and from there `pm grant` / `appops set` / `settings put` are just shell lines —
 * the same lines the settings screens already print. The user pairs once (the daemon shows a
 * code), and after that the app can re-grant itself.
 *
 * A thin Kotlin port of libadb's reference `AdbConnectionManager`. The RSA key pair and the
 * X509 certificate are generated once and kept in `filesDir`, so the daemon remembers this
 * client across launches and a reconnect needs no second pairing.
 *
 * Everything here blocks on the network and must be called off the main thread.
 */
class AdbManager private constructor(context: Context) : AbsAdbConnectionManager() {

    private val mPrivateKey: PrivateKey
    private val mCertificate: Certificate

    init {
        api = Build.VERSION.SDK_INT
        setTimeout(20L, TimeUnit.SECONDS)
        val existingKey = readPrivateKey(context)
        val existingCert = readCertificate(context)
        if (existingKey != null && existingCert != null) {
            mPrivateKey = existingKey
            mCertificate = existingCert
        } else {
            val generator = KeyPairGenerator.getInstance("RSA")
            generator.initialize(2048, SecureRandom.getInstance("SHA1PRNG"))
            val pair = generator.generateKeyPair()
            mPrivateKey = pair.private
            val publicKey = pair.public

            val subject = "CN=BrightControl"
            val algorithm = "SHA512withRSA"
            val notBefore = Date()
            // Ten years. The daemon only cares that it is valid at pairing time, and a client
            // cert that expires would silently stop reconnecting long after anyone remembers why.
            val notAfter = Date(System.currentTimeMillis() + 10L * 365 * 86_400_000)

            val extensions = CertificateExtensions()
            extensions.set(
                "SubjectKeyIdentifier",
                SubjectKeyIdentifierExtension(KeyIdentifier(publicKey).identifier),
            )
            extensions.set("PrivateKeyUsage", PrivateKeyUsageExtension(notBefore, notAfter))

            val info = X509CertInfo()
            info.set("version", CertificateVersion(2))
            info.set("serialNumber", CertificateSerialNumber(Random().nextInt() and Int.MAX_VALUE))
            info.set("algorithmID", CertificateAlgorithmId(AlgorithmId.get(algorithm)))
            info.set("subject", CertificateSubjectName(X500Name(subject)))
            info.set("key", CertificateX509Key(publicKey))
            info.set("validity", CertificateValidity(notBefore, notAfter))
            info.set("issuer", CertificateIssuerName(X500Name(subject)))
            info.set("extensions", extensions)

            val cert = X509CertImpl(info)
            cert.sign(mPrivateKey, algorithm)
            mCertificate = cert

            writePrivateKey(context, mPrivateKey)
            writeCertificate(context, cert)
        }
    }

    override fun getPrivateKey(): PrivateKey = mPrivateKey

    override fun getCertificate(): Certificate = mCertificate

    override fun getDeviceName(): String = "BrightControl"

    /**
     * Pair with the phone's own adb daemon, finding the pairing port ourselves over mDNS.
     *
     * This is the crux of single-device pairing. Android's "Pair device with pairing code"
     * dialog shows a port *and* a code, but the port changes every time and the dialog closes
     * the moment you switch to another app to type it — so asking the user for the port cannot
     * work. Instead we discover the pairing service (`_adb-tls-pairing._tcp`) that the dialog
     * advertises while it is open, and the user supplies only the six-digit code. This is exactly
     * what libadb's own reference app does.
     *
     * For this to land, the pairing dialog must still be *alive* when this runs, and "alive"
     * means Settings is still the foreground app. Home does not preserve it: the dialog's own
     * `onStop()` dismisses it and calls `disablePairing()`. That is why this is normally driven
     * by [AdbPairReader], which reads the code without the user ever leaving the dialog.
     * Discovery waits up to [timeoutMs] for the service to appear.
     */
    fun pairViaMdns(context: Context, code: String, timeoutMs: Long): Boolean {
        val latch = CountDownLatch(1)
        val portRef = AtomicInteger(-1)
        val hostRef = AtomicReference<String?>(null)
        val mdns = AdbMdns(context, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { host: InetAddress?, port: Int ->
            if (port > 0) {
                portRef.set(port)
                hostRef.set(host?.hostAddress)
                latch.countDown()
            }
        }
        mdns.start()
        try {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) return false
        } finally {
            runCatching { mdns.stop() }
        }
        val port = portRef.get()
        if (port <= 0) return false
        val host = hostRef.get() ?: AndroidUtils.getHostIpAddress(context)
        return pair(host, port, code)
    }

    /**
     * Connect to the running daemon, preferring mDNS discovery of `_adb-tls-connect._tcp` and
     * falling back to a port the user typed off the Wireless-debugging screen. The connect port is
     * stable (unlike the pairing port) so typing it is a fine fallback when mDNS finds nothing.
     */
    fun connectAuto(context: Context, timeoutMs: Long): Boolean =
        autoConnect(context, timeoutMs)

    fun connectPort(context: Context, port: Int): Boolean =
        connect(AndroidUtils.getHostIpAddress(context), port)

    /**
     * Run one shell command and return everything it prints, stdout and stderr merged the way
     * a `shell:` service already merges them. Blocks until the command exits.
     */
    fun runCommand(command: String): String {
        val stream = openStream("shell:$command")
        val output = StringBuilder()
        stream.openInputStream().use { input ->
            val buffer = ByteArray(4096)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.append(String(buffer, 0, read, StandardCharsets.UTF_8))
            }
        }
        return output.toString().trim()
    }

    /** Whether a live connection is up. Wraps the Java isConnected() so the screen can gate on it. */
    fun connected(): Boolean = runCatching { isConnected }.getOrDefault(false)

    /**
     * Whether the connection can actually carry a command, asked by sending one.
     *
     * [connected] reads a flag that is set when the socket is opened and cleared when it is
     * closed deliberately. A socket the daemon dropped underneath us is neither, so the flag
     * keeps saying yes long after nothing works — which is how a screen offers a RUN button,
     * fires six commands into a dead socket, and gets six identical `Stream closed` back. The
     * flag was never lying about what it tracks; it was being asked the wrong question.
     *
     * So this asks the only question that has a real answer: send something trivial and see if
     * it comes back. One round trip on a working connection, and on a broken one it fails here,
     * once, instead of once per grant.
     */
    fun alive(): Boolean = runCatching {
        connected() && runCommand("echo $PROBE").contains(PROBE)
    }.getOrDefault(false)

    companion object {
        /** Distinctive enough that a shell banner or a stray line cannot be mistaken for it. */
        private const val PROBE = "lc-alive"

        @Volatile
        private var instance: AdbManager? = null

        fun getInstance(context: Context): AdbManager =
            instance ?: synchronized(this) {
                instance ?: AdbManager(context.applicationContext).also { instance = it }
            }

        /**
         * Drop the connection and the cached manager. A failed connect can leave the manager
         * holding a half-open socket that every later call then reuses and fails on; resetting
         * makes the next attempt start clean. The key pair on disk is untouched, so no re-pair.
         */
        fun reset() {
            synchronized(this) {
                runCatching { instance?.close() }
                instance = null
            }
        }

        private fun certFile(context: Context) = File(context.filesDir, "adb_cert.pem")
        private fun keyFile(context: Context) = File(context.filesDir, "adb_private.key")

        private fun readCertificate(context: Context): Certificate? = runCatching {
            val file = certFile(context)
            if (!file.exists()) return null
            file.inputStream().use {
                CertificateFactory.getInstance("X.509").generateCertificate(it)
            }
        }.getOrNull()

        private fun writeCertificate(context: Context, certificate: Certificate) {
            certFile(context).outputStream().use { os ->
                os.write(X509Factory.BEGIN_CERT.toByteArray(StandardCharsets.UTF_8))
                os.write('\n'.code)
                BASE64Encoder().encode(certificate.encoded, os)
                os.write('\n'.code)
                os.write(X509Factory.END_CERT.toByteArray(StandardCharsets.UTF_8))
            }
        }

        private fun readPrivateKey(context: Context): PrivateKey? = runCatching {
            val file = keyFile(context)
            if (!file.exists()) return null
            val bytes = file.readBytes()
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(bytes))
        }.getOrNull()

        private fun writePrivateKey(context: Context, privateKey: PrivateKey) {
            keyFile(context).outputStream().use { it.write(privateKey.encoded) }
        }
    }
}
