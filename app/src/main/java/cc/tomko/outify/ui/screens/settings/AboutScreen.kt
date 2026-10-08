package cc.tomko.outify.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cc.tomko.outify.BuildConfig
import cc.tomko.outify.MyIcons
import cc.tomko.outify.R
import cc.tomko.outify.ScreenBottomPadding
import cc.tomko.outify.ui.viewmodel.AboutViewModel
import cc.tomko.outify.ui.viewmodel.UpdateState

private const val DEVELOPER = "TomKo"
private const val GITHUB_URL = "https://github.com/iTomKo/Outify"
private const val GITHUB_DISPLAY = "github.com/iTomKo/Outify"

private sealed interface AboutItem {
    data class Info(val label: String, val value: String) : AboutItem
    data class Body(val text: String) : AboutItem
    data class Developer(val label: String, val name: String) : AboutItem
    data class Link(
        val icon: ImageVector,
        val label: String,
        val sub: String,
        val onClick: () -> Unit,
    ) : AboutItem
    data class Update(val state: UpdateState) : AboutItem
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenUrl: (String) -> Unit = {},
    viewModel: AboutViewModel = viewModel(),
) {
    val updateState by viewModel.updateState.collectAsState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.common_about)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back)
                        )
                    }
                },
                scrollBehavior = scrollBehavior
            )
        },
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding() + 8.dp,
                start = 16.dp,
                end = 16.dp,
                bottom = 12.dp
            ),
            verticalArrangement = Arrangement.spacedBy(28.dp)
        ) {
            item { Hero() }

            item {
                AboutSection(
                    title = stringResource(R.string.about_section_app),
                    icon = Icons.Rounded.Info,
                    items = listOf(
                        AboutItem.Info(stringResource(R.string.about_version), BuildConfig.VERSION_NAME),
                        AboutItem.Info(stringResource(R.string.about_build), "#${BuildConfig.VERSION_CODE}"),
                    )
                )
            }

            item {
                AboutSection(
                    title = stringResource(R.string.about_updates),
                    icon = Icons.Rounded.SystemUpdate,
                    items = if (updateState is UpdateState.Idle) emptyList()
                    else listOf(AboutItem.Update(updateState)),
                    footer = {
                        UpdateButton(
                            state = updateState,
                            onCheck = viewModel::checkForUpdates,
                            onDownload = onOpenUrl,
                        )
                    }
                )
            }

            item {
                AboutSection(
                    title = stringResource(R.string.about_have_idea),
                    icon = Icons.Rounded.Lightbulb,
                    items = listOf(
                        AboutItem.Body(stringResource(R.string.about_report)),
                        AboutItem.Developer(stringResource(R.string.about_made_by), DEVELOPER),
                        AboutItem.Link(
                            icon = Icons.Rounded.Code,
                            label = stringResource(R.string.about_source_code),
                            sub = GITHUB_DISPLAY,
                            onClick = { onOpenUrl(GITHUB_URL) }
                        ),
                    )
                )
            }

            item {
                Spacer(Modifier.height(ScreenBottomPadding))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Hero(modifier: Modifier = Modifier) {
    val shape = remember { MaterialShapes.Cookie12Sided }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(112.dp)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.tertiary,
                        )
                    ),
                    shape = shape.toShape()
                )
        ) {
            Icon(
                imageVector = MyIcons.Logo,
                contentDescription = "Outify",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(52.dp)
            )
        }

        Text(
            text = "Outify",
            style = MaterialTheme.typography.displaySmallEmphasized,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun UpdateButton(
    state: UpdateState,
    onCheck: () -> Unit,
    onDownload: (String) -> Unit,
) {
    val available = state as? UpdateState.Available
    val checking = state is UpdateState.Checking

    Button(
        onClick = { if (available != null) onDownload(available.url) else onCheck() },
        enabled = !checking,
        shapes = ButtonDefaults.shapes(),
        contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .height(ButtonDefaults.MediumContainerHeight)
    ) {
        Icon(
            imageVector = if (available != null) Icons.Rounded.Download else Icons.Rounded.Refresh,
            contentDescription = null
        )
        Spacer(Modifier.width(ButtonDefaults.iconSpacingFor(ButtonDefaults.MediumContainerHeight)))
        Text(
            stringResource(
                if (available != null) R.string.about_update_download
                else R.string.about_check_updates
            )
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AboutSection(
    title: String,
    icon: ImageVector,
    items: List<AboutItem>,
    modifier: Modifier = Modifier,
    footer: (@Composable () -> Unit)? = null,
) {
    val badgeShape = remember { MaterialShapes.Cookie9Sided }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(44.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, badgeShape.toShape())
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(24.dp)
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLargeEmphasized,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items.forEachIndexed { index, item ->
                val onClick = (item as? AboutItem.Link)?.onClick

                Surface(
                    shape = segmentedShape(index, items.size),
                    color = item.containerColor(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Surface clips to its shape, so the ripple stays inside the rounded corners
                    Box(
                        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
                    ) {
                        when (item) {
                            is AboutItem.Info -> InfoRow(item.label, item.value)
                            is AboutItem.Body -> BodyRow(item.text)
                            is AboutItem.Developer -> DeveloperRow(item.label, item.name)
                            is AboutItem.Link -> LinkRow(item.icon, item.label, item.sub)
                            is AboutItem.Update -> UpdateRow(item.state)
                        }
                    }
                }
            }
        }

        footer?.invoke()
    }
}

@Composable
private fun AboutItem.containerColor(): Color = when (this) {
    is AboutItem.Update -> when (state) {
        is UpdateState.Available -> MaterialTheme.colorScheme.tertiaryContainer
        is UpdateState.Error -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    else -> MaterialTheme.colorScheme.surfaceContainerHigh
}

private fun segmentedShape(index: Int, count: Int): RoundedCornerShape {
    val outer = 28.dp
    val inner = 6.dp
    val top = if (index == 0) outer else inner
    val bottom = if (index == count - 1) outer else inner
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun BodyRow(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp)
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DeveloperRow(label: String, name: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(44.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, MaterialShapes.Cookie6Sided.toShape())
        ) {
            Text(
                text = name.first().uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Icon(
                    imageVector = Icons.Rounded.Favorite,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun LinkRow(icon: ImageVector, label: String, sub: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = sub,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
            contentDescription = stringResource(R.string.common_open),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun UpdateRow(state: UpdateState) {
    val contentColor = when (state) {
        is UpdateState.Available -> MaterialTheme.colorScheme.onTertiaryContainer
        is UpdateState.Error -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurface
    }

    val title: String
    val supporting: String?
    val icon: ImageVector

    when (state) {
        is UpdateState.Available -> {
            title = stringResource(R.string.about_update_available, state.version)
            supporting = stringResource(R.string.about_update_available_desc)
            icon = Icons.Rounded.SystemUpdate
        }
        is UpdateState.Error -> {
            title = stringResource(R.string.about_update_error)
            supporting = state.message
            icon = Icons.Rounded.Error
        }
        is UpdateState.UpToDate -> {
            title = stringResource(R.string.about_up_to_date)
            supporting = null
            icon = Icons.Rounded.CheckCircle
        }
        else -> { // Checking (Idle shows no row)
            title = stringResource(R.string.about_checking_updates)
            supporting = null
            icon = Icons.Rounded.Refresh
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Box(modifier = Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            if (state is UpdateState.Checking) {
                LoadingIndicator(Modifier.size(32.dp))
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = contentColor
            )
            if (supporting != null) {
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.8f)
                )
            }
        }
    }
}