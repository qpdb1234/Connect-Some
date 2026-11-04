/*
 * The device identity for LocalSend: one self-signed certificate, minted once and stored
 * in internal storage, whose SHA-256 fingerprint is who we are on the network.
 *
 * The shape matches the official Rust side (packages/core/src/crypto/cert.rs): RSA-2048,
 * CN=LocalSend User, no SANs, a validity window so wide that the certificate never has to
 * be rotated. Peers never run this through the system trust chain - every TLS connection
 * is pinned to the fingerprint learned during discovery - so a CA signature would add
 * nothing.
 */
package com.limelight.localsend

import android.util.Base64
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager

class LsSecurityContext(
    val privateKeyPem: String,
    val certificatePem: String,
    /** SHA-256 of the certificate DER, uppercase hex - the LocalSend fingerprint. */
    val fingerprint: String,
) {
    val certificate: X509Certificate by lazy { LsCrypto.parseCert(certificatePem) }
}

object LsCrypto {
    private const val KEY_FILE = "localsend_key.pem"
    private const val CERT_FILE = "localsend_cert.pem"

    init {
        // Idempotent; Moonlight-core may already have registered it.
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    /** Loads the stored identity, minting and persisting one on first use. */
    @Synchronized
    fun loadOrCreate(dataDir: File): LsSecurityContext {
        val keyFile = File(dataDir, KEY_FILE)
        val certFile = File(dataDir, CERT_FILE)
        if (keyFile.isFile && certFile.isFile) {
            val certPem = certFile.readText()
            return LsSecurityContext(keyFile.readText(), certPem, fingerprintOf(pemDer(certPem)))
        }

        val (privateKey, certificate) = generateIdentity()
        val keyPem = toPem("PRIVATE KEY", privateKey.encoded)
        val certPem = toPem("CERTIFICATE", certificate.encoded)
        keyFile.writeText(keyPem)
        certFile.writeText(certPem)
        return LsSecurityContext(keyPem, certPem, fingerprintOf(certificate.encoded))
    }

    private fun generateIdentity(): Pair<java.security.PrivateKey, X509Certificate> {
        // Android P (API 28) crippled the built-in "BC" provider: KeyPairGenerator.RSA and
        // Signature.SHA256withRSA both throw NoSuchAlgorithmException there (Google's 2018
        // crypto change), and the interception is keyed on the provider name "BC", so passing
        // our own BouncyCastleProvider instance does not dodge it either. Generate the keypair
        // and the signature through the platform default provider (AndroidOpenSSL/Conscrypt,
        // which really implements RSA + SHA256withRSA). BouncyCastle is used only for the
        // certificate *structure* below (JcaX509v3CertificateBuilder works on ASN.1 directly
        // and queries no JCA provider), so none of the crippled lookups are hit.
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        val keyPair: KeyPair = generator.generateKeyPair()

        // rcgen's default serial is derived from the public key hash; any stable
        // non-zero value is equally fine for a self-signed identity.
        // BigInteger(int, byte[]) takes a SIGNUM (-1/0/1), not a bit length - signum 1 makes
        // the digest a positive magnitude, which is what an X.509 serial number must be.
        val serial = BigInteger(1, MessageDigest.getInstance("SHA-256").digest(keyPair.public.encoded))
        val name = X500Name("CN=LocalSend User")
        val notBefore = Date(System.currentTimeMillis() - 24L * 3600_000)
        val notAfter = Date(System.currentTimeMillis() + 3650L * 24L * 3600_000)

        val builder = JcaX509v3CertificateBuilder(name, serial, notBefore, notAfter, name, keyPair.public)
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        val signer = JcaContentSignerBuilder("SHA256WithRSA").build(keyPair.private)
        val certificate = JcaX509CertificateConverter().getCertificate(builder.build(signer))
        return keyPair.private to certificate
    }

    fun fingerprintOf(certDer: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(certDer)
        return digest.joinToString("") { "%02X".format(it) }
    }

    /** The fingerprint of the certificate a TLS session was actually established with. */
    fun fingerprintOf(session: javax.net.ssl.SSLSession): String? {
        val cert = session.peerCertificates.firstOrNull() as? X509Certificate ?: return null
        return fingerprintOf(cert.encoded)
    }

    fun parseCert(certPem: String): X509Certificate {
        val der = pemDer(certPem)
        val factory = java.security.cert.CertificateFactory.getInstance("X.509")
        return factory.generateCertificate(java.io.ByteArrayInputStream(der)) as X509Certificate
    }

    fun parseKey(keyPem: String): java.security.PrivateKey {
        val der = pemDer(keyPem)
        return java.security.KeyFactory.getInstance("RSA")
            .generatePrivate(java.security.spec.PKCS8EncodedKeySpec(der))
    }

    /** The base64 body of a single PEM block, headers and line breaks stripped. */
    private fun pemDer(pem: String): ByteArray {
        val body = pem.lineSequence()
            .filterNot { it.startsWith("-----") || it.isBlank() }
            .joinToString("")
        return Base64.decode(body, Base64.DEFAULT)
    }

    private fun toPem(type: String, der: ByteArray): String {
        val body = Base64.encodeToString(der, Base64.NO_WRAP)
        val builder = StringBuilder("-----BEGIN $type-----\n")
        body.chunked(64).forEach { builder.append(it).append('\n') }
        builder.append("-----END $type-----\n")
        return builder.toString()
    }

    /** The socket factory and the matching trust manager OkHttp must be handed together;
     *  the pinning lives in the manager, not in OkHttp's own chain validation. */
    data class LsTls(val sslContext: SSLContext, val trustManager: X509TrustManager)

    /**
     * Client context: presents our certificate (the v2 server side checks the client
     * certificate's fingerprint against the register body) and accepts any peer whose
     * certificate hashes to `pinnedFingerprint`. Null disables the check and is only ever
     * used for discovery, exactly like the Rust `create_reqwest_client`.
     */
    fun clientContext(identity: LsSecurityContext, pinnedFingerprint: String?): LsTls {
        val keyManager = keyManagerFor(identity)
        val trust: X509TrustManager = PinningTrustManager(pinnedFingerprint, client = false)
        val ctx = SSLContext.getInstance("TLS").apply { init(arrayOf(keyManager), arrayOf(trust), null) }
        return LsTls(ctx, trust)
    }

    /**
     * Server context: our certificate on the wire, any client certificate accepted so the
     * handshake succeeds and its fingerprint can be read back from the session - the same
     * "trust the fingerprint, not the chain" model the official verifier uses.
     */
    fun serverContext(identity: LsSecurityContext): SSLContext {
        val keyManager = keyManagerFor(identity)
        val trust: X509TrustManager = PinningTrustManager(null, client = true)
        return SSLContext.getInstance("TLS").apply { init(arrayOf(keyManager), arrayOf(trust), null) }
    }

    private fun keyManagerFor(identity: LsSecurityContext): X509KeyManager {
        val store = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType())
        store.load(null, null)
        store.setKeyEntry(
            "localsend",
            parseKey(identity.privateKeyPem),
            CharArray(8),
            arrayOf(identity.certificate),
        )
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(store, CharArray(8))
        return kmf.keyManagers.first { it is X509KeyManager } as X509KeyManager
    }

    private class PinningTrustManager(
        private val pinnedFingerprint: String?,
        private val client: Boolean,
    ) : X509TrustManager {

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            check(chain)
        }

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
            // The server accepts any client certificate; the register handler then
            // compares the handshake fingerprint with the one claimed in the body.
            if (!client) {
                check(chain)
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

        private fun check(chain: Array<X509Certificate>) {
            val expected = pinnedFingerprint ?: return
            val actual = fingerprintOf(chain.first().encoded)
            if (!actual.equals(expected, ignoreCase = true)) {
                throw java.security.cert.CertificateException(
                    "Peer certificate fingerprint $actual does not match the pinned $expected")
            }
        }
    }
}
