package cc.tomko.outify.di

import android.content.Context
import cc.tomko.outify.OutifyApplication
import cc.tomko.outify.data.dao.AlbumArtistDao
import cc.tomko.outify.data.dao.AlbumDao
import cc.tomko.outify.data.dao.AlbumTrackDao
import cc.tomko.outify.data.dao.ArtistDao
import cc.tomko.outify.data.dao.EpisodeDao
import cc.tomko.outify.data.dao.LikedDao
import cc.tomko.outify.data.dao.LikedItemsDao
import cc.tomko.outify.data.dao.PlaylistDao
import cc.tomko.outify.data.dao.ShowDao
import cc.tomko.outify.data.dao.ShowEpisodeDao
import cc.tomko.outify.data.dao.TrackArtistDao
import cc.tomko.outify.data.dao.TrackDao
import cc.tomko.outify.data.dao.TrackFileDao
import cc.tomko.outify.data.database.AppDatabase
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.request.crossfade
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import java.time.Clock
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return (context.applicationContext as OutifyApplication).database
    }

    @Provides
    @Singleton
    fun provideImageLoader(@ApplicationContext context: Context): ImageLoader {
        return ImageLoader.Builder(context)
            .crossfade(true)
            .memoryCache(
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.10)
                    .weakReferencesEnabled(true)
                    .build()
            )
            .diskCache(
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache"))
                    .maxSizePercent(0.25)
                    .build()
            ).build()
    }

    @Provides
    fun provideIoScope(): CoroutineScope = CoroutineScope(Dispatchers.IO)

    @Provides
    @Named("metadataConcurrency")
    fun provideMetadataConcurrency(): Int = 10

    @Provides
    @Singleton
    fun provideJson(): Json {
        return json
    }

    @Provides
    @Singleton
    fun provideClock(): Clock {
        return Clock.systemUTC()
    }

    @Provides
    @Singleton
    fun provideTrackDao(database: AppDatabase): TrackDao {
        return database.trackDao()
    }

    @Provides
    @Singleton
    fun provideArtistDao(database: AppDatabase): ArtistDao {
        return database.artistDao()
    }

    @Provides
    @Singleton
    fun provideTrackArtistDao(database: AppDatabase): TrackArtistDao {
        return database.trackArtistDao()
    }

    @Provides
    @Singleton
    fun provideTrackFileDao(database: AppDatabase): TrackFileDao {
        return database.trackFileDao()
    }

    @Provides
    @Singleton
    fun provideAlbumDao(database: AppDatabase): AlbumDao {
        return database.albumDao()
    }

    @Provides
    @Singleton
    fun provideAlbumArtistDao(database: AppDatabase): AlbumArtistDao {
        return database.albumArtistDao()
    }

    @Provides
    @Singleton
    fun provideAlbumTrackDao(database: AppDatabase): AlbumTrackDao {
        return database.albumTrackDao()
    }

    @Provides
    @Singleton
    fun providePlaylistDao(database: AppDatabase): PlaylistDao {
        return database.playlistDao()
    }

    @Provides
    @Singleton
    fun provideLikedDao(database: AppDatabase): LikedDao {
        return database.likedDao()
    }

    @Provides
    @Singleton
    fun provideLikedItemsDao(database: AppDatabase): LikedItemsDao {
        return database.likedItemsDao()
    }

    @Provides
    @Singleton
    fun provideShowDao(database: AppDatabase): ShowDao {
        return database.showDao()
    }

    @Provides
    @Singleton
    fun provideEpisodeDao(database: AppDatabase): EpisodeDao {
        return database.episodeDao()
    }

    @Provides
    @Singleton
    fun provideShowEpisodeDao(database: AppDatabase): ShowEpisodeDao {
        return database.showEpisodeDao()
    }
}