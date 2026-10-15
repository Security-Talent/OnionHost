package com.onionhost.app.chat

import android.net.Uri
import com.onionhost.app.http.AnonymousChatStore
import com.onionhost.app.tor.TorOnionSocketConnector
import com.onionhost.app.tor.TorManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runInterruptible
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale

data class ChatInvite(val onionAddress: String, val room: String) {
    val shareUrl: String get() = "http://$onionAddress/chat/$room"

    companion object {
        fun parse(value: String): ChatInvite {
            val uri = Uri.parse(value.trim())
            require(uri.scheme.equals("http", true)) { "Use the http:// v3 onion chat link shared by OnionHost." }
            require(uri.userInfo == null && (uri.port == -1 || uri.port == 80)) { "Only standard OnionHost chat links on port 80 are supported." }
            val onion = uri.host?.lowercase(Locale.ROOT).orEmpty()
            require(TorManager.isValidTorV3Address(onion)) { "This is not a valid v3 .onion address." }
            val parts = uri.pathSegments
            require(parts.size == 2 && parts[0] == "chat") { "The link must point to /chat/{room}." }
            val room = parts[1]
            require(room.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "The chat room in this link is invalid." }
            return ChatInvite(onion, room)
        }
    }
}

data class RemoteChatState(
    val invite: ChatInvite,
    val status: String,
    val messages: List<AnonymousChatStore.Message> = emptyList(),
    val activeUsers: Int = 0,
    val error: String? = null,
    val historyRevision: Long = 0L
)

data class RemoteChatPoll(val messages: List<AnonymousChatStore.Message>, val activeUsers: Int, val historyRevision: Long = 0L)

/** HTTP/1.0 chat client. All sockets are opened through Tor SOCKS; clearnet fallback is impossible. */
class TorChatClient(private val connector: TorOnionSocketConnector = TorOnionSocketConnector()) {
    suspend fun joinStatus(invite: ChatInvite, clientId: String): String = withContext(Dispatchers.IO) {
        val path = "/api/chat/join?room=${Uri.encode(invite.room)}&clientId=${Uri.encode(clientId)}"
        request(invite, "GET", path).json.optString("status", "unknown")
    }

    suspend fun requestJoin(invite: ChatInvite, clientId: String): String = withContext(Dispatchers.IO) {
        val path = "/api/chat/join?room=${Uri.encode(invite.room)}&clientId=${Uri.encode(clientId)}"
        request(invite, "POST", path).json.optString("status", "unknown")
    }

    suspend fun poll(invite: ChatInvite, clientId: String, after: Long): RemoteChatPoll = withContext(Dispatchers.IO) {
        val path = "/api/chat/messages?room=${Uri.encode(invite.room)}&after=$after&clientId=${Uri.encode(clientId)}"
        val payload = request(invite, "GET", path).json
        val rows = payload.optJSONArray("messages")
        val messages = buildList {
            if (rows != null) for (i in 0 until rows.length()) {
                val item = rows.optJSONObject(i) ?: continue
                val ownerIsThisClient = item.optBoolean("canDelete")
                val attachmentJson = item.optJSONObject("attachment")
                val attachment = attachmentJson?.let {
                    AnonymousChatStore.Attachment(it.optString("data"), it.optString("name"), it.optString("type"))
                }
                add(AnonymousChatStore.Message(
                    id = item.optLong("id"), text = item.optString("text"), sentAt = item.optLong("sentAt"),
                    ownerId = if (ownerIsThisClient) null else "remote-peer", attachment = attachment,
                    sender = item.optString("sender", "Anonymous")
                ))
            }
        }
        RemoteChatPoll(messages, payload.optInt("activeUsers"), payload.optLong("historyRevision"))
    }

    suspend fun deleteMessage(invite: ChatInvite, clientId: String, messageId: Long): Unit = withContext(Dispatchers.IO) {
        val path = "/api/chat/messages?room=${Uri.encode(invite.room)}&id=$messageId&clientId=${Uri.encode(clientId)}"
        request(invite, "DELETE", path)
        Unit
    }

