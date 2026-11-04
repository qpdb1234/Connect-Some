/*
 * The link side of LocalSend: hands a handful of files out over plain HTTP so that any
 * browser on the same network can pull them with a URL.
 *
 * This is deliberately not the LocalSend protocol. The official transfer is mutual HTTPS
 * with pinned self-signed certificates, which a browser cannot do without the user clicking
 * through a warning, and it needs the other machine to run LocalSend at all. A plain GET on
 * a link works everywhere - a phone with the app closed, a laptop, a TV - which is the whole
 * point of the feature. What is given up is the pull side of that trade: the link is
 * unauthenticated and unencrypted, so it is only ever meaningful inside the LAN it was made
 * in, it exists only while this app runs, and it is not a relay. Nothing here ever listens
 * for anything but these two paths.
 */
package com.limelight.localsend

import android.content.Context
import android.text.format.Formatter
import com.limelight.util.Log
import com.limelight.R
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.UUID

private const val TAG = "LsShareServer"

/** The two bytes every HTTP line and every chunk of a body is terminated with. */
private val CRLF = byteArrayOf(13, 10)

/**
 * What the UI is told about one live link: enough to draw the row (its address, how many files
 * and which) without exposing the streams behind them. [files] is ordered and each entry keeps a
 * stable [SharedFileInfo.id], so a row can add or drop a file without invalidating the URLs of
 * the files that stayed.
 */
data class SharedFileInfo(val id: String, val name: String, val size: Long)

data class ShareInfo(val token: String, val url: String, val files: List<SharedFileInfo>)

/** A shared file paired with the id its download URL is built from. */
private class ShareFile(val id: String, val outgoing: LsOutgoingFile)

/** One picked batch of files, reachable under its own random path. */
private class Share(val token: String, val host: String, val port: Int, val files: MutableList<ShareFile>) {
    val url: String get() = "http://$host:$port/s/$token"
    fun info() = ShareInfo(token, url, files.map { SharedFileInfo(it.id, it.outgoing.fileName, it.outgoing.size) })
}

