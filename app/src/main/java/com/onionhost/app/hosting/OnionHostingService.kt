package com.onionhost.app.hosting

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.onionhost.app.R
import com.onionhost.app.analytics.AnalyticsTracker
import com.onionhost.app.database.dao.LogDao
import com.onionhost.app.database.dao.WebsiteDao
import com.onionhost.app.database.entity.LogEntity
import com.onionhost.app.database.entity.LogLevel
import com.onionhost.app.database.entity.WebsiteEntity
import com.onionhost.app.http.HttpServerEngine
import com.onionhost.app.repository.SettingsRepository
import com.onionhost.app.security.RateLimiter
import com.onionhost.app.storage.StorageManager
import com.onionhost.app.tor.TorManager
import com.onionhost.app.tor.TorState
import com.onionhost.app.ui.main.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class OnionHostingService : Service() {

    @Inject lateinit var torManager: TorManager
    @Inject lateinit var websiteDao: WebsiteDao
    @Inject lateinit var logDao: LogDao
    @Inject lateinit var analyticsTracker: AnalyticsTracker
    @Inject lateinit var storageManager: StorageManager
    @Inject lateinit var settingsRepository: SettingsRepository

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var httpServer: HttpServerEngine? = null
    private var activeWebsite: WebsiteEntity? = null
    private var hostingJob: Job? = null
    private var requestedWebsiteId: String? = null

    companion object {
        const val CHANNEL_ID = "onion_host_service_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_RESTART = "ACTION_RESTART"
        const val EXTRA_WEBSITE_ID = "EXTRA_WEBSITE_ID"

        fun startService(context: Context, websiteId: String) {
            val intent = Intent(context, OnionHostingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_WEBSITE_ID, websiteId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, OnionHostingService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun restartService(context: Context, websiteId: String) {
            val intent = Intent(context, OnionHostingService::class.java).apply {
                action = ACTION_RESTART
                putExtra(EXTRA_WEBSITE_ID, websiteId)
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        when (action) {
            ACTION_START -> {
                val websiteId = intent?.getStringExtra(EXTRA_WEBSITE_ID)
                startHosting(websiteId)
            }
            ACTION_STOP -> stopHosting()
            ACTION_RESTART -> {
                restartHosting(intent?.getStringExtra(EXTRA_WEBSITE_ID) ?: activeWebsite?.id)
            }
        }
        return START_STICKY
    }

    private fun startHosting(websiteId: String?) {
        if (hostingJob?.isActive == true) {
            if ((requestedWebsiteId == websiteId || websiteId == null) && torManager.torStatus.value.state != TorState.ERROR) return
            val retryId = websiteId ?: requestedWebsiteId
            if (retryId == null) return
            restartHosting(retryId)
            return
        }
        requestedWebsiteId = websiteId
        val notification = createNotification("Initializing Tor Onion Service...")
        startForeground(NOTIFICATION_ID, notification)

        hostingJob = serviceScope.launch {
            try {
                val website = if (websiteId != null) {
                    websiteDao.getWebsiteById(websiteId)
                } else {
                    websiteDao.getActiveWebsite()
                }

                if (website == null) {
                    logDao.insertLog(LogEntity(level = LogLevel.ERROR, tag = "Service", message = "No active website found to host."))
                    stopSelf()
                    return@launch
                }

                activeWebsite = website
                websiteDao.setActiveWebsite(website.id)

                val webDir = storageManager.getWebsiteDirectory(website.id)
                val configuredRateLimit = settingsRepository.rateLimitPerMin.first().coerceIn(1, 10_000)
                logDao.insertLog(LogEntity(level = LogLevel.INFO, tag = "HTTP", message = "Starting HTTP Server on port ${website.port}..."))

                // Start Embedded HTTP Server
                httpServer?.stop()
                httpServer = HttpServerEngine(
                    port = website.port,
                    webRootDir = webDir,
                    rateLimiter = RateLimiter(maxRequestsPerMinute = configuredRateLimit),
                    requiresAuth = website.requiresAuth,
                    authUsername = website.authUsername,
                    authPasswordHash = website.authPasswordHash,
                    onRequestServed = { path, status, bytes, isDownload ->
                        serviceScope.launch {
                            analyticsTracker.recordRequest(website.id, path, status, bytes, isDownload)
                        }
                    }
                ).apply {
                    start()
                }

                // Collect detailed Tor logs into database
                serviceScope.launch {
                    torManager.torLogFlow.collect { logMsg ->
                        val level = if (logMsg.contains("[ERROR]")) LogLevel.ERROR else LogLevel.TOR
                        logDao.insertLog(LogEntity(level = level, tag = "Tor", message = logMsg))
                    }
                }

                // Start Tor daemon & provision Hidden Service
                logDao.insertLog(LogEntity(level = LogLevel.TOR, tag = "Tor", message = "Bootstrapping Tor Hidden Service for port ${website.port}..."))
                torManager.startTor(website.port, serviceScope)

                // Observe status & update notification
                torManager.torStatus.collect { status ->
                    val text = when (status.state) {
                        TorState.STARTING -> "Starting Tor..."
                        TorState.BOOTSTRAPPING -> "Bootstrapping Tor (${status.bootstrapProgress}%)..."
                        TorState.RUNNING -> "Hosting live on ${status.onionAddress}"
                        TorState.ERROR -> "Tor Error: ${status.errorMessage}"
                        TorState.STOPPED -> "Stopped"
                    }

                    if (status.onionAddress.isNotBlank() && status.onionAddress != website.onionAddress) {
                        websiteDao.updateWebsite(website.copy(onionAddress = status.onionAddress))
                        logDao.insertLog(LogEntity(level = LogLevel.INFO, tag = "Tor", message = "Published Onion Address saved to DB: ${status.onionAddress}"))
                    }

                    if (status.state == TorState.ERROR) {
                        logDao.insertLog(LogEntity(level = LogLevel.ERROR, tag = "Tor", message = "Tor error: ${status.errorMessage ?: "Unknown Tor error"}"))
                    }

                    updateNotification(text)
                }

            } catch (e: Exception) {
                e.printStackTrace()
                logDao.insertLog(LogEntity(level = LogLevel.ERROR, tag = "Service", message = "Hosting failure: ${e.localizedMessage}"))
            }
        }
    }

    private fun stopHosting() {
        hostingJob?.cancel()
        hostingJob = null
        requestedWebsiteId = null
        serviceScope.launch {
            httpServer?.stop()
            httpServer = null
            torManager.stopTor()
            websiteDao.disableAllWebsites()
            logDao.insertLog(LogEntity(level = LogLevel.INFO, tag = "Service", message = "Hosting stopped."))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun restartHosting(websiteId: String?) {
        if (websiteId == null) return
        hostingJob?.cancel()
        hostingJob = null
        requestedWebsiteId = null
        serviceScope.launch {
            httpServer?.stop()
            httpServer = null
            torManager.stopTor()
            delay(500)
            logDao.insertLog(LogEntity(level = LogLevel.INFO, tag = "Service", message = "Restarting hosting service."))
            startHosting(websiteId)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(statusText: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingOpenApp = PendingIntent.getActivity(
            this, 0, openAppIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, OnionHostingService::class.java).apply { action = ACTION_STOP }
        val pendingStop = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        val restartIntent = Intent(this, OnionHostingService::class.java).apply { action = ACTION_RESTART }
        val pendingRestart = PendingIntent.getService(this, 2, restartIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OnionHost Active")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentIntent(pendingOpenApp)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop", pendingStop)
            .addAction(android.R.drawable.ic_popup_sync, "Restart", pendingRestart)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification(text))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        hostingJob?.cancel()
        serviceScope.cancel()
        httpServer?.stop()
        torManager.stopTor()
    }
}
