package cc.tomko.outify.ui.components

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.background
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.size
import cc.tomko.outify.data.setting.LocalUiSettings
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.toBitmap

@Composable
fun SmartImage(
    url: String?,
    modifier: Modifier = Modifier,
    imageSize: Dp? = null,
    contentDescription: String? = null,
    shape: Shape = RoundedCornerShape(6.dp),
    alignment: Alignment = Alignment.Center,

    monochrome: Boolean? = null,
) {
    val monochromeImage = monochrome ?: LocalUiSettings.current.monochromeImages

    val context = LocalContext.current
    val density = LocalDensity.current

    val imageSizePx = imageSize?.let { with(density) { it.roundToPx() } }

    val imageLoader = context.imageLoader
    val imageRequest = rememberSmartImageRequest(url, imageSizePx)

    val colorFilter = remember(monochromeImage) {
        if (!monochromeImage) null
        else ColorFilter.colorMatrix(
            ColorMatrix().apply { setToSaturation(0f) }
        )
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = shape,
        modifier = if (imageSize != null) modifier.size(imageSize) else modifier
    ) {
        AsyncImage(
            model = imageRequest,
            imageLoader = imageLoader,
            contentDescription = contentDescription,
            modifier = Modifier
                .fillMaxSize()
                .clip(shape),
            contentScale = ContentScale.Crop,
            alignment = alignment,
            colorFilter = colorFilter
        )
    }
}

@Composable
fun GlanceSmartImage(
    bitmap: Bitmap?,
    modifier: GlanceModifier = GlanceModifier,
    size: Dp = 48.dp
) {
    Box(
        modifier = modifier
            .background(GlanceTheme.colors.background)
            .size(size)
    ) {
        bitmap?.let {
            Image(
                provider = ImageProvider(it),
                contentDescription = null,
                modifier = GlanceModifier
                    .fillMaxSize()
                    .size(size),
                contentScale = androidx.glance.layout.ContentScale.Crop
            )
        }
    }
}

@Composable
fun rememberSmartImageRequest(url: String?, size: Int?): ImageRequest {
    val context = LocalContext.current

    return remember(url, size) {
        ImageRequest.Builder(context)
            .data(url)
            .apply { if (size != null) size(size) }
            .crossfade(true)
            .allowHardware(true)
            .build()
    }
}

suspend fun loadBitmap(context: Context, url: String): Bitmap? {
    val imageLoader = ImageLoader(context)

    val request = ImageRequest.Builder(context)
        .data(url)
        .allowHardware(false)
        .build()

    val result = imageLoader.execute(request)
    return result.image?.toBitmap()
}