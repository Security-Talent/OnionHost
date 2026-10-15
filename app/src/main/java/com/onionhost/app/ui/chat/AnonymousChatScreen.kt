package com.onionhost.app.ui.chat

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.onionhost.app.ui.home.HomeViewModel
import com.onionhost.app.chat.ChatInvite
import com.onionhost.app.ui.theme.ChatIncomingBubble
import com.onionhost.app.ui.theme.ChatOutgoingBubble
import com.onionhost.app.ui.theme.DarkCard
import com.onionhost.app.tor.TorState

@Composable
fun AnonymousChatScreen(viewModel: HomeViewModel) {
    val activeWebsite by viewModel.activeWebsite.collectAsState()
    val torStatus by viewModel.torStatus.collectAsState()
    val activeRoom by viewModel.activeChatRoom.collectAsState()
    val messages by viewModel.chatMessages.collectAsState()
    val rooms by viewModel.chatRooms.collectAsState()
    val joinRequests by viewModel.chatJoinRequests.collectAsState()
    val activeParticipants by viewModel.activeChatParticipants.collectAsState()
    val actionInProgress by viewModel.chatActionInProgress.collectAsState()
    val sendStatus by viewModel.chatSendStatus.collectAsState()
    val activeUsers by viewModel.activeChatUsers.collectAsState()
    val remoteChat by viewModel.remoteChat.collectAsState()
    val remoteChatError by viewModel.remoteChatError.collectAsState()
    var draft by rememberSaveable { mutableStateOf("") }
    var roomName by rememberSaveable { mutableStateOf("") }
    var chatTab by rememberSaveable { mutableStateOf(0) }
    var openLocalConversation by rememberSaveable { mutableStateOf(false) }
    var showJoinDialog by rememberSaveable { mutableStateOf(false) }
    var showApprovalDialog by rememberSaveable { mutableStateOf(false) }
    var showHowTo by rememberSaveable { mutableStateOf(true) }
    var pendingAttachment by remember { mutableStateOf<Uri?>(null) }
    var pendingAttachmentName by remember { mutableStateOf("") }
    var inviteLink by rememberSaveable { mutableStateOf("") }
    var joinLinkError by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val attachmentPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        pendingAttachment = uri
        pendingAttachmentName = uri?.let { context.contentResolver.query(it, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } } ?: ""
    }
    LaunchedEffect(sendStatus) {
        if (sendStatus == "Attachment sent") {
            pendingAttachment = null
            pendingAttachmentName = ""
            draft = ""
        }
    }

    if (remoteChat != null) {
        RemoteChatSessionScreen(
            state = remoteChat!!,
            draft = draft,
            onDraftChange = { draft = it.take(1_000) },
            pendingAttachment = pendingAttachment,
            pendingAttachmentName = pendingAttachmentName,
            sendStatus = sendStatus,
            actionInProgress = actionInProgress,
            onAttach = { attachmentPicker.launch("*/*") },
            onRemoveAttachment = { pendingAttachment = null; pendingAttachmentName = "" },
            onSendText = { text -> viewModel.sendRemoteChatMessage(text) { draft = "" } },
            onSendAttachment = { uri -> viewModel.sendRemoteChatAttachment(context, draft, uri) },
            onDeleteMessage = viewModel::deleteRemoteChatMessage,
            onLeave = viewModel::leaveRemoteChat
        )
        return
    }

    if (openLocalConversation) {
        LocalConversationScreen(
            room = activeRoom, messages = messages, activeUsers = activeUsers, draft = draft,
            pendingAttachment = pendingAttachment, pendingAttachmentName = pendingAttachmentName,
            sendStatus = sendStatus, actionInProgress = actionInProgress, participants = activeParticipants,
            onionAddress = activeWebsite?.onionAddress.orEmpty(),
            onBack = { openLocalConversation = false },
            onDraftChange = { draft = it.take(1_000) },
            onAttach = { attachmentPicker.launch("*/*") },
            onRemoveAttachment = { pendingAttachment = null; pendingAttachmentName = "" },
            onSendText = { viewModel.sendChatMessage(draft); draft = "" },
            onSendAttachment = { uri -> viewModel.sendChatAttachment(context, draft, uri) },
            onDeleteMessage = viewModel::deleteChatMessage,
            onBlockClient = viewModel::blockChatClient,
            onDeleteHistory = viewModel::deleteChatHistory,
            onRemoveChat = { viewModel.deleteActiveChatRoom(); openLocalConversation = false }
        )
        return
    }

    val personalRooms = (rooms.filter { it.startsWith("personal-") || it.startsWith("private-") } +
        activeRoom.takeIf { activeWebsite != null && (it.startsWith("personal-") || it.startsWith("private-")) }.orEmpty()).distinct().asReversed()
    val groupRooms = rooms.filterNot { it.startsWith("personal-") || it.startsWith("private-") }.asReversed()
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(15.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Chat, null, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text("Chats", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val torReady = torStatus.state == TorState.RUNNING
                    Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(if (torReady) Color(0xFF25D366) else MaterialTheme.colorScheme.outline))
                    Spacer(Modifier.width(6.dp))
                    Text(when {
                        torReady && torStatus.isHosting && activeUsers > 0 -> "$activeUsers active · Tor hosting live"
                        torReady && torStatus.isHosting -> "Tor hosting live"
                        torReady -> "Tor connected · ready to join"
                        torStatus.state == TorState.BOOTSTRAPPING || torStatus.state == TorState.STARTING -> "Connecting to Tor (${torStatus.bootstrapProgress}%)"
                        torStatus.state == TorState.ERROR -> "Tor connection needs attention"
                        else -> "Tor is off"
                    }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (joinRequests.isNotEmpty()) IconButton(onClick = { showApprovalDialog = true }) {
                BadgedBox(badge = { Badge { Text(joinRequests.size.toString()) } }) { Icon(Icons.Default.PersonAdd, "Join requests") }
            }
            FilledTonalButton(onClick = { showJoinDialog = true }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                Icon(Icons.Default.Link, "Join with invite", modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(5.dp))
                Text("Join")
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChatCategoryButton("Personal", Icons.Default.Person, chatTab == 0, Modifier.weight(1f)) { chatTab = 0 }
            ChatCategoryButton("Rooms", Icons.Default.Groups, chatTab == 1, Modifier.weight(1f)) { chatTab = 1 }
        }
        if (showHowTo && activeWebsite == null) {
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f))) {
                Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("How to start", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text("1. Open Home and tap Start Hosting.  2. Come back and create a personal chat or room.  3. Copy its invite and share it. The host approves join requests.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { showHowTo = false }) { Text("Got it") }
                }
            }
        }
        val inputLinkError = joinLinkError ?: remoteChatError
        if (!inputLinkError.isNullOrBlank()) {
            Surface(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 3.dp), color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
                Text(inputLinkError, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (chatTab == 0) {
                FilledTonalButton(onClick = { viewModel.createPrivateChatRoom(); openLocalConversation = true }, enabled = activeWebsite != null) {
                    Icon(Icons.Default.Add, null); Spacer(Modifier.width(7.dp)); Text("New chat")
                }
                Text("Create a private invite and share it with one person.", Modifier.weight(1f).padding(start = 10.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                OutlinedTextField(roomName, { roomName = it.take(40) }, Modifier.weight(1f), placeholder = { Text("Room name") }, singleLine = true, shape = RoundedCornerShape(18.dp))
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(onClick = { viewModel.createRoom(roomName); roomName = ""; openLocalConversation = true }, enabled = activeWebsite != null && roomName.isNotBlank()) {
                    Icon(Icons.Default.Add, "Create room")
                }
            }
        }
        val visibleRooms = if (chatTab == 0) personalRooms else groupRooms
        if (visibleRooms.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(if (chatTab == 0) Icons.Default.Person else Icons.Default.Groups, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(if (chatTab == 0) "Start a conversation" else "No rooms yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(if (chatTab == 0) "Create a private invite and share it with someone." else "Create a room and invite people with its link.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(top = 4.dp, bottom = 12.dp)) {
                items(visibleRooms, key = { it }) { room ->
                    val isPersonal = room.startsWith("personal-") || room.startsWith("private-")
                    val last = viewModel.chatPreview(room)
                    val title = if (isPersonal) "Personal chat" else room.removePrefix("room-")
                    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clickable {
                        viewModel.selectChatRoom(room); openLocalConversation = true
                    }) {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp, top = 9.dp, bottom = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(shape = RoundedCornerShape(50), color = if (isPersonal) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.size(54.dp)) {
                                Box(contentAlignment = Alignment.Center) { Icon(if (isPersonal) Icons.Default.Person else Icons.Default.Groups, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp)) }
                            }
                            Spacer(Modifier.width(13.dp))
                            Column(Modifier.weight(1f)) {
                                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                Text(last?.let { if (it.attachment != null) "▧ Photo or attachment" else "${it.sender}: ${it.text}" } ?: if (isPersonal) "Private anonymous chat" else "Group room",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                if (last != null) Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(last.sentAt)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton(onClick = {
                                    val invite = "http://${activeWebsite?.onionAddress.orEmpty()}/chat/$room"
                                    shareChatInvite(context, invite, if (isPersonal) "Personal chat invite" else "Room invite")
                                }, enabled = !activeWebsite?.onionAddress.isNullOrBlank(), contentPadding = PaddingValues(horizontal = 8.dp)) {
                                    Icon(Icons.Default.Share, "Share invite", modifier = Modifier.size(17.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Share", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (showJoinDialog) AlertDialog(
            onDismissRequest = { showJoinDialog = false }, icon = { Icon(Icons.Default.Link, null) }, title = { Text("Join Anonymous Chat") },
            text = { Column {
                OutlinedTextField(inviteLink, { inviteLink = it.take(300); joinLinkError = null }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Invite link") }, placeholder = { Text("http://…onion/chat/…") }, trailingIcon = {
                        Row {
                            IconButton(onClick = { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString()?.let { inviteLink = it } }) { Icon(Icons.Default.ContentCopy, "Paste invite") }
                            if (inviteLink.isNotBlank()) IconButton(onClick = { inviteLink = "" }) { Icon(Icons.Default.Delete, "Clear invite") }
                        }
                    })
                Spacer(Modifier.height(8.dp)); Text("Connects through OnionHost's Tor service.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!joinLinkError.isNullOrBlank()) Text(joinLinkError.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
            } },
            confirmButton = { TextButton(onClick = {
                val valid = runCatching { ChatInvite.parse(inviteLink) }
                valid.onSuccess { viewModel.joinChatInvite(context, inviteLink); showJoinDialog = false; joinLinkError = null }
                    .onFailure { joinLinkError = it.message ?: "This invite link is not supported." }
            }, enabled = inviteLink.isNotBlank() && !actionInProgress) { Text("Join Chat") } },
            dismissButton = { TextButton(onClick = { showJoinDialog = false }) { Text("Cancel") } }
        )
        if (showApprovalDialog) AlertDialog(onDismissRequest = { showApprovalDialog = false }, icon = { Icon(Icons.Default.PersonAdd, null) }, title = { Text("Join requests (${joinRequests.size})") },
            text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                joinRequests.forEach { request -> ListItem(headlineContent = { Text(request.displayName) }, supportingContent = { Text(request.room, maxLines = 1) }, trailingContent = {
                    Row { IconButton(onClick = { viewModel.cancelChatJoin(request.room, request.clientId) }) { Icon(Icons.Default.Delete, "Cancel") }; IconButton(onClick = { viewModel.approveChatJoin(request.room, request.clientId) }) { Icon(Icons.Default.PersonAdd, "Approve") } }
                }); HorizontalDivider() }
            } }, confirmButton = { TextButton(onClick = { showApprovalDialog = false }) { Text("Done") } })
    }
}
@Composable
private fun LocalConversationScreen(
    room: String,
    messages: List<com.onionhost.app.http.AnonymousChatStore.Message>,
    activeUsers: Int,
    draft: String,
    pendingAttachment: Uri?,
    pendingAttachmentName: String,
    sendStatus: String?,
    actionInProgress: Boolean,
    participants: List<com.onionhost.app.http.AnonymousChatStore.ActiveChatClient>,
    onionAddress: String,
    onBack: () -> Unit,
    onDraftChange: (String) -> Unit,
    onAttach: () -> Unit,
    onRemoveAttachment: () -> Unit,
    onSendText: () -> Unit,
    onSendAttachment: (Uri) -> Unit,
    onDeleteMessage: (Long) -> Unit,
    onBlockClient: (String, String) -> Unit,
    onDeleteHistory: (String) -> Unit,
    onRemoveChat: () -> Unit
) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    var showManage by rememberSaveable(room) { mutableStateOf(false) }
    var confirmClear by rememberSaveable(room) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 10.dp, vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back to chats") }
            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(42.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(if (room.startsWith("room-")) Icons.Default.Groups else Icons.Default.Person, null, tint = MaterialTheme.colorScheme.primary) }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(if (room.startsWith("room-")) room.removePrefix("room-") else "Personal chat", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(if (activeUsers > 0) "$activeUsers online · Onion Service" else "Anonymous chat", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = {
                val invite = "http://$onionAddress/chat/$room"
                shareChatInvite(context, invite, if (room.startsWith("room-")) "Room invite" else "Personal chat invite")
            }, enabled = onionAddress.isNotBlank()) { Icon(Icons.Default.Share, "Share chat invite") }
            IconButton(onClick = { showManage = true }) { Icon(Icons.Default.MoreVert, "Chat options") }
        }
        Card(Modifier.weight(1f).fillMaxWidth().padding(vertical = 5.dp), shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp)) {
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), reverseLayout = true) {
                    if (messages.isEmpty()) item { ChatEmptyState("Start a conversation", "Share this chat invite. Messages from approved visitors appear here.") }
                    items(messages.asReversed(), key = { it.id }) { message ->
                        val outgoing = message.ownerId == null
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start) {
                            Column(Modifier.fillMaxWidth(0.88f).widthIn(max = 380.dp)
                                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = if (outgoing) 5.dp else 18.dp, bottomStart = if (outgoing) 18.dp else 5.dp))
                                .background(chatBubbleColor(outgoing, dark)).padding(horizontal = 13.dp, vertical = 9.dp)) {
                                Text(message.sender, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                                if (message.text.isNotBlank()) Text(message.text, style = MaterialTheme.typography.bodyMedium)
                                message.attachment?.let { attachment ->
                                    if (attachment.mimeType.startsWith("image/")) ChatImagePreview(attachment.dataUrl, attachment.name)
                                    else Text("▧ ${attachment.name}", style = MaterialTheme.typography.labelSmall)
                                }
                                Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                                    Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.sentAt)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (outgoing) IconButton(onClick = { onDeleteMessage(message.id) }, modifier = Modifier.size(26.dp)) { Icon(Icons.Default.Delete, "Delete my message", modifier = Modifier.size(15.dp)) }
                                }
                            }
                        }
                    }
                }
                pendingAttachment?.let { uri ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        val preview = rememberLocalImagePreview(context, uri)
                        if (preview != null) Image(preview, "Attachment preview", Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)))
                        Text(pendingAttachmentName.ifBlank { "Attachment" }, Modifier.weight(1f).padding(horizontal = 8.dp), maxLines = 1, style = MaterialTheme.typography.labelSmall)
                        TextButton(onClick = onRemoveAttachment) { Text("Remove") }
                    }
                }
                Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Row(Modifier.padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(draft, onDraftChange, Modifier.weight(1f), placeholder = { Text("Message") }, singleLine = true,
                            shape = RoundedCornerShape(24.dp), colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Color.Transparent, focusedBorderColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedContainerColor = Color.Transparent))
                        IconButton(onClick = onAttach, enabled = !actionInProgress) { Icon(Icons.Default.AttachFile, "Attach photo or file") }
                        IconButton(onClick = { if (pendingAttachment != null) onSendAttachment(pendingAttachment) else onSendText() }, enabled = !actionInProgress && (draft.isNotBlank() || pendingAttachment != null)) {
                            if (actionInProgress) CircularProgressIndicator(Modifier.size(19.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Send, "Send")
                        }
                    }
                }
                if (actionInProgress || !sendStatus.isNullOrBlank()) Text(sendStatus ?: "Sending…", style = MaterialTheme.typography.labelSmall, color = if (sendStatus?.contains("sent", true) == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp, top = 3.dp))
            }
        }
    }
    if (showManage) AlertDialog(onDismissRequest = { showManage = false }, icon = { Icon(Icons.Default.MoreVert, null) }, title = { Text("Manage chat") },
        text = { Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
            Text("Participants", style = MaterialTheme.typography.titleSmall)
            if (participants.isEmpty()) Text("No approved visitors yet.", style = MaterialTheme.typography.bodySmall)
            participants.forEach { person -> ListItem(headlineContent = { Text(person.displayName) }, supportingContent = { Text(if (person.isActive) "Online" else "Approved · offline") }, trailingContent = { TextButton(onClick = { onBlockClient(person.room, person.clientId) }) { Text("Block") } }) }
            HorizontalDivider()
            TextButton(onClick = { showManage = false; confirmClear = true }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Delete, null); Spacer(Modifier.width(6.dp)); Text("Delete full chat history") }
            TextButton(onClick = onRemoveChat, modifier = Modifier.fillMaxWidth()) { Text("Remove chat from this device") }
        } }, confirmButton = { TextButton(onClick = { showManage = false }) { Text("Done") } })
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("Delete history for everyone?") },
        text = { Text("The host and connected chat views clear on sync. Offline devices clear when they reconnect.") },
        confirmButton = { TextButton(onClick = { onDeleteHistory(room); confirmClear = false }) { Text("Delete history") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } })
}

