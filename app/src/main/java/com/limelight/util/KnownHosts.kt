/*
 * Copyright (c) 2024  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.util

import android.content.Context
import android.util.Base64
import com.limelight.util.Log
import java.io.File
import java.security.MessageDigest
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.security.auth.x500.X500Principal

private const val TAG = "KnownHosts"

//-------------------- Known SSH Hosts ----------------------------
fun getKnownHostsFile(context: Context) = File(context.filesDir, "known-hosts")

fun forgetKnownHosts(context: Context): Boolean {
    return runCatching { getKnownHostsFile(context).delete() }
            .onFailure { Log.e(TAG, "Cannot forget known hosts", it) }
            .isSuccess
}

//------------------ Trusted certificates -------------------------
private fun bytesToHex(bytes: ByteArray): String {
    val chars = "0123456789abcdef"
    val result = StringBuilder(2 * bytes.size)
    for (b in bytes) {
        // convert to unsigned
        val v = b.toInt() and 0xff
        result.append(chars[v / 16])
        result.append(chars[v % 16])
    }
    return result.toString()
}

private fun getTrustedCertsDir(context: Context) = File(context.filesDir, "trusted_certs")

private fun digestOf(certificate: Certificate, algorithm: String): String =
        bytesToHex(MessageDigest.getInstance(algorithm).digest(certificate.encoded))

/**
 * Name of a stored certificate: its SHA-256 digest.
 *
 * Older builds used SHA-1, which is no longer collision resistant: two different
 * certificates can be made to share one SHA-1 digest. That matters here because the digest
 * is both the file name and the fingerprint the user is asked to compare - a crafted pair
 * would let an attacker present a certificate whose stored twin already exists, and show
 * the same fingerprint the user checked last time. Trust itself is never decided by the
 * digest alone (the stored bytes are compared as a whole), so this is about the human
 * verification step and about not letting one slot hold two candidates.
 */
private fun fileForTrustedCertificate(context: Context, certificate: Certificate): File =
        File(getTrustedCertsDir(context), digestOf(certificate, "SHA-256"))

/** File name written by builds that hashed with SHA-1. Still read, then migrated. */
private fun legacyFileForTrustedCertificate(context: Context, certificate: Certificate): File =
        File(getTrustedCertsDir(context), digestOf(certificate, "SHA-1"))

/**
 * Adds given [certificate] to trusted list.
 */
fun trustCertificate(context: Context, certificate: Certificate) {
    runCatching {
        val certDir = getTrustedCertsDir(context)
        val certFile = fileForTrustedCertificate(context, certificate)
        certDir.mkdirs()
        certFile.writeBytes(certificate.encoded)
    }.onFailure {
        Log.e(TAG, "Error trusting certificate", it)
    }
}

fun deleteTrustedCertificates(context: Context): Boolean {
    return runCatching { getTrustedCertsDir(context).deleteRecursively() }
            .onFailure { Log.e(TAG, "Cannot delete trusted", it) }
            .isSuccess
}

/**
 * The SSH known-hosts file as text, or null when nothing has been recorded. Host keys are public
 * material - the point of the file is to remember what a host's key looked like - so a backup can
 * carry it without the secrets gate. Restoring it means the user is not prompted again for every
 * host after a reinstall.
 */
fun exportKnownHosts(context: Context): String? = runCatching {
    val file = getKnownHostsFile(context)
    if (!file.exists()) null else file.readText().takeIf { it.isNotBlank() }
}.getOrNull()

/** Replaces the known-hosts file with the backed-up [text]. */
fun importKnownHosts(context: Context, text: String) {
    runCatching { getKnownHostsFile(context).writeText(text) }
            .onFailure { Log.e(TAG, "Cannot restore known hosts", it) }
}

/**
 * Every trusted VNC certificate, keyed by the name it is stored under (its SHA-256 digest) and
 * carried as base64 of its encoded bytes. These are trust decisions the user already made, not
 * private keys, so they belong in a backup that does not unlock the secrets section.
 */
