package com.example.samplerpiano

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View

/** Tastiera verticale: note acute in alto, 8 ottave (C1..B8), 14 tasti bianchi visibili.
 *  Si scorre trascinando la barra a destra; i tasti suonano anche in multitouch e glissando. */
class KeyboardView(ctx: Context, private val engine: Engine) : View(ctx) {
    private val LOW = 24
    private val HIGH = 119
    private val blackSet = setOf(1, 3, 6, 8, 10)
    private val whites = (LOW..HIGH).filter { it % 12 !in blackSet }
    private val blacks = (LOW..HIGH).filter { it % 12 in blackSet }
    private val whiteIdx = IntArray(HIGH + 1).also { a -> whites.forEachIndexed { i, n -> a[n] = i } }
    private val d = resources.displayMetrics.density
    private val strip = 44 * d
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var scroll = -1f
    private val notes = HashMap<Int, Int>()
    private val voices = HashMap<Int, Voice?>()
    private var stripPtr = -1

    private val kh get() = height / 14f
    private val total get() = whites.size * kh
    private val maxScroll get() = total - height
    private val keysW get() = width - strip

    private fun whiteTop(n: Int) = (whites.size - 1 - whiteIdx[n]) * kh - scroll
    private fun blackTop(n: Int) = whiteTop(n - 1) - kh * 0.3f

    override fun onDraw(c: Canvas) {
        if (scroll < 0f) scroll = (whiteTop(60) + scroll - height / 2f + kh / 2f).coerceIn(0f, maxScroll)
        val h = kh
        val pressed = notes.values.toSet()
        for (n in whites) {
            val t = whiteTop(n)
            if (t > height || t + h < 0) continue
            p.style = Paint.Style.FILL
            p.color = if (n in pressed) Color.rgb(255, 190, 215) else Color.WHITE
            c.drawRect(0f, t, keysW, t + h, p)
            p.color = Color.GRAY; p.strokeWidth = 1f
            c.drawLine(0f, t + h, keysW, t + h, p)
            if (n % 12 == 0) {
                p.color = Color.DKGRAY; p.textSize = 14 * d
                c.drawText("C${n / 12 - 1}", 10 * d, t + h / 2 + 5 * d, p)
            }
        }
        for (n in blacks) {
            val t = blackTop(n)
            if (t > height || t + h * 0.6f < 0) continue
            p.color = if (n in pressed) Color.rgb(214, 51, 108) else Color.rgb(20, 20, 20)
            c.drawRoundRect(0f, t, keysW * 0.6f, t + h * 0.6f, 6 * d, 6 * d, p)
        }
        // barra di scorrimento con le ottave
        p.color = Color.rgb(225, 225, 220)
        c.drawRect(keysW, 0f, width.toFloat(), height.toFloat(), p)
        p.color = Color.GRAY; p.textSize = 11 * d
        for (o in 1..8) {
            val y = (whites.size - 1 - whiteIdx[o * 12 + 12]) * h / total * height
            c.drawText("C$o", keysW + 6 * d, y + 12 * d + 0f, p)
        }
        val th = height * height / total
        val ty = if (maxScroll > 0) scroll / maxScroll * (height - th) else 0f
        p.color = Color.argb(150, 214, 51, 108)
        c.drawRoundRect(keysW + 4 * d, ty, width - 4 * d, ty + th, 8 * d, 8 * d, p)
    }

    private fun noteAt(x: Float, y: Float): Int {
        if (x < keysW * 0.6f) for (n in blacks) {
            val t = blackTop(n)
            if (y >= t && y <= t + kh * 0.6f) return n
        }
        val wi = whites.size - 1 - ((y + scroll) / kh).toInt()
        return whites[wi.coerceIn(0, whites.size - 1)]
    }

    private fun scrollTo(y: Float) {
        scroll = (y / height).coerceIn(0f, 1f) * maxScroll
    }

    private fun on(id: Int, n: Int) { notes[id] = n; voices[id] = engine.noteOn(n) }
    private fun off(id: Int) { engine.noteOff(voices.remove(id)); notes.remove(id) }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex; val id = e.getPointerId(i)
                if (e.getX(i) >= keysW) { stripPtr = id; scrollTo(e.getY(i)) }
                else on(id, noteAt(e.getX(i), e.getY(i)))
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) {
                val id = e.getPointerId(i)
                if (id == stripPtr) scrollTo(e.getY(i))
                else if (notes.containsKey(id) && e.getX(i) < keysW) {
                    val n = noteAt(e.getX(i), e.getY(i))
                    if (n != notes[id]) { off(id); on(id, n) }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(e.actionIndex)
                if (id == stripPtr) stripPtr = -1 else off(id)
            }
            MotionEvent.ACTION_CANCEL -> { notes.keys.toList().forEach { off(it) }; stripPtr = -1 }
        }
        invalidate()
        return true
    }
}
