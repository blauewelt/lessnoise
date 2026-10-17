package ch.lessnoise.app.position

import android.content.Context
import android.os.Handler

/**
 * The Online-Version (and, never reached, the Offline and Labor builds): no position signal for
 * «unterwegs» (0.6.9 is a Labor·Karte iteration, src/laborKarte/kotlin/.../position/). This stand-in
 * is all they contain of it: it reads nothing and adds no foreground-service type.
 */
object PositionWatch {
    const val AVAILABLE = false

    interface Handle {
        val distanceM: Double?
        val moving: Boolean
        fun holdMs(nowMs: Long): Long
        fun stop(): Long?
    }

    /** A change of the signal (never produced here). */
    class Transition(val atMs: Long, val moving: Boolean, val spanStartMs: Long, val distanceM: Double, val thresholdM: Double)

    fun foregroundType(context: Context): Int? = null

    fun start(context: Context, handler: Handler, onChange: (Transition) -> Unit): Handle? = null
}
