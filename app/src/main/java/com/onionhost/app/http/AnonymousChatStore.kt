package com.onionhost.app.http

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** Chat state is private to the host app and survives hosting/app restarts. */
object AnonymousChatStore {
    data class Attachment(val dataUrl: String, val name: String, val mimeType: String)
    data class Message(val id: Long, val text: String, val sentAt: Long, val ownerId: String?, val attachment: Attachment? = null, val sender: String = "Anonymous")
    data class JoinRequest(val room: String, val clientId: String, val requestedAt: Long, val displayName: String)
    data class ActiveChatClient(val room: String, val clientId: String, val displayName: String, val lastSeen: Long, val isActive: Boolean)

    private val rooms = ConcurrentHashMap<String, CopyOnWriteArrayList<Message>>()
    // Clients use the last seen ID as a cursor. IDs must therefore be ordered;
    // random IDs caused valid newer messages to be skipped by polling clients.
    private val nextMessageId = AtomicLong(0)
    private val changes = MutableStateFlow(0L)
    private val presenceChanges = MutableStateFlow(0L)
    private val activeClients = ConcurrentHashMap<String, Long>()
    private val historyRevisions = ConcurrentHashMap<String, Long>()
    private val pendingJoins = ConcurrentHashMap<String, JoinRequest>()
    private val approvedClients = ConcurrentHashMap.newKeySet<String>()
    private val deniedClients = ConcurrentHashMap.newKeySet<String>()
    private val joinRequests = MutableStateFlow<List<JoinRequest>>(emptyList())
    private var storageFile: File? = null

    @Synchronized
    fun initialize(context: Context) {
        if (storageFile != null) return
        storageFile = File(context.filesDir, "anonymous_chat.json")
        val primary = storageFile ?: return
        // Keep a second local copy.  A restart must never discard a conversation
        // just because Android was interrupted while replacing the primary file.
        val files = listOf(primary, File(primary.parentFile, "${primary.name}.bak"))
        for (file in files) {
            if (!file.exists()) continue
            try {
                rooms.clear()
                val root = JSONObject(file.readText())
                val savedRooms = root.optJSONObject("rooms") ?: continue
                historyRevisions.clear()
                root.optJSONObject("historyRevisions")?.let { saved ->
                    saved.keys().forEach { room -> historyRevisions[room] = saved.optLong(room, 0L) }
                }
                approvedClients.clear()
                deniedClients.clear()
                root.optJSONArray("approvedClients")?.let { entries ->
                    for (index in 0 until entries.length()) entries.optString(index).takeIf(String::isNotBlank)?.let(approvedClients::add)
                }
                root.optJSONArray("deniedClients")?.let { entries ->
                    for (index in 0 until entries.length()) entries.optString(index).takeIf(String::isNotBlank)?.let(deniedClients::add)
                }
            var largestId = 0L
            savedRooms.keys().forEach { room ->
                val savedMessages = savedRooms.optJSONArray(room) ?: return@forEach
                val restored = CopyOnWriteArrayList<Message>()
                for (index in 0 until savedMessages.length()) {
                    val item = savedMessages.getJSONObject(index)
                    val attachmentJson = item.optJSONObject("attachment")
                    val attachment = attachmentJson?.let { Attachment(it.optString("data"), it.optString("name"), it.optString("type")) }
                    val ownerId = item.optString("ownerId").ifBlank { null }
                    val message = Message(
                        item.getLong("id"), item.optString("text"), item.getLong("sentAt"), ownerId,
                        attachment, item.optString("sender").ifBlank { if (ownerId == null) "Host" else anonymousName(ownerId) }
                    )
                    restored += message
                    largestId = maxOf(largestId, message.id)
                }
                rooms[room] = restored
            }
            nextMessageId.set(largestId)
                return
            } catch (_: Exception) {
                // Try the backup before starting a clean in-memory archive.
            }
        }
    }

