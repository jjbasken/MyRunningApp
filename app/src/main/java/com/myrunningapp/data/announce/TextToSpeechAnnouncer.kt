package com.myrunningapp.data.announce

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.myrunningapp.data.prefs.PreferencesRepository
import com.myrunningapp.di.ApplicationScope
import com.myrunningapp.domain.announce.Announcement
import com.myrunningapp.domain.announce.AnnouncementText
import com.myrunningapp.domain.announce.Announcer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The real voice: Android's [TextToSpeech], wrapped so the rest of the app can
 * simply say what happened.
 *
 * Three things this has to get right, none of which belong in the tracking code:
 *
 *  - **Start-up latency.** The engine takes a moment to initialise, and a run can
 *    begin before it is ready. Lines spoken in that window are held and flushed on
 *    init rather than dropped.
 *  - **Other audio.** Runs happen with music or a podcast playing, so each
 *    utterance takes transient audio focus with `MAY_DUCK`: the other app dips
 *    instead of stopping, and focus is handed straight back.
 *  - **Muting.** The preference is honoured here, at the last moment, so a mute
 *    mid-run silences the very next announcement.
 *
 * Callbacks arrive on the engine's own thread, so the queue and focus state are
 * guarded by a lock.
 */
@Singleton
class TextToSpeechAnnouncer @Inject constructor(
    @ApplicationContext private val context: Context,
    preferencesRepository: PreferencesRepository,
    @ApplicationScope scope: CoroutineScope,
) : Announcer {

    private val lock = Any()

    private var engine: TextToSpeech? = null
    private var ready = false
    /** Lines that arrived before the engine finished starting up. */
    private val pending = mutableListOf<Utterance>()
    /** Utterance ids spoken but not yet finished, so focus is held exactly as long as needed. */
    private val speaking = mutableSetOf<String>()
    private var focusRequest: AudioFocusRequest? = null
    private var nextUtteranceId = 0L

    @Volatile
    private var voiceEnabled: Boolean = true

    private data class Utterance(val text: String, val flush: Boolean)

    init {
        scope.launch {
            preferencesRepository.preferences
                .map { it.voiceAnnouncementsEnabled }
                .collect { enabled ->
                    voiceEnabled = enabled
                    if (!enabled) stop()
                }
        }
    }

    override fun announce(announcement: Announcement) {
        if (!voiceEnabled) return
        val utterance = Utterance(
            text = AnnouncementText.of(announcement),
            flush = announcement.priority == Announcement.Priority.IMMEDIATE,
        )

        val engine = synchronized(lock) {
            ensureEngineLocked()
            if (!ready) {
                pending += utterance
                return
            }
            engine
        } ?: return

        speak(engine, utterance)
    }

    override fun stop() {
        val engine = synchronized(lock) {
            pending.clear()
            engine
        }
        engine?.stop()
        synchronized(lock) { speaking.clear() }
        abandonAudioFocus()
    }

    // --- engine --------------------------------------------------------------

    /**
     * Creates the engine on first use, so an app that never starts a run never
     * starts a speech engine. Once created it is kept for the life of the process:
     * a run outlives the tracking service — the finish line is still being spoken
     * as the service stops itself — and re-initialising costs a second the app
     * would have to speak through.
     */
    private fun ensureEngineLocked() {
        if (engine != null) return
        engine = TextToSpeech(context) { status -> onEngineInit(status) }.apply {
            setOnUtteranceProgressListener(progressListener)
        }
    }

    private fun onEngineInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "TextToSpeech unavailable (status $status); announcements are off")
            synchronized(lock) { pending.clear() }
            return
        }

        val (engine, queued) = synchronized(lock) {
            val e = engine ?: return
            e.language = Locale.getDefault().takeIf { supported(e, it) } ?: Locale.US
            e.setAudioAttributes(AUDIO_ATTRIBUTES)
            ready = true
            val queued = pending.toList()
            pending.clear()
            e to queued
        }
        // Only the last of a backlog is still true; earlier ones are stale by now.
        queued.lastOrNull()?.let { speak(engine, it) }
    }

    private fun supported(engine: TextToSpeech, locale: Locale): Boolean =
        engine.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE

    private fun speak(engine: TextToSpeech, utterance: Utterance) {
        val id = synchronized(lock) {
            val id = "announcement-${nextUtteranceId++}"
            if (utterance.flush) speaking.clear()
            speaking += id
            id
        }

        requestAudioFocus()
        val queueMode = if (utterance.flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val result = engine.speak(utterance.text, queueMode, null, id)
        if (result != TextToSpeech.SUCCESS) {
            Log.w(TAG, "TextToSpeech refused: ${utterance.text}")
            finished(id)
        }
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) {
            finished(utteranceId)
        }

        @Deprecated("Kept for the pre-API-21 signature the platform still calls")
        override fun onError(utteranceId: String?) {
            finished(utteranceId)
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            finished(utteranceId)
        }
    }

    /** Releases audio focus once nothing is left to say, so music comes back up. */
    private fun finished(utteranceId: String?) {
        val idle = synchronized(lock) {
            utteranceId?.let { speaking -= it }
            speaking.isEmpty()
        }
        if (idle) abandonAudioFocus()
    }

    // --- audio focus ---------------------------------------------------------

    /**
     * `TRANSIENT_MAY_DUCK`: whatever the runner is listening to dips for the two
     * seconds of the announcement instead of being paused outright.
     */
    private fun requestAudioFocus() {
        synchronized(lock) {
            if (focusRequest != null) return
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(AUDIO_ATTRIBUTES)
                .setWillPauseWhenDucked(false)
                .build()
            focusRequest = request
            audioManager()?.requestAudioFocus(request)
        }
    }

    private fun abandonAudioFocus() {
        val request = synchronized(lock) {
            val r = focusRequest
            focusRequest = null
            r
        } ?: return
        audioManager()?.abandonAudioFocusRequest(request)
    }

    private fun audioManager(): AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private companion object {
        const val TAG = "Announcer"

        /**
         * Navigation guidance rather than media: it is the category Android ducks
         * music for, and it survives Do Not Disturb the way a turn instruction does.
         */
        val AUDIO_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}
