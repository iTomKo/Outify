package cc.tomko.outify.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
import cc.tomko.outify.data.database.album.AlbumArtistEntity
import cc.tomko.outify.data.database.album.AlbumTrackCrossRef
import cc.tomko.outify.data.database.playlist.PlaylistDiffEntity
import cc.tomko.outify.data.database.playlist.PlaylistItemEntity
import cc.tomko.outify.data.database.show.ShowEpisodeCrossRef
import cc.tomko.outify.data.database.track.LikedTrackEntity
import cc.tomko.outify.data.database.track.PlaylistTrackEntity

@Database(
    entities = [
        TrackEntity::class,
        TrackFileEntity::class,
        ArtistEntity::class,
        TrackArtistEntity::class,
        AlbumEntity::class,
        AlbumTrackCrossRef::class,
        AlbumArtistEntity::class,
        PlaylistEntity::class,
        PlaylistItemEntity::class,
        PlaylistDiffEntity::class,
        PlaylistTrackEntity::class,
        LikedTrackEntity::class,
        LikedItemsEntity::class,
        ShowEntity::class,
        EpisodeEntity::class,
        ShowEpisodeCrossRef::class,
    ],
    version = 18,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun trackFileDao(): TrackFileDao
    abstract fun artistDao(): ArtistDao
    abstract fun trackArtistDao(): TrackArtistDao
    abstract fun albumDao(): AlbumDao
    abstract fun albumArtistDao(): AlbumArtistDao
    abstract fun albumTrackDao(): AlbumTrackDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun likedDao(): LikedDao
    abstract fun likedItemsDao(): LikedItemsDao
    abstract fun showDao(): ShowDao
    abstract fun episodeDao(): EpisodeDao
    abstract fun showEpisodeDao(): ShowEpisodeDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS liked_items (
                        uri TEXT NOT NULL PRIMARY KEY,
                        type TEXT NOT NULL,
                        addedAt INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent()
                )
            }
        }

        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS shows (
                        showId TEXT NOT NULL PRIMARY KEY,
                        uri TEXT NOT NULL,
                        name TEXT NOT NULL,
                        description TEXT NOT NULL,
                        publisher TEXT NOT NULL,
                        language TEXT NOT NULL,
                        isExplicit INTEGER NOT NULL,
                        mediaType TEXT NOT NULL,
                        consumptionOrder TEXT NOT NULL,
                        trailerUri TEXT NULL,
                        hasMusicAndTalk INTEGER NOT NULL,
                        isAudiobook INTEGER NOT NULL,
                        keywordsJson TEXT NOT NULL,
                        smallCoverUri TEXT,
                        mediumCoverUri TEXT,
                        largeCoverUri TEXT,
                        lastUpdated INTEGER NOT NULL
                    )
                """.trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS index_shows_showId ON shows (showId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_shows_lastUpdated ON shows (lastUpdated)")

                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS episodes (
                        episodeId TEXT NOT NULL PRIMARY KEY,
                        uri TEXT NOT NULL,
                        name TEXT NOT NULL,
                        duration INTEGER NOT NULL,
                        description TEXT NOT NULL,
                        number INTEGER NOT NULL,
                        publishTime INTEGER NOT NULL,
                        language TEXT NOT NULL,
                        isExplicit INTEGER NOT NULL,
                        showName TEXT NOT NULL,
                        allowBackgroundPlayback INTEGER NOT NULL,
                        externalUrl TEXT NOT NULL,
                        episodeType TEXT NOT NULL,
                        hasMusicAndTalk INTEGER NOT NULL,
                        isAudiobookChapter INTEGER NOT NULL,
                        keywordsJson TEXT NOT NULL,
                        smallCoverUri TEXT,
                        mediumCoverUri TEXT,
                        largeCoverUri TEXT,
                        isLibraryItem INTEGER NOT NULL,
                        lastAccessed INTEGER NOT NULL,
                        lastUpdated INTEGER NOT NULL
                    )
                """.trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS index_episodes_episodeId ON episodes (episodeId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_episodes_lastAccessed ON episodes (lastAccessed)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_episodes_lastUpdated ON episodes (lastUpdated)")

                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS show_episodes (
                        showId TEXT NOT NULL,
                        episodeId TEXT NOT NULL,
                        position INTEGER NOT NULL,
                        PRIMARY KEY(showId, episodeId)
                    )
                """.trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS index_show_episodes_showId ON show_episodes (showId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_show_episodes_episodeId ON show_episodes (episodeId)")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "outify_database"
                )
                    .addMigrations(MIGRATION_15_16, MIGRATION_16_17)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}