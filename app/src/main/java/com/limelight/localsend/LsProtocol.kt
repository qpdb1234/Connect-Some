/*
 * LocalSend protocol v2.2 wire types.
 *
 * Mirrors the official implementation (localsend-main, packages/core): devices announce
 * themselves over UDP multicast on port 53317 with a flat camelCase JSON, confirm each
 * other with an HTTP `register` request, and move data through `prepare-upload` +
 * `upload`. The DTOs below are deliberately shaped like the Rust serde structs
 * (`RegisterDtoV2`, `FileDto`, `PrepareUploadRequestDtoV2`...) so a stock LocalSend peer
 * accepts everything we emit; unknown fields on incoming JSON are ignored, which is how
 * older/newer peers stay compatible.
 */
package com.limelight.localsend

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Fixed protocol constants, identical to the official app. */
object LsConstants {
    /** UDP multicast + HTTPS server port. The v1 apps used 53331; v2 settled on 53317. */
    const val PORT = 53317

    /** Inside 224.0.0.0/24: the only range some Android devices can receive multicast on. */
    const val MULTICAST_GROUP = "224.0.0.167"

    /** Protocol version we advertise. Two digits; peers only branch on the major one. */
    const val VERSION = "2.2"

    const val PATH_PREFIX = "/api/localsend/v2"

    /** Same field the HTTPS certificate fingerprint check pins a peer's identity to. */
    const val PROTOCOL_HTTPS = "https"

    /** Announcement burst delays of the official sender: single datagrams get lost. */
    val ANNOUNCE_DELAYS_MS = longArrayOf(100, 500, 2000)

    /** A peer that stopped re-confirming itself is dropped after this long. */
    const val PEER_TTL_MS = 10 * 60 * 1000L
}

/** Lenient on purpose: a peer from a different protocol minor may add fields. */
val lsJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    isLenient = true
}

/**
 * The device block, shared by the UDP announcement, the `register` body and the
 * `prepare-upload` `info` field (they are the same struct upstream). `announce` is only
 * serialized into the UDP datagram; HTTP bodies leave it null so it is omitted.
 */
@Serializable
data class LsDeviceInfo(
    val alias: String,
    val version: String,
    @SerialName("deviceModel") val deviceModel: String? = null,
    @SerialName("deviceType") val deviceType: String? = null,
    val fingerprint: String,
    val port: Int,
    val protocol: String = LsConstants.PROTOCOL_HTTPS,
    val download: Boolean = false,
    val announce: Boolean? = null,
)

/** Same names as the Rust `FileDto`. `fileType` travels as a MIME string since v2.1. */
@Serializable
data class LsFileDto(
    val id: String,
    val fileName: String,
    val size: Long,
    @SerialName("fileType") val fileType: String,
    val sha256: String? = null,
    val preview: String? = null,
    val metadata: LsFileMetadata? = null,
)

@Serializable
data class LsFileMetadata(
    val modified: String? = null,
    val accessed: String? = null,
)

@Serializable
data class LsPrepareUploadRequest(
    val info: LsDeviceInfo,
    val files: Map<String, LsFileDto>,
)

@Serializable
data class LsPrepareUploadResponse(
    val sessionId: String,
    val files: Map<String, String>,
)

/** Reply body of `register` and `info` on the v2 wire. */
@Serializable
data class LsInfoResponse(
    val alias: String,
    val version: String,
    @SerialName("deviceModel") val deviceModel: String? = null,
    @SerialName("deviceType") val deviceType: String? = null,
    val fingerprint: String,
    val download: Boolean = false,
)

@Serializable
data class LsErrorResponse(val message: String = "")

/**
 * A peer we confirmed on the network. `fingerprint` is the SHA-256 (uppercase hex) of the
 * TLS certificate the peer presented during the register handshake - not the one claimed
 * in a body, which is why transfers pin every connection to it.
 */
data class LsPeer(
    val fingerprint: String,
    val alias: String,
    val host: String,
    val port: Int,
    val deviceModel: String?,
    val version: String,
    val lastSeen: Long,
)
