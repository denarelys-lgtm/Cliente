package com.example.remoteclient

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout
import kotlin.math.max

class ResizableCameraContainer constructor(
    context: Context, 
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private var isResizing = false
    private var isMoving = false
    private var lastX = 0f
    private var lastY = 0f
    
    private val minSize = 300
    private val handleArea = 100f
    private val cornerRadius = 40f

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
        alpha = 100
    }

    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
    }

    private val rectF = RectF()
    private val clipPath = Path()

    init {
        setWillNotDraw(false)
        elevation = 12f
        setBackgroundColor(Color.parseColor("#22000000"))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // El Path se recalcula únicamente cuando cambian las dimensiones del layout, evitando crear objetos en dispatchDraw
        rectF.set(0f, 0f, w.toFloat(), h.toFloat())
        clipPath.reset()
        clipPath.addRoundRect(rectF, cornerRadius, cornerRadius, Path.Direction.CW)
    }

    override fun dispatchDraw(canvas: Canvas) {
        canvas.save()
        canvas.clipPath(clipPath)
        super.dispatchDraw(canvas)
        canvas.restore()

        canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, borderPaint)
        drawResizeHandle(canvas)
    }

    private fun drawResizeHandle(canvas: Canvas) {
        val padding = 30f
        val lineSpacing = 15f
        for (i in 0..2) {
            val offset = i * lineSpacing
            canvas.drawLine(
                width - padding - offset,
                height - padding,
                width - padding,
                height - padding - offset,
                handlePaint
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.rawX
                lastY = event.rawY
                isResizing = event.x >= width - handleArea && event.y >= height - handleArea
                isMoving = !isResizing
                alpha = 0.8f
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - lastX
                val dy = event.rawY - lastY
                
                if (isResizing) {
                    val params = layoutParams
                    params.width = max(minSize, (width + dx).toInt())
                    params.height = max(minSize, (height + dy).toInt())
                    layoutParams = params // Forzar solicitud de actualización estructural limpia
                } else if (isMoving) {
                    x += dx
                    y += dy
                }
                lastX = event.rawX
                lastY = event.rawY
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isResizing = false
                isMoving = false
                alpha = 1.0f
            }
        }
        return super.onTouchEvent(event)
    }
}
