package com.example.crackprank

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import kotlin.random.Random

/**
 * Draws, at a random spot on screen:
 *  - the real shatter-crack texture (res/drawable-nodpi/crack_texture.png),
 *    scaled/rotated around its focal (impact) point so it looks like an
 *    actual crack radiating from that spot
 *  - a small patch of dead/stuck black pixels right at that same impact point
 *  - 1-3 horizontal "glitched row" bars elsewhere on screen
 *
 * Also owns the dismiss gesture: hold BOTH volume keys for >= HOLD_MS to
 * clear the overlay. The window hosting this view must be focusable (so it
 * receives key events) but NOT touchable (so normal phone use underneath is
 * unaffected) — that combination is set up in DropDetectionService.
 */
class CrackOverlayView(context: Context, private val onDismiss: () -> Unit) : View(context) {

    companion object {
        private const val HOLD_MS = 3000L

        // Where the crack "radiates from" inside crack_texture.png, as a
        // fraction of the bitmap's own width/height. Tweak if you swap in a
        // different texture whose focal point sits elsewhere in the image.
        private const val TEXTURE_FOCAL_X = 0.52f
        private const val TEXTURE_FOCAL_Y = 0.50f
    }

    private val handler = Handler(Looper.getMainLooper())

    private var volUpDown = false
    private var volDownDown = false
    private var holdStartedAt = 0L

    private val dismissRunnable = Runnable {
        if (volUpDown && volDownDown) onDismiss()
    }

    // --- random, fixed-per-trigger layout -------------------------------------------------

    private val impactX = Random.nextFloat()
    private val impactY = Random.nextFloat()
    private val rotationDeg = Random.nextFloat() * 360f
    private val sizeFactor = 0.55f + Random.nextFloat() * 0.45f // how big the crack is vs. screen
    private val pixelSeed = Random.nextLong()
    private val glitchSeed = Random.nextLong()

    private val crackBitmap: Bitmap? by lazy {
        BitmapFactory.decodeResource(resources, R.drawable.crack_texture)
    }

    private val crackPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val deadPixelPaint = Paint().apply { color = Color.BLACK }
    private val glitchPaint = Paint()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val cx = impactX * w
        val cy = impactY * h

        drawCrackTexture(canvas, cx, cy, w, h)
        drawDeadPixelCluster(canvas, cx, cy)
        drawGlitchBars(canvas, w, h, glitchSeed)
    }

    /** Places the real crack texture so its focal point lands exactly on (cx, cy), at a random size and rotation. */
    private fun drawCrackTexture(canvas: Canvas, cx: Float, cy: Float, w: Float, h: Float) {
        val bmp = crackBitmap ?: return

        val targetSize = sizeFactor * minOf(w, h)
        val scale = targetSize / minOf(bmp.width, bmp.height)
        val focalX = bmp.width * TEXTURE_FOCAL_X
        val focalY = bmp.height * TEXTURE_FOCAL_Y

        val matrix = Matrix().apply {
            // move the texture's focal point to the origin, rotate + scale around it,
            // then translate the origin out to the chosen impact point
            postTranslate(-focalX, -focalY)
            postRotate(rotationDeg)
            postScale(scale, scale)
            postTranslate(cx, cy)
        }

        canvas.drawBitmap(bmp, matrix, crackPaint)
    }

    /** A small cluster of fully-black "dead pixel" squares right at the impact point. */
    private fun drawDeadPixelCluster(canvas: Canvas, cx: Float, cy: Float) {
        val rnd = Random(pixelSeed)
        val clusterRadius = 10f + rnd.nextInt(14)
        val count = 10 + rnd.nextInt(18)
        repeat(count) {
            val dx = (rnd.nextFloat() - 0.5f) * clusterRadius * 2
            val dy = (rnd.nextFloat() - 0.5f) * clusterRadius * 2
            val size = 1f + rnd.nextFloat() * 2.5f
            canvas.drawRect(cx + dx, cy + dy, cx + dx + size, cy + dy + size, deadPixelPaint)
        }
    }

    /** 1-3 thin full-width "glitched scanline" bars with a subtle RGB-split look, placed away from the crack. */
    private fun drawGlitchBars(canvas: Canvas, w: Float, h: Float, seed: Long) {
        val rnd = Random(seed)
        val bars = 1 + rnd.nextInt(3)
        repeat(bars) {
            val y = rnd.nextFloat() * h
            val barHeight = 2f + rnd.nextFloat() * 5f
            val colors = intArrayOf(
                Color.argb(180, 255, 60, 90),
                Color.argb(180, 60, 255, 210),
                Color.argb(220, 255, 255, 255)
            )
            colors.forEachIndexed { idx, c ->
                glitchPaint.color = c
                val offset = (idx - 1) * 3f
                canvas.drawRect(0f, y + offset, w, y + offset + barHeight, glitchPaint)
            }
        }
    }

    // --- volume-hold dismiss ----------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> volUpDown = event.action == KeyEvent.ACTION_DOWN
            KeyEvent.KEYCODE_VOLUME_DOWN -> volDownDown = event.action == KeyEvent.ACTION_DOWN
            else -> return super.dispatchKeyEvent(event)
        }

        if (volUpDown && volDownDown) {
            if (holdStartedAt == 0L) {
                holdStartedAt = System.currentTimeMillis()
                handler.postDelayed(dismissRunnable, HOLD_MS)
            }
        } else {
            holdStartedAt = 0L
            handler.removeCallbacks(dismissRunnable)
        }

        // swallow the key while the crack is up so volume doesn't jump around mid-hold
        return true
    }

    fun onRemoved() {
        handler.removeCallbacks(dismissRunnable)
    }
}