@Composable
private fun ChatCategoryButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.height(48.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(19.dp), tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun chatBubbleColor(outgoing: Boolean, darkMode: Boolean): Color = when {
    darkMode && outgoing -> Color(0xFF174B3C)
    darkMode -> DarkCard
    outgoing -> ChatOutgoingBubble
    else -> ChatIncomingBubble
}

@Composable
private fun RemoteChatSessionScreen(
    state: com.onionhost.app.chat.RemoteChatState,
    draft: String,
    onDraftChange: (String) -> Unit,
    pendingAttachment: Uri?,
    pendingAttachmentName: String,
    sendStatus: String?,
    actionInProgress: Boolean,
    onAttach: () -> Unit,
    onRemoveAttachment: () -> Unit,
    onSendText: (String) -> Unit,
    onSendAttachment: (Uri) -> Unit,
    onDeleteMessage: (Long) -> Unit,
    onLeave: () -> Unit
) {
    val context = LocalContext.current
    val darkMode = isSystemInDarkTheme()
    val connected = state.status.startsWith("Connected")
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(10.dp)) {
        Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onLeave) { Icon(Icons.Default.ArrowBack, contentDescription = "Leave remote chat") }
                Column(Modifier.weight(1f)) {
                    Text("OnionHost Chat", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(state.invite.room, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(if (connected) "Connected" else "Waiting", style = MaterialTheme.typography.labelSmall, color = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(8.dp))
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text(state.status, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                if (!state.error.isNullOrBlank()) Text(state.error.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                if (!connected) Text("The host must approve your request before chat messages are available.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(8.dp))
        Card(Modifier.weight(1f).fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxSize().padding(10.dp)) {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    reverseLayout = true
                ) {
                    if (state.messages.isEmpty()) item {
                        ChatEmptyState(
                            if (connected) "No messages yet" else "Waiting for approval",
                            if (connected) "Send a message to start this conversation." else "You will see messages after the host approves your request."
                        )
                    }
                    items(state.messages.asReversed(), key = { it.id }) { message ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.ownerId == null) Arrangement.End else Arrangement.Start) {
                            Column(
                                Modifier.fillMaxWidth(0.86f).widthIn(max = 360.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(chatBubbleColor(message.ownerId == null, darkMode))
                                    .padding(horizontal = 12.dp, vertical = 9.dp)
                            ) {
                                Text(message.sender, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                                if (message.text.isNotBlank()) Text(message.text, style = MaterialTheme.typography.bodyMedium)
                                message.attachment?.let { attachment ->
                                    if (attachment.mimeType.startsWith("image/")) ChatImagePreview(attachment.dataUrl, attachment.name)
                                    else Text("📎 ${attachment.name}", style = MaterialTheme.typography.labelSmall)
                                }
                                Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                                    Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.sentAt)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (message.ownerId == null) IconButton(onClick = { onDeleteMessage(message.id) }, modifier = Modifier.size(28.dp)) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete my message", modifier = Modifier.size(15.dp))
                                    }
                                }
                            }
                        }
                    }
                }
                pendingAttachment?.let { uri ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        val preview = rememberLocalImagePreview(context, uri)
                        if (preview != null) Image(preview, contentDescription = "Selected image preview", modifier = Modifier.size(54.dp).clip(RoundedCornerShape(9.dp)))
                        Text(pendingAttachmentName.ifBlank { "Attachment" }, Modifier.weight(1f).padding(horizontal = 8.dp), maxLines = 1, style = MaterialTheme.typography.labelSmall)
                        TextButton(onClick = onRemoveAttachment) { Text("Remove") }
                    }
                }
                Surface(shape = RoundedCornerShape(25.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = draft, onValueChange = onDraftChange, modifier = Modifier.weight(1f),
                            placeholder = { Text("Message") }, singleLine = true, enabled = connected && !actionInProgress,
                            shape = RoundedCornerShape(24.dp),
                            colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Color.Transparent, focusedBorderColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedContainerColor = Color.Transparent)
                        )
                        IconButton(onClick = onAttach, enabled = connected && !actionInProgress) { Icon(Icons.Default.AttachFile, contentDescription = "Attach file") }
                        IconButton(
                            onClick = { if (pendingAttachment != null) onSendAttachment(pendingAttachment) else onSendText(draft) },
                            enabled = connected && !actionInProgress && (draft.isNotBlank() || pendingAttachment != null)
                        ) {
                            if (actionInProgress) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Default.Send, contentDescription = "Send")
                        }
                    }
                }
                if (!sendStatus.isNullOrBlank()) Text(sendStatus.orEmpty(), style = MaterialTheme.typography.labelSmall, color = if (sendStatus == "Message sent" || sendStatus == "Attachment sent") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun rememberLocalImagePreview(context: Context, uri: Uri): androidx.compose.ui.graphics.ImageBitmap? {
    val bitmap by produceState<Bitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            }.getOrNull()
        }
    }
    return bitmap?.asImageBitmap()
}

