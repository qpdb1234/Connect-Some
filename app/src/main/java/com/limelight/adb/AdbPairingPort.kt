/*
 * Adapted from Easycontrol_For_Car (https://github.com/Eiyooooo/Easycontrol_For_Car),
 * Copyright (c) Eiyooooo.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.adb

import android.os.Build
import com.limelight.util.Log
import com.limelight.App
import com.limelight.R
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbPairingRequiredException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.util.Date

/**
 * Pairing with an Android 11+ " Wireless debugging" target: the 6-digit code that the
 * target's pairing dialog shows is turned into a long-lived trust relationship through
 * SPAKE2 + TLS-PSK, after which [Adb] can connect over plain TCP with the same key.
 *
 * The UI must not know anything about the underlying library - only this interface - so
 * a build without the pairing component (see [isAvailable]) degrades to "USB / already
 * authorised TCP only" without touching any call site.
 */
interface AdbPairingPort {

    /** False when this build has no wireless-pairing component; the UI greys the entry out. */
    val isAvailable: Boolean

    /** A one-line, user-presentable explanation of why the last [pair] failed, or null. */
    val lastFailureReason: String?

    /**
     * @param port the *pairing* port shown next to "Pair device with pairing code" - which
     *             is a different socket than the port later used to connect.
     * @param code the 6-digit pairing code.
     */
    suspend fun pair(host: String, port: Int, code: String): Boolean

    companion object {
        @JvmStatic
        fun get(): AdbPairingPort = WirelessPairingPort
    }
}

private object WirelessPairingPort : AdbPairingPort {

    private const val TAG = "ConnectSomeAdbPair"

    /**
     * Presence probe rather than a compile-time switch: the library is an optional
     * artifact (JitPack), and a build that could not resolve it must still install and
     * run - just without wireless pairing.
     */
    override val isAvailable: Boolean by lazy {
        try {
            Class.forName("io.github.muntashirakon.adb.AbsAdbConnectionManager")
            true
        } catch (e: ClassNotFoundException) {
            false
        }
    }

    @Volatile
    private var failureReason: String? = null
    override val lastFailureReason: String? get() = failureReason

    /** The manager keeps state between calls, so it is created once and reused. */
    private val manager by lazy { HostKeyAdbManager() }

    /**
     * One pairing at a time.
     *
     * [manager] is a single object holding a single TLS socket and a single result flag,
     * so a second concurrent pairing would drive it from two threads: the two handshakes
     * would read each other's bytes, and - worse - whichever one finished last would
     * [close] the socket the other one was still negotiating over.
     */
    private val pairLock = Any()

    override suspend fun pair(host: String, port: Int, code: String): Boolean =
            withContext(Dispatchers.IO) {
                if (!isAvailable) {
                    failureReason = App.appContext
                            .getString(R.string.msg_android_pairing_unavailable)
                    return@withContext false
                }

                synchronized(pairLock) {
                    failureReason = null
                    Log.i(TAG, "pairing with $host:$port")
                    val accepted = try {
                        manager.pair(host, port, code.trim())
                    } catch (e: Exception) {
                        failureReason = describe(e, host, port)
                        Log.w(TAG, "pairing failed", e)
                        false
                    } finally {
                        // Pairing owns a TLS socket; the trust relationship outlives it, so
                        // dropping the connection here is both safe and required - otherwise
                        // a retry would race the socket we are about to leak.
                        runCatching { manager.close() }
                    }

                    if (accepted) {
                        Log.i(TAG, "paired with $host")
                    } else if (failureReason == null) {
                        // The device simply refused the handshake without throwing.
                        failureReason = App.appContext.getString(R.string.error_pair_rejected)
                    }
                    accepted
                }
            }

    private fun describe(e: Exception, host: String, port: Int): String = when {
        e is AdbPairingRequiredException ->
            App.appContext.getString(R.string.error_pair_needed)

        e is javax.net.ssl.SSLException ->
            App.appContext.getString(R.string.error_pair_handshake)

        e is java.net.ConnectException || e is java.net.SocketTimeoutException ||
                e is java.io.FileNotFoundException ->
            App.appContext.getString(R.string.error_pair_unreachable, host, port)

        else -> e.message ?: App.appContext.getString(
                R.string.error_pair_failed, e.javaClass.simpleName)
    }
}

/**
 * The library authenticates *and* pairs with `certificate.getPublicKey()` - the very
 * identity our own [Adb] later presents in the AUTH phase. Two different keys would mean
 * "paired with a key we never connect with", so the certificate is self-signed over the
 * existing [AdbKeyPair] private key instead of generating its own.
 */
private class HostKeyAdbManager : AbsAdbConnectionManager() {

    // Written on whichever IO thread got there first and read from another one. Without
    // the volatile the cache can appear half-filled, and a retry of pairing would then
    // reload - or, worse, publish a key the reader thread never saw being assigned.
    @Volatile
    private var cachedPrivateKey: PrivateKey? = null
    @Volatile
    private var cachedCertificate: Certificate? = null
    @Volatile
    private var cachedDeviceName: String? = null

    init {
        // Wireless debugging exists since R; nothing older can be paired over TCP.
        setApi(Build.VERSION_CODES.R)
    }

