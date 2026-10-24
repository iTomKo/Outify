package cc.tomko.outify

import android.app.Application
import android.system.Os
import android.util.Log
import android.widget.Toast
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.media3.common.util.UnstableApi
import cc.tomko.outify.core.spirc.SpircController
import cc.tomko.outify.data.database.AppDatabase
import cc.tomko.outify.data.repository.SettingsRepository
import cc.tomko.outify.ui.viewmodel.detail.DetailViewModelStore
import cc.tomko.outify.ui.viewmodel.detail.setDetailViewModelStore
import cc.tomko.outify.utils.ExceptionCollector
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

const val ALBUM_COVER_URL: String = "https://i.scdn.co/image/"

val ScreenBottomPadding = 160.dp;

fun fabBottomPadding(isMediaPlaying: Boolean) =
    if (isMediaPlaying) 160.dp else 60.dp

fun widgetMediaPreference(id: GlanceId) =
    stringPreferencesKey("widget_media_$id")

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ImageLoaderEntryPoint {
    fun imageLoader(): ImageLoader
}

@HiltAndroidApp
class OutifyApplication : Application(), SingletonImageLoader.Factory {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }

    @Inject
    lateinit var spircController: SpircController

    @Inject
    lateinit var detailViewModelStore: DetailViewModelStore

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var exceptionCollector: ExceptionCollector

    // context.imageLoader must resolve to the Hilt loader so there is one memory and disk cache.
    // An entry point works even if Coil asks before field injection has run.
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        EntryPointAccessors.fromApplication(this, ImageLoaderEntryPoint::class.java).imageLoader()

    @UnstableApi
    override fun onCreate() {
        super.onCreate()

        // Android <= 12: prevent Rust temp_dir() from using /data/local/tmp.
        Os.setenv("TMPDIR", cacheDir.absolutePath, true)
        Log.i("OutifyTMP", "TMPDIR=${System.getenv("TMPDIR")}")
        exceptionCollector.install()

        setDetailViewModelStore(detailViewModelStore)

        val libraryLoaded = try {
            System.loadLibrary("librespot_ffi")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.e("OutifyApplication", "Failed to load librespot_ffi", e)
            false
        }

        if (!libraryLoaded) {
            Toast.makeText(this, getString(R.string.toast_librespot_failed), Toast.LENGTH_LONG).show()
        }

        appScope.launch {
            val (spotifyId, spotifySecret) = settingsRepository.resolveClientCredentials()

            if (spotifySecret.isBlank() || spotifyId.isBlank()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@OutifyApplication,
                        getString(R.string.toast_no_creds),
                        Toast.LENGTH_LONG
                    ).show()
                }

                return@launch
            }

            LibrespotFfi.libInit(applicationContext, spotifyId, spotifySecret)

            spircController.start()
        }
    }
}