    fun add(room: String, text: String, ownerId: String? = null, attachment: Attachment? = null, sender: String = if (ownerId == null) "Host" else anonymousName(ownerId)): Message {
        val message = Message(
            id = nextMessageId.incrementAndGet(),
            text = text.take(MAX_MESSAGE_LENGTH),
            sentAt = System.currentTimeMillis(), ownerId = ownerId, attachment = attachment, sender = sender
        )
        val messages = rooms.getOrPut(room) { CopyOnWriteArrayList() }
        messages += message
        // Keep memory bounded even when an invite link is shared widely.
        while (messages.size > MAX_MESSAGES_PER_ROOM) messages.removeAt(0)
        persist()
        changes.value += 1
        return message
    }

    fun ensureRoom(room: String) {
        if (rooms.putIfAbsent(room, CopyOnWriteArrayList()) == null) {
            persist()
            changes.value += 1
        }
    }

    fun since(room: String, after: Long): List<Message> =
        rooms[room].orEmpty().filter { it.id > after }.sortedBy { it.sentAt }

    fun messages(room: String): List<Message> = rooms[room].orEmpty().sortedBy { it.sentAt }

    fun historyRevision(room: String): Long = historyRevisions[room] ?: 0L

    @Synchronized
    fun deleteRoomHistory(room: String): Long {
        rooms.getOrPut(room) { CopyOnWriteArrayList() }.clear()
        val revision = historyRevisions.merge(room, 1L, Long::plus) ?: 1L
        persist()
        changes.value += 1
        return revision
    }

    fun roomNames(): List<String> = rooms.keys().toList().sorted()

    fun messagesFlow(room: String): Flow<List<Message>> = changes.map { messages(room) }
    fun roomNamesFlow(): Flow<List<String>> = changes.map { roomNames() }
    fun activeClientCountFlow(room: String): Flow<Int> = presenceChanges.map { activeClientCount(room) }

    fun touchClient(room: String, clientId: String) {
        activeClients["$room:$clientId"] = System.currentTimeMillis()
        presenceChanges.value += 1
    }

    private fun joinKey(room: String, clientId: String) = "$room:$clientId"

    @Synchronized
    fun requestJoin(room: String, clientId: String): String {
        if (clientId.length !in 8..80 || !clientId.matches(Regex("[A-Za-z0-9_-]+")) || room.isBlank()) return "invalid"
        val key = joinKey(room, clientId)
        return when {
            approvedClients.contains(key) -> "approved"
            deniedClients.contains(key) -> "cancelled"
            else -> {
                if (pendingJoins.values.count { it.room == room } >= MAX_PENDING_JOINS_PER_ROOM) return "busy"
                pendingJoins.putIfAbsent(key, JoinRequest(room, clientId, System.currentTimeMillis(), anonymousName(clientId)))
                publishJoinRequests()
                "pending"
            }
        }
    }

    fun joinStatus(room: String, clientId: String): String {
        val key = joinKey(room, clientId)
        return when {
            approvedClients.contains(key) -> "approved"
            deniedClients.contains(key) -> "cancelled"
            pendingJoins.containsKey(key) -> "pending"
            else -> "not_requested"
        }
    }

    @Synchronized
    fun approveJoin(room: String, clientId: String): Boolean {
        val key = joinKey(room, clientId)
        if (pendingJoins.remove(key) == null) return false
        deniedClients.remove(key)
        approvedClients.add(key)
        persist()
        publishJoinRequests()
        presenceChanges.value += 1
        return true
    }

    @Synchronized
    fun cancelJoin(room: String, clientId: String): Boolean {
        val key = joinKey(room, clientId)
        if (pendingJoins.remove(key) == null) return false
        approvedClients.remove(key)
        deniedClients.add(key)
        persist()
        publishJoinRequests()
        return true
    }

    fun isApproved(room: String, clientId: String): Boolean = joinKey(room, clientId) in approvedClients

    fun joinRequestsFlow(): Flow<List<JoinRequest>> = joinRequests

    private fun publishJoinRequests() {
        joinRequests.value = pendingJoins.values.sortedByDescending { it.requestedAt }
    }

