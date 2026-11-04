/*
 * The receiving half of LocalSend: an HTTPS server on 53317 that speaks just enough
 * HTTP/1.1 for the protocol - no keep-alive, no pipelining, no HTTP/2, mirroring the ALPN
 * restriction both peers already observe. Two body shapes have to be understood: the JSON
 * of `register`/`prepare-upload` (short, Content-Length), and the file stream of `upload`,
 * which the official Rust sender transmits as chunked because it wraps a file stream of
 * unknown length. Everything else on the wire would be a peer testing our patience.
 *
 * The user is part of the protocol here: a `prepare-upload` blocks on the accept/decline
 * dialog exactly like upstream's `ServerEventV2::PrepareUpload` waits on `decision_tx`,
 * and an unanswered question times out into a decline.
 */
package com.limelight.localsend

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import com.limelight.util.Log
import com.limelight.R
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

private const val TAG = "LsReceiver"

/** One `prepare-upload` the user has to rule on. */
class IncomingOffer(
    val sender: LsDeviceInfo,
    val files: Map<String, LsFileDto>,
    internal val decision: CompletableFuture<Set<String>>,
) {
    companion object {
        /** Sentinel completing [decision]: the user pressed decline (HTTP 403). */
        @JvmField
        val DECLINED: Set<String> = java.util.Collections.unmodifiableSet(HashSet<String>())
    }
}

class LsReceiver {

    private var serverSocket: ServerSocket? = null
    private var identityProvider: (() -> LsSecurityContext)? = null
    private var infoProvider: (() -> LsInfoResponse)? = null
    private var registerListener: ((LsDeviceInfo, String?, String) -> Unit)? = null

    /** fileId -> token of an accepted session; upload must present the matching one. */
    private val sessions = ConcurrentHashMap<String, Session>()
    private val random = SecureRandom()

    private class Session(val senderFingerprint: String?, val alias: String, val files: Map<String, PreparedFile>) {
        val totalBytes: Long = files.values.sumOf { it.dto.size }.coerceAtLeast(1L)
        val fileCount: Int = files.size
        private val received = java.util.concurrent.atomic.AtomicLong(0)
        /** Files that have reached an end, whether they landed or were written off. */
        private val accounted = java.util.concurrent.atomic.AtomicInteger(0)

        /** Bytes seen so far across every file of this session, mirrored to the shared bar. */
        fun noteProgress(fileName: String, delta: Long) {
            val done = received.addAndGet(delta).coerceAtMost(totalBytes)
            LsManager.transferProgress.value = LsTransferProgress(
                label = fileName, doneBytes = done, totalBytes = totalBytes,
                fileIndex = (accounted.get() + 1).coerceAtMost(fileCount), fileCount = fileCount,
                incoming = true,
            )
        }

        /** One file landed. The bar is only reset once the last file of the batch is done. */
        fun fileDone() {
            if (accounted.incrementAndGet() >= fileCount) clear()
        }

        /**
         * One file could not be saved. Only the bar is left alone here: a bad file must not wipe
         * the shared progress of the ones still arriving, so the batch keeps reporting until every
         * file is accounted for, at which point the ordinary [clear] runs.
         */
        fun fileFailed() {
            if (accounted.incrementAndGet() >= fileCount) clear()
        }

        fun clear() {
            LsManager.transferProgress.value = null
        }
    }
    private class PreparedFile(val token: String, val dto: LsFileDto)

    fun configure(
        identity: () -> LsSecurityContext,
        info: () -> LsInfoResponse,
        onRegister: (LsDeviceInfo, String?, String) -> Unit,
    ) {
        identityProvider = identity
        infoProvider = info
        registerListener = onRegister
    }

    @Synchronized
    fun start(keepAlive: () -> Boolean) {
        if (serverSocket != null) return
        try {
            val identity = identityProvider!!()
            val context = LsCrypto.serverContext(identity)
            val factory = context.serverSocketFactory
            val socket = factory.createServerSocket(LsConstants.PORT) as SSLServerSocket
            // The official sender always presents its certificate; asking for one is how
            // we learn the peer's true fingerprint instead of the one its body claims.
            socket.wantClientAuth = true
            socket.enabledProtocols = socket.enabledProtocols.filter { it.startsWith("TLSv1") }.toTypedArray()
            serverSocket = socket
            Thread {
                while (keepAlive()) {
                    val incoming = try {
                        socket.accept()
                    } catch (e: Exception) {
                        if (socket.isClosed) break else {
                            Log.w(TAG, "accept failed", e); continue
                        }
                    }
                    Thread { handleConnection(incoming) }.also { it.isDaemon = true }.start()
                }
                closeQuietly()
            }.also { it.isDaemon = true }.start()
        } catch (e: Exception) {
            Log.w(TAG, "Could not bind the receive port (taken by another LocalSend app?)", e)
            serverSocket = null
        }
    }

