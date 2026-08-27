package com.focusflow.services

import kotlinx.coroutines.*
import javax.sound.sampled.*
import kotlin.math.*

/**
 * Chime styles available for the work-session-start and break-start events.
 * Persisted via Database.setSetting("pomodoro_work_chime") / ("pomodoro_break_chime").
 */
enum class ChimeStyle(val label: String) {
    DEFAULT("Default"),
    SOFT_BELL("Soft Bell"),
    DIGITAL("Digital"),
    DEEP("Deep"),
    MINIMAL("Minimal")
}

/**
 * SoundAversion — three-layer audio feedback engine
 *
 * Layer 1 — Richer synthesis:
 *   All tones use additive synthesis: a fundamental frequency plus harmonic
 *   overtones (2nd at ½ amplitude, 3rd at ¼ amplitude). This produces a
 *   warmer, more natural sound compared to a bare sine wave.
 *   A full ADSR envelope (attack / decay / sustain / release) shapes each tone
 *   so notes don't start or end with a click.
 *
 * Layer 2 — Expanded event library:
 *   - playBlockAlert()      harsh 880 Hz buzz cluster — aversive block feedback
 *   - playSessionStart()    ascending three-tone chime — positive reinforcement
 *   - playSessionEnd()      two-tone descend — session completion cue
 *   - playBreakReminder()   gentle two-tone nudge — break time notification
 *   - playMilestone()       four-tone ascending fanfare — 25/50/75% session mark
 *   - playNuclearAlert()    three-pulse low-high burst — nuclear mode activated
 *
 * Layer 3 — User control:
 *   [volumeMultiplier] (0.0–1.0) scales all playback volume uniformly.
 *   [isEnabled] gates all sound globally.
 *   [workChimeStyle] / [breakChimeStyle] pick the synthesized preset for each event.
 *   Every play call falls back to [Toolkit.beep()] if the audio mixer is busy
 *   or unavailable, so there is always some audible feedback.
 */
object SoundAversion {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile var isEnabled: Boolean = true

    /**
     * Global volume scalar (0.0 = mute, 1.0 = full).
     * Applied on top of each sound's own base volume.
     */
    @Volatile var volumeMultiplier: Float = 1.0f
        set(v) { field = v.coerceIn(0f, 1f) }

    /** Chime played when a work session starts or resumes. */
    @Volatile var workChimeStyle: ChimeStyle = ChimeStyle.DEFAULT

    /** Chime played when a Pomodoro break begins. */
    @Volatile var breakChimeStyle: ChimeStyle = ChimeStyle.DEFAULT

    /** Sound preset used when a blocked app is detected. */
    @Volatile var blockAlertStyle: ChimeStyle = ChimeStyle.DEFAULT

    // ── Layer 2: Sound event library ─────────────────────────────────────────

    /** Harsh 880 Hz buzz — immediate aversive feedback when an app is blocked. */
    fun playBlockAlert() {
        if (!isEnabled) return
        scope.launch {
            tryPlay {
                when (blockAlertStyle) {
                    ChimeStyle.DEFAULT -> {
                        playNote(880.0, 0.10, volume = 0.90f)
                        delay(30)
                        playNote(1100.0, 0.08, volume = 0.85f)
                        delay(30)
                        playNote(880.0, 0.14, volume = 0.90f)
                    }
                    ChimeStyle.SOFT_BELL -> {
                        playNote(330.0, 0.18, volume = 0.42f)
                        delay(40)
                        playNote(392.0, 0.18, volume = 0.45f)
                        delay(40)
                        playNote(494.0, 0.30, volume = 0.48f)
                    }
                    ChimeStyle.DIGITAL -> {
                        playNote(800.0, 0.06, volume = 0.50f)
                        delay(15)
                        playNote(1000.0, 0.08, volume = 0.55f)
                    }
                    ChimeStyle.DEEP -> playNote(220.0, 0.40, volume = 0.65f)
                    ChimeStyle.MINIMAL -> playNote(440.0, 0.10, volume = 0.28f)
                }
            }
        }
    }

