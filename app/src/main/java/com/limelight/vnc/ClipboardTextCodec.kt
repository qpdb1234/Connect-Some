/**
 * Encoding rules for RFB clipboard text, shared by both sides that speak it:
 * the full client [RfbClientNative] and the clipboard-only [RfbClipboardBridge].
 *
 * It lives here because a clipboard payload read one way and written the other
 * is worse than reading both wrong: the two have to agree on what an unmarked
 * byte means, and on which codepage the user's `Strict Latin-1` switch forgives.
 */
package com.limelight.vnc

import com.limelight.util.Log
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale

object ClipboardTextCodec {

    /** Tag for the clipboard diagnostics; kept as is so old log filters still match. */
    private const val TAG = "RfbClientNative"

    /**
     * Turns a cut-text payload into a string.
     *
     * The UTF-8 flavour of the clipboard (RFB extended clipboard) is unambiguous. The
     * classic `ServerCutText` message is specified as Latin-1 - and Windows servers do
     * not honour that for CJK: they put the clipboard's *ANSI codepage* bytes in there,
     * because that is what `GetClipboardData(CF_TEXT)` hands them. Read as Latin-1,
     * `电脑` arrives as `µçÄÔ`.
     *
     * So for a payload that is not valid Latin-1 text, try the codepages that can
     * actually explain the bytes, and fall back to what the spec promises. The guess
     * can be turned off with [strictLatin1] - see
     * [com.limelight.util.AppPreferences.Server.clipboardStrictLatin1]. UTF-8 announced
     * by the extended clipboard is not a guess, so it keeps working either way.
     */
    fun decode(bytes: ByteArray, isUTF8: Boolean, strictLatin1: Boolean): String {
        if (isUTF8) return StandardCharsets.UTF_8.decode(ByteBuffer.wrap(bytes)).toString()
        if (strictLatin1) return latin1(bytes)

        // Accented Latin-1 text (`café`) is not valid UTF-8, and a Chinese payload is
        // neither ASCII nor UTF-8 - but UTF-8 is unambiguous when it does type-check,
        // so it goes first: plenty of servers ignore the type byte and send UTF-8.
        tryDecode(StandardCharsets.UTF_8, bytes)?.let { return it }

        for (charset in ansiCutTextCharsets()) {
            val decoded = tryDecode(charset, bytes) ?: continue
            // Two legacy bytes that happen to form an assigned code point are not
            // evidence of a CJK payload; `Üb` is a legal GBK pair. Insist on a real
            // CJK character, otherwise trust the Latin-1 the server claimed.
            if (decoded.any { it.isCjkScript() }) {
                Log.i(TAG, "server sent ${charset.name()} in a Latin-1 clipboard field")
                return decoded
            }
        }

        return latin1(bytes)
    }

    /**
     * Encodes clipboard text for the classic cut-text message. [decode] may meet a
     * server without extended-clipboard support sending GBK/Big5; this is the same
     * choice in the other direction. Latin-1 stays the default because that is the
     * protocol, and it is only abandoned when it cannot represent the text at all
     * (every CJK character would otherwise leave the wire as `?`).
     */
    fun encode(text: String, strictLatin1: Boolean): ByteArray {
        if (strictLatin1) return latin1(text)

        if (StandardCharsets.ISO_8859_1.newEncoder().canEncode(text)) return latin1(text)

        for (charset in ansiCutTextCharsets()) {
            if (charset.newEncoder().canEncode(text)) return text.toByteArray(charset)
        }
        return latin1(text)
    }

    /** What the classic cut-text field promises, and what the switch settles on. */
    private fun latin1(bytes: ByteArray): String =
            StandardCharsets.ISO_8859_1.decode(ByteBuffer.wrap(bytes)).toString()

    private fun latin1(text: String): ByteArray = text.toByteArray(StandardCharsets.ISO_8859_1)

    /**
     * The ANSI codepages a Windows server may have used for the Latin-1 field.
     *
     * The bytes cannot settle it: every candidate here is a superset that will
     * happily decode a Western payload (`Grüße` contains a legal GBK pair, and the
     * umlaut eats a letter while it is at it). So the guess is only offered when the
     * phone's own locale points at that script - which is also the codepage the
     * server most likely runs, since both follow the user's system language. For
     * everyone else the protocol's Latin-1 is honoured, mojibake and all.
     */
    private fun ansiCutTextCharsets(): List<Charset> {
        val locale = Locale.getDefault()
        val candidates = when (locale.language) {
            "zh" -> {
                val traditional = locale.country in listOf("TW", "HK", "MO") || locale.script == "Hant"
                if (traditional) listOf("Big5", "GBK") else listOf("GBK", "Big5")
            }
            "ja" -> listOf("Shift_JIS", "EUC-JP")
            "ko" -> listOf("EUC-KR")
            else -> return emptyList()
        }
        return candidates
                .mapNotNull { name -> runCatching { Charset.forName(name) }.getOrNull() }
    }

    /** Strict decode - null when [bytes] cannot be represented in [charset] at all. */
    private fun tryDecode(charset: Charset, bytes: ByteArray): String? = runCatching {
        charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
    }.getOrNull()

    /**
     * Han, kana, Hangul, CJK punctuation and compatibility forms - the characters
     * an ANSI-codepage clipboard payload is recognised by. Latin-1 mojibake only
     * ever lands on the Cyrillic/Greek/symbol half of the codepage, never here.
     */
    private fun Char.isCjkScript(): Boolean =
            this in '\u2E80'..'\u9FFF' || this in '\uAC00'..'\uD7AF' ||
                    this in '\uF900'..'\uFAFF' || this in '\uFF00'..'\uFF60'
}