    @Throws(Exception::class)
    private fun keys(): PairingKeys {
        cachedPrivateKey?.let { key ->
            cachedCertificate?.let { cert ->
                cachedDeviceName?.let { name -> return PairingKeys(key, cert, name) }
            }
        }
        val loaded = loadOrCreateKeys(App.appContext.filesDir)
        cachedPrivateKey = loaded.privateKey
        cachedCertificate = loaded.certificate
        cachedDeviceName = loaded.deviceName
        return loaded
    }

    override fun getPrivateKey(): PrivateKey = keys().privateKey

    override fun getCertificate(): Certificate = keys().certificate

    override fun getDeviceName(): String = keys().deviceName
}

private class PairingKeys(
        val privateKey: PrivateKey,
        val certificate: Certificate,
        /** Shown on the target's "Allow USB debugging?" list, so it must be recognisable. */
        val deviceName: String,
)

private const val PAIR_CERT_FILE = "adb_pair_cert.der"
private const val KEY_NAME_PREFIX = "ConnectSome"

/** Ten years: a self-signed host credential outlives every plausible install. */
private const val CERT_VALIDITY_MS = 10L * 365 * 24 * 60 * 60 * 1000

@Throws(Exception::class)
private fun loadOrCreateKeys(filesDir: File): PairingKeys {
    val keyPair = AdbManager.keyPair
    val privateKey = keyPair.privateKey
    val publicKey = publicKeyOf(privateKey)
    val deviceName = deviceNameFor(privateKey)

    val certFile = File(filesDir, PAIR_CERT_FILE)
    readCertificate(certFile, publicKey)?.let { return PairingKeys(privateKey, it, deviceName) }

    val certificate = selfSign(privateKey, publicKey, deviceName)
    certFile.writeBytes(certificate.encoded)
    return PairingKeys(privateKey, certificate, deviceName)
}

/**
 * The stored certificate is only valid while it wraps our current key; a regenerated
 * `adb_private.key` (cleared data, deliberate reset) silently invalidates past pairings,
 * so a mismatch is treated as "no certificate" and a new one is issued.
 */
private fun readCertificate(certFile: File, publicKey: RSAPublicKey): X509Certificate? {
    if (!certFile.isFile) return null
    return try {
        val certificate = certFile.inputStream().use {
            CertificateFactory.getInstance("X.509").generateCertificate(it)
        } as X509Certificate
        val stored = certificate.publicKey as? RSAPublicKey ?: return null
        if (stored.modulus == publicKey.modulus) certificate else null
    } catch (e: Exception) {
        Log.w("ConnectSomeAdbPair", "unreadable pairing certificate", e)
        certFile.delete()
        null
    }
}

/**
 * A minimal self-signed X.509v3 certificate: adb only needs a public key bound to a
 * signature, and `adbd` never validates a chain or a name. Built with BouncyCastle
 * because Android exposes no certificate API, and deliberately without registering a
 * provider globally - the platform's own `SHA512withRSA` is used.
 */
@Throws(Exception::class)
private fun selfSign(
        privateKey: PrivateKey,
        publicKey: RSAPublicKey,
        deviceName: String,
): X509Certificate {
    val now = System.currentTimeMillis()
    val name = X500Name("CN=$deviceName")
    // Random, positive, and within the 20-byte limit RFC 5280 imposes on serials.
    val serial = BigInteger(62, SecureRandom()).add(BigInteger.ONE)
    val builder = JcaX509v3CertificateBuilder(
            name,
            serial,
            Date(now - 24L * 60 * 60 * 1000),  // a device clock behind ours must not reject it
            Date(now + CERT_VALIDITY_MS),
            name,
            publicKey,
    )
    builder.addExtension(
            Extension.subjectKeyIdentifier,
            false,
            JcaX509ExtensionUtils().createSubjectKeyIdentifier(publicKey),
    )
    val signer = JcaContentSignerBuilder("SHA512withRSA").build(privateKey)
    return JcaX509CertificateConverter().getCertificate(builder.build(signer))
}

/** A DB's public half, recovered from the private exponent so nothing else has to be stored. */
@Throws(Exception::class)
private fun publicKeyOf(privateKey: PrivateKey): RSAPublicKey {
    val crt = privateKey as? RSAPrivateCrtKey
            ?: throw IllegalStateException(App.appContext.getString(R.string.error_adb_key_format))
    val spec = RSAPublicKeySpec(crt.modulus, crt.publicExponent)
    return KeyFactory.getInstance("RSA").generatePublic(spec) as RSAPublicKey
}

/**
 * Short per-install identity, shown on the target's "Allow USB debugging?" list and used
 * as the certificate's CN.
 *
 * It is hashed from the key's modulus, which is the only thing that makes it the same
 * string on every boot: a `PrivateKey.hashCode()` is an object identity hash, so the
 * previous scheme produced a new name for the same device each time the process started,
 * and that name contradicted the CN of the certificate already sitting in
 * `adb_pair_cert.der`.
 */
@Throws(Exception::class)
private fun deviceNameFor(privateKey: PrivateKey): String {
    val digest = MessageDigest.getInstance("SHA-256")
            .digest(publicKeyOf(privateKey).modulus.toByteArray())
    val suffix = digest.take(4)
            .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    return "$KEY_NAME_PREFIX-$suffix"
}