    /**
     * Session start chime — routes to the selected [workChimeStyle].
     * DEFAULT: ascending three-tone warm chime.
     * SOFT_BELL: lower-pitched, slower, gentler.
     * DIGITAL: crisp short electronic blip.
     * DEEP: single warm resonant low note.
     * MINIMAL: barely-there single quiet note.
     */
    fun playSessionStart() {
        if (!isEnabled) return
        scope.launch {
            tryPlay {
                when (workChimeStyle) {
                    ChimeStyle.DEFAULT -> {
                        playNote(440.0, 0.12, volume = 0.55f)
                        delay(25)
                        playNote(550.0, 0.12, volume = 0.55f)
                        delay(25)
                        playNote(660.0, 0.22, volume = 0.60f)
                    }
                    ChimeStyle.SOFT_BELL -> {
                        playNote(330.0, 0.18, volume = 0.42f)
                        delay(40)
                        playNote(392.0, 0.18, volume = 0.45f)
                        delay(40)
                        playNote(494.0, 0.30, volume = 0.48f)
                    }
                    ChimeStyle.DIGITAL -> {
                        playNote(800.0, 0.06, volume = 0.50f)
                        delay(15)
                        playNote(1000.0, 0.08, volume = 0.55f)
                    }
                    ChimeStyle.DEEP -> {
                        playNote(220.0, 0.40, volume = 0.65f)
                    }
                    ChimeStyle.MINIMAL -> {
                        playNote(440.0, 0.10, volume = 0.28f)
                    }
                }
            }
        }
    }

    /** Two-tone descend — session completion cue. */
    fun playSessionEnd() {
        if (!isEnabled) return
        scope.launch {
            tryPlay {
                playNote(660.0, 0.15, volume = 0.55f)
                delay(35)
                playNote(440.0, 0.28, volume = 0.50f)
            }
        }
    }

    /**
     * Break start chime — routes to the selected [breakChimeStyle].
     * DEFAULT: gentle two-tone nudge (C5 → E5).
     * SOFT_BELL: single soft descending tone.
     * DIGITAL: single short blip.
     * DEEP: single low resonant tone.
     * MINIMAL: near-silent single note.
     */
    fun playBreakReminder() {
        if (!isEnabled) return
        scope.launch {
            tryPlay {
                when (breakChimeStyle) {
                    ChimeStyle.DEFAULT -> {
                        playNote(523.0, 0.12, volume = 0.40f)   // C5
                        delay(60)
                        playNote(659.0, 0.20, volume = 0.40f)   // E5
                    }
                    ChimeStyle.SOFT_BELL -> {
                        playNote(440.0, 0.22, volume = 0.35f)
                        delay(50)
                        playNote(330.0, 0.30, volume = 0.32f)
                    }
                    ChimeStyle.DIGITAL -> {
                        playNote(600.0, 0.07, volume = 0.42f)
                    }
                    ChimeStyle.DEEP -> {
                        playNote(196.0, 0.38, volume = 0.55f)
                    }
                    ChimeStyle.MINIMAL -> {
                        playNote(523.0, 0.09, volume = 0.22f)
                    }
                }
            }
        }
    }

    /**
     * Four-tone ascending fanfare — plays at 25%, 50%, and 75% session milestones.
     * Rewarding enough to feel like an achievement without breaking flow.
     */
    fun playMilestone() {
        if (!isEnabled) return
        scope.launch {
            tryPlay {
                playNote(440.0, 0.10, volume = 0.45f)
                delay(20)
                playNote(523.0, 0.10, volume = 0.50f)
                delay(20)
                playNote(659.0, 0.10, volume = 0.55f)
                delay(20)
                playNote(880.0, 0.22, volume = 0.60f)
            }
        }
    }

    /**
     * Three-pulse low-high burst — fired when Nuclear Mode activates.
     * Distinct enough that the user immediately understands something serious
     * has changed, without being painfully loud.
     */
    fun playNuclearAlert() {
        if (!isEnabled) return
        scope.launch {
            tryPlay {
                repeat(3) {
                    playNote(220.0, 0.07, volume = 0.75f)
                    delay(15)
                    playNote(880.0, 0.07, volume = 0.80f)
                    delay(50)
                }
            }
        }
    }

