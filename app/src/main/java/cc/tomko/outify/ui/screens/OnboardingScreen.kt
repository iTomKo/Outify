package cc.tomko.outify.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Recommend
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.tomko.outify.ui.viewmodel.OnboardingStep
import cc.tomko.outify.ui.viewmodel.OnboardingViewModel
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val steps by viewModel.steps.collectAsStateWithLifecycle()
    val currentIndex by viewModel.currentIndex.collectAsStateWithLifecycle()
    val isPlaybackLoggedIn by viewModel.isPlaybackLoggedIn.collectAsStateWithLifecycle()
    val isAccountLoggedIn by viewModel.isAccountLoggedIn.collectAsStateWithLifecycle()

    val currentStep = steps.getOrNull(currentIndex) ?: OnboardingStep.PLAYBACK

    if (steps.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(48.dp),
                color = MaterialTheme.colorScheme.primary
            )
        }
        return
    }

    AnimatedContent(
        targetState = currentStep,
        transitionSpec = {
            (fadeIn(tween(320)) togetherWith fadeOut(tween(220)))
        },
        label = "onboardingStep",
        modifier = modifier.fillMaxSize(),
    ) { step ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
        ) {
            StepIndicator(
                currentIndex = currentIndex,
                total = steps.size
            )

            Spacer(Modifier.height(16.dp))

            when (step) {
                OnboardingStep.PLAYBACK -> PlaybackLoginContent(
                    isLoggedIn = isPlaybackLoggedIn,
                    onConnect = { viewModel.startPlaybackAuth(context) }
                )

                OnboardingStep.ACCOUNT -> AccountLoginContent(
                    isLoggedIn = isAccountLoggedIn,
                    onConnect = { viewModel.startAccountAuth(context) }
                )
            }
        }
    }
}

@Composable
private fun StepIndicator(
    currentIndex: Int,
    total: Int,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 0 until total) {
            val isActive = i == currentIndex
            val isDone = i < currentIndex
            val width by animateDpAsState(if (isActive) 28.dp else 8.dp, label = "stepWidth")

            Surface(
                modifier = Modifier
                    .width(width)
                    .height(8.dp),
                shape = CircleShape,
                color = when {
                    isDone -> MaterialTheme.colorScheme.primary
                    isActive -> MaterialTheme.colorScheme.primaryContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
            ) {}
            if (i < total - 1) {
                Spacer(Modifier.width(8.dp))
            }
        }
    }
}

@Composable
private fun HeroArtwork(
    icon: ImageVector,
    containerColor: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
) {
    Surface(
        modifier = Modifier.size(180.dp),
        shape = AbsoluteSmoothCornerShape(
            cornerRadiusBL = 32.dp,
            cornerRadiusBR = 32.dp,
            cornerRadiusTL = 32.dp,
            cornerRadiusTR = 32.dp,
        ),
        color = containerColor,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(84.dp)
            )
        }
    }
}

@Composable
private fun PlaybackLoginContent(
    isLoggedIn: Boolean,
    onConnect: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        HeroArtwork(
            icon = Icons.Default.GraphicEq,
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )

        Spacer(Modifier.height(40.dp))

        Text(
            text = "Playback login",
            style = MaterialTheme.typography.headlineLargeEmphasized,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground,
        )

        Spacer(Modifier.height(12.dp))

        Text(
            text = "This login is required to stream audio. Outify uses librespot with anonymous Spotify credentials to power playback - independent of your personal account.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(32.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = AbsoluteSmoothCornerShape(
                cornerRadiusBL = 24.dp,
                cornerRadiusBR = 24.dp,
                cornerRadiusTL = 24.dp,
                cornerRadiusTR = 24.dp,
            ),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                LoginFeature(
                    icon = Icons.Default.GraphicEq,
                    text = "Stream tracks from Outify"
                )
                LoginFeature(
                    icon = Icons.Default.LibraryMusic,
                    text = "View artists, albums and playlists"
                )
                LoginFeature(
                    icon = Icons.Default.AccountCircle,
                    text = "Keep your listening synced"
                )
            }
        }

        Spacer(Modifier.height(40.dp))

        AnimatedVisibility(visible = isLoggedIn) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Playback login connected",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Button(
            onClick = onConnect,
            enabled = !isLoggedIn,
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp),
            shape = AbsoluteSmoothCornerShape(
                cornerRadiusBL = 20.dp,
                cornerRadiusBR = 20.dp,
                cornerRadiusTL = 20.dp,
                cornerRadiusTR = 20.dp,
            ),
        ) {
            Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text(
                text = if (isLoggedIn) "Connected" else "Connect playback",
                style = MaterialTheme.typography.titleMedium
            )
        }

        Spacer(Modifier.height(48.dp))
    }
}

@Composable
private fun AccountLoginContent(
    isLoggedIn: Boolean,
    onConnect: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        HeroArtwork(
            icon = Icons.Default.AccountCircle,
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        )

        Spacer(Modifier.height(40.dp))

        Text(
            text = "Account login",
            style = MaterialTheme.typography.headlineLargeEmphasized,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground,
        )

        Spacer(Modifier.height(12.dp))

        Text(
            text = "Connect your Spotify account to unlock library, playlists, likes and recommendations. This is handled securely via OAuth.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(32.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = AbsoluteSmoothCornerShape(
                cornerRadiusBL = 24.dp,
                cornerRadiusBR = 24.dp,
                cornerRadiusTL = 24.dp,
                cornerRadiusTR = 24.dp,
            ),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                LoginFeature(
                    icon = Icons.Default.ThumbUp,
                    text = "Like and unlike tracks, albums and artists"
                )
                LoginFeature(
                    icon = Icons.Default.Recommend,
                    text = "Search Spotify"
                )
                LoginFeature(
                    icon = Icons.Default.LibraryMusic,
                    text = "Create and manage your playlists"
                )
            }
        }

        Spacer(Modifier.height(40.dp))

        AnimatedVisibility(visible = isLoggedIn) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Account connected",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Button(
            onClick = onConnect,
            enabled = !isLoggedIn,
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp),
            shape = AbsoluteSmoothCornerShape(
                cornerRadiusBL = 20.dp,
                cornerRadiusBR = 20.dp,
                cornerRadiusTL = 20.dp,
                cornerRadiusTR = 20.dp,
            ),
        ) {
            Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text(
                text = if (isLoggedIn) "Connected" else "Connect account",
                style = MaterialTheme.typography.titleMedium
            )
        }

        Spacer(Modifier.height(48.dp))
    }
}

@Composable
private fun LoginFeature(
    icon: ImageVector,
    text: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            modifier = Modifier.size(40.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
