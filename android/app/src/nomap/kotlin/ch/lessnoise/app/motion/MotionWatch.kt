package ch.lessnoise.app.motion

import android.content.Context
import ch.lessnoise.app.AppContainer

/**
 * The Offline and Labor builds: no «Unterwegs erkannt», no motion detector and no sensor listener.
 * This stand-in is all they contain of it (the real one is src/karte/.../motion/, compiled only into
 * public and laborKarte). A measurement there never becomes «moving».
 */
object MotionWatch {
    const val AVAILABLE = false

    interface Handle {
        fun stop()
    }

    fun start(context: Context, container: AppContainer, position: Boolean = false): Handle? = null

    /** 0.6.9: no position signal, no foreground type «location». */
    fun positionForegroundType(context: Context): Int? = null

    /** 0.6.9: nothing to hold for. */
    fun extraHoldMs(nowMs: Long): Long = 0L
}