    // ── Layer 1: Richer synthesis ─────────────────────────────────────────────

    /**
     * Synthesise and play a single note.
     *
     * Additive synthesis — sums three harmonics:
     *   • Fundamental  at [frequencyHz]        — amplitude 1.0
     *   • 2nd harmonic at [frequencyHz] × 2    — amplitude 0.50
     *   • 3rd harmonic at [frequencyHz] × 3    — amplitude 0.25
     *
     * ADSR envelope:
     *   • Attack  — first 5% of samples, linear ramp 0→1
     *   • Decay   — next 10%, linear ramp 1→sustainLevel
     *   • Sustain — middle 70% at sustainLevel (0.80)
     *   • Release — final 15%, linear ramp sustainLevel→0
     */
    private fun playNote(
        frequencyHz: Double,
        durationSec: Double,
        volume: Float = 0.60f
    ) {
        val effective   = volume * volumeMultiplier
        val sampleRate  = 44_100f
        val numSamples  = (sampleRate * durationSec).toInt().coerceAtLeast(1)
        val data        = ByteArray(numSamples * 2)

        val sustainLevel = 0.80
        val attackEnd    = (numSamples * 0.05).toInt()
        val decayEnd     = (numSamples * 0.15).toInt()
        val releaseStart = (numSamples * 0.85).toInt()

        for (i in 0 until numSamples) {
            val t = i.toDouble() / sampleRate

            val env = when {
                i < attackEnd    -> i.toDouble() / attackEnd.coerceAtLeast(1)
                i < decayEnd     -> 1.0 - (1.0 - sustainLevel) * (i - attackEnd) /
                                        (decayEnd - attackEnd).coerceAtLeast(1)
                i < releaseStart -> sustainLevel
                else             -> sustainLevel * (numSamples - i) /
                                        (numSamples - releaseStart).coerceAtLeast(1)
            }

            val sample = sin(2.0 * PI * frequencyHz       * t) * 1.00 +
                         sin(2.0 * PI * frequencyHz * 2.0 * t) * 0.50 +
                         sin(2.0 * PI * frequencyHz * 3.0 * t) * 0.25

            val value = (Short.MAX_VALUE * effective * env * (sample / 1.75)).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()

            data[i * 2]     = (value.toInt() and 0xFF).toByte()
            data[i * 2 + 1] = (value.toInt() shr 8).toByte()
        }

        val format = AudioFormat(sampleRate, 16, 1, true, false)
        val info   = DataLine.Info(SourceDataLine::class.java, format)
        // Reinitialise a fresh SourceDataLine on every call so rapid consecutive block
        // events never share a line that is still being drained or was left open by a
        // previous call that threw before reaching close(). The full sequence —
        // getLine(), open(), start(), write(), drain() — is inside the try block so
        // the finally always executes line.close() regardless of where the failure occurs.
        val line   = AudioSystem.getLine(info) as SourceDataLine
        try {
            line.open(format)
            line.start()
            line.write(data, 0, data.size)
            line.drain()
        } finally {
            // runCatching prevents a secondary exception from close() (e.g. if open()
            // never succeeded) from swallowing the original one.
            runCatching { line.close() }
        }
    }

    // ── Layer 3: Graceful fallback ────────────────────────────────────────────

    /**
     * Wraps a sound-producing block with a two-level fallback:
     *   1. Try the full synthesised playback via [playNote].
     *   2. On any [LineUnavailableException] or other audio error, fall back to
     *      [java.awt.Toolkit.getDefaultToolkit().beep()] — always available,
     *      always produces some feedback.
     */
    private suspend fun tryPlay(block: suspend () -> Unit) {
        try {
            block()
        } catch (_: LineUnavailableException) {
            withContext(Dispatchers.Main) {
                runCatching { java.awt.Toolkit.getDefaultToolkit().beep() }
            }
        } catch (_: Exception) {
            runCatching { java.awt.Toolkit.getDefaultToolkit().beep() }
        }
    }
}
