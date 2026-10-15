package com.onionhost.app.ui.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.onionhost.app.common.generateQrCodeBitmap
import com.onionhost.app.database.entity.WebsiteType
import com.onionhost.app.tor.TorState

@Composable
fun HomeScreen(
    viewModel: HomeViewModel
) {
    val context = LocalContext.current
    val activeWebsite by viewModel.activeWebsite.collectAsState()
    val torStatus by viewModel.torStatus.collectAsState()
    val metrics by viewModel.systemMetrics.collectAsState()
    var pendingFileType by rememberSaveable { mutableStateOf(WebsiteType.SINGLE_FILE) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.importAndHost(context, it, pendingFileType) }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            viewModel.importAndHost(context, it, WebsiteType.FOLDER)
        }
    }

    val qrDownloadLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("image/png")
    ) { uri: Uri? ->
        val address = activeWebsite?.onionAddress.orEmpty()
        val bitmap = if (address.isBlank()) null else "http://$address".generateQrCodeBitmap()
        if (uri != null && bitmap != null) {
            val saved = context.contentResolver.openOutputStream(uri)?.use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            } == true
            Toast.makeText(context, if (saved) "QR code saved" else "Could not save QR code", Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Image(
                    painter = androidx.compose.ui.res.painterResource(id = com.onionhost.app.R.drawable.app_logo),
                    contentDescription = "OnionHost Logo",
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
                Column {
                    Text(
                        text = "OnionHost",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "Anonymous Tor Web Server",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }
            IconButton(
                enabled = activeWebsite != null && torStatus.isHosting,
                onClick = {
                    viewModel.restartHosting(context)
                    Toast.makeText(context, "Restarting Onion server…", Toast.LENGTH_SHORT).show()
                }
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "Restart server")
            }
        }

        // Main Status Card
        Card(
            colors = CardDefaults.cardColors(
                containerColor = when (torStatus.state) {
                    TorState.RUNNING -> Color(0xFF064E3B)
                    TorState.BOOTSTRAPPING, TorState.STARTING -> Color(0xFF78350F)
                    TorState.ERROR -> Color(0xFF7F1D1D)
                    else -> MaterialTheme.colorScheme.surface
                }
            ),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            val statusTextColor = when (torStatus.state) {
                TorState.RUNNING, TorState.BOOTSTRAPPING, TorState.STARTING, TorState.ERROR -> Color.White
                TorState.STOPPED -> MaterialTheme.colorScheme.onSurface
            }
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = when (torStatus.state) {
                        TorState.RUNNING -> Icons.Default.Public
                        TorState.BOOTSTRAPPING, TorState.STARTING -> Icons.Default.Sync
                        TorState.ERROR -> Icons.Default.Warning
                        else -> Icons.Default.PublicOff
                    },
                    contentDescription = "Status Icon",
                    modifier = Modifier.size(48.dp),
                    tint = Color.White
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = when (torStatus.state) {
                        TorState.RUNNING -> if (torStatus.isHosting) "Hosting Live on Tor" else "Tor Client Connected"
                        TorState.BOOTSTRAPPING -> if (torStatus.isHosting) "Bootstrapping Tor (${torStatus.bootstrapProgress}%)" else "Connecting to Tor (${torStatus.bootstrapProgress}%)"
                        TorState.STARTING -> "Starting Engine..."
                        TorState.ERROR -> "Tor Error"
                        TorState.STOPPED -> "Hosting Inactive"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = statusTextColor
                )

                if (torStatus.state == TorState.BOOTSTRAPPING) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = torStatus.bootstrapProgress / 100f,
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                if (torStatus.state == TorState.ERROR && !torStatus.errorMessage.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "${torStatus.errorMessage}\n\nCheck your internet connection, turn off battery restriction for OnionHost, then retry. Hosting also needs the app to remain running while Tor publishes the service.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFFCA5A5)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // One Button Host Control
                if (torStatus.isHosting && (torStatus.state == TorState.RUNNING || torStatus.state == TorState.BOOTSTRAPPING)) {
                    Button(
                        onClick = { viewModel.stopHosting(context) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Stop Hosting")
                    }
                } else if (!torStatus.isHosting && (torStatus.state == TorState.RUNNING || torStatus.state == TorState.BOOTSTRAPPING || torStatus.state == TorState.STARTING)) {
                    Button(
                        onClick = viewModel::stopTorClient,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Disconnect Tor Client")
                    }
                } else {
                    Button(
                        onClick = {
                            if (torStatus.state == TorState.ERROR && !torStatus.isHosting) {
                                viewModel.retryTorClient()
                            } else {
                                activeWebsite?.let { viewModel.startHosting(context, it.id) }
                                    ?: folderPickerLauncher.launch(null)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(when {
                            torStatus.state == TorState.ERROR && !torStatus.isHosting -> "Retry Tor Connection"
                            torStatus.state == TorState.ERROR -> "Retry Hosting"
                            else -> "Start Hosting"
                        })
                    }
                }
            }
        }

        // Active Website Details & Onion Address Card
        activeWebsite?.let { website ->
            Card(
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Active Site: ${website.name}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    if (website.onionAddress.isNotBlank()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.background)
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "http://${website.onionAddress}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            IconButton(onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("Onion Address", "http://${website.onionAddress}")
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "Copied Onion Address!", Toast.LENGTH_SHORT).show()
                            }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
                            }
                        }

                        // QR Code display
                        remember(website.onionAddress) {
                            "http://${website.onionAddress}".generateQrCodeBitmap()
                        }?.let { qrBitmap ->
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("Website Server QR Code", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    bitmap = qrBitmap.asImageBitmap(),
                                    contentDescription = "Onion Address QR — tap to download",
                                    modifier = Modifier
                                        .size(160.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { qrDownloadLauncher.launch("onionhost-qr.png") }
                                )
                            }
                            TextButton(
                                onClick = { qrDownloadLauncher.launch("onionhost-qr.png") },
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Download QR Code")
                            }
                        }
                    }
                }
            }
        }

        // Import actions are full-width so their icons and labels remain
        // readable on narrow phones.
        Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Select Content to Host",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Choose a folder, ZIP archive, or a single file.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ImportContentButton(
                    label = "Choose Folder",
                    description = "Host a complete website folder",
                    icon = Icons.Default.Folder,
                    onClick = { folderPickerLauncher.launch(null) }
                )
                ImportContentButton(
                    label = "Choose ZIP File",
                    description = "Extract and host a ZIP archive",
                    icon = Icons.Default.FolderZip,
                    onClick = {
                        pendingFileType = WebsiteType.ZIP
                        filePickerLauncher.launch("application/zip")
                    }
                )
                ImportContentButton(
                    label = "Choose Single File",
                    description = "Host an HTML file, document, or media file",
                    icon = Icons.Default.InsertDriveFile,
                    onClick = {
                        pendingFileType = WebsiteType.SINGLE_FILE
                        filePickerLauncher.launch("*/*")
                    }
                )
            }
        }

        // Dashboard Metrics Card
        Card(
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Live Dashboard Metrics",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    MetricTile("Total Visits", "${activeWebsite?.totalVisits ?: 0}", Icons.Default.Visibility)
                    MetricTile("Downloads", "${activeWebsite?.totalDownloads ?: 0}", Icons.Default.Download)
                    MetricTile("Memory", "${metrics.memoryUsageMb} MB", Icons.Default.Memory)
                }
            }
        }
    }
}

@Composable
private fun ImportContentButton(
    label: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.labelSmall)
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null)
    }
}

@Composable
fun MetricTile(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
    }
}
