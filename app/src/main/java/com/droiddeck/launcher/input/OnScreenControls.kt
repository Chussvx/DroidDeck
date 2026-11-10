package com.droiddeck.launcher.input

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import com.droiddeck.launcher.ui.InputSurfaceView

class OnScreenControls(context: Context, pad: PadBridge?, val dock: OnScreenLayout.Dock? = null) : FrameLayout(context) {
    private val surface = InputSurfaceView(context, pad, inputRole = InputRole.PRIMARY, haptics = dock != null)
    private var layout: SavedInputLayout? = null
    private val safe = Rect()
    private var viewWidth = 0
    private var viewHeight = 0
    private val placements = object : LinkedHashMap<Placement, List<InputSurfaceElement>>(4, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Placement, List<InputSurfaceElement>>) = size > PLACEMENT_CACHE
    }
    private val quickToggle = QuickToggle(context)
    var isButtonsOnly = false
        private set
    var isQuickHidden = false
        private set
    var isPinnedOnly = false
        private set
    val hasPinned get() = layout?.elements?.any { it.alwaysShown } == true
    var onTouchMiss: ((MotionEvent) -> Unit)?
        get() = surface.onTouchMiss
        set(value) { surface.onTouchMiss = value }

    init {
        addView(surface, LayoutParams(-1, -1))
        val size = (QUICK_HIT_DP * 2 * resources.displayMetrics.density).toInt()
        addView(quickToggle, LayoutParams(size, size, Gravity.START or Gravity.BOTTOM))
    }

    fun updateLayout(value: SavedInputLayout?) {
        if (layout == value) return
        releaseAll()
        layout = value
        render()
    }

    fun setButtonsOnly(enabled: Boolean) {
        if (isButtonsOnly == enabled) return
        releaseAll()
        isButtonsOnly = enabled
        isQuickHidden = false
        render()
    }

    fun setQuickHidden(hidden: Boolean) {
        if (isButtonsOnly || isQuickHidden == hidden) return
        releaseAll()
        isQuickHidden = hidden
        render()
    }

    fun setPinnedOnly(enabled: Boolean) {
        if (isPinnedOnly == enabled) return
        releaseAll()
        isPinnedOnly = enabled
        render()
    }

    private data class Placement(val layout: SavedInputLayout?, val width: Int, val height: Int, val safe: Rect, val buttonsOnly: Boolean)

    private fun placed(): List<InputSurfaceElement> {
        val key = Placement(layout, viewWidth, viewHeight, Rect(safe), isButtonsOnly)
        return placements.getOrPut(key) {
            OnScreenLayout.elements(context, layout, viewWidth, viewHeight, safe, isButtonsOnly, dock)
        }
    }

    private fun render() {
        val elements = placed()
        surface.updateCustomLayout(when {
            isPinnedOnly -> elements.filter { it.alwaysShown }
            isQuickHidden -> elements.filter { it.id == "builtin:keyboard" || it.alwaysShown }
            else -> elements
        })
        val style = layout?.style ?: InputSurfaceStyle()
        surface.updateStyle(style)
        quickToggle.tint = style.tint
        quickToggle.alpha = style.opacity / 100f
        quickToggle.visibility = if (isButtonsOnly || isPinnedOnly || layout == null || dock != null) GONE else VISIBLE
        quickToggle.invalidate()
    }

    fun takesTouchAt(x: Float, y: Float) = surface.takesTouchAt(x, y) || quickToggle.visibility == VISIBLE &&
        x >= quickToggle.left + quickToggle.translationX && x < quickToggle.right + quickToggle.translationX &&
        y >= quickToggle.top + quickToggle.translationY && y < quickToggle.bottom + quickToggle.translationY
    fun releaseAll() = surface.releaseInputs()
    fun reload() { releaseAll(); placements.clear(); render() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        if (w != viewWidth || h != viewHeight) {
            viewWidth = w
            viewHeight = h
            render()
            quickToggle.restorePosition()
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onDetachedFromWindow() { releaseAll(); super.onDetachedFromWindow() }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val cutout = if (Build.VERSION.SDK_INT >= 28) insets.displayCutout else null
        val offset = ((QUICK_CENTER_DP - QUICK_HIT_DP) * resources.displayMetrics.density).toInt()
        val params = quickToggle.layoutParams as LayoutParams
        val left = (cutout?.safeInsetLeft ?: 0) + offset
        val bottom = (cutout?.safeInsetBottom ?: 0) + offset
        if (params.leftMargin != left || params.bottomMargin != bottom) {
            params.leftMargin = left
            params.bottomMargin = bottom
            quickToggle.layoutParams = params
        }
        quickToggle.restorePosition()
        val fresh = Rect(cutout?.safeInsetLeft ?: 0, if (dock == null) cutout?.safeInsetTop ?: 0 else 0, cutout?.safeInsetRight ?: 0, cutout?.safeInsetBottom ?: 0)
        if (fresh != safe) {
            safe.set(fresh)
            render()
        }
        return super.onApplyWindowInsets(insets)
    }

    private inner class QuickToggle(context: Context) : View(context) {
        var tint: Int? = ControllerPrefs.STEAM_BLUE
        private val density = resources.displayMetrics.density
        private var armed = false
        private var moving = false
        private val timer = android.os.Handler(android.os.Looper.getMainLooper())
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0f
        private var startY = 0f
        private val startMove = Runnable {
            if (!armed) return@Runnable
            armed = false
            moving = true
            startX = translationX
            startY = translationY
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            invalidate()
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * density }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 22 * density
        }

        override fun onDraw(canvas: Canvas) {
            val x = width / 2f
            val y = height / 2f
            val radius = QUICK_RADIUS_DP * density
            val color = tint
            fill.color = if (color != null) InputSurfaceView.shade(color, 190, 0.12f) else (InputSurfaceView.SURFACE and 0x00ffffff) or (190 shl 24)
            canvas.drawCircle(x, y, radius, fill)
            stroke.color = if (color != null) InputSurfaceView.light(color, 150, 0.25f) else InputSurfaceView.LINE
            canvas.drawCircle(x, y, radius, stroke)
            if (moving) canvas.drawCircle(x, y, radius + 4 * density, stroke)
            text.color = if (color != null) InputSurfaceView.light(color, 210, 0.75f) else InputSurfaceView.TEXT
            canvas.drawText(if (isQuickHidden) "+" else "−", x, y + text.textSize * 0.35f, text)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    armed = true
                    moving = false
                    downRawX = event.rawX
                    downRawY = event.rawY
                    timer.postDelayed(startMove, QUICK_MOVE_HOLD_MS)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (moving) moveTo(startX + dx, startY + dy)
                    else if (armed && dx * dx + dy * dy > (QUICK_SLOP_DP * density).let { it * it }) {
                        armed = false
                        timer.removeCallbacks(startMove)
                    }
                }
                MotionEvent.ACTION_CANCEL -> finish(false)
                MotionEvent.ACTION_UP -> {
                    if (!moving && armed && event.x in 0f..width.toFloat() && event.y in 0f..height.toFloat()) {
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        setQuickHidden(!isQuickHidden)
                    }
                    finish(moving)
                }
            }
            return true
        }

        private fun finish(save: Boolean) {
            timer.removeCallbacks(startMove)
            armed = false
            if (moving) {
                moving = false
                invalidate()
                if (save) savePosition()
            }
        }

        private fun moveTo(x: Float, y: Float) {
            val parent = this@OnScreenControls
            if (parent.width <= 0 || parent.height <= 0 || width <= 0) return
            translationX = x.coerceIn(-left.toFloat(), (parent.width - right).toFloat())
            translationY = y.coerceIn(-top.toFloat(), (parent.height - bottom).toFloat())
        }

        private fun prefs() = context.getSharedPreferences("controller", Context.MODE_PRIVATE)

        private fun key() = "quickToggle.${this@OnScreenControls.width}x${this@OnScreenControls.height}"

        private fun savePosition() {
            val parent = this@OnScreenControls
            if (parent.width <= 0 || parent.height <= 0) return
            prefs().edit()
                .putFloat(key() + ".x", (left + translationX + width / 2f) / parent.width)
                .putFloat(key() + ".y", (top + translationY + height / 2f) / parent.height)
                .apply()
        }

        fun restorePosition() {
            timer.post {
                val parent = this@OnScreenControls
                if (parent.width <= 0 || parent.height <= 0 || width <= 0 || moving) return@post
                val p = prefs()
                val fx = p.getFloat(key() + ".x", -1f)
                val fy = p.getFloat(key() + ".y", -1f)
                if (fx < 0f || fy < 0f) {
                    translationX = 0f
                    translationY = 0f
                    return@post
                }
                moveTo(fx * parent.width - left - width / 2f, fy * parent.height - top - height / 2f)
            }
        }
    }

    private companion object {
        const val QUICK_CENTER_DP = 34f
        const val QUICK_HIT_DP = 26f
        const val QUICK_RADIUS_DP = 18f
        const val PLACEMENT_CACHE = 4
        const val QUICK_MOVE_HOLD_MS = 3_000L
        const val QUICK_SLOP_DP = 12f
    }
}
