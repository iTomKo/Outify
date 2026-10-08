package cc.tomko.outify

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource

object MyIcons {
    val BrokenHeart: ImageVector
        @Composable
        get() = ImageVector.vectorResource(id = R.drawable.heart_broken)

    val Shuffle: ImageVector
        @Composable
        get() = ImageVector.vectorResource(id = R.drawable.shuffle)

    val NoShuffle: ImageVector
        @Composable
        get() = ImageVector.vectorResource(id = R.drawable.no_shuffle)

    val Logo: ImageVector
        @Composable
        get() = ImageVector.vectorResource(id = R.drawable.ic_launcher_foreground)
}