    @Synchronized
    fun stop() {
        val socket = serverSocket
        serverSocket = null
        try {
            socket?.close()
        } catch (e: Exception) {
            // already gone
        }
        sessions.clear()
    }

    private fun closeQuietly() {
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // ignore
        }
        serverSocket = null
    }

    // region connection handling

    private fun handleConnection(raw: Socket) {
        try {
            raw.use { socket ->
                socket.soTimeout = 120_000
                val input = socket.getInputStream()
                // Headers are read one byte at a time: any buffering here would steal
                // body bytes from the raw stream the handlers read next.
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(' ')
                if (parts.size < 2) return
                val method = parts[0]
                val pathAndQuery = parts[1]
                val path = pathAndQuery.substringBefore('?')
                val query = parseQuery(pathAndQuery.substringAfter('?', ""))
                val headers = readHeaders(input)

                if (!path.startsWith(LsConstants.PATH_PREFIX)) {
                    respond(socket.getOutputStream(), 404, "application/json",
                        JSONObject().put("message", "Not found").toString())
                    return
                }

                val certFingerprint = (socket as? SSLSocket)
                    ?.session?.let { LsCrypto.fingerprintOf(it) }

                when {
                    path.endsWith("/register") && method == "POST" -> {
                        val info = lsJson.decodeFromString<LsDeviceInfo>(
                            readJsonBody(socket.getInputStream(), headers))
                        // Upstream only trusts a claim the client certificate proves.
                        val proven = certFingerprint == null ||
                            info.fingerprint.equals(certFingerprint, ignoreCase = true)
                        if (proven) registerListener?.invoke(info, certFingerprint, socket.inetAddress.hostAddress)
                        respondJson(socket.getOutputStream(), 200, infoProvider!!())
                    }

                    path.endsWith("/info") -> {
                        respondJson(socket.getOutputStream(), 200, infoProvider!!())
                    }

                    path.endsWith("/prepare-upload") && method == "POST" ->
                        handlePrepareUpload(socket, headers, certFingerprint)

                    path.endsWith("/upload") && method == "POST" ->
                        handleUpload(socket, headers, query)

                    path.endsWith("/cancel") && method == "POST" -> {
                        sessions.remove(query["sessionId"])
                        respondJson(socket.getOutputStream(), 200, "{}")
                    }

                    else -> respond(socket.getOutputStream(), 404, "application/json",
                        JSONObject().put("message", "Not found").toString())
                }
            }
        } catch (e: Exception) {
            // A peer dropping the connection mid-request is normal (cancel, declined).
            Log.d(TAG, "connection ended abruptly", e)
        }
    }

    private fun handlePrepareUpload(
        socket: Socket,
        headers: Map<String, String>,
        certFingerprint: String?,
    ) {
        val body = readJsonBody(socket.getInputStream(), headers)
        val request = lsJson.decodeFromString(LsPrepareUploadRequest.serializer(), body)

        val decision = CompletableFuture<Set<String>>()
        val offer = IncomingOffer(request.info, request.files, decision)
        val context = LsUi.context()
        if (context == null) {
            // No UI to ask: decline rather than accept silently on the user's behalf.
            respond(socket.getOutputStream(), 403, "application/json",
                JSONObject().put("message", "Receiver has no UI to confirm").toString())
            return
        }
        LsUi.showIncomingOffer(context, offer)

        // The dialog rules from the main thread; a question nobody answers becomes a
        // decline, not an indefinitely held-open connection.
        val acceptedIds = try {
            decision.get(60, TimeUnit.SECONDS)
        } catch (e: Exception) {
            emptySet()
        }
        if (acceptedIds.isEmpty()) {
            // An empty selection answers 204 (protocol): "transfer over, nothing saved".
            // An explicit decline completes the future with the sentinel below.
            if (acceptedIds === IncomingOffer.DECLINED) {
                respond(socket.getOutputStream(), 403, "application/json",
                    JSONObject().put("message", "Rejected by user").toString())
                return
            }
            respond(socket.getOutputStream(), 204, null, null)
            return
        }

        val sessionId = java.util.UUID.randomUUID().toString()
        val tokens = HashMap<String, String>()
        val prepared = HashMap<String, PreparedFile>()
        for (id in acceptedIds) {
            val dto = request.files[id] ?: continue
            val token = ByteArray(18).also { random.nextBytes(it) }.let {
                Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            }
            tokens[id] = token
            prepared[id] = PreparedFile(token, dto)
        }
        sessions[sessionId] = Session(certFingerprint, request.info.alias, prepared)

        val response = LsPrepareUploadResponse(sessionId, tokens)
        respondJson(socket.getOutputStream(), 200, response)
    }

    private fun handleUpload(socket: Socket, headers: Map<String, String>, query: Map<String, String>) {
        val session = sessions[query["sessionId"]]
        val prepared = session?.files?.get(query["fileId"])
        if (prepared == null || prepared.token != query["token"]) {
            respond(socket.getOutputStream(), 403, "application/json",
                JSONObject().put("message", "Invalid token").toString())
            return
        }

        val context = LsUi.context() ?: return
        val content = socket.getInputStream()
        val sink = LsSave.openOutput(context, prepared.dto.fileName)
            ?: run {
                session.fileFailed()
                respond(socket.getOutputStream(), 500, "application/json",
                    JSONObject().put("message", "Could not open a save target").toString())
                return
            }

        val digest = java.security.MessageDigest.getInstance("SHA-256")
        try {
            val out = sink.stream ?: throw java.io.IOException("Could not write to the save target")
            readBody(content, headers) { chunk, len ->
                digest.update(chunk, 0, len)
                out.write(chunk, 0, len)
                session.noteProgress(prepared.dto.fileName, len.toLong())
            }
        } catch (e: Exception) {
            LsSave.abort(sink)
            session.fileFailed()
            respond(socket.getOutputStream(), 500, "application/json",
                JSONObject().put("message", "Incomplete upload").toString())
            return
        }

        // The checksum is optional on the wire; when it is there, it is binding (422).
        val expected = prepared.dto.sha256
        if (expected != null) {
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(expected, ignoreCase = true)) {
                LsSave.discard(sink)
                session.fileFailed()
                respond(socket.getOutputStream(), 422, "application/json",
                    JSONObject().put("message", "Checksum mismatch").toString())
                return
            }
        }

        LsSave.finish(sink)
        LsUi.toast(context, context.getString(
            R.string.msg_localsend_saved, prepared.dto.fileName, LsSave.describeTarget(sink)))
        session.fileDone()
        if (prepared.dto.preview != null && prepared.dto.fileType.startsWith("text/")) {
            LsUi.showMessage(LsUi.context(), session.alias, prepared.dto.preview)
        }
        respond(socket.getOutputStream(), 200, null, "{}")
    }

    // region http plumbing

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isEmpty()) return emptyMap()
        return raw.split('&').mapNotNull { pair ->
            val key = pair.substringBefore('=').ifEmpty { return@mapNotNull null }
            val value = java.net.URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
            key to value
        }.toMap()
    }

    private fun readHeaders(input: InputStream): Map<String, String> {
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.substring(0, idx).lowercase(Locale.ROOT)] = line.substring(idx + 1).trim()
        }
        return headers
    }

    private fun readJsonBody(input: InputStream, headers: Map<String, String>): String {
        val out = ByteArrayOutputStream()
        readBody(input, headers) { chunk, len -> out.write(chunk, 0, len) }
        return out.toString(Charsets.UTF_8.name())
    }

    /**
     * One CR/LF-terminated ISO-8859-1 line, read byte by byte so the stream stays
     * positioned right after it for whatever reader comes next.
     */
    private fun readLine(input: InputStream): String? {
        val builder = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (builder.isEmpty()) null else builder.toString()
            if (b == '\n'.code) {
                if (builder.isNotEmpty() && builder.last() == '\r') builder.deleteCharAt(builder.length - 1)
                return builder.toString()
            }
            builder.append(b.toChar())
        }
    }

    /**
     * Streams the request body, Content-Length or chunked-transfer-coding, feeding every
     * fragment to [consume]. Chunked is what the official Rust sender produces for uploads.
     */
    private fun readBody(input: InputStream, headers: Map<String, String>, consume: (ByteArray, Int) -> Unit) {
        if (headers["transfer-encoding"]?.lowercase(Locale.ROOT)?.contains("chunked") == true) {
            while (true) {
                val sizeLine = readLine(input) ?: return
                val size = sizeLine.substringBefore(';').trim().toInt(16)
                    ?: throw java.io.IOException("Bad chunk header")
                if (size == 0) {
                    readLine(input) // trailing CRLF after the last chunk
                    return
                }
                var remaining = size
                val buffer = ByteArray(minOf(size, 8192))
                while (remaining > 0) {
                    val read = input.read(buffer, 0, minOf(remaining, buffer.size))
                    if (read <= 0) throw java.io.IOException("Truncated chunk")
                    consume(buffer, read)
                    remaining -= read
                }
                readLine(input) // CRLF after the chunk data
            }
        } else {
            val length = headers["content-length"]?.toLongOrNull() ?: 0L
            val buffer = ByteArray(8192)
            var left = length
            while (left > 0) {
                val read = input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
                if (read <= 0) break
                consume(buffer, read)
                left -= read
            }
        }
    }

    private fun respondJson(out: OutputStream, code: Int, value: Any) {
        val body = when (value) {
            is String -> value
            is LsInfoResponse -> lsJson.encodeToString(LsInfoResponse.serializer(), value)
            is LsPrepareUploadResponse -> lsJson.encodeToString(LsPrepareUploadResponse.serializer(), value)
            else -> value.toString()
        }
        respond(out, code, "application/json", body)
    }

    private fun respond(out: OutputStream, code: Int, contentType: String?, body: String?) {
        val status = when (code) {
            200 -> "OK"; 204 -> "No Content"; 403 -> "Forbidden"
            404 -> "Not Found"; 422 -> "Unprocessable Entity"; 500 -> "Internal Server Error"
            else -> "Unknown"
        }
        val payload = body?.toByteArray(Charsets.UTF_8)?.takeIf { it.isNotEmpty() }
        val builder = StringBuilder()
        builder.append("HTTP/1.1 $code $status\r\n")
        if (code != 204) {
            if (payload != null) {
                if (contentType != null) builder.append("Content-Type: $contentType\r\n")
                builder.append("Content-Length: ${payload.size}\r\n")
            } else {
                builder.append("Content-Length: 0\r\n")
            }
        }
        builder.append("Connection: close\r\n\r\n")
        out.write(builder.toString().toByteArray(Charsets.ISO_8859_1))
        payload?.let { out.write(it) }
        out.flush()
    }

    // endregion

    // region saving

    /**
     * Where an incoming file lands. On API 29+ MediaStore's public Downloads collection -
     * no runtime permission needed and the user finds the file where they expect it. Below
     * that, scoped storage rules made the app-specific directory the only guaranteed
     * writable target without a permission we do not declare.
     */
    object LsSave {
        class Target(val uri: android.net.Uri?, val file: File?, var stream: OutputStream?)

        fun openOutput(context: Context, fileName: String): Target? {
            return try {
                if (Build.VERSION.SDK_INT >= 29) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val resolver = context.contentResolver
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: return null
                    Target(uri, null, resolver.openOutputStream(uri))
                } else {
                    val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                        ?: context.filesDir
                    val file = uniqueFile(File(dir, fileName))
                    Target(null, file, file.outputStream())
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not open the save target", e)
                null
            }
        }

        fun finish(target: Target) {
            runCatching { target.stream?.flush(); target.stream?.close() }
            target.stream = null
        }

        /** Human hint of where the file landed, shown in the saved toast. */
        fun describeTarget(target: Target): String = when {
            target.file != null -> target.file.absolutePath
            else -> Environment.DIRECTORY_DOWNLOADS
        }

        fun abort(target: Target) {
            runCatching { target.stream?.close() }
            target.stream = null
            discard(target)
        }

        fun discard(target: Target) {
            target.uri?.let { runCatching { LsUi.context()?.contentResolver?.delete(it, null, null) } }
            target.file?.delete()
        }

        private fun uniqueFile(candidate: File): File {
            if (!candidate.exists()) return candidate
            val name = candidate.nameWithoutExtension
            val ext = candidate.extension
            var index = 1
            while (true) {
                val next = File(candidate.parentFile, if (ext.isEmpty()) "$name ($index)" else "$name ($index).$ext")
                if (!next.exists()) return next
                index++
            }
        }
    }

    // endregion
}