    fun activeClientCount(room: String): Int {
        val cutoff = System.currentTimeMillis() - ACTIVE_CLIENT_WINDOW_MS
        activeClients.entries.removeIf { it.value < cutoff }
        return activeClients.entries.count { it.key.startsWith("$room:") }
    }

    fun participants(room: String): List<ActiveChatClient> {
        val cutoff = System.currentTimeMillis() - ACTIVE_CLIENT_WINDOW_MS
        activeClients.entries.removeIf { it.value < cutoff }
        val prefix = "$room:"
        val ids = approvedClients.asSequence().filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }.toMutableSet()
        activeClients.keys.filter { it.startsWith(prefix) }.mapTo(ids) { it.removePrefix(prefix) }
        return ids.map { clientId ->
            val lastSeen = activeClients[joinKey(room, clientId)] ?: 0L
            ActiveChatClient(room, clientId, anonymousName(clientId), lastSeen, lastSeen >= cutoff)
        }.sortedWith(compareByDescending<ActiveChatClient> { it.isActive }.thenByDescending { it.lastSeen })
    }

    fun activeClientsFlow(room: String): Flow<List<ActiveChatClient>> = presenceChanges.map { participants(room) }

    @Synchronized
    fun blockClient(room: String, clientId: String): Boolean {
        if (clientId.length !in 8..80) return false
        val key = joinKey(room, clientId)
        pendingJoins.remove(key)
        approvedClients.remove(key)
        deniedClients.add(key)
        activeClients.remove(key)
        persist()
        publishJoinRequests()
        presenceChanges.value += 1
        return true
    }

    fun deleteByOwner(room: String, id: Long, ownerId: String): Boolean =
        delete(room) { it.id == id && it.ownerId == ownerId }

    fun deleteByHost(room: String, id: Long): Boolean = delete(room) { it.id == id }

    fun deleteRoom(room: String): Boolean {
        val removed = rooms.remove(room) != null
        if (removed) { persist(); changes.value += 1 }
        return removed
    }

    private fun delete(room: String, predicate: (Message) -> Boolean): Boolean {
        val result = rooms[room]?.removeIf(predicate) == true
        if (result) { persist(); changes.value += 1 }
        return result
    }

    fun anonymousName(clientId: String): String = "Anon-" + clientId.hashCode().toUInt().toString(16).take(6).uppercase()

    @Synchronized
    private fun persist() {
        val file = storageFile ?: return
        try {
            val savedRooms = JSONObject()
            rooms.forEach { (room, messages) ->
                val array = JSONArray()
                messages.forEach { message ->
                    val item = JSONObject().put("id", message.id).put("text", message.text).put("sentAt", message.sentAt)
                        .put("ownerId", message.ownerId ?: "").put("sender", message.sender)
                    message.attachment?.let { item.put("attachment", JSONObject().put("data", it.dataUrl).put("name", it.name).put("type", it.mimeType)) }
                    array.put(item)
                }
                savedRooms.put(room, array)
            }
            val temporary = File(file.parentFile, "${file.name}.tmp")
            val backup = File(file.parentFile, "${file.name}.bak")
            val approved = JSONArray().apply { approvedClients.forEach(::put) }
            val denied = JSONArray().apply { deniedClients.forEach(::put) }
            val revisions = JSONObject().apply { historyRevisions.forEach { (room, revision) -> put(room, revision) } }
            val payload = JSONObject().put("rooms", savedRooms).put("approvedClients", approved).put("deniedClients", denied).put("historyRevisions", revisions).toString()
            temporary.writeText(payload)
            // Write the backup first; if the app is stopped at any point there is
            // always a complete JSON archive available for the next launch.
            backup.writeText(payload)
            if (!temporary.renameTo(file)) { file.writeText(payload); temporary.delete() }
        } catch (_: Exception) { }
    }

    const val MAX_MESSAGE_LENGTH = 1_000
    const val MAX_ATTACHMENT_CHARS = 7_000_000
    private const val MAX_MESSAGES_PER_ROOM = 200
    private const val MAX_PENDING_JOINS_PER_ROOM = 200
    private const val ACTIVE_CLIENT_WINDOW_MS = 15_000L
}
