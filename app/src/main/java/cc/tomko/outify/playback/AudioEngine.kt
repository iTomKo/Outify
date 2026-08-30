package cc.tomko.outify.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import cc.tomko.outify.core.spirc.VolumeController.Companion.SPOTIFY_MAX_VOLUME
import cc.tomko.outify.playback.callbacks.PlayerEventCallback
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.max

private const val TAG = "AudioEngine"

enum class PcmFormat {
    S16,
}

/**
 * Plays the received PCM audio using modern AudioAttributes/AudioFormat API.
 */
@UnstableApi
class AudioEngine(
    val context: Context,
    eventCallback: PlayerEventCallback,
) {
    @Volatile
    private var audioTrack: AudioTrack? = null
    private var currentSampleRate = -1
    private var currentChannels = -1
    private var currentFormat: PcmFormat? = null

    private val pcmBuffer = ByteBuffer.allocateDirect(4 * 8192)

    private val sonic = SonicAudioProcessor()
    @Volatile
    private var playbackSpeed = 1.5f
    private var sonicSampleRate = -1
    private var sonicChannels = -1
    private var sonicCommittedSpeed = 1f

    private val writeLock = ReentrantLock()

    init {
        // Registers this class as the PCM callback.
        // Rust stores the GlobalRef and calls the onPcm method
        registerPcmCallback(this, pcmBuffer)

        // Registers callbacks to handle librespot events
        registerPlayerEventListener(eventCallback)
    }

    private fun ensureAudioTrack(sampleRate: Int, channels: Int, format: PcmFormat): Boolean {
        writeLock.withLock {
            val existing = audioTrack
            if (existing != null
                && sampleRate == currentSampleRate
                && channels == currentChannels
                && format == currentFormat
                && existing.state == AudioTrack.STATE_INITIALIZED
            ) {
                return true
            }

            // Otherwise recreate
            releaseAudioTrack()

            val channelMask = when (channels) {
                1 -> AudioFormat.CHANNEL_OUT_MONO
                2 -> AudioFormat.CHANNEL_OUT_STEREO
                else -> {
                    // fallback to stereo for unknown channel counts
                    Log.w(TAG, "Unsupported channel count $channels, falling back to stereo")
                    AudioFormat.CHANNEL_OUT_STEREO
                }
            }

            val encoding = when (format) {
                PcmFormat.S16 -> AudioFormat.ENCODING_PCM_16BIT
            }

            val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
            if (minBufferSize <= 0) {
                Log.e(TAG, "Invalid min buffer size: $minBufferSize")
                return false
            }

            val bytesPerSample = when (encoding) {
                AudioFormat.ENCODING_PCM_16BIT -> 2
                AudioFormat.ENCODING_PCM_8BIT -> 1
                AudioFormat.ENCODING_PCM_FLOAT -> 4
                else -> 2
            }
            val frameSize = bytesPerSample * max(1, channels)
            val bufferSize = max(minBufferSize, frameSize * 1024)

            try {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()

                val formatBuilder = AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(encoding)
                    .setChannelMask(channelMask)
                    .build()

                val newTrack = AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(formatBuilder)
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                if (newTrack.state != AudioTrack.STATE_INITIALIZED) {
                    Log.e(TAG, "Failed to initialize AudioTrack: state=${newTrack.state}")
                    newTrack.release()
                    return false
                }

                newTrack.play()

                audioTrack = newTrack
                currentSampleRate = sampleRate
                currentChannels = channels
                currentFormat = format

                Log.d(
                    TAG,
                    "AudioTrack created: sampleRate=$sampleRate, channels=$channels, encoding=$encoding, buffer=$bufferSize"
                )
                return true
            } catch (t: Throwable) {
                Log.e(TAG, "Exception while creating AudioTrack", t)
                return false
            }
        }
    }

    fun releaseAudioTrack() {
        writeLock.withLock {
            drainSonic()
            val t = audioTrack ?: return
            try {
                if (t.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    try {
                        t.stop()
                    } catch (ignored: IllegalStateException) {
                        // ignore - may happen if already stopped
                    }
                } else if (t.playState == AudioTrack.PLAYSTATE_PAUSED) {
                    try {
                        t.stop()
                    } catch (ignored: IllegalStateException) {
                    }
                }
            } catch (ignored: Exception) {
            } finally {
                try {
                    t.release()
                } catch (ignored: Exception) {
                }
                audioTrack = null
                currentSampleRate = -1
                currentChannels = -1
                currentFormat = null
            }
        }
    }

    /**
     * Releases native resources — frees JNI GlobalRefs for PCM callback
     * and player event listener.
     */
    fun releaseNative() {
        unregisterPcmCallback()
        unregisterPlayerEventListener()
    }

    fun pause() {
        writeLock.withLock {
            audioTrack?.let {
                try {
                    it.pause()
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "pause() failed", e)
                }
            }
        }
    }

    fun setVolume(volume: Float) {
        audioTrack?.setVolume(
            volume.coerceIn(0.0f, AudioTrack.getMaxVolume())
        )
    }

    fun setSpeed(speed: Float) {
        writeLock.withLock {
            playbackSpeed = speed.coerceAtLeast(0.1f)
        }
    }

    fun flush() {
        writeLock.withLock {
            drainSonic()
            audioTrack?.let {
                try {
                    it.flush()
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "flush() failed", e)
                }
            }
        }
    }

    /**
     * Marks this class as the one to receive onPcm data
     */
    private external fun registerPcmCallback(callbackPtr: AudioEngine?, buffer: ByteBuffer)

    /**
     * Called from rust trampoline when the PCMBuffer is filled with PCM.
     */
    fun onPcmReady(size: Int, sampleRate: Int, channels: Int) {
        writeLock.withLock {
            pcmBuffer.order(ByteOrder.nativeOrder())

            if (!ensureAudioTrack(sampleRate, channels, PcmFormat.S16)) {
                Log.w(TAG, "ensureAudioTrack failed - dropping frame")
                return
            }

            val cap = pcmBuffer.capacity()
            if (size > cap) {
                Log.w(TAG, "pcm size $size > buffer capacity $cap; dropping frame")
                return
            }

            pcmBuffer.position(0)
            pcmBuffer.limit(size)

            try {
                val track = audioTrack ?: run {
                    Log.w(TAG, "audioTrack is null in onPcmReady")
                    return
                }

                val speed = playbackSpeed
                if (speed == 1f) {
                    writeToTrack(pcmBuffer, size, track)
                } else {
                    prepareSonic(sampleRate, channels, speed)
                    sonic.queueInput(pcmBuffer)
                    drainSonicTo(track)
                }
            } catch (ise: IllegalStateException) {
                Log.e(TAG, "AudioTrack write failed", ise)
            } finally {
                pcmBuffer.position(0)
                pcmBuffer.limit(pcmBuffer.capacity())
            }
        }
    }

    private fun prepareSonic(sampleRate: Int, channels: Int, speed: Float) {
        val formatChanged = sampleRate != sonicSampleRate || channels != sonicChannels
        val speedChanged = speed != sonicCommittedSpeed
        if (!formatChanged && !speedChanged) return

        drainSonic()

        if (formatChanged) {
            try {
                sonic.reset()
                val inputFormat = AudioProcessor.AudioFormat(
                    sampleRate, channels, C.ENCODING_PCM_16BIT
                )
                sonic.configure(inputFormat)
                sonicSampleRate = sampleRate
                sonicChannels = channels
            } catch (e: AudioProcessor.UnhandledAudioFormatException) {
                Log.e(TAG, "Sonic configure failed", e)
                sonicSampleRate = -1
                sonicChannels = -1
                sonicCommittedSpeed = 1f
                return
            }
        }

        sonic.setSpeed(speed)
        sonicCommittedSpeed = speed
        sonic.flush(AudioProcessor.StreamMetadata.DEFAULT)
    }

    private fun drainSonicTo(track: AudioTrack) {
        var output = sonic.getOutput()
        while (output.hasRemaining()) {
            writeToTrack(output, output.remaining(), track)
            output = sonic.getOutput()
        }
    }

    private fun drainSonic() {
        if (sonicSampleRate < 0) return
        sonic.queueEndOfStream()
        val track = audioTrack
        var output = sonic.getOutput()
        while (track != null && output.hasRemaining()) {
            writeToTrack(output, output.remaining(), track)
            output = sonic.getOutput()
        }
        sonic.flush(AudioProcessor.StreamMetadata.DEFAULT)
        sonicCommittedSpeed = 1f
    }

    private fun writeToTrack(buffer: ByteBuffer, size: Int, track: AudioTrack) {
        val written = track.write(buffer, size, AudioTrack.WRITE_BLOCKING)
        if (written < 0) {
            Log.e(TAG, "AudioTrack.write returned error: $written")
        } else if (written < size) {
            Log.w(TAG, "AudioTrack wrote $written / $size bytes (partial write)")
        }
    }

    /**
     * Registers PlayerEvent listener to FFI.
     * FFI stores the GlobalRef of the callback
     */
    external fun registerPlayerEventListener(callback: PlayerEventCallback);

    /**
     * Unregisters the PCM callback, freeing its JNI GlobalRef
     */
    private external fun unregisterPcmCallback()

    /**
     * Unregisters the player event listener, freeing its JNI GlobalRef
     */
    private external fun unregisterPlayerEventListener()
}
