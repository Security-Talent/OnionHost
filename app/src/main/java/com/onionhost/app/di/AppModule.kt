package com.onionhost.app.di

import android.content.Context
import androidx.room.Room
import com.onionhost.app.analytics.AnalyticsTracker
import com.onionhost.app.chat.discovery.TorDiscoveryNodeClient
import com.onionhost.app.chat.discovery.UsernameDiscoveryRepository
import com.onionhost.app.chat.TorChatClient
import com.onionhost.app.database.OnionHostDatabase
import com.onionhost.app.database.dao.LogDao
import com.onionhost.app.database.dao.VisitorStatDao
import com.onionhost.app.database.dao.WebsiteDao
import com.onionhost.app.repository.AnalyticsRepository
import com.onionhost.app.repository.AnalyticsRepositoryImpl
import com.onionhost.app.repository.SettingsRepository
import com.onionhost.app.repository.WebsiteRepository
import com.onionhost.app.repository.WebsiteRepositoryImpl
import com.onionhost.app.storage.StorageManager
import com.onionhost.app.tor.TorManager
import com.onionhost.app.tor.TorOnionSocketConnector
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): OnionHostDatabase {
        return Room.databaseBuilder(
            context,
            OnionHostDatabase::class.java,
            "onionhost.db"
        ).fallbackToDestructiveMigration().build()
    }

    @Provides
    fun provideWebsiteDao(db: OnionHostDatabase): WebsiteDao = db.websiteDao()

    @Provides
    fun provideVisitorStatDao(db: OnionHostDatabase): VisitorStatDao = db.visitorStatDao()

    @Provides
    fun provideLogDao(db: OnionHostDatabase): LogDao = db.logDao()

    @Provides
    @Singleton
    fun provideStorageManager(@ApplicationContext context: Context): StorageManager {
        return StorageManager(context)
    }

    @Provides
    @Singleton
    fun provideTorManager(@ApplicationContext context: Context): TorManager {
        return TorManager(context)
    }

    @Provides
    @Singleton
    fun provideOnionSocketConnector(): TorOnionSocketConnector = TorOnionSocketConnector()

    @Provides
    @Singleton
    fun provideTorChatClient(connector: TorOnionSocketConnector): TorChatClient = TorChatClient(connector)

    @Provides
    @Singleton
    fun provideUsernameDiscoveryRepository(connector: TorOnionSocketConnector): UsernameDiscoveryRepository =
        UsernameDiscoveryRepository(TorDiscoveryNodeClient(connector))

    @Provides
    @Singleton
    fun provideAnalyticsTracker(
        visitorStatDao: VisitorStatDao,
        websiteDao: WebsiteDao
    ): AnalyticsTracker {
        return AnalyticsTracker(visitorStatDao, websiteDao)
    }

    @Provides
    @Singleton
    fun provideWebsiteRepository(
        websiteDao: WebsiteDao,
        storageManager: StorageManager
    ): WebsiteRepository {
        return WebsiteRepositoryImpl(websiteDao, storageManager)
    }

    @Provides
    @Singleton
    fun provideAnalyticsRepository(
        visitorStatDao: VisitorStatDao,
        logDao: LogDao
    ): AnalyticsRepository {
        return AnalyticsRepositoryImpl(visitorStatDao, logDao)
    }

    @Provides
    @Singleton
    fun provideSettingsRepository(@ApplicationContext context: Context): SettingsRepository {
        return SettingsRepository(context)
    }
}
