package org.moire.ultrasonic.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioManager.OnAudioFocusChangeListener
import androidx.media.AudioAttributesCompat
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat
import org.koin.java.KoinJavaComponent.inject
import org.moire.ultrasonic.domain.PlayerState
import org.moire.ultrasonic.util.Settings
import timber.log.Timber

class AudioFocusHandler(private val context: Context) {
    // TODO: This is a circular reference, try to remove it
    // This should be doable by using the native MediaController framework
    private val mediaPlayerControllerLazy =
        inject<MediaPlayerController>(MediaPlayerController::class.java)

    private val audioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private val lossPref: Int
        get() = Settings.tempLoss

    private val audioAttributesCompat by lazy {
        AudioAttributesCompat.Builder()
            .setUsage(AudioAttributesCompat.USAGE_MEDIA)
            .setContentType(AudioAttributesCompat.CONTENT_TYPE_MUSIC)
            .setLegacyStreamType(AudioManager.STREAM_MUSIC)
            .build()
    }

    fun requestAudioFocus(): Boolean {
        if (!hasFocus) {
            hasFocus = (AudioManagerCompat.requestAudioFocus(audioManager, focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        }
        return hasFocus
    }

    fun abandonAudioFocus() {
        if (hasFocus) {
            hasFocus = false
            AudioManagerCompat.abandonAudioFocusRequest(audioManager, focusRequest)
        }
    }

    private val listener = OnAudioFocusChangeListener { focusChange ->

        val mediaPlayerController = mediaPlayerControllerLazy.value

        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                Timber.v("Regained Audio Focus")
                if (pauseFocus) {
                    pauseFocus = false
                    mediaPlayerController.softStart()
                    Timber.v("started again")
                }
                if (lowerFocus) {
                    lowerFocus = false
                    mediaPlayerController.setVolume(1.0f)
                    Timber.v("restored volume")
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                if (!mediaPlayerController.isJukeboxEnabled) {
                    hasFocus = false
                    mediaPlayerController.pause()
                    Timber.v("Abandoned Audio Focus")
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (!mediaPlayerController.isJukeboxEnabled) {
                    Timber.v("Lost Audio Focus")

                    if (mediaPlayerController.playerState === PlayerState.STARTED) {
                        if (lossPref == 0 || lossPref == 1) {
                            pauseFocus = true
                            mediaPlayerController.softPause()
                        }
                    }
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
//                if (!mediaPlayerController.isJukeboxEnabled) {
//                    Timber.v("Lost Audio Focus (can duck)")
//
//                    if (mediaPlayerController.playerState === PlayerState.STARTED) {
//                        if (lossPref == 2 || lossPref == 1) {
//                            lowerFocus = true
//                            mediaPlayerController.setVolume(0.1f)
//                            Timber.v("lowered volume")
//                        } else if (lossPref == 0 || lossPref == 1) {
//                            pauseFocus = true
//                            mediaPlayerController.softPause()
//                            Timber.v("paused")
//                        }
//                    }
//                }
            }
        }
    }

    private val focusRequest: AudioFocusRequestCompat by lazy {
        AudioFocusRequestCompat.Builder(AudioManagerCompat.AUDIOFOCUS_GAIN)
            .setAudioAttributes(audioAttributesCompat)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(listener)
            .build()
    }

    companion object {
        private var hasFocus = false
        private var pauseFocus = false
        private var lowerFocus = false

        // TODO: This can be removed if we switch to androidx.media2.player
        fun getAudioAttributes(): AudioAttributes {
            return AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setLegacyStreamType(AudioManager.STREAM_MUSIC)
                .build()
        }
    }
}
