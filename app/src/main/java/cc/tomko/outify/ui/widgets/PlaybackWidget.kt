package cc.tomko.outify.ui.widgets

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtMost
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.components.Scaffold
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import cc.tomko.outify.ALBUM_COVER_URL
import cc.tomko.outify.MainActivity
import cc.tomko.outify.core.Spirc.SpircWrapper
import cc.tomko.outify.core.model.CoverSize
import cc.tomko.outify.core.model.OutifyUri
import cc.tomko.outify.core.model.PlayableAudio
import cc.tomko.outify.core.model.getCover
import cc.tomko.outify.playback.PlaybackStateHolder
import cc.tomko.outify.ui.components.GlanceSmartImage
import cc.tomko.outify.ui.extractThemeColor
import cc.tomko.outify.widgetMediaPreference
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json

@EntryPoint
@InstallIn(SingletonComponent::class)
interface PlaybackWidgetEntryPoint {
    fun spircWrapper(): SpircWrapper
    fun playbackStateHolder(): PlaybackStateHolder
}

class PlaybackWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            PlaybackWidgetEntryPoint::class.java
        )

        val spircWrapper = entryPoint.spircWrapper()
        val playbackStateHolder = entryPoint.playbackStateHolder()

        provideContent {
            val prefs = currentState<Preferences>()
            val mediaUris =
                Json.decodeFromString<List<String>>(prefs[widgetMediaPreference(id)] ?: "[]")

            val mediaItems = mediaUris.map { mediaUri ->
                val snapshot = context.imageLoader.diskCache
                    ?.openSnapshot(mediaUri)
                val bitmap = snapshot?.data?.toFile()?.let {
                    BitmapFactory.decodeFile(it.absolutePath)
                }

                MediaItem(mediaUri, bitmap)
            }

            val playbackState by playbackStateHolder.state.collectAsState()
            val currentAudio = playbackState.currentAudio
            var currentTrackBitmap by remember { mutableStateOf<Bitmap?>(null) }

            LaunchedEffect(currentAudio) {
                val request = ImageRequest.Builder(context)
                    .data(currentAudio?.getCover(CoverSize.SMALL)?.uri?.let { ALBUM_COVER_URL + it })
                    .allowHardware(false)
                    .size(300)
                    .build()

                currentTrackBitmap = context.imageLoader.execute(request).image?.toBitmap()
            }

            GlanceTheme {
                Scaffold(
                    modifier = GlanceModifier
                        .appWidgetBackground()
                        .background(GlanceTheme.colors.background)
                        .cornerRadius(android.R.dimen.system_app_widget_background_radius)
                ) {
                    Content(
                        itemsList = mediaItems,
                        currentAudio = currentAudio,
                        currentAudioBitmap = currentTrackBitmap,
                        isPlaying = playbackState.isPlaying,
                        spirc = spircWrapper
                    )
                }
            }
        }
    }

    data class MediaItem(val uri: String, val bitmap: Bitmap?)

    @Composable
    private fun Content(
        itemsList: List<MediaItem>,
        currentAudio: PlayableAudio?,
        currentAudioBitmap: Bitmap?,
        isPlaying: Boolean,
        spirc: SpircWrapper
    ) {
        val size = LocalSize.current
        if (itemsList.isEmpty()) {
            Text("No items to display", modifier = GlanceModifier.fillMaxSize())
            return
        }

        val gap = 8.dp
        val outerPadding = 8.dp
        val maxItemsPerRow = 5
        val scaffoldHorizontalPadding = 32.dp
        val availableWidth = size.width - scaffoldHorizontalPadding - outerPadding * 2

        val possibleItemsPerRow = (availableWidth / 80.dp).toInt()
        val itemsPerRow = possibleItemsPerRow
            .coerceIn(1, maxItemsPerRow)
            .coerceAtMost(itemsList.size)

        val itemSize =
            ((availableWidth - gap * (itemsPerRow - 1)) / itemsPerRow).coerceAtMost(150.dp)
        val gridRows = itemsList.chunked(itemsPerRow)

        Column(modifier = GlanceModifier.padding(vertical = outerPadding)) {
            LazyColumn(
                modifier = GlanceModifier
                    .padding(outerPadding)
                    .cornerRadius(android.R.dimen.system_app_widget_inner_radius)
                    .background(GlanceTheme.colors.secondaryContainer),
                horizontalAlignment = Alignment.Horizontal.CenterHorizontally
            ) {
                gridRows.forEachIndexed { rowIndex, rowItems ->
                    item {
                        Row(
                            modifier = GlanceModifier
                                .fillMaxWidth()
                                .let { if (rowIndex > 0) it.padding(top = gap) else it }
                        ) {
                            rowItems.forEachIndexed { itemIndex, mediaItem ->
                                if (itemIndex > 0) Spacer(GlanceModifier.width(gap))
                                MediaEntry(item = mediaItem, size = itemSize, spirc)
                            }
                        }
                    }
                }
            }

            Spacer(GlanceModifier.height(outerPadding))


            if (currentAudio != null) {
                val accentColor = currentAudioBitmap?.extractThemeColor()
                val context = LocalContext.current

                Row(
                    modifier = GlanceModifier
                        .let { m ->
                            if (accentColor != null) m.background(accentColor)
                            else m.background(GlanceTheme.colors.tertiaryContainer)
                        }
                        .cornerRadius(android.R.dimen.system_app_widget_inner_radius)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .fillMaxSize()
                        .clickable(
                            actionStartActivity(
                                Intent(context, MainActivity::class.java)
                            )
                        ),
                    verticalAlignment = Alignment.Vertical.CenterVertically
                ) {
                    // Track thumbnail
                    GlanceSmartImage(
                        bitmap = currentAudioBitmap,
                        modifier = GlanceModifier
                            .cornerRadius(8.dp),
                    )

                    Spacer(GlanceModifier.width(8.dp))

                    Column(modifier = GlanceModifier.defaultWeight()) {
                        Text(
                            text = currentAudio.name,
                            maxLines = 1,
                            style = TextStyle(
                                color = GlanceTheme.colors.onTertiaryContainer,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        )

                        val subtitle = currentAudio.artists?.joinToString { it.name }
                            ?: currentAudio.showName
                            ?: "Unknown source"

                        Text(
                            text = subtitle,
                            maxLines = 1,
                            style = TextStyle(
                                color = GlanceTheme.colors.onTertiaryContainer,
                                fontSize = 11.sp,
                            )
                        )
                    }

                    // Playback controls
                    Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
                        Image(
                            provider = ImageProvider(androidx.media3.session.R.drawable.media3_icon_previous),
                            contentDescription = "Previous",
                            colorFilter = ColorFilter.tint(GlanceTheme.colors.onTertiaryContainer),
                            modifier = GlanceModifier.size(28.dp)
                                .clickable { spirc.playerPrevious() }
                        )
                        Spacer(GlanceModifier.width(8.dp))
                        val playPauseIcon =
                            if (isPlaying) androidx.media3.session.R.drawable.media3_icon_pause else androidx.media3.session.R.drawable.media3_icon_play

                        Image(
                            provider = ImageProvider(playPauseIcon),
                            contentDescription = "Play / Pause",
                            colorFilter = ColorFilter.tint(GlanceTheme.colors.onTertiaryContainer),
                            modifier = GlanceModifier.size(32.dp)
                                .clickable { spirc.playerPlayPause() }
                        )
                        Spacer(GlanceModifier.width(8.dp))
                        Image(
                            provider = ImageProvider(androidx.media3.session.R.drawable.media3_icon_next),
                            contentDescription = "Next",
                            colorFilter = ColorFilter.tint(GlanceTheme.colors.onTertiaryContainer),
                            modifier = GlanceModifier.size(28.dp).clickable { spirc.playerNext() }
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun MediaEntry(item: MediaItem, size: Dp, spirc: SpircWrapper) {
        GlanceSmartImage(
            item.bitmap,
            modifier = GlanceModifier
                .cornerRadius(android.R.dimen.system_app_widget_inner_radius)
                .clickable { spirc.load(OutifyUri.fromUriString(item.uri)) },
            size = size
        )
    }
}