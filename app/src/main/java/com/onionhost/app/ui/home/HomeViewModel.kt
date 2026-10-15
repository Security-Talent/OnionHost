package com.onionhost.app.ui.home

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.onionhost.app.database.entity.WebsiteEntity
import com.onionhost.app.database.entity.WebsiteType
import com.onionhost.app.chat.discovery.UsernameDiscoveryRepository
import com.onionhost.app.chat.discovery.UsernameLookupResult
import com.onionhost.app.chat.ChatInvite
import com.onionhost.app.chat.RemoteChatState
import com.onionhost.app.chat.TorChatClient
import com.onionhost.app.hosting.OnionHostingService
import com.onionhost.app.http.AnonymousChatStore
import com.onionhost.app.repository.WebsiteRepository
import com.onionhost.app.repository.SettingsRepository
import com.onionhost.app.storage.StorageManager
import com.onionhost.app.tor.TorManager
import com.onionhost.app.tor.TorStatus
import com.onionhost.app.tor.TorState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import java.util.UUID
import java.io.ByteArrayOutputStream
import javax.inject.Inject

data class SystemMetrics(
    val memoryUsageMb: Long = 0
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val websiteRepository: WebsiteRepository,
    private val torManager: TorManager,
    private val storageManager: StorageManager,
    private val settingsRepository: SettingsRepository,
    private val usernameDiscoveryRepository: UsernameDiscoveryRepository,
    private val torChatClient: TorChatClient
) : ViewModel() {

    val activeWebsite: StateFlow<WebsiteEntity?> = websiteRepository.getActiveWebsiteFlow()
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    val torStatus: StateFlow<TorStatus> = torManager.torStatus
    val usernameDiscoveryNodes: StateFlow<String> = settingsRepository.usernameDiscoveryNodes
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")
    private val _usernameLookup = MutableStateFlow<UsernameLookupResult?>(null)
    val usernameLookup: StateFlow<UsernameLookupResult?> = _usernameLookup.asStateFlow()
    private val _usernameLookupInProgress = MutableStateFlow(false)
    val usernameLookupInProgress: StateFlow<Boolean> = _usernameLookupInProgress.asStateFlow()
    private var usernameLookupJob: kotlinx.coroutines.Job? = null
    private var lastUsernameQuery: String = ""
    private var usernameSearchGeneration: Long = 0

    private val selectedChatRoom = MutableStateFlow("")
    val activeChatRoom: StateFlow<String> = combine(activeWebsite, selectedChatRoom) { website, selected ->
        selected.ifBlank { website?.id?.let { "personal-$it" }.orEmpty() }
    }.stateIn(viewModelScope, SharingStarted.Lazily, "")

    val chatMessages: StateFlow<List<AnonymousChatStore.Message>> = activeChatRoom
        .flatMapLatest { room ->
            if (room.isBlank()) flowOf(emptyList()) else AnonymousChatStore.messagesFlow(room)
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val chatRooms: StateFlow<List<String>> = AnonymousChatStore.roomNamesFlow()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    fun chatPreview(room: String): AnonymousChatStore.Message? = AnonymousChatStore.messages(room).lastOrNull()
    val chatJoinRequests: StateFlow<List<AnonymousChatStore.JoinRequest>> = AnonymousChatStore.joinRequestsFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val activeChatUsers: StateFlow<Int> = activeChatRoom.flatMapLatest { room ->
        if (room.isBlank()) flowOf(0) else AnonymousChatStore.activeClientCountFlow(room)
    }.stateIn(viewModelScope, SharingStarted.Lazily, 0)
    val activeChatParticipants: StateFlow<List<AnonymousChatStore.ActiveChatClient>> = activeChatRoom.flatMapLatest { room ->
        if (room.isBlank()) flowOf(emptyList()) else AnonymousChatStore.activeClientsFlow(room)
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    private val _chatActionInProgress = MutableStateFlow(false)
    val chatActionInProgress: StateFlow<Boolean> = _chatActionInProgress.asStateFlow()
    private val _chatSendStatus = MutableStateFlow<String?>(null)
    val chatSendStatus: StateFlow<String?> = _chatSendStatus.asStateFlow()
    private val _remoteChat = MutableStateFlow<RemoteChatState?>(null)
    val remoteChat: StateFlow<RemoteChatState?> = _remoteChat.asStateFlow()
    private val _remoteChatError = MutableStateFlow<String?>(null)
    val remoteChatError: StateFlow<String?> = _remoteChatError.asStateFlow()
    private var remoteChatJob: kotlinx.coroutines.Job? = null
    private var remoteClientId: String? = null

    private val _systemMetrics = MutableStateFlow(SystemMetrics())
    val systemMetrics: StateFlow<SystemMetrics> = _systemMetrics.asStateFlow()

    init {
        updateMetrics()
    }

    private fun updateMetrics() {
        val runtime = Runtime.getRuntime()
        val usedMem = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        _systemMetrics.value = SystemMetrics(memoryUsageMb = usedMem)
    }

    fun startHosting(context: Context, websiteId: String) {
        OnionHostingService.startService(context, websiteId)
    }

    fun stopHosting(context: Context) {
        OnionHostingService.stopService(context)
    }

    fun stopTorClient() {
        torManager.stopTorClient()
    }

    fun retryTorClient() {
        torManager.stopTorClient()
        torManager.startTorClient(viewModelScope)
    }

    fun restartHosting(context: Context) {
        activeWebsite.value?.id?.let { OnionHostingService.restartService(context, it) }
    }

    fun sendChatMessage(text: String) {
        val room = activeChatRoom.value.ifBlank { return }
        val message = text.trim()
        if (message.isNotEmpty()) AnonymousChatStore.add(room, message)
    }

    fun joinChatInvite(context: Context, rawLink: String) {
        val invite = try { ChatInvite.parse(rawLink) } catch (error: Exception) {
            _remoteChatError.value = error.message ?: "Invalid OnionHost chat link."
            return
        }
        _remoteChatError.value = null
        remoteChatJob?.cancel()
        val preferences = context.getSharedPreferences("onionhost_chat_client", Context.MODE_PRIVATE)
        val clientId = preferences.getString("client_id", null) ?: UUID.randomUUID().toString().also {
            preferences.edit().putString("client_id", it).apply()
        }
        remoteClientId = clientId
        _remoteChat.value = RemoteChatState(invite, "Starting Tor…")
        torManager.startTorClient(viewModelScope)
        remoteChatJob = viewModelScope.launch {
            try {
                val tor = torManager.torStatus.first { it.state == TorState.RUNNING || it.state == TorState.ERROR }
                if (tor.state == TorState.ERROR) throw IllegalStateException(tor.errorMessage ?: "Tor could not connect.")
                updateRemoteStatus("Requesting host approval…")
                val currentState = torChatClient.joinStatus(invite, clientId)
                if (currentState == "not_requested") {
                    val requested = torChatClient.requestJoin(invite, clientId)
                    if (requested == "busy") updateRemoteStatus("Room is busy. Retrying access request…")
                }
                var cursor = 0L
                var approved = false
                while (true) {
                    currentCoroutineContext().ensureActive()
                    // Approval is persistent for this invite client. Once approved,
                    // avoid an extra Tor round trip on every message refresh.
                    val access = if (approved) "approved" else torChatClient.joinStatus(invite, clientId)
                    when (access) {
                        "pending" -> updateRemoteStatus("Waiting for the host to approve your request…")
                        "cancelled" -> {
                            _remoteChat.value = _remoteChat.value?.copy(status = "Request cancelled", error = "The host cancelled this request. Ask the host to share a new invite if you still need access.")
                            return@launch
                        }
                        "approved" -> {
                            approved = true
                            try {
                                val batch = torChatClient.poll(invite, clientId, cursor)
                                if (batch.messages.isNotEmpty()) cursor = maxOf(cursor, batch.messages.maxOf { it.id })
                                _remoteChat.value = _remoteChat.value?.let { old ->
                                    val priorMessages = if (old.historyRevision == batch.historyRevision) old.messages else emptyList()
                                    old.copy(status = "Connected · ${batch.activeUsers} active", messages = (priorMessages + batch.messages).distinctBy { it.id }, activeUsers = batch.activeUsers, historyRevision = batch.historyRevision, error = null)
                                }
                            } catch (error: Exception) {
                                approved = false
                                _remoteChat.value = _remoteChat.value?.copy(status = "Reconnecting…", error = error.message ?: "Chat connection interrupted.")
                            }
                        }
                        "busy" -> updateRemoteStatus("This room has too many pending requests. Retrying…")
                        else -> {
                            val requested = torChatClient.requestJoin(invite, clientId)
                            updateRemoteStatus(if (requested == "busy") "Room is busy. Retrying…" else "Waiting for the host to approve your request…")
                        }
                    }
                    kotlinx.coroutines.delay(2_000)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _remoteChat.value = _remoteChat.value?.copy(status = "Could not connect", error = error.message ?: "Tor chat connection failed.")
            }
        }
    }

    private fun updateRemoteStatus(status: String) {
        _remoteChat.value = _remoteChat.value?.copy(status = status, error = null)
    }

    fun sendRemoteChatMessage(text: String, onSent: () -> Unit = {}) {
        viewModelScope.launch {
            _chatActionInProgress.value = true
            try { if (sendRemoteContent(text, null)) onSent() }
            finally { _chatActionInProgress.value = false }
        }
    }

    fun sendRemoteChatAttachment(context: Context, text: String, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _chatActionInProgress.value = true
            _chatSendStatus.value = "Preparing attachment…"
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > 5 * 1024 * 1024) break
                        output.write(buffer, 0, count)
                    }
                    if (total > 5 * 1024 * 1024) ByteArray(5 * 1024 * 1024 + 1) else output.toByteArray()
                } ?: throw IllegalStateException("Could not open the selected attachment.")
                if (bytes.size > 5 * 1024 * 1024) throw IllegalArgumentException("Attachment is larger than 5 MB.")
                val type = context.contentResolver.getType(uri) ?: throw IllegalArgumentException("Could not identify this attachment type.")
                if (type !in setOf("image/png", "image/jpeg", "image/gif", "image/webp", "video/mp4", "video/webm", "audio/mpeg", "audio/ogg", "audio/wav", "audio/webm")) {
                    throw IllegalArgumentException("This file type is not supported.")
                }
                val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }?.take(120) ?: "attachment"
                val data = "data:$type;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
                sendRemoteContent(text, AnonymousChatStore.Attachment(data, name, type))
            } catch (error: Exception) {
                _chatSendStatus.value = error.message ?: "Attachment could not be sent."
            } finally { _chatActionInProgress.value = false }
        }
    }

    private suspend fun sendRemoteContent(text: String, attachment: AnonymousChatStore.Attachment?): Boolean {
        val session = _remoteChat.value ?: return false
        val clientId = remoteClientId ?: return false
        if (text.isBlank() && attachment == null) return false
        _chatSendStatus.value = "Sending…"
        try {
            torChatClient.send(session.invite, clientId, text, attachment)
            _chatSendStatus.value = if (attachment != null) "Attachment sent" else "Message sent"
            return true
        } catch (error: Exception) {
            _chatSendStatus.value = error.message ?: "Message could not be sent."
            return false
        }
    }

    fun leaveRemoteChat() {
        remoteChatJob?.cancel()
        remoteChatJob = null
        remoteClientId = null
        _remoteChat.value = null
        _remoteChatError.value = null
        torManager.stopTorClient()
    }

    fun sendChatAttachment(context: Context, text: String, uri: Uri) {
        val room = activeChatRoom.value.ifBlank { return }
        viewModelScope.launch(Dispatchers.IO) {
            _chatActionInProgress.value = true
            _chatSendStatus.value = "Preparing attachment…"
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > 5 * 1024 * 1024) break
                        output.write(buffer, 0, count)
                    }
                    if (total > 5 * 1024 * 1024) ByteArray(5 * 1024 * 1024 + 1) else output.toByteArray()
                }
                if (bytes == null) {
                    _chatSendStatus.value = "Could not open the selected attachment."
                    return@launch
                }
                if (bytes.size > 5 * 1024 * 1024) {
                    _chatSendStatus.value = "Attachment is larger than 5 MB. Choose a smaller file."
                    return@launch
                }
                val type = context.contentResolver.getType(uri)
                if (type == null) {
                    _chatSendStatus.value = "Could not identify this attachment type."
                    return@launch
                }
                val allowed = setOf("image/png", "image/jpeg", "image/gif", "image/webp", "video/mp4", "video/webm", "audio/mpeg", "audio/ogg", "audio/wav", "audio/webm")
                if (type !in allowed) {
                    _chatSendStatus.value = "This file type is not supported."
                    return@launch
                }
                val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                    ?.take(120) ?: "attachment"
                val data = "data:$type;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
                AnonymousChatStore.add(room, text.trim(), attachment = AnonymousChatStore.Attachment(data, name, type))
                _chatSendStatus.value = "Attachment sent"
            } catch (_: Exception) {
                _chatSendStatus.value = "Attachment could not be sent. Check storage space and try again."
            } finally { _chatActionInProgress.value = false }
        }
    }

    fun deleteChatMessage(messageId: Long) {
        _chatActionInProgress.value = true
        activeChatRoom.value.takeIf { it.isNotBlank() }?.let { AnonymousChatStore.deleteByHost(it, messageId) }
        _chatActionInProgress.value = false
    }

    fun deleteRemoteChatMessage(messageId: Long) {
        val session = _remoteChat.value ?: return
        val clientId = remoteClientId ?: return
        viewModelScope.launch {
            _chatActionInProgress.value = true
            try {
                torChatClient.deleteMessage(session.invite, clientId, messageId)
                _remoteChat.value = _remoteChat.value?.copy(messages = _remoteChat.value?.messages.orEmpty().filterNot { it.id == messageId })
                _chatSendStatus.value = "Message deleted"
            } catch (error: Exception) {
                _chatSendStatus.value = error.message ?: "Could not delete message."
            } finally { _chatActionInProgress.value = false }
        }
    }

    fun blockChatClient(room: String, clientId: String) {
        AnonymousChatStore.blockClient(room, clientId)
    }

    fun deleteChatHistory(room: String) {
        if (room.isNotBlank()) AnonymousChatStore.deleteRoomHistory(room)
    }

    fun deleteActiveChatHistory() = deleteChatHistory(activeChatRoom.value)

    fun approveChatJoin(room: String, clientId: String) {
        AnonymousChatStore.approveJoin(room, clientId)
    }

    fun cancelChatJoin(room: String, clientId: String) {
        AnonymousChatStore.cancelJoin(room, clientId)
    }

    fun selectChatRoom(name: String) {
        selectedChatRoom.value = name.lowercase().filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(80)
    }

    fun createPrivateChatRoom() {
        selectedChatRoom.value = "personal-${UUID.randomUUID()}"
        AnonymousChatStore.ensureRoom(selectedChatRoom.value)
    }
    fun createRoom(name: String) {
        val cleanName = name.lowercase().filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(70)
        if (cleanName.isNotBlank()) {
            selectedChatRoom.value = "room-$cleanName"
            AnonymousChatStore.ensureRoom(selectedChatRoom.value)
        }
    }
    fun deleteActiveChatRoom() {
        activeChatRoom.value.takeIf { it.isNotBlank() }?.let(AnonymousChatStore::deleteRoom)
        selectedChatRoom.value = ""
    }

    fun saveUsernameDiscoveryNodes(nodes: String) {
        viewModelScope.launch { settingsRepository.setUsernameDiscoveryNodes(nodes) }
    }

    fun searchUsername(query: String) {
        lastUsernameQuery = query
        val generation = ++usernameSearchGeneration
        usernameLookupJob?.cancel()
        usernameLookupJob = viewModelScope.launch {
            _usernameLookupInProgress.value = true
            _usernameLookup.value = null
            try {
                val pinned = com.onionhost.app.chat.discovery.UsernameRegistration.normalizeUsername(query)
                    ?.let { settingsRepository.pinnedUsernameFingerprint(it) }
                _usernameLookup.value = usernameDiscoveryRepository.lookup(
                    query,
                    usernameDiscoveryNodes.value,
                    System.currentTimeMillis(),
                    pinned
                )
            } catch (_: kotlinx.coroutines.CancellationException) {
                throw kotlinx.coroutines.CancellationException("Username lookup cancelled")
            } catch (error: Exception) {
                _usernameLookup.value = UsernameLookupResult.Error(error.localizedMessage ?: "Username lookup failed.")
            } finally {
                if (generation == usernameSearchGeneration) _usernameLookupInProgress.value = false
            }
        }
    }

    fun retryUsernameSearch() {
        if (lastUsernameQuery.isNotBlank()) searchUsername(lastUsernameQuery)
    }

    fun cancelUsernameSearch() {
        usernameSearchGeneration++
        usernameLookupJob?.cancel()
        usernameLookupJob = null
        _usernameLookupInProgress.value = false
        _usernameLookup.value = null
    }

    fun trustUsernameFingerprint(username: String, fingerprint: String) {
        val normalized = com.onionhost.app.chat.discovery.UsernameRegistration.normalizeUsername(username) ?: return
        viewModelScope.launch {
            settingsRepository.pinUsernameFingerprint(normalized, fingerprint)
            searchUsername(normalized)
        }
    }

    fun importAndHost(context: Context, uri: Uri, type: WebsiteType) {
        viewModelScope.launch {
            val websiteId = UUID.randomUUID().toString()
            storageManager.importWebsiteContent(uri, type, websiteId)
            val configuredPort = settingsRepository.defaultPort.first().coerceIn(1024, 65535)

            val newWebsite = WebsiteEntity(
                id = websiteId,
                name = "Site-${websiteId.take(6)}",
                localPath = websiteId,
                websiteType = type,
                isEnabled = true,
                port = configuredPort
            )
            websiteRepository.insertWebsite(newWebsite)
            websiteRepository.setActiveWebsite(websiteId)
            startHosting(context, websiteId)
        }
    }
}