class LsShareServer(
    private val context: Context,
    /** Called on every change so the owner can mirror the list into its own state. */
    private val onChanged: (List<ShareInfo>) -> Unit = {},
) {

    private var socket: ServerSocket? = null

    /** Live shares, keyed by token. Insertion order is preserved for the UI list. */
    private val shares = LinkedHashMap<String, Share>()

    /** Whether anything is being handed out right now; drives the "stop sharing" button. */
    val active: Boolean get() = socket != null && !socket!!.isClosed

    /**
     * Publishes [files] and returns the URL that lists them, or null when nothing could be
     * served. [host] is this device's own LAN address, which is the only address a browser
     * on that network can reach it at.
     */
    @Synchronized
    fun share(files: List<LsOutgoingFile>, host: String?): String? {
        if (files.isEmpty() || host.isNullOrEmpty()) return null
        val server = ensureRunning() ?: return null
        val share = Share(
                UUID.randomUUID().toString().take(8), host, server.localPort,
                files.map { ShareFile(newFileId(), it) }.toMutableList())
        shares[share.token] = share
        publish()
        return share.url
    }

    /** Adds [files] to the share named [token], if it still exists. */
    @Synchronized
    fun addFiles(token: String, files: List<LsOutgoingFile>) {
        val share = shares[token] ?: return
        if (files.isEmpty()) return
        share.files.addAll(files.map { ShareFile(newFileId(), it) })
        publish()
    }

    /** Drops the one file [fileId] from [token]; a share emptied this way is taken down. */
    @Synchronized
    fun removeFile(token: String, fileId: String) {
        val share = shares[token] ?: return
        share.files.removeAll { it.id == fileId }
        if (share.files.isEmpty()) revoke(token) else publish()
    }

    /** Takes one link down. A browser holding it finds the path gone (404) or the port refused. */
    @Synchronized
    fun revoke(token: String) {
        shares.remove(token) ?: return
        if (shares.isEmpty()) closeListener()
        publish()
    }

    /** Closes the listener and forgets every link; a browser holding one gets a refused port. */
    @Synchronized
    fun stop() {
        shares.clear()
        closeListener()
        publish()
    }

    private fun newFileId(): String = UUID.randomUUID().toString().take(8)

    private fun publish() = onChanged(shares.values.map { it.info() })

    private fun closeListener() {
        try {
            socket?.close()
        } catch (e: Exception) {
            // already gone
        }
        socket = null
    }

    private fun ensureRunning(): ServerSocket? {
        socket?.takeIf { !it.isClosed }?.let { return it }
        // 53318 is LocalSend's port plus one: nearby machines already know the range is this
        // app's, and an ephemeral port would put a different number in every link. Taken?
        // Fall to whatever the kernel offers rather than refusing to share.
        val bound = runCatching { ServerSocket(LsConstants.PORT + 1) }
            .recoverCatching { ServerSocket(0) }
            .onFailure { Log.w(TAG, "Could not open the share port", it) }
            .getOrNull() ?: return null
        socket = bound
        Thread { acceptLoop(bound) }.also { it.isDaemon = true }.start()
        return bound
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val incoming = try {
                server.accept()
            } catch (e: Exception) {
                // stop() closes the socket, which lands here; anything else is one bad peer.
                if (server.isClosed) break else Log.d(TAG, "accept failed", e)
                continue
            }
            Thread { handleConnection(incoming) }.also { it.isDaemon = true }.start()
        }
        closeQuietly()
    }

    private fun closeQuietly() {
        try {
            socket?.close()
        } catch (e: Exception) {
            // ignore
        }
        socket = null
    }

    // region connection handling

    private fun handleConnection(raw: Socket) {
        try {
            raw.use { socket ->
                socket.soTimeout = 30_000
                val input = socket.getInputStream()
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(' ')
                if (parts.size < 2 || !parts[0].equals("GET", ignoreCase = true)) return
                readHeaders(input) // If-Match and friends are not honoured; drain them.

                val path = parts[1].substringBefore('?')
                val out = socket.getOutputStream()
                // Only the two paths of a link are served; everything else, probe or typo,
                // is a 404 without a word about what else exists.
                val segments = path.removePrefix("/s/").takeIf { path.startsWith("/s/") }
                    ?.split('/') ?: return respond(out, 404, "text/plain; charset=utf-8", "Not found\r\n")

                val share = shares[segments.firstOrNull().orEmpty()]
                    ?: return respond(out, 404, "text/plain; charset=utf-8", "Link expired\r\n")

                if (segments.size == 1) respond(out, 200, "text/html; charset=utf-8", listingOf(share))
                else {
                    val file = share.files.firstOrNull { it.id == segments[1] }
                        ?: return respond(out, 404, "text/plain; charset=utf-8", "No such file\r\n")
                    respondFile(out, file.outgoing)
                }
            }
        } catch (e: Exception) {
            // A browser that gives up mid-download is normal; the next one asks again.
            Log.d(TAG, "Share connection ended abruptly", e)
        }
    }

    /**
     * The page a link opens: the picked files as one line each. No stylesheet and no script -
     * the page has to survive a browser with data saver on, and nothing here needs more than
     * an anchor.
     */
    private fun listingOf(share: Share): String {
        val heading = context.getString(R.string.title_localsend_home)
        val builder = StringBuilder()
        builder.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
            .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
            .append("<title>").append(escape(heading)).append("</title></head>")
            .append("<body style=\"font-family:sans-serif;margin:24px\">")
            .append("<h2>").append(escape(heading)).append("</h2><ul>")
        share.files.forEach { file ->
            builder.append("<li style=\"margin:8px 0\"><a href=\"/s/").append(share.token)
                .append('/').append(file.id).append("\">").append(escape(file.outgoing.fileName))
                .append("</a> (").append(Formatter.formatShortFileSize(context, file.outgoing.size))
                .append(")</li>")
        }
        builder.append("</ul></body></html>")
        return builder.toString()
    }

    /**
     * Streams one shared file. The name goes out RFC 5987-style because a filename is free
     * text and a quoted `filename=` of a non-ASCII name is not something every browser reads
     * the same way.
     */
    private fun respondFile(out: OutputStream, file: LsOutgoingFile) {
        val disposition = "attachment; filename*=UTF-8''" + percentEncode(file.fileName)
        if (file.size <= 0L) {
            // No length to promise (a document whose provider would not say): chunked, so the
            // browser still shows progress and knows where the body ends.
            writeHead(out, 200, "OK", file.mime, null, disposition, chunked = true)
            streamChunked(out, file)
            return
        }
        writeHead(out, 200, "OK", file.mime, file.size, disposition, chunked = false)
        runCatching { file.open().use { it.copyTo(out) } }
        out.flush()
    }

    // region http plumbing

    /** Headers of a file response, written separately from the body so both shapes share it. */
    private fun writeHead(
        out: OutputStream, code: Int, status: String, contentType: String,
        length: Long?, disposition: String, chunked: Boolean,
    ) {
        val builder = StringBuilder()
        builder.append("HTTP/1.1 $code $status\r\n")
        builder.append("Content-Type: $contentType\r\n")
        if (chunked) builder.append("Transfer-Encoding: chunked\r\n")
        else builder.append("Content-Length: ${length ?: 0}\r\n")
        builder.append("Content-Disposition: $disposition\r\n")
        builder.append("Connection: close\r\n\r\n")
        out.write(builder.toString().toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    private fun streamChunked(out: OutputStream, file: LsOutgoingFile) {
        val buffer = ByteArray(8192)
        runCatching {
            file.open().use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    out.write(String.format(Locale.ROOT, "%x\r\n", read).toByteArray(Charsets.ISO_8859_1))
                    out.write(buffer, 0, read)
                    out.write(CRLF)
                }
            }
            out.write("0\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            out.flush()
        }
    }

    private fun respond(out: OutputStream, code: Int, contentType: String, body: String) {
        val status = when (code) {
            200 -> "OK"; 404 -> "Not Found"; else -> "Unknown"
        }
        val payload = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code $status\r\nContent-Type: $contentType\r\n" +
                "Content-Length: ${payload.size}\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(payload)
        out.flush()
    }

    /**
     * One CR/LF-terminated ISO-8859-1 line, read byte by byte so the stream stays positioned
     * right after it for whatever reader comes next.
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

    /** The subset of HTML that would otherwise end the anchor early or open a tag. */
    private fun escape(value: String): String = value
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** RFC 5987 basic encoding: a name is not ASCII half the time, and `+` is not a space. */
    private fun percentEncode(value: String): String = java.net.URLEncoder
        .encode(value, "UTF-8").replace("+", "%20")

    // endregion
}
