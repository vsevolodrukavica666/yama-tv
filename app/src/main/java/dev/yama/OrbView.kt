package dev.yama

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** The glowing "My wave" orb: breathes slowly, and beats with the music while something plays. */
class OrbView(ctx: Context, attrs: AttributeSet?) : View(ctx, attrs) {
    private val orb = BitmapFactory.decodeResource(resources, R.drawable.art_orb)
    // the art carries its glow as alpha (not on black + ADD): the scroll view's fade layer would turn ADD's black opaque
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()
    private var peak = 0.05f
    private var beat = 0f

    override fun onDraw(c: Canvas) {
        val t = SystemClock.uptimeMillis() / 1000f
        val raw = if (Playback.player.isPlaying) Playback.level else 0f
        // compare against a slowly fading peak so quiet and loud tracks both pulse visibly
        peak = max(peak * 0.995f, max(raw, 0.05f))
        val v = (raw / peak).let { it * it }
        beat += (v - beat) * if (v > beat) 0.6f else 0.12f

        val scale = 0.8f + sin(t * 1.4f) * 0.025f + beat * 0.16f
        val r = min(width, height) / 2f * scale
        val cx = width / 2f
        val cy = height / 2f
        dst.set(cx - r, cy - r, cx + r, cy + r)
        paint.alpha = (170 + 85 * beat).toInt().coerceIn(0, 255)
        c.save()
        c.rotate(t * 8f, cx, cy)
        c.drawBitmap(orb, null, dst, paint)
        c.restore()
        postInvalidateOnAnimation()
    }
}
