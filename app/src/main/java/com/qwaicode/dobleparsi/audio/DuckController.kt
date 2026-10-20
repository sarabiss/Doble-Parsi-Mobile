/*
 * Doble Parsi V1.0.0 - live AI dubbing for Android
 * Engineered by QW-AI-Code
 *
 * Copyright (c) 2026 QW-AI-Code - https://github.com/QW-AI-Code
 * SPDX-License-Identifier: MIT
 */
package com.qwaicode.dobleparsi.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Requests transient audio focus so compatible media players can duck their audio.
 *
 * Audio focus is cooperative: Android cannot directly change another app's volume.
 * Some Android TV players/firmware may ignore focus changes.
 */
class DuckController(context: Context, private val releaseMs: Long = 420L) {

    companion object {
        private const val TAG = "DobleParsi-Duck"
    }

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var request: AudioFocusRequest? = null

    @Volatile
    private var held = false

    private val release = Runnable { abandon() }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                held = true
                Log.i(TAG, "Audio focus gained")
            }

            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Do not leave a stale 'held=true' flag: a later dub utterance must retry.
                held = false
                Log.w(TAG, "Audio focus lost ($change); next dub utterance will retry")
            }
        }
    }

    @Synchronized
    fun duck() {
        handler.removeCallbacks(release)
        if (held) return

        // Build once and reuse the request when focus needs to be reacquired.
        val focus = request ?: AudioFocusRequest.Builder(
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        )
            // This app outputs media audio; ASSISTANT usage can be treated differently
            // by device-specific audio policies, particularly on TV firmware.
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener(focusListener, handler)
            .build()
            .also { request = it }

        val result = audioManager.requestAudioFocus(focus)
        held = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        Log.i(TAG, "requestAudioFocus result=$result held=$held")
    }

    fun scheduleRelease() {
        handler.removeCallbacks(release)
        handler.postDelayed(release, releaseMs)
    }

    @Synchronized
    fun abandon() {
        handler.removeCallbacks(release)
        request?.let { focus ->
            runCatching { audioManager.abandonAudioFocusRequest(focus) }
        }
        request = null
        held = false
        Log.d(TAG, "Audio focus abandoned")
    }
}
