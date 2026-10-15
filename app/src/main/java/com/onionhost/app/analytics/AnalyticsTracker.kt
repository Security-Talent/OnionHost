package com.onionhost.app.analytics

import com.onionhost.app.database.dao.VisitorStatDao
import com.onionhost.app.database.dao.WebsiteDao
import com.onionhost.app.database.entity.VisitorStatEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class AnalyticsTracker(
    private val visitorStatDao: VisitorStatDao,
    private val websiteDao: WebsiteDao
) {
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)
        .withZone(ZoneId.systemDefault())

    suspend fun recordRequest(
        websiteId: String,
        path: String,
        statusCode: Int,
        bytesTransferred: Long,
        isDownload: Boolean
    ) {
        val now = System.currentTimeMillis()
        val todayStr = dateFormatter.format(Instant.ofEpochMilli(now))
        val stat = VisitorStatEntity(
            websiteId = websiteId,
            requestedPath = path,
            statusCode = statusCode,
            bytesTransferred = bytesTransferred,
            timestamp = now,
            dateString = todayStr,
            isDownload = isDownload
        )

        visitorStatDao.insertStat(stat)
        websiteDao.incrementVisits(websiteId)

        if (isDownload) {
            websiteDao.incrementDownloads(websiteId)
        }
    }
}
