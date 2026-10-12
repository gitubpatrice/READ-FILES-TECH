package com.readfilestech.read_files_tech.ui.viewer.image

import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import kotlin.math.roundToInt

/**
 * An animated image (`AnimatedImageDrawable`, API 28+) as a Compose painter: it plays while remembered —
 * on screen — and stops when forgotten. A frame the drawable announces redraws the painter. Compose has
 * no painter for a Drawable; this one is the few lines it takes, rather than a library.
 */
internal class DrawablePainter(private val drawable: Drawable) : Painter(), RememberObserver {
    private var frame by mutableIntStateOf(0)
    private val main = Handler(Looper.getMainLooper())

    private val callback = object : Drawable.Callback {
        override fun invalidateDrawable(who: Drawable) {
            frame++
        }

        // Drawn on a software canvas, the drawable schedules its next frame here, in uptime milliseconds;
        // on the hardware canvas Compose draws on, the render thread plays it alone.
        override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
            main.postAtTime(what, `when`)
        }

        override fun unscheduleDrawable(who: Drawable, what: Runnable) {
            main.removeCallbacks(what)
        }
    }

    override val intrinsicSize: Size
        get() = Size(drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())

    override fun DrawScope.onDraw() {
        // Read to redraw on each new frame.
        frame
        drawIntoCanvas { canvas ->
            drawable.setBounds(0, 0, size.width.roundToInt(), size.height.roundToInt())
            drawable.draw(canvas.nativeCanvas)
        }
    }

    override fun onRemembered() {
        drawable.callback = callback
        (drawable as? Animatable)?.start()
    }

    override fun onForgotten() {
        (drawable as? Animatable)?.stop()
        drawable.callback = null
        main.removeCallbacksAndMessages(null)
    }

    override fun onAbandoned() = onForgotten()
}
