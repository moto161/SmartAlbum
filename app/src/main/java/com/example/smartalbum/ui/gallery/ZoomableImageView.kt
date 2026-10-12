package com.example.smartalbum.ui.gallery

import android.content.Context
import android.graphics.Matrix
import android.graphics.PointF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

/**
 * 支持双指缩放和双击放大的ImageView
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val matrix = Matrix()
    private val scaleGestureDetector: ScaleGestureDetector
    private val gestureDetector: GestureDetector

    private var scaleFactor = 1.0f
    private var minScale = 1.0f
    private var maxScale = 5.0f

    private var lastPoint = PointF()
    private var startPoint = PointF()
    private var isDragging = false

    private var onPhotoTapListener: (() -> Unit)? = null
    private var onSwipeDownListener: (() -> Unit)? = null
    private var downY = 0f
    private var downX = 0f
    private var isSwipingDown = false

    init {
        scaleType = ScaleType.MATRIX

        scaleGestureDetector = ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val scale = detector.scaleFactor
                val newScale = scaleFactor * scale
                if (newScale in minScale..maxScale) {
                    scaleFactor = newScale
                    matrix.postScale(scale, scale, detector.focusX, detector.focusY)
                    imageMatrix = matrix
                }
                return true
            }
        })

        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (scaleFactor > 1.5f) {
                    // 缩小到原始大小
                    resetScale()
                } else {
                    // 放大到2倍
                    scaleFactor = 2.0f
                    matrix.postScale(2.0f, 2.0f, e.x, e.y)
                    imageMatrix = matrix
                }
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onPhotoTapListener?.invoke()
                return true
            }
        })
    }

    fun setOnPhotoTapListener(listener: () -> Unit) {
        this.onPhotoTapListener = listener
    }

    fun setOnSwipeDownListener(listener: (() -> Unit)?) {
        this.onSwipeDownListener = listener
    }

    fun resetScale() {
        scaleFactor = 1.0f
        matrix.reset()
        imageMatrix = matrix
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleGestureDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                isSwipingDown = false
                lastPoint.set(event.x, event.y)
                startPoint.set(event.x, event.y)
                isDragging = true
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = event.y - downY
                val dx = event.x - downX
                // 只有在原始缩放比例、向下滑动、且垂直位移大于水平位移时才触发
                if (scaleFactor <= 1.1f && dy > 150 && Math.abs(dy) > Math.abs(dx) * 1.5) {
                    isSwipingDown = true
                }
                if (isDragging && !scaleGestureDetector.isInProgress) {
                    val tdx = event.x - lastPoint.x
                    val tdy = event.y - lastPoint.y
                    if (scaleFactor > 1.0f) {
                        matrix.postTranslate(tdx, tdy)
                        imageMatrix = matrix
                    }
                    lastPoint.set(event.x, event.y)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isSwipingDown && onSwipeDownListener != null) {
                    onSwipeDownListener?.invoke()
                    isDragging = false
                    return true
                }
                isDragging = false
            }
        }
        return true
    }

    override fun setImageResource(resId: Int) {
        super.setImageResource(resId)
        resetScale()
    }
}
