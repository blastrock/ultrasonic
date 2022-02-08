/*
 * LocalMediaPlayer.kt
 * Copyright (C) 2009-2021 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.service

import android.content.Context
import android.content.Context.POWER_SERVICE
import android.content.Intent
import android.media.MediaPlayer
import android.media.MediaPlayer.OnCompletionListener
import android.media.audiofx.AudioEffect
import android.os.PowerManager
import android.os.PowerManager.PARTIAL_WAKE_LOCK
import android.os.PowerManager.WakeLock
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.*
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.moire.ultrasonic.audiofx.EqualizerController
import org.moire.ultrasonic.audiofx.VisualizerController
import org.moire.ultrasonic.data.ActiveServerProvider.Companion.isOffline
import org.moire.ultrasonic.domain.PlayerState
import org.moire.ultrasonic.util.Constants
import org.moire.ultrasonic.util.Settings
import org.moire.ultrasonic.util.Storage
import org.moire.ultrasonic.util.StreamProxy
import timber.log.Timber

/**
 * Represents a Media Player which uses the mobile's resources for playback
 */
@Suppress("TooManyFunctions")
class LocalMediaPlayer : KoinComponent {

    private val audioFocusHandler by inject<AudioFocusHandler>()
    private val context by inject<Context>()

    @JvmField
    var onSongCompleted: ((DownloadFile?) -> Unit?)? = null

    @JvmField
    var onPrepared: (() -> Any?)? = null

    @JvmField
    var onNextSongRequested: (() -> Unit)? = null

    @JvmField
    @Volatile
    var playerState = PlayerState.IDLE

    @JvmField
    var currentPlaying: DownloadFile? = null

    @JvmField
    var nextPlaying: DownloadFile? = null

    private var nextPlayerState = PlayerState.IDLE
    private var nextSetup = false
    private var mediaPlayer: MediaPlayer = MediaPlayer()
    private var nextMediaPlayer: MediaPlayer? = null
    private var cachedPosition = 0
    private var proxy: StreamProxy? = null

    private val pm = context.getSystemService(POWER_SERVICE) as PowerManager
    private val wakeLock: WakeLock = pm.newWakeLock(PARTIAL_WAKE_LOCK, this.javaClass.name)

    val secondaryProgress: MutableLiveData<Int> = MutableLiveData(0)

    private val mediaPlayerScope = CoroutineScope(Job() + Dispatchers.Main)
    private var positionCacheScope: CoroutineScope? = null
    private val bufferScope = CoroutineScope(Job() + Dispatchers.Main)
    private val nextPlayingScope = CoroutineScope(Job() + Dispatchers.Main)

    fun init() {
        mediaPlayer.setWakeMode(context, PARTIAL_WAKE_LOCK)
        mediaPlayer.setOnErrorListener { _, what, more ->
            handleError(
                Exception(
                    String.format(
                        Locale.getDefault(),
                        "MediaPlayer error: %d (%d)", what, more
                    )
                )
            )
            false
        }
        try {
            val i = Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            i.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, mediaPlayer.audioSessionId)
            i.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
            context.sendBroadcast(i)
        } catch (ignored: Throwable) {
            // Froyo or lower
        }

        // Create Equalizer and Visualizer in background as this can potentially take some time
        mediaPlayerScope.launch(Dispatchers.IO) {
            EqualizerController.create(context, mediaPlayer)
            VisualizerController.create(mediaPlayer)
        }

