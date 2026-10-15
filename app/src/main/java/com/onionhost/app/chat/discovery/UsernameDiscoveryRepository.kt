package com.onionhost.app.chat.discovery

import com.onionhost.app.tor.TorManager
import com.onionhost.app.tor.TorOnionSocketConnector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Locale

/** A user-configured, independently operated Tor onion discovery node. */
data class DiscoveryNode(val onionAddress: String, val port: Int = 80) {
    fun endpoint(): String = if (port == 80) onionAddress else "$onionAddress:$port"

    companion object {
        fun parseList(text: String): List<DiscoveryNode> {
            val values = text.lineSequence().flatMap { it.split(',', ';').asSequence() }
                .map(String::trim).filter(String::isNotEmpty).toList()
            require(values.size <= MAX_NODES) { "Configure at most $MAX_NODES discovery nodes." }
            val parsed = values.map { raw ->
                require(!raw.contains("://") && !raw.contains('/') && !raw.contains('@')) {
                    "Enter onion addresses only, one per line (optional :port)."
                }
                val address = raw.substringBeforeLast(':').takeIf { raw.count { c -> c == ':' } == 1 }
                    ?: raw
                val port = if (address == raw) 80 else raw.substringAfterLast(':').toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid discovery node port.")
                val normalized = address.lowercase(Locale.ROOT)
                require(TorManager.isValidTorV3Address(normalized)) { "Invalid v3 onion discovery node: $address" }
                require(port in 1..65535) { "Discovery node port must be between 1 and 65535." }
                DiscoveryNode(normalized, port)
            }
            require(parsed.map { it.endpoint() }.distinct().size == parsed.size) { "Discovery nodes must be unique." }
            return parsed
        }

        const val MAX_NODES = 12
    }
}

sealed interface UsernameLookupResult {
    data object NotConfigured : UsernameLookupResult
    data class Found(
        val record: UsernameRegistration.SignedRecord,
        val fingerprint: String,
        val respondingNodes: Int,
        val unavailableNodes: Int,
        val isPinned: Boolean
    ) : UsernameLookupResult
    data class NotFound(val respondingNodes: Int, val unavailableNodes: Int) : UsernameLookupResult
    data class Conflict(val fingerprints: Set<String>) : UsernameLookupResult
    data class IdentityChanged(val pinned: String, val discovered: Set<String>) : UsernameLookupResult
    data class InvalidRecords(val details: Map<UsernameRegistration.Rejection, Int>) : UsernameLookupResult
    data object Offline : UsernameLookupResult
    data object Timeout : UsernameLookupResult
    data class Error(val message: String) : UsernameLookupResult
}

/**
 * Wire protocol v1: GET /.well-known/onionhost/users/{lowercase-handle},
 * HTTP 200 with Content-Length and MIME text/plain; body is zero or more
 * newline-delimited Base64URL records from UsernameRegistration.encode().
 * Requests are sent only to configured onion endpoints via local Tor SOCKS.
 */
fun interface DiscoveryNodeClient {
    suspend fun query(node: DiscoveryNode, username: String): List<String>
}

class TorDiscoveryNodeClient(
    private val connector: TorOnionSocketConnector = TorOnionSocketConnector(),
    private val timeoutMs: Int = 12_000
) : DiscoveryNodeClient {
    override suspend fun query(node: DiscoveryNode, username: String): List<String> = withContext(Dispatchers.IO) {
        kotlinx.coroutines.runInterruptible {
        connector.connect(node.onionAddress, node.port).use { socket ->
            socket.soTimeout = timeoutMs
            val path = "/.well-known/onionhost/users/$username"
            socket.getOutputStream().write(
                "GET $path HTTP/1.0\r\nHost: ${node.onionAddress}\r\nAccept: text/plain\r\nConnection: close\r\n\r\n"
                    .toByteArray(Charsets.US_ASCII)
            )
            socket.getOutputStream().flush()
            val input = BufferedInputStream(socket.getInputStream())
            val status = readLine(input, 4096).split(' ')
            if (status.size < 2 || !status[0].startsWith("HTTP/1.")) throw IOException("Malformed discovery HTTP status.")
            val code = status[1].toIntOrNull() ?: throw IOException("Malformed discovery HTTP status.")
            val headers = mutableMapOf<String, String>()
            var headersEnded = false
            for (headerIndex in 0 until 32) {
                val line = readLine(input, 4096)
                if (line.isEmpty()) { headersEnded = true; break }
                val colon = line.indexOf(':')
                if (colon <= 0) throw IOException("Malformed discovery HTTP header.")
                headers[line.substring(0, colon).trim().lowercase(Locale.ROOT)] = line.substring(colon + 1).trim()
            }
            if (!headersEnded) throw IOException("Too many discovery HTTP headers.")
            if (headers["transfer-encoding"]?.equals("chunked", ignoreCase = true) == true) {
                throw IOException("Chunked discovery responses are not supported by protocol v1.")
            }
            if (code == 404 || code == 204) {
                emptyList()
            } else {
                if (code != 200) throw IOException("Discovery node returned HTTP $code.")
                if (headers["content-type"]?.substringBefore(';')?.trim() != "text/plain") {
                    throw IOException("Discovery node returned an unsupported content type.")
                }
                val length = headers["content-length"]?.toIntOrNull()
                    ?: throw IOException("Discovery node omitted Content-Length.")
                if (length !in 0..MAX_RESPONSE_BYTES) throw IOException("Discovery response exceeds the size limit.")
                val body = ByteArray(length)
                var offset = 0
                while (offset < length) {
                    val count = input.read(body, offset, length - offset)
                    if (count < 0) throw IOException("Discovery response ended early.")
                    offset += count
                }
                val text = body.toString(Charsets.US_ASCII)
                if (text.any { it.code !in 0x0a..0x7e }) throw IOException("Discovery response contains invalid characters.")
                text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
            }
        }
        }
    }

    private fun readLine(input: BufferedInputStream, maxBytes: Int): String {
        val output = ByteArrayOutputStream()
        while (output.size() <= maxBytes) {
            val next = input.read()
            if (next < 0) throw IOException("Discovery node closed the response unexpectedly.")
            if (next == '\n'.code) break
            if (next != '\r'.code) output.write(next)
        }
        if (output.size() > maxBytes) throw IOException("Discovery HTTP line is too long.")
        return output.toString(Charsets.US_ASCII.name())
    }

    companion object { private const val MAX_RESPONSE_BYTES = 32 * 1024 }
}

