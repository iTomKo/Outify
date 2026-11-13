package cc.tomko.outify.playback

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class PlaybackIntentTest {
    @Test
    fun `audio focus gain cannot undo a user pause`() {
        val intent = PlaybackIntent()
        intent.pauseByUser()

        assertFalse(intent.resumeAfterAudioFocus())
        assertFalse(intent.canPlay)
        assertTrue(intent.state.value.userPaused)
    }

    @Test
    fun `user pause during a focus interruption cancels automatic resume`() {
        val intent = PlaybackIntent()
        intent.pauseForAudioFocus()
        intent.pauseByUser()

        assertFalse(intent.resumeAfterAudioFocus())
        assertFalse(intent.canPlay)
    }

    @Test
    fun `focus interruption may resume only once`() {
        val intent = PlaybackIntent()
        intent.pauseForAudioFocus()

        assertFalse(intent.canPlay)
        assertTrue(intent.resumeAfterAudioFocus())
        assertTrue(intent.canPlay)
        assertFalse(intent.resumeAfterAudioFocus())
    }

    @Test
    fun `unsolicited focus gain does not start playback`() {
        assertFalse(PlaybackIntent().resumeAfterAudioFocus())
    }

    @Test
    fun `explicit play clears the user pause`() {
        val intent = PlaybackIntent()
        intent.pauseByUser()
        intent.pauseForAudioFocus()
        intent.playByUser()

        assertTrue(intent.canPlay)
        assertFalse(intent.state.value.userPaused)
        assertFalse(intent.state.value.focusPaused)
    }

    @Test
    fun `a pause invalidates an asynchronous playback request even if already paused`() {
        val intent = PlaybackIntent()
        intent.pauseByUser()
        val requestGeneration = intent.state.value.generation
        intent.pauseByUser()

        assertTrue(intent.state.value.generation > requestGeneration)
        assertFalse(intent.canPlay)
    }

    @Test
    fun `play helper also respects explicit pause`() = runBlocking {
        val holder = PlaybackStateHolder()
        holder.playbackIntent.pauseByUser()
        holder.play()

        assertFalse(holder.state.value.isPlaying)
    }

    @Test
    fun `delayed native playing event cannot override pause`() = runBlocking {
        val holder = PlaybackStateHolder()
        holder.playbackIntent.pauseByUser()
        holder.setPlaying(true)

        assertFalse(holder.state.value.isPlaying)
    }

    @Test
    fun `resetting playback state does not forget the user pause`() = runBlocking {
        val holder = PlaybackStateHolder()
        holder.playbackIntent.pauseByUser()
        holder.reset()
        holder.setPlaying(true)

        assertFalse(holder.state.value.isPlaying)
        assertTrue(holder.playbackIntent.state.value.userPaused)
    }

    @Test
    fun `paused position stays frozen until explicit play`() = runBlocking {
        val holder = PlaybackStateHolder()
        holder.setActiveDevice(true)
        holder.seekTo(12.seconds)
        holder.playbackIntent.pauseByUser()
        holder.setPlaying(true)

        assertEquals(12.seconds, holder.estimatePosition())
        holder.playbackIntent.playByUser()
        holder.setPlaying(true)
        assertTrue(holder.state.value.isPlaying)
        holder.playbackIntent.pauseByUser()
        holder.setPlaying(false)
        assertEquals(holder.state.value.position.active, holder.estimatePosition())
    }
}
