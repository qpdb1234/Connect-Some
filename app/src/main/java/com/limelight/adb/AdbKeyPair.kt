/*
 * Adapted from Easycontrol_For_Car (https://github.com/Eiyooooo/Easycontrol_For_Car),
 * Copyright (c) Eiyooooo.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.adb

import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher

/**
 * The RSA key pair this app presents to `adbd` as an ADB *host* credential.
 *
 * Everything here is plain JDK crypto - no Android keystore involved - because the
 * wire format is peculiar:
 *
 *  - the token is signed with raw `RSA/ECB/NoPadding` over its SHA-1, feeding the PKCS#1
 *    v1.5 padding by hand ([SIGNATURE_PADDING] + a SHA-1 `DigestInfo` prefix);
 *  - the *public* key is not X.509/SPKI but adb's own 524-byte Montgomery layout.
 *
 * The private key is persisted as PKCS#8 DER next to the public key in text form
 * (`base64(524 bytes) + " user@host"`, the layout `adb` itself writes to
 * `$HOME/.android/adb_keys`), so a user who ever authorised a desktop ADB host can
 * recognise the file. Generated once per install: the controlled device shows
 * "Allow USB debugging?" against *this* key, and forgetting it means re-authorising.
 */
class AdbKeyPair private constructor(
        // Readable inside the module: wireless pairing has to self-sign a certificate
        // over *this* key, because adbd trusts the public half of whatever paired socket
        // authenticated - a second key would pair an identity we never connect with.
        internal val privateKey: PrivateKey,
        /** Ready-to-send AUTH_TYPE_RSA_PUBLIC payload: base64 text + comment + NUL. */
        val publicKeyBytes: ByteArray,
) {

    /** Signs an AUTH challenge token. Returns the 256-byte RSA blob. */
    @Throws(Exception::class)
    fun signPayload(payload: ByteBuffer?): ByteArray {
        if (payload == null) return byteArrayOf(0)
        // Exactly the remaining bytes of the token, honouring position and limit. Going
        // through `payload.array()` instead would sign the whole backing array - which is
        // only the same thing while the buffer starts at offset 0 and fills it.
        val token = ByteArray(payload.remaining())
        payload.duplicate().get(token)

        // adbd verifies with RSA_verify(NID_sha1, token, ...): it treats the 20-byte token
        // ITSELF as the already-computed digest and only wraps it in the DigestInfo prefix,
        // so the raw-RSA block is DigestInfo(SHA-1) || token - NOT || SHA-1(token). Feeding
        // it a second hash of the token (an earlier revision did exactly that) produced a
        // well formed 256 byte block that never matched, so every connection was rejected at
        // the signature step and fell through to the AUTH_TYPE_RSA_PUBLIC path - which some
        // devices (a MIX 2S on Android 14 among them) answer with silence, hanging the
        // handshake and forcing a fresh "Allow USB debugging?" tap every single time.
        val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, privateKey)
        cipher.update(SIGNATURE_PADDING)
        return cipher.doFinal(token)
    }

    companion object {

        private const val KEY_LENGTH_BITS = 2048
        private const val KEY_LENGTH_BYTES = KEY_LENGTH_BITS / 8
        private const val KEY_LENGTH_WORDS = KEY_LENGTH_BYTES / 4

        /**
         * The exponent for R^2 mod N. [BigInteger.TWO] would read better but it only
         * exists from API 33 while this app supports API 26, and touching it throws
         * NoSuchFieldError on older devices - so build the value the portable way.
         */
        private val BIG_TWO: BigInteger = BigInteger.valueOf(2)

        const val PRIVATE_KEY_FILE = "adb_private.key"
        const val PUBLIC_KEY_FILE = "adb_public.key"

        /** Comment written into the public key file; shows up on the confirmation dialog. */
        private const val KEY_COMMENT = " ConnectSome"

        /**
         * Returns the stored key pair, creating (and persisting) one on first use.
         * A half-written pair is regenerated as a whole - the two files are only
         * meaningful together.
         */
        @Throws(Exception::class)
        @JvmStatic
        fun loadOrCreate(filesDir: File): AdbKeyPair {
            val privateFile = File(filesDir, PRIVATE_KEY_FILE)
            val publicFile = File(filesDir, PUBLIC_KEY_FILE)
            if (!privateFile.isFile || !publicFile.isFile || publicFile.length() == 0L) {
                privateFile.delete()
                publicFile.delete()
                generate(privateFile, publicFile)
            }
            try {
                return read(privateFile, publicFile)
            } catch (e: Exception) {
                // A key file that cannot be parsed is not a key pair this app can ever
                // authenticate with - a write that died halfway (killed install, full
                // storage) leaves exactly this behind. Regenerating costs the user one
                // "Allow USB debugging?" tap on each device; refusing to start would cost
                // the whole feature, and it would repeat on every retry.
                privateFile.delete()
                publicFile.delete()
                generate(privateFile, publicFile)
                return read(privateFile, publicFile)
            }
        }

        @Throws(Exception::class)
        private fun read(privateKeyFile: File, publicKeyFile: File): AdbKeyPair {
            // adb terminates the public key payload with a NUL, which is *not* part of
            // the file; grow the copy by one byte and leave it zeroed.
            val publicKeyRaw = publicKeyFile.readBytes()
            val publicKeyBytes = publicKeyRaw.copyOf(publicKeyRaw.size + 1)
            val privateKey = KeyFactory.getInstance("RSA")
                    .generatePrivate(PKCS8EncodedKeySpec(privateKeyFile.readBytes()))
            return AdbKeyPair(privateKey, publicKeyBytes)
        }

        @Throws(Exception::class)
        private fun generate(privateKeyFile: File, publicKeyFile: File) {
            val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
            keyPairGenerator.initialize(KEY_LENGTH_BITS)
            val keyPair = keyPairGenerator.generateKeyPair()

            val adbPublicKey = convertRsaPublicKeyToAdbFormat(keyPair.public as RSAPublicKey)
            // Written atomically enough: a crash between the two files is repaired by
            // loadOrCreate() deleting both.
            privateKeyFile.outputStream().use { it.write(keyPair.private.encoded) }
            publicKeyFile.writeText(
                    AdbBase64.encodeToString(adbPublicKey).replace("\n", "") + KEY_COMMENT)
        }

        /**
         * Montgomery-friendly RSA layout adb expects:
         * `words, -n0inv, n[words], rr = R^2 mod N, exponent` - all 32-bit little endian.
         */
        private fun convertRsaPublicKeyToAdbFormat(pubkey: RSAPublicKey): ByteArray {
            val r32 = BigInteger.ZERO.setBit(32)
            var n = pubkey.modulus
            var rr = BigInteger.ZERO.setBit(KEY_LENGTH_WORDS * 32).modPow(BIG_TWO, n)
            val n0inv = n.remainder(r32).modInverse(r32)

            val myN = IntArray(KEY_LENGTH_WORDS)
            val myRr = IntArray(KEY_LENGTH_WORDS)
            for (i in 0 until KEY_LENGTH_WORDS) {
                val resRr = rr.divideAndRemainder(r32)
                rr = resRr[0]
                myRr[i] = resRr[1].toInt()

                val resN = n.divideAndRemainder(r32)
                n = resN[0]
                myN[i] = resN[1].toInt()
            }

            val bbuf = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
            bbuf.putInt(KEY_LENGTH_WORDS)
            bbuf.putInt(n0inv.negate().toInt())
            for (value in myN) bbuf.putInt(value)
            for (value in myRr) bbuf.putInt(value)
            bbuf.putInt(pubkey.publicExponent.toInt())
            return bbuf.array()
        }

        /**
         * PKCS#1 v1.5 block type 0 prefix for a 2048-bit RSA signature over SHA-1:
         * `00 01` then 0xff padding, a `00` separator and the 15-byte DigestInfo. The
         * 20-byte token is appended by [Cipher.doFinal] into the DigestInfo's digest slot
         * (adbd hashes nothing itself - the token already is a 20-byte digest), so the total
         * is exactly 256 bytes - which means this array itself is 256-20 long and the
         * DigestInfo occupies its last 15 bytes.
         */
        private val SHA1_RSA_DIGEST_INFO = byteArrayOf(
                0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
        )

        @JvmField
        val SIGNATURE_PADDING: ByteArray =
                ByteArray(256 - 20).also { padding ->
                    val digestInfoAt = padding.size - SHA1_RSA_DIGEST_INFO.size
                    padding[0] = 0x00.toByte()
                    padding[1] = 0x01.toByte()
                    for (i in 2 until digestInfoAt - 1) padding[i] = 0xff.toByte()
                    padding[digestInfoAt - 1] = 0x00.toByte()
                    SHA1_RSA_DIGEST_INFO.copyInto(padding, digestInfoAt)
                }
    }
}