class UsernameDiscoveryRepository(private val nodeClient: DiscoveryNodeClient) {
    suspend fun lookup(
        input: String,
        nodeText: String,
        nowMillis: Long,
        pinnedFingerprint: String? = null
    ): UsernameLookupResult {
        val username = UsernameRegistration.normalizeUsername(input)
            ?: return UsernameLookupResult.Error("Enter 3–32 ASCII characters: start with a letter, then use letters, digits, or underscores.")
        val nodes = try { DiscoveryNode.parseList(nodeText) }
        catch (e: IllegalArgumentException) { return UsernameLookupResult.Error(e.message ?: "Invalid discovery node list.") }
        if (nodes.isEmpty()) return UsernameLookupResult.NotConfigured

        val outcomes = try {
            supervisorScope {
                nodes.map { node -> async {
                    try { Result.success(nodeClient.query(node, username)) }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { Result.failure(e) }
                } }.awaitAll()
            }
        } catch (_: kotlinx.coroutines.CancellationException) {
            throw kotlinx.coroutines.CancellationException("Username lookup cancelled")
        }
        val successful = outcomes.filter { it.isSuccess }
        val failures = outcomes.filter { it.isFailure }
        val timeoutCount = failures.count { it.exceptionOrNull() is SocketTimeoutException }
        if (successful.isEmpty()) {
            return if (timeoutCount == failures.size) UsernameLookupResult.Timeout else UsernameLookupResult.Offline
        }

        val wireRecords = successful.flatMap { it.getOrNull().orEmpty() }
        val decoded = wireRecords.map { UsernameRegistration.decode(it) }
        if (decoded.any { it == null }) return UsernameLookupResult.InvalidRecords(emptyMap())
        val records = decoded.filterNotNull()
        val checked = records.map { it to UsernameRegistration.verify(it, nowMillis) }
        val matching = checked.filter { UsernameRegistration.normalizeUsername(it.first.username) == username }
        val rejected = matching.mapNotNull { it.second as? UsernameRegistration.Verification.Rejected }
        val identityMismatchClaims = matching.mapNotNull { (record, verification) ->
            (verification as? UsernameRegistration.Verification.Verified)?.let { it.fingerprint }
        }.toSet()
        if (pinnedFingerprint != null && identityMismatchClaims.any { !it.equals(pinnedFingerprint, true) }) {
            return UsernameLookupResult.IdentityChanged(pinnedFingerprint, identityMismatchClaims)
        }
        val valid = matching.mapNotNull { (_, result) -> result as? UsernameRegistration.Verification.Verified }
        val identities = valid.map { it.fingerprint }.toSet()
        if (identities.size > 1) return UsernameLookupResult.Conflict(identities)
        if (valid.isEmpty()) {
            if (rejected.isNotEmpty()) return UsernameLookupResult.InvalidRecords(rejected.groupingBy { it.reason }.eachCount())
            return UsernameLookupResult.NotFound(successful.size, failures.size)
        }
        val chosen = valid.maxBy { it.record.issuedAtMillis }
        return UsernameLookupResult.Found(
            chosen.record,
            chosen.fingerprint,
            successful.size,
            failures.size,
            pinnedFingerprint != null && chosen.fingerprint.equals(pinnedFingerprint, true)
        )
    }
}