@Composable
private fun ChatImagePreview(dataUrl: String, name: String) {
    val cacheKey = remember(dataUrl) { "${dataUrl.length}:${dataUrl.hashCode()}" }
    var expanded by remember(dataUrl) { mutableStateOf(false) }
    val bitmap by produceState<Bitmap?>(ChatImageMemoryCache.get(cacheKey), cacheKey) {
        if (value == null) value = withContext(Dispatchers.Default) {
            runCatching {
                val bytes = Base64.decode(dataUrl.substringAfter("base64,", ""), Base64.DEFAULT)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            }.getOrNull()?.also { ChatImageMemoryCache.put(cacheKey, it) }
        }
    }
    bitmap?.let { image ->
        Image(image.asImageBitmap(), contentDescription = "View $name", contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).clip(RoundedCornerShape(12.dp)).clickable { expanded = true })
        if (expanded) androidx.compose.ui.window.Dialog(onDismissRequest = { expanded = false }) {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { expanded = false }) { Text("Close") }
                    Image(image.asImageBitmap(), contentDescription = name, contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 720.dp))
                    Text(name, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), maxLines = 1, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    } ?: Text("Image preview unavailable · $name", style = MaterialTheme.typography.labelSmall)
}

/** Process-memory cache avoids decoding the same image again on recomposition. */
private object ChatImageMemoryCache {
    private val bitmaps = object : LruCache<String, Bitmap>(8 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }
    fun get(key: String): Bitmap? = bitmaps.get(key)
    fun put(key: String, bitmap: Bitmap) { bitmaps.put(key, bitmap) }
}

@Composable
private fun ChatEmptyState(title: String, detail: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(6.dp))
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun shareChatInvite(context: Context, invite: String, title: String) {
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, invite)
    }
    context.startActivity(Intent.createChooser(sendIntent, title))
}
