package kr.ac.pbl.busdriver

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class RouteTimeline(context: Context, attrs: AttributeSet) : View(context, attrs) {
    var stops: List<Stop> = emptyList()
    var current: Int = 0
    var designScale: Float = 1f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        if (stops.isEmpty()) return
        val d = resources.displayMetrics.density * designScale
        val first = (current - 1).coerceAtLeast(0).coerceAtMost((stops.size - 5).coerceAtLeast(0))
        val visible = stops.drop(first).take(5)
        val pad = 32 * d; val step = (width - pad * 2) / (visible.size - 1).coerceAtLeast(1)
        val y = 21 * d
        paint.strokeWidth = 5 * d; paint.color = Color.rgb(217, 220, 225)
        c.drawLine(pad, y, pad + step * (visible.size - 1), y, paint)
        visible.forEachIndexed { i, stop ->
            val x = pad + step * i
            paint.color = Color.WHITE; c.drawCircle(x, y, 12 * d, paint)
            paint.color = Color.parseColor(when { stop.sequence < current -> "#62666C"; stop.sequence == current -> "#1B73E8"; stop.sequence == current + 1 -> "#F44336"; else -> "#D9DCE1" })
            c.drawCircle(x, y, 8 * d, paint)
            if (stop.sequence > current + 1) paint.color = Color.rgb(98, 102, 108)
            paint.textAlign = Paint.Align.CENTER; paint.textSize = 9 * d; paint.isFakeBoldText = stop.sequence == current
            val name = if (stop.name.length > 10) stop.name.take(9) + "…" else stop.name
            c.drawText(name, x, y + 21 * d, paint)
            paint.textSize = 8 * d; paint.isFakeBoldText = false
            c.drawText(when { stop.sequence < current -> "이전"; stop.sequence == current -> "현재"; stop.sequence == current + 1 -> "다음"; else -> "${stop.sequence - current}개 정류장 후" }, x, y + 34 * d, paint)
        }
        contentDescription = stops.joinToString { "${it.sequence + 1}. ${it.name}" }
    }
}
