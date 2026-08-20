package cc.tomko.outify.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Build
import androidx.annotation.OptIn
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.util.UnstableApi
import androidx.palette.graphics.Palette
import cc.tomko.outify.ALBUM_COVER_URL
import cc.tomko.outify.core.model.CoverSize
import cc.tomko.outify.core.model.PlayableAudio
import cc.tomko.outify.core.model.Track
import cc.tomko.outify.core.model.getCover
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.google.android.material.color.utilities.DynamicScheme
import com.google.android.material.color.utilities.Hct
import com.google.android.material.color.utilities.SchemeTonalSpot
import com.google.android.material.color.utilities.Score

val DefaultThemeColor = Color(0xFF465D69)

@OptIn(UnstableApi::class)
@SuppressLint("RestrictedApi")
@Composable
fun OutifyTheme(
    audio: PlayableAudio?,
    themeMode: ThemeMode = ThemeMode.DYNAMIC_ALBUM,
    staticAccentColor: Color = DefaultThemeColor,
    darkTheme: Boolean = isSystemInDarkTheme(),
    pureBlack: Boolean = false,
    highContrastCompat: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current

    var albumColor by rememberSaveable(stateSaver = ColorSaver) {
        mutableStateOf(DefaultThemeColor)
    }

    val contrastLevel = if (highContrastCompat) 1.0 else 0.0

    LaunchedEffect(audio?.id, themeMode) {
        if (themeMode != ThemeMode.DYNAMIC_ALBUM) return@LaunchedEffect

        val coverUrl = audio?.getCover(CoverSize.SMALL)?.let {
            ALBUM_COVER_URL + it.uri
        } ?: return@LaunchedEffect

        val result = context.imageLoader.execute(
            ImageRequest.Builder(context)
                .data(coverUrl)
                .allowHardware(false)
                .build()
        )

        val extracted = result.image?.toBitmap()?.extractThemeColor()

        albumColor = extracted ?: DefaultThemeColor
    }

    val colorScheme =
        when (themeMode) {
            ThemeMode.STATIC -> {
                SchemeTonalSpot(
                    Hct.fromInt(staticAccentColor.toArgb()),
                    darkTheme,
                    contrastLevel
                ).toColorScheme()
            }

            ThemeMode.DYNAMIC_SYSTEM -> {
                val systemTheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (darkTheme) {
                        dynamicDarkColorScheme(context).pureBlack(pureBlack)
                    } else {
                        dynamicLightColorScheme(context)
                    }
                } else null

                if (systemTheme != null) {
                    if (highContrastCompat) {
                        systemTheme.copy(
                            secondaryContainer = systemTheme.surfaceContainerHigh,
                            onSecondaryContainer = systemTheme.secondary,
                        )
                    } else systemTheme
                } else {
                    SchemeTonalSpot(
                        Hct.fromInt(DefaultThemeColor.toArgb()),
                        darkTheme,
                        0.0
                    ).toColorScheme()
                }
            }

            ThemeMode.DYNAMIC_ALBUM -> {
                SchemeTonalSpot(
                    Hct.fromInt(albumColor.toArgb()),
                    darkTheme,
                    contrastLevel
                ).toColorScheme().pureBlack(pureBlack)
            }
        }

    val animatedScheme = animateColorScheme(
        target = colorScheme,
        animationSpec = tween(
            durationMillis = 600,
            easing = FastOutSlowInEasing
        )
    )
    MaterialTheme(
        colorScheme = animatedScheme,
        typography = MaterialTheme.typography,
        content = content
    )
}

@SuppressLint("RestrictedApi")
fun Bitmap.extractThemeColor(): Color {
    val colorsToPopulation = Palette.from(this)
        .maximumColorCount(8)
        .generate()
        .swatches
        .associate { it.rgb to it.population }
    val rankedColors = Score.score(colorsToPopulation)
    return Color(rankedColors.first())
}

@SuppressLint("RestrictedApi")
fun Bitmap.extractGradientColors(): List<Color> {
    val extractedColors = Palette.from(this)
        .maximumColorCount(16)
        .generate()
        .swatches
        .associate { it.rgb to it.population }

    val orderedColors = Score.score(extractedColors, 2, 0xff4285f4.toInt(), true)
        .sortedByDescending { Color(it).luminance() }

    return if (orderedColors.size >= 2)
        listOf(Color(orderedColors[0]), Color(orderedColors[1]))
    else
        listOf(Color(0xFF595959), Color(0xFF0D0D0D))
}

