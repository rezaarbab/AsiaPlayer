package com.asiaplayer

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs

class GestureOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Listener {
        fun onSingleTap()
        fun onDoubleTapLeft()
        fun onDoubleTapCenter()
        fun onDoubleTapRight()
        fun onVerticalSwipeLeft(deltaY: Float, isStart: Boolean)
        fun onVerticalSwipeRight(deltaY: Float, isStart: Boolean)
        fun onHorizontalSwipe(deltaX: Float, isStart: Boolean)
        fun onGestureEnd()
        fun onPinchZoom(scaleFactor: Float, isStart: Boolean)
    }

    var listener: Listener? = null

    private var pinchActive = false
    private var lastX = 0f
    private var lastY = 0f
    private var verticalActive = false
    private var horizontalActive = false
    private var directionLocked = false
    private var verticalReported = false
    private var horizontalReported = false
    private var gestureEndPending = false

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
                pinchActive = true
                listener?.onPinchZoom(1f, true)
                return true
            }
            override fun onScale(d: ScaleGestureDetector): Boolean {
                listener?.onPinchZoom(d.scaleFactor, false)
                return true
            }
            override fun onScaleEnd(d: ScaleGestureDetector) {
                pinchActive = false
                listener?.onGestureEnd()
            }
        })

    private val tapDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                listener?.onSingleTap(); return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val w = width.toFloat()
                when {
                    e.x < w / 3f -> listener?.onDoubleTapLeft()
                    e.x > w * 2f / 3f -> listener?.onDoubleTapRight()
                    else -> listener?.onDoubleTapCenter()
                }
                return true
            }
        })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        scaleDetector.onTouchEvent(event)
        if (!pinchActive && event.pointerCount <= 1) tapDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureEndPending = false
                lastX = event.x; lastY = event.y
                verticalActive = false; horizontalActive = false
                directionLocked = false; verticalReported = false; horizontalReported = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount > 1 || pinchActive) return true
                val dx = event.x - lastX
                val dy = event.y - lastY
                if (!directionLocked && (abs(dx) > 12f || abs(dy) > 12f)) {
                    directionLocked = true
                    if (abs(dx) > abs(dy)) horizontalActive = true else verticalActive = true
                }
                if (verticalActive) {
                    val isStart = !verticalReported
                    if (event.x < width / 2f) listener?.onVerticalSwipeLeft(dy, isStart)
                    else listener?.onVerticalSwipeRight(dy, isStart)
                    verticalReported = true; lastY = event.y
                } else if (horizontalActive) {
                    listener?.onHorizontalSwipe(dx, !horizontalReported)
                    horizontalReported = true; lastX = event.x
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if ((verticalActive || horizontalActive || pinchActive) && !gestureEndPending) {
                    gestureEndPending = true
                    listener?.onGestureEnd()
                }
                verticalActive = false; horizontalActive = false
                directionLocked = false; verticalReported = false; horizontalReported = false
            }
        }
        return true
    }
}