        wakeLock.setReferenceCounted(false)
        Timber.i("LocalMediaPlayer created")
    }

    fun release() {
        mediaPlayerScope.coroutineContext.cancelChildren()
        // Calling reset() will result in changing this player's state. If we allow
        // the onPlayerStateChanged callback, then the state change will cause this
        // to resurrect the media session which has just been destroyed.
        reset()
        try {
            val i = Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
            i.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, mediaPlayer.audioSessionId)
            i.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
            context.sendBroadcast(i)
            EqualizerController.release()
            VisualizerController.release()
            mediaPlayer.release()

            mediaPlayer = MediaPlayer()

            if (nextMediaPlayer != null) {
                nextMediaPlayer!!.release()
            }
            bufferScope.coroutineContext.cancelChildren()
            nextPlayingScope.coroutineContext.cancelChildren()

            wakeLock.release()
        } catch (exception: Throwable) {
            Timber.w(exception, "LocalMediaPlayer onDestroy exception: ")
        }
        Timber.i("LocalMediaPlayer destroyed")
    }

    @Synchronized
    fun setPlayerState(playerState: PlayerState, track: DownloadFile?) {
        Timber.i("%s -> %s (%s)", this.playerState.name, playerState.name, track)
        this.playerState = playerState
        if (playerState === PlayerState.STARTED) {
            audioFocusHandler.requestAudioFocus()
        }

        RxBus.playerStatePublisher.onNext(RxBus.StateWithTrack(playerState, track))

        if (playerState === PlayerState.STARTED && positionCacheScope == null) {
            positionCacheScope = CoroutineScope(Job() + Dispatchers.Main)
            positionCacheScope!!.launch {
                cachePositionLoop()
            }
        } else if (playerState !== PlayerState.STARTED && positionCacheScope != null) {
            positionCacheScope!!.cancel()
            positionCacheScope = null
        }
    }

    /*
    * Set the current playing file. It's called with null to reset the player.
    */
    @Synchronized
    fun setCurrentPlaying(currentPlaying: DownloadFile?) {
        // In some cases this function is called twice
        if (this.currentPlaying == currentPlaying) return
        this.currentPlaying = currentPlaying
        RxBus.playerStatePublisher.onNext(RxBus.StateWithTrack(playerState, currentPlaying))
    }

    /*
    * Set the next playing file. nextToPlay cannot be null
    */
    @Synchronized
    fun setNextPlaying(nextToPlay: DownloadFile) {
        nextPlaying = nextToPlay
        setNextPlayerState(PlayerState.IDLE)
        nextPlayingScope.launch {
            checkCompletionLoop(nextToPlay)
        }
    }

    /*
    * Clear the next playing file. setIdle controls whether the playerState is affected as well
    */
    @Synchronized
    fun clearNextPlaying(setIdle: Boolean) {
        nextSetup = false
        nextPlaying = null
        nextPlayingScope.coroutineContext.cancelChildren()

        if (setIdle) {
            setNextPlayerState(PlayerState.IDLE)
        }
    }

    @Synchronized
    fun setNextPlayerState(playerState: PlayerState) {
        Timber.i("Next: %s -> %s (%s)", nextPlayerState.name, playerState.name, nextPlaying)
        nextPlayerState = playerState
    }

    /*
    * Public method to play a given file.
    * Optionally specify a position to start at.
    */
    @Synchronized
    @JvmOverloads
    fun play(fileToPlay: DownloadFile?, position: Int = 0, autoStart: Boolean = true) {
        nextPlayingScope.coroutineContext.cancelChildren()
        setCurrentPlaying(fileToPlay)

        if (fileToPlay != null) {
            bufferAndPlay(fileToPlay, position, autoStart)
        }
    }

    @Synchronized
    fun playNext() {
        if (nextMediaPlayer == null || nextPlaying == null) return

        mediaPlayer = nextMediaPlayer!!

        setCurrentPlaying(nextPlaying)
        setPlayerState(PlayerState.STARTED, currentPlaying)

        attachHandlersToPlayer(mediaPlayer, nextPlaying!!, false)

        onNextSongRequested?.let { it() }

        // Proxy should not be being used here since the next player was already setup to play
        proxy?.stop()
        proxy = null
    }

    @Synchronized
    fun pause() {
        try {
            mediaPlayer.pause()
        } catch (x: Exception) {
            handleError(x)
        }
    }

    @Synchronized
    fun start() {
        try {
            mediaPlayer.start()
        } catch (x: Exception) {
            handleError(x)
        }
    }

    @Synchronized
    fun seekTo(position: Int) {
        try {
            mediaPlayer.seekTo(position)
            cachedPosition = position
        } catch (x: Exception) {
            handleError(x)
        }
    }

    @get:Synchronized
    val playerPosition: Int
        get() = try {
            when (playerState) {
                PlayerState.IDLE -> 0
                PlayerState.DOWNLOADING -> 0
                PlayerState.PREPARING -> 0
                else -> cachedPosition
            }
        } catch (x: Exception) {
            handleError(x)
            0
        }

    @get:Synchronized
    val playerDuration: Int
        get() {
            if (currentPlaying != null) {
                val duration = currentPlaying!!.song.duration
                if (duration != null) {
                    return duration * 1000
                }
            }
            if (playerState !== PlayerState.IDLE &&
                playerState !== PlayerState.DOWNLOADING &&
                playerState !== PlayerState.PREPARING
            ) {
                try {
                    return mediaPlayer.duration
                } catch (x: Exception) {
                    handleError(x)
                }
            }
            return 0
        }

    fun setVolume(volume: Float) {
        mediaPlayer.setVolume(volume, volume)
    }

    @Synchronized
    private fun bufferAndPlay(fileToPlay: DownloadFile, position: Int, autoStart: Boolean) {
        if (playerState !== PlayerState.PREPARED && !fileToPlay.isWorkDone) {
            reset()
            bufferScope.launch {
                bufferLoop(fileToPlay, position, autoStart)
            }
        } else {
            doPlay(fileToPlay, position, autoStart)
        }
    }

    @Synchronized
    private fun doPlay(downloadFile: DownloadFile, position: Int, start: Boolean) {
        setPlayerState(PlayerState.IDLE, downloadFile)

        // In many cases we will be resetting the mediaPlayer a second time here.
        // figure out if we can remove this call...
        resetMediaPlayer()

        try {
            downloadFile.setPlaying(false)

            val file = Storage.getFromPath(downloadFile.completeOrPartialFile)
            val partial = !downloadFile.isCompleteFileAvailable

            // TODO this won't work with SAF, we should use something else, e.g. a recent list
            // downloadFile.updateModificationDate()
            mediaPlayer.setOnCompletionListener(null)

            setAudioAttributes(mediaPlayer)

            var streamUrl: String? = null
            if (partial) {
                if (proxy == null) {
                    proxy = StreamProxy(object : Supplier<DownloadFile?>() {
                        override fun get(): DownloadFile {
                            return currentPlaying!!
                        }
                    })
                    proxy!!.start()
                }
                streamUrl = String.format(
                    Locale.getDefault(), "http://127.0.0.1:%d/%s",
                    proxy!!.port, URLEncoder.encode(file!!.path, Constants.UTF_8)
                )
                Timber.i("Data Source: %s", streamUrl)
            } else if (proxy != null) {
                proxy?.stop()
                proxy = null
            }

            Timber.i("Preparing media player")

            if (streamUrl != null) {
                Timber.v("LocalMediaPlayer doPlay dataSource: %s", streamUrl)
                mediaPlayer.setDataSource(streamUrl)
            } else {
                Timber.v("LocalMediaPlayer doPlay Path: %s", file!!.path)
                val descriptor = file.getDocumentFileDescriptor("r")!!
                mediaPlayer.setDataSource(descriptor.fileDescriptor)
                descriptor.close()
            }

            setPlayerState(PlayerState.PREPARING, downloadFile)

            mediaPlayer.setOnBufferingUpdateListener { mp, percent ->
                val song = downloadFile.song

                if (percent == 100) {
                    mp.setOnBufferingUpdateListener(null)
                }

                // The secondary progress is an indicator of how far the song is cached.
                if (song.transcodedContentType == null && Settings.maxBitRate == 0) {
                    val progress = (percent.toDouble() / 100.toDouble() * playerDuration).toInt()
                    secondaryProgress.postValue(progress)
                }
            }

            mediaPlayer.setOnPreparedListener {
                Timber.i("Media player prepared")
                setPlayerState(PlayerState.PREPARED, downloadFile)

                // Populate seek bar secondary progress if we have a complete file for consistency
                if (downloadFile.isWorkDone) {
                    secondaryProgress.postValue(playerDuration)
                }

                if (position != 0) {
                    Timber.i("Restarting player from position %d", position)
                    seekTo(position)
                }
                cachedPosition = position
                if (start) {
                    mediaPlayer.start()
                    setPlayerState(PlayerState.STARTED, downloadFile)
                } else {
                    setPlayerState(PlayerState.PAUSED, downloadFile)
                }

                onPrepared?.let { it() }
            }

            attachHandlersToPlayer(mediaPlayer, downloadFile, partial)
            mediaPlayer.prepareAsync()
        } catch (x: Exception) {
            handleError(x)
        }
    }

    private fun setAudioAttributes(player: MediaPlayer) {
        player.setAudioAttributes(AudioFocusHandler.getAudioAttributes())
    }

    @Suppress("ComplexCondition")
    @Synchronized
    private fun setupNext(downloadFile: DownloadFile) {
        try {
            val file = Storage.getFromPath(downloadFile.completeOrPartialFile)

            // Release the media player if it is not our active player
            if (nextMediaPlayer != null && nextMediaPlayer != mediaPlayer) {
                nextMediaPlayer!!.setOnCompletionListener(null)
                nextMediaPlayer!!.release()
                nextMediaPlayer = null
            }
            nextMediaPlayer = MediaPlayer()
            nextMediaPlayer!!.setWakeMode(context, PARTIAL_WAKE_LOCK)

            setAudioAttributes(nextMediaPlayer!!)

            // This has nothing to do with the MediaSession, it is used to associate
            // the equalizer or visualizer with the player
            try {
                nextMediaPlayer!!.audioSessionId = mediaPlayer.audioSessionId
            } catch (ignored: Throwable) {
            }

            Timber.v("LocalMediaPlayer setupNext Path: %s", file!!.path)
            val descriptor = file.getDocumentFileDescriptor("r")!!
            nextMediaPlayer!!.setDataSource(descriptor.fileDescriptor)
            descriptor.close()

            setNextPlayerState(PlayerState.PREPARING)
            nextMediaPlayer!!.setOnPreparedListener {
                try {
                    setNextPlayerState(PlayerState.PREPARED)
                    if (Settings.gaplessPlayback &&
                        (playerState === PlayerState.STARTED || playerState === PlayerState.PAUSED)
                    ) {
                        mediaPlayer.setNextMediaPlayer(nextMediaPlayer)
                        nextSetup = true
                    }
                } catch (x: Exception) {
                    handleErrorNext(x)
                }
            }
            nextMediaPlayer!!.setOnErrorListener { _, what, extra ->
                Timber.w("Error on playing next (%d, %d): %s", what, extra, downloadFile)
                true
            }
            nextMediaPlayer!!.prepareAsync()
        } catch (x: Exception) {
            handleErrorNext(x)
        }
    }

    private fun attachHandlersToPlayer(
        mediaPlayer: MediaPlayer,
        downloadFile: DownloadFile,
        isPartial: Boolean
    ) {
        mediaPlayer.setOnErrorListener { _, what, extra ->
            Timber.w("Error on playing file (%d, %d): %s", what, extra, downloadFile)
            val pos = cachedPosition
            reset()
            downloadFile.setPlaying(false)
            doPlay(downloadFile, pos, true)
            downloadFile.setPlaying(true)
            true
        }

        var duration = 0
        if (downloadFile.song.duration != null) {
            duration = downloadFile.song.duration!! * 1000
        }

        mediaPlayer.setOnCompletionListener(object : OnCompletionListener {
            override fun onCompletion(mediaPlayer: MediaPlayer) {
                // Acquire a temporary wakelock, since when we return from
                // this callback the MediaPlayer will release its wakelock
                // and allow the device to go to sleep.
                wakeLock.acquire(60000)
                val pos = cachedPosition
                Timber.i("Ending position %d of %d", pos, duration)

                if (!isPartial || downloadFile.isWorkDone && abs(duration - pos) < 1000) {
                    setPlayerState(PlayerState.COMPLETED, downloadFile)
                    if (Settings.gaplessPlayback &&
                        nextPlaying != null &&
                        nextPlayerState === PlayerState.PREPARED
                    ) {
                        if (nextSetup) {
                            nextSetup = false
                        }
                        playNext()
                    } else {
                        onSongCompleted?.let { it(currentPlaying) }
                    }
                    return
                }

                if (downloadFile.isWorkDone) {
                    // Complete was called early even though file is fully buffered
                    Timber.i("Requesting restart from %d of %d", pos, duration)
                    reset()
                    downloadFile.setPlaying(false)
                    doPlay(downloadFile, pos, true)
                    downloadFile.setPlaying(true)
                } else {
                    Timber.i("Requesting restart from %d of %d", pos, duration)
                    reset()
                    bufferScope.launch {
                        bufferLoop(downloadFile, pos, true)
                    }
                }
            }
        })
    }

    @Synchronized
    fun reset() {
        bufferScope.coroutineContext.cancelChildren()

        resetMediaPlayer()

        try {
            setPlayerState(PlayerState.IDLE, currentPlaying)
            mediaPlayer.setOnErrorListener(null)
            mediaPlayer.setOnCompletionListener(null)
        } catch (x: Exception) {
            handleError(x)
        }
    }

    @Synchronized
    fun resetMediaPlayer() {
        try {
            mediaPlayer.reset()
        } catch (x: Exception) {
            Timber.w(x, "MediaPlayer was released but LocalMediaPlayer was not destroyed")

            // Recreate MediaPlayer
            mediaPlayer = MediaPlayer()
        }
    }

    private suspend fun bufferLoop(downloadFile: DownloadFile, position: Int, autoStart: Boolean) {
        var bufferLength = Settings.bufferLength.toLong()
        if (bufferLength == 0L) {
            // Set to seconds in a day, basically infinity
            bufferLength = 86400L
        }

        // Calculate roughly how many bytes BUFFER_LENGTH_SECONDS corresponds to.
        val bitRate = downloadFile.getBitRate()
        val byteCount = max(100000, bitRate * 1024L / 8L * bufferLength)

        // Find out how large the file should grow before resuming playback.
        Timber.i("Buffering from position %d and bitrate %d", position, bitRate)
        val expectedFileSize = position * bitRate / 8 + byteCount

        setPlayerState(PlayerState.DOWNLOADING, downloadFile)
        while (!isBufferComplete(downloadFile, expectedFileSize) && !isOffline()) {
            delay(1000)
        }

        doPlay(downloadFile, position, autoStart)
    }

    private fun isBufferComplete(downloadFile: DownloadFile, expectedFileSize: Long): Boolean {
        val partialFile = downloadFile.partialFile
        val completeFileAvailable = downloadFile.isWorkDone
        val size = Storage.getFromPath(partialFile)?.length ?: 0

        Timber.i(
            "Buffering %s (%d/%d, %s)",
            partialFile, size, expectedFileSize, completeFileAvailable
        )

        return completeFileAvailable || size >= expectedFileSize
    }

    private suspend fun checkCompletionLoop(downloadFile: DownloadFile) {
        // Do an initial sleep so this prepare can't compete with main prepare
        delay(5000)
        while (!nextBufferComplete(downloadFile)) {
            delay(5000)
        }

        // Start the setup of the next media player
        setupNext(downloadFile)
    }

    private fun nextBufferComplete(downloadFile: DownloadFile): Boolean {
        val partialFile = downloadFile.partialFile
        val completeFileAvailable = downloadFile.isWorkDone
        val state = (playerState === PlayerState.STARTED || playerState === PlayerState.PAUSED)

        val length = Storage.getFromPath(partialFile)?.length ?: 0

        Timber.i("Buffering next %s (%d)", partialFile, length)

        return completeFileAvailable && state
    }

    private suspend fun cachePositionLoop() {
        // Stop checking position before the song reaches completion
        while (true) {
            try {
                if (playerState === PlayerState.STARTED) {
                    if (playerState === PlayerState.STARTED) {
                        cachedPosition = mediaPlayer.currentPosition
                    }
                    RxBus.playbackPositionPublisher.onNext(cachedPosition)
                }
                delay(100)
            } catch (e: Exception) {
                if (e !is CancellationException)
                    Timber.w(e, "Crashed getting current position")
                return
            }
        }
    }

    private fun handleError(x: Exception) {
        Timber.w(x, "Media player error")
        try {
            mediaPlayer.reset()
        } catch (ex: Exception) {
            Timber.w(ex, "Exception encountered when resetting media player")
        }
    }

    private fun handleErrorNext(x: Exception) {
        Timber.w(x, "Next Media player error")
        nextMediaPlayer!!.reset()
    }
}