fun exportTrustedCerts(context: Context): Map<String, String> = runCatching {
    val files = getTrustedCertsDir(context).listFiles()?.filter { it.isFile } ?: return emptyMap()
    files.associate { it.name to Base64.encodeToString(it.readBytes(), Base64.NO_WRAP) }
}.getOrElse {
    Log.w(TAG, "Cannot read trusted certificates", it)
    emptyMap()
}

/**
 * Writes backed-up trusted certificates into the trusted store. The stored name is the digest, so
 * a key that is not a long hex string is refused - a hand-edited backup must not be able to name
 * an arbitrary file inside the app's directory.
 */
fun importTrustedCerts(context: Context, certs: Map<String, String>) {
    if (certs.isEmpty()) return
    runCatching {
        val dir = getTrustedCertsDir(context)
        dir.mkdirs()
        for ((name, value) in certs) {
            if (!name.matches(Regex("[0-9a-fA-F]{16,}"))) continue
            val bytes = Base64.decode(value, Base64.NO_WRAP)
            File(dir, name).writeBytes(bytes)
        }
    }.onFailure { Log.e(TAG, "Cannot restore trusted certificates", it) }
}

/**
 * Checks whether given [certificate] is trusted.
 */
fun isCertificateTrusted(context: Context, certificate: Certificate): Boolean {
    runCatching {
        val certFactory = CertificateFactory.getInstance("X.509")
        if (matchesStoredCertificate(fileForTrustedCertificate(context, certificate), certificate, certFactory))
            return true

        // A certificate trusted by an older build lives under its SHA-1 name. Accept it
        // and move it, so the upgrade does not re-prompt for every host the user already
        // verified.
        val legacy = legacyFileForTrustedCertificate(context, certificate)
        if (matchesStoredCertificate(legacy, certificate, certFactory)) {
            val current = fileForTrustedCertificate(context, certificate)
            if (!legacy.renameTo(current)) {
                runCatching { legacy.copyTo(current, overwrite = true) }
                        .onFailure { Log.w(TAG, "Cannot migrate a trusted certificate", it) }
            }
            return true
        }
    }.onFailure {
        Log.w(TAG, "Error checking certificate", it)
    }
    return false
}

/**
 * Whether the stored file holds exactly this certificate. The digest only picks the file;
 * the content is compared as a whole, so a hash collision cannot borrow somebody else's
 * slot.
 */
private fun matchesStoredCertificate(file: File, certificate: Certificate, factory: CertificateFactory): Boolean {
    if (!file.exists())
        return false

    // This should always succeed once file exists
    return file.inputStream().use { factory.generateCertificate(it) }.equals(certificate)
}

fun getUnknownCertificateMessage(certificate: X509Certificate): String {
    // The subject and issuer of the certificate we are about to trust are chosen by whoever
    // owns that server, and this text is shown to the user as the thing to verify. RFC 2253
    // escaping does not cover newlines, so a CN holding one would start writing its own
    // lines into the dialog - a forged "This is your own PC", or a second fingerprint.
    // Keep only what a name can actually be made of.
    fun printableOnly(value: String) = buildString {
        value.forEach { append(if (it.isISOControl()) ' ' else it) }
    }.trim().take(128)

    fun commonName(p: X500Principal) = p.name.split(',')  // Doesn't handle escaped comma
                                               .find { it.startsWith("CN=", true) }
                                               ?.let { printableOnly(it.drop(3)) }
                                               ?.takeIf { it.isNotEmpty() }
                                               ?: "Unknown"

    val subject = commonName(certificate.subjectX500Principal)
    val issuer = commonName(certificate.issuerX500Principal)
    val fingerprint = digestOf(certificate, "SHA-256")
            .uppercase()
            .chunked(2)
            .joinToString(" ")

    return """
        Certificate received from server is not trusted. Someone might be impersonating the server.
        
        Subject: $subject
        Issuer: $issuer
        Fingerprint (SHA-256): $fingerprint 
        
        Make sure you are connecting to right server. Click Continue to add this certificate to trusted list.
    """.trimIndent()
}