    suspend fun send(invite: ChatInvite, clientId: String, text: String, attachment: AnonymousChatStore.Attachment? = null): Unit = withContext(Dispatchers.IO) {
        val body = JSONObject().put("text", text.take(AnonymousChatStore.MAX_MESSAGE_LENGTH)).put("clientId", clientId)
        if (attachment != null) {
            body.put("attachmentData", attachment.dataUrl).put("attachmentType", attachment.mimeType).put("attachmentName", attachment.name)
        }
        val path = "/api/chat/messages?room=${Uri.encode(invite.room)}"
        request(invite, "POST", path, body.toString().toByteArray(Charsets.UTF_8))
        Unit
    }

    private suspend fun request(invite: ChatInvite, method: String, path: String, body: ByteArray? = null): HttpResult = runInterruptible {
        connector.connect(invite.onionAddress, 80).use { socket ->
            socket.soTimeout = SOCKET_TIMEOUT_MS
            val payload = body ?: ByteArray(0)
            val headers = buildString {
                append("$method $path HTTP/1.0\r\n")
                append("Host: ${invite.onionAddress}\r\nAccept: application/json\r\nConnection: close\r\n")
                if (body != null) append("Content-Type: application/json\r\nContent-Length: ${payload.size}\r\n")
                append("\r\n")
            }
            val output = socket.getOutputStream()
            output.write(headers.toByteArray(Charsets.US_ASCII))
            if (body != null) output.write(payload)
            output.flush()
            val input = BufferedInputStream(socket.getInputStream())
            val statusLine = readLine(input, 4096).split(' ', limit = 3)
            if (statusLine.size < 2 || !statusLine[0].startsWith("HTTP/1.")) throw IOException("Invalid chat server response.")
            val statusCode = statusLine[1].toIntOrNull() ?: throw IOException("Invalid chat server response code.")
            val responseHeaders = mutableMapOf<String, String>()
            var ended = false
            for (headerIndex in 0 until 40) {
                val line = readLine(input, 4096)
                if (line.isEmpty()) { ended = true; break }
                val colon = line.indexOf(':')
                if (colon <= 0) throw IOException("Invalid chat server response header.")
                responseHeaders[line.substring(0, colon).trim().lowercase(Locale.ROOT)] = line.substring(colon + 1).trim()
            }
            if (!ended) throw IOException("Too many chat response headers.")
            if (responseHeaders["transfer-encoding"]?.equals("chunked", true) == true) throw IOException("Unsupported chunked chat response.")
            val length = responseHeaders["content-length"]?.toIntOrNull() ?: throw IOException("Chat server omitted response length.")
            if (length !in 0..MAX_RESPONSE_BYTES) throw IOException("Chat response exceeds the size limit.")
            val bytes = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val count = input.read(bytes, offset, length - offset)
                if (count < 0) throw IOException("Chat server closed the response early.")
                offset += count
            }
            val responseText = bytes.toString(Charsets.UTF_8)
            val json = try { JSONObject(responseText) } catch (_: Exception) { throw IOException("Chat server returned malformed data.") }
            if (statusCode !in 200..299) throw IOException(json.optString("error").ifBlank { "Chat server returned HTTP $statusCode." })
            HttpResult(statusCode, json)
        }
    }

    private fun readLine(input: BufferedInputStream, maximum: Int): String {
        val bytes = ByteArrayOutputStream()
        while (bytes.size() <= maximum) {
            val value = input.read()
            if (value < 0) throw IOException("Chat server closed the response unexpectedly.")
            if (value == '\n'.code) break
            if (value != '\r'.code) bytes.write(value)
        }
        if (bytes.size() > maximum) throw IOException("Chat response line is too long.")
        return bytes.toString(Charsets.US_ASCII.name())
    }

    private data class HttpResult(val code: Int, val json: JSONObject)

    companion object {
        private const val SOCKET_TIMEOUT_MS = 20_000
        private const val MAX_RESPONSE_BYTES = 9 * 1024 * 1024
    }
}