@SuppressLint("RestrictedApi")
fun DynamicScheme.toColorScheme() = ColorScheme(
    primary = Color(primary),
    onPrimary = Color(onPrimary),
    primaryContainer = Color(primaryContainer),
    onPrimaryContainer = Color(onPrimaryContainer),
    inversePrimary = Color(inversePrimary),
    secondary = Color(secondary),
    onSecondary = Color(onSecondary),
    secondaryContainer = Color(secondaryContainer),
    onSecondaryContainer = Color(onSecondaryContainer),
    tertiary = Color(tertiary),
    onTertiary = Color(onTertiary),
    tertiaryContainer = Color(tertiaryContainer),
    onTertiaryContainer = Color(onTertiaryContainer),
    background = Color(background),
    onBackground = Color(onBackground),
    surface = Color(surface),
    onSurface = Color(onSurface),
    surfaceVariant = Color(surfaceVariant),
    onSurfaceVariant = Color(onSurfaceVariant),
    surfaceTint = Color(primary),
    inverseSurface = Color(inverseSurface),
    inverseOnSurface = Color(inverseOnSurface),
    error = Color(error),
    onError = Color(onError),
    errorContainer = Color(errorContainer),
    onErrorContainer = Color(onErrorContainer),
    outline = Color(outline),
    outlineVariant = Color(outlineVariant),
    scrim = Color(scrim),
    surfaceBright = Color(surfaceBright),
    surfaceDim = Color(surfaceDim),
    surfaceContainer = Color(surfaceContainer),
    surfaceContainerHigh = Color(surfaceContainerHigh),
    surfaceContainerHighest = Color(surfaceContainerHighest),
    surfaceContainerLow = Color(surfaceContainerLow),
    surfaceContainerLowest = Color(surfaceContainerLowest),
    primaryFixed = Color(primaryFixed),
    primaryFixedDim = Color(primaryFixedDim),
    onPrimaryFixed = Color(onPrimaryFixed),
    onPrimaryFixedVariant = Color(onPrimaryFixedVariant),
    secondaryFixed = Color(secondaryFixed),
    secondaryFixedDim = Color(secondaryFixedDim),
    onSecondaryFixed = Color(onSecondaryFixed),
    onSecondaryFixedVariant = Color(onSecondaryFixedVariant),
    tertiaryFixed = Color(tertiaryFixed),
    tertiaryFixedDim = Color(tertiaryFixedDim),
    onTertiaryFixed = Color(onTertiaryFixed),
    onTertiaryFixedVariant = Color(onTertiaryFixedVariant),
)

fun ColorScheme.pureBlack(apply: Boolean) =
    if (apply) copy(
        surface = Color.Black,
        background = Color.Black
    ) else this

@Composable
fun animateColorScheme(target: ColorScheme, animationSpec: AnimationSpec<Color>): ColorScheme {
    val primary by animateColorAsState(target.primary, animationSpec)
    val onPrimary by animateColorAsState(target.onPrimary, animationSpec)
    val primaryContainer by animateColorAsState(target.primaryContainer, animationSpec)
    val onPrimaryContainer by animateColorAsState(target.onPrimaryContainer, animationSpec)

    val secondary by animateColorAsState(target.secondary, animationSpec)
    val onSecondary by animateColorAsState(target.onSecondary, animationSpec)
    val secondaryContainer by animateColorAsState(target.secondaryContainer, animationSpec)
    val onSecondaryContainer by animateColorAsState(target.onSecondaryContainer, animationSpec)

    val background by animateColorAsState(target.background, animationSpec)
    val onBackground by animateColorAsState(target.onBackground, animationSpec)

    val surface by animateColorAsState(target.surface, animationSpec)
    val onSurface by animateColorAsState(target.onSurface, animationSpec)

    val error by animateColorAsState(target.error, animationSpec)
    val onError by animateColorAsState(target.onError, animationSpec)

    val outline by animateColorAsState(target.outline, animationSpec)

    return target.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondary = secondary,
        onSecondary = onSecondary,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        background = background,
        onBackground = onBackground,
        surface = surface,
        onSurface = onSurface,
        error = error,
        onError = onError,
        outline = outline
    )
}

val ColorSaver = object : Saver<Color, Int> {
    override fun restore(value: Int): Color = Color(value)
    override fun SaverScope.save(value: Color): Int = value.toArgb()
}

enum class ThemeMode {
    DYNAMIC_SYSTEM,
    DYNAMIC_ALBUM,
    STATIC
}