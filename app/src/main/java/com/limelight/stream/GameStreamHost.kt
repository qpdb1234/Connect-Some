package com.limelight.stream

import android.content.Context
import android.util.Base64
import com.limelight.util.Log
import com.limelight.binding.crypto.AndroidCryptoProvider
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.NvApp
import com.limelight.nvstream.http.NvHTTP
import com.limelight.nvstream.http.PairingManager
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * Lightweight, native-free helper that talks to a GameStream / Sunshine host
 * purely over HTTP(S) using [NvHTTP].
 *
 * Unlike [MoonlightStreamClient], this helper does not require
 * `libmoonlight-core.so`: pairing ([pair]) and app discovery ([getAppList]) are
 * implemented entirely in the Java `nvstream.http` layer. That makes host setup
 * usable even while the streaming native library is still a placeholder.
 *
 * All methods perform blocking network I/O and must be called off the main
 * thread (e.g. from a coroutine on [kotlinx.coroutines.Dispatchers.IO]).
 */
class GameStreamHost(
        private val context: Context,
        private val host: String,
        private val httpsPort: Int,
        /** Pinned server certificate (DER). Empty = not yet paired/trusted. */
        private val serverCertDer: ByteArray? = null,
        /** Stable client unique id used for pairing. */
        private val clientUniqueId: String = "",
) {

    /** Minimal, UI-friendly view of a launchable app. */
    data class AppInfo(val id: Int, val name: String, val hdrSupported: Boolean)

    /** Result of a pairing attempt. */
    data class PairOutcome(val state: PairState, val pinnedCertDer: ByteArray?)

    companion object {
        private const val TAG = "GameStreamHost"
        const val DEFAULT_HTTPS_PORT = 47984
        // GameStream / Sunshine serves its plaintext HTTP endpoints (serverinfo,
        // pairing handshake commands) on a *separate* port from HTTPS. NvHTTP builds
        // its http base from the address tuple's port, so this must be 47989 (matches
        // NvHTTP.DEFAULT_HTTP_PORT); sending plaintext HTTP to the TLS port (47984)
        // makes Sunshine drop the connection -> java.io.EOFException: limit=0.
        const val DEFAULT_HTTP_PORT = 47989

        /** Reuse the historical VNC port remapping so callers can pass a raw profile port. */
        fun resolveHttpsPort(port: Int): Int = when (port) {
            5900, 0, 5901 -> DEFAULT_HTTPS_PORT
            else -> port
        }

        /**
         * Generates a client-side pairing PIN exactly like Moonlight's
         * [com.limelight.nvstream.http.PairingManager.generatePinString]. The user
         * types THIS pin into the host (Sunshine web UI / GFE), not the other way
         * around - GameStream pairing is client-initiated.
         */
        fun generatePairingPin(): String = PairingManager.generatePinString()
    }

    private fun newHttp(cert: X509Certificate? = decodeServerCert(serverCertDer)): NvHTTP {
        val crypto = AndroidCryptoProvider(context)
        val uniqueId = clientUniqueId.ifBlank {
            crypto.getPemEncodedClientCertificate().hashCode().toString()
        }
        // Address tuple carries the *HTTP* port (used for serverinfo + pairing);
        // the HTTPS port is passed separately so NvHTTP can build the TLS URL itself.
        val address = ComputerDetails.AddressTuple(host, DEFAULT_HTTP_PORT)
        return NvHTTP(address, httpsPort, uniqueId, cert, crypto)
    }

    /**
     * Checks whether this host considers us paired. On first contact (no pinned
     * cert) this reaches the host over plain HTTP, which is enough to read the
     * pairing status.
     */
    fun isPaired(): Boolean = try {
        val http = newHttp()
        http.getPairState(http.getServerInfo(true)) == PairingManager.PairState.PAIRED
    } catch (t: Throwable) {
        Log.w(TAG, "isPaired check failed: ${t.message}")
        false
    }

    /**
     * Runs the full GameStream PIN pairing handshake.
     *
     * @return a [PairOutcome]; when [PairState.PAIRED], [PairOutcome.pinnedCertDer]
     *         carries the server certificate to pin for future HTTPS connections.
     */
    fun pair(pin: String): PairOutcome {
        return try {
            val crypto = AndroidCryptoProvider(context)
            val http = newHttp()
            val pm = PairingManager(http, crypto)
            val state = pm.pair(http.getServerInfo(true), pin.trim())
            val cert = pm.pairedCert?.encoded
            when (state) {
                PairingManager.PairState.PAIRED -> PairOutcome(PairState.PAIRED, cert)
                PairingManager.PairState.PIN_WRONG -> PairOutcome(PairState.PIN_WRONG, null)
                PairingManager.PairState.ALREADY_IN_PROGRESS -> PairOutcome(PairState.FAILED, null)
                else -> PairOutcome(PairState.FAILED, null)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Pairing failed", t)
            PairOutcome(PairState.FAILED, null)
        }
    }

    /**
     * Fetches the list of apps available for streaming. Requires a successful
     * pairing (HTTPS), so callers should pair first when [isPaired] returns false.
     */
    fun getAppList(): List<AppInfo> {
        val http = newHttp()
        // Fetch serverinfo to refresh the negotiated HTTPS port before the applist call.
        http.getServerInfo(true)
        return http.getAppList().map { app: NvApp ->
            AppInfo(app.appId, app.appName, app.isHdrSupported)
        }
    }

    /**
     * The host's own MAC address, in the colon form Wake-on-LAN wants, or null when it
     * does not say.
     *
     * `serverinfo` carries a `<mac>` element and both Sunshine and GameStream answer that
     * query over plaintext HTTP without any pairing, which makes this the most reliable
     * way to learn a streaming machine's hardware address - the neighbour cache has usually
     * aged the entry out by the time the user opens the editor, and NetBIOS is answered only
     * by Windows. Blocking network I/O: call it off the main thread.
     */
    fun queryMacAddress(): String? = runCatching {
        newHttp().getComputerDetails(false).macAddress
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun decodeServerCert(der: ByteArray?): X509Certificate? {
        if (der == null || der.isEmpty()) return null
        return try {
            val cf = CertificateFactory.getInstance("X.509")
            cf.generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        } catch (e: Exception) {
            Log.w(TAG, "Unable to decode pinned server cert", e)
            null
        }
    }

    /** Encodes a DER certificate to the base64 form stored in [com.limelight.model.ServerProfile]. */
    fun encodeCertForProfile(der: ByteArray?): String =
            if (der == null) "" else Base64.encodeToString(der, Base64.NO_WRAP)
}
