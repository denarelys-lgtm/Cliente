package com.example.remoteclient

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout
import kotlin.math.max

class ResizableCameraContainer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private var isResizing = false
    private var isMoving = false
    private var lastX = 0f
    private var lastY = 0f

    private val minSize = 300 // Un poco más grande para mejor UX
    private val handleArea = 100f
    private val cornerRadius = 40f

    // --- Objetos de dibujo (Reutilizados para rendimiento) ---
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
        // Elevación para sombra real
        elevation = 12f
        // Fondo semi-transparente oscuro para que el contenido resalte
        setBackgroundColor(Color.parseColor("#22000000"))
    }

    override fun dispatchDraw(canvas: Canvas) {
        // Recortar las esquinas de los hijos (la cámara)
        clipPath.reset()
        rectF.set(0f, 0f, width.toFloat(), height.toFloat())
        clipPath.addRoundRect(rectF, cornerRadius, cornerRadius, Path.Direction.CW)
        canvas.clipPath(clipPath)
        
        super.dispatchDraw(canvas)
        
        // Dibujar borde sutil
        canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, borderPaint)
        
        // Dibujar el "handle" de redimensión (3 líneas diagonales estéticas)
        drawResizeHandle(canvas)
    }

    private fun drawResizeHandle(canvas: Canvas) {
        val padding = 30f
        val lineSpacing = 15f
        
        for (i in 0..2) {
            val offset = i * lineSpacing
            canvas.drawLine(
                width - padding - offset, height - padding,
                width - padding, height - padding - offset,
                handlePaint
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.rawX
                lastY = event.rawY

                // Detectar si toca la esquina inferior derecha
                isResizing = event.x >= width - handleArea && event.y >= height - handleArea
                isMoving = !isResizing
                
                // Feedback visual simple al tocar
                alpha = 0.8f
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - lastX
                val dy = event.rawY - lastY

                if (isResizing) {
                    layoutParams.width = max(minSize, (width + dx).toInt())
                    layoutParams.height = max(minSize, (height + dy).toInt())
                    requestLayout()
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
