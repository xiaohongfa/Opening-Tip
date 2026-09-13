package com.openingtip.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 桌面悬浮灵动倒计时胶囊管理器：
 * 1. 【灵动胶囊倒计时】：在用户输入意图后进入手机，在屏幕右上角显示一个半透明极简药丸胶囊（🎯 MM:SS）；
 * 2. 【自由拖拽吸边】：支持手指任意拖拽移动，不遮挡手机内容；
 * 3. 【点击展开交互】：轻点胶囊展开卡片，显示本次意图、剩余时长、以及【放下手机/锁屏】与【+1分钟】按钮；
 * 4. 【超时变红警示】：时间用尽后，胶囊转为醒目橙红色警示（⚠️ 超时 MM:SS），提醒用户保持自律；
 * 5. 【生命周期安全】：熄屏、锁屏或完成意图时自动平滑移除，0内存泄露。
 */
class FloatingTimerManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var floatingView: View? = null
    private var pillTextView: TextView? = null
    private var expandedContainer: LinearLayout? = null
    private var intentDetailTextView: TextView? = null
    private var timerDetailTextView: TextView? = null

    private var currentIntentText: String = ""
    private var isExpanded: Boolean = false
    private var isTimeoutState: Boolean = false

    private var onLockAction: (() -> Unit)? = null
    private var onExtendAction: (() -> Unit)? = null

    @SuppressLint("ClickableViewAccessibility")
    fun show(
        intentText: String,
        targetDurationMinutes: Int,
        onLock: () -> Unit,
        onExtend: () -> Unit
    ) {
        mainHandler.post {
            try {
                if (!Settings.canDrawOverlays(context)) {
                    Log.w(TAG, "SYSTEM_ALERT_WINDOW not granted, cannot show floating pill")
                    return@post
                }

                currentIntentText = intentText
                onLockAction = onLock
                onExtendAction = onExtend
                isExpanded = false
                isTimeoutState = false

                if (floatingView != null) {
                    hideInternal()
                }

                val dpToPx = { dp: Float ->
                    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, context.resources.displayMetrics).toInt()
                }

                // 根布局：垂直容器
                val rootLayout = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                }

                // 1. 灵动胶囊主体
                val pillLayout = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    setPadding(dpToPx(12f), dpToPx(6f), dpToPx(12f), dpToPx(6f))
                    background = createPillBackground(isTimeout = false)
                    elevation = dpToPx(6f).toFloat()
                }

                val pillTv = TextView(context).apply {
                    text = "🎯 ${formatTime(targetDurationMinutes * 60)}"
                    setTextColor(Color.WHITE)
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                }
                pillLayout.addView(pillTv)
                pillTextView = pillTv

                // 2. 展开卡片详情
                val detailLayout = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.START
                    visibility = View.GONE
                    setPadding(dpToPx(14f), dpToPx(12f), dpToPx(14f), dpToPx(12f))
                    background = createCardBackground()
                    elevation = dpToPx(8f).toFloat()
                }

                val intentTitleTv = TextView(context).apply {
                    text = "🎯 本次专注意图"
                    setTextColor(Color.parseColor("#9E9E9E"))
                    textSize = 11f
                }
                detailLayout.addView(intentTitleTv)

                val intentContentTv = TextView(context).apply {
                    text = intentText
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, dpToPx(2f), 0, dpToPx(8f))
                }
                detailLayout.addView(intentContentTv)
                intentDetailTextView = intentContentTv

                val timerTv = TextView(context).apply {
                    text = "⏳ 剩余：${formatTime(targetDurationMinutes * 60)}"
                    setTextColor(Color.parseColor("#81C784"))
                    textSize = 12f
                    setPadding(0, 0, 0, dpToPx(10f))
                }
                detailLayout.addView(timerTv)
                timerDetailTextView = timerTv

                // 操作按钮栏
                val buttonBar = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }

                val lockBtn = Button(context).apply {
                    text = "放下手机"
                    textSize = 12f
                    setTextColor(Color.WHITE)
                    background = createButtonBackground(Color.parseColor("#2E7D32"))
                    setOnClickListener {
                        hideInternal()
                        onLockAction?.invoke()
                    }
                }
                buttonBar.addView(lockBtn, LinearLayout.LayoutParams(dpToPx(80f), dpToPx(34f)).apply {
                    marginEnd = dpToPx(8f)
                })

                val extendBtn = Button(context).apply {
                    text = "+1分钟"
                    textSize = 12f
                    setTextColor(Color.WHITE)
                    background = createButtonBackground(Color.parseColor("#424242"))
                    setOnClickListener {
                        onExtendAction?.invoke()
                    }
                }
                buttonBar.addView(extendBtn, LinearLayout.LayoutParams(dpToPx(70f), dpToPx(34f)))

                detailLayout.addView(buttonBar)
                expandedContainer = detailLayout

                rootLayout.addView(pillLayout)
                rootLayout.addView(detailLayout, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dpToPx(6f)
                })

                val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    layoutFlag,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.END
                    x = dpToPx(16f)
                    y = dpToPx(90f) // 避开状态栏
                }

                // 拖动手势与点击展开切换
                var initialX = 0
                var initialY = 0
                var initialTouchX = 0f
                var initialTouchY = 0f
                var isClick = false

                pillLayout.setOnTouchListener { _, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialX = params.x
                            initialY = params.y
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            isClick = true
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = (event.rawX - initialTouchX).toInt()
                            val dy = (event.rawY - initialTouchY).toInt()
                            if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                                isClick = false
                            }
                            params.x = initialX - dx
                            params.y = initialY + dy
                            try {
                                windowManager.updateViewLayout(rootLayout, params)
                            } catch (_: Exception) {}
                            true
                        }
                        MotionEvent.ACTION_UP -> {
                            if (isClick) {
                                toggleExpanded()
                            }
                            true
                        }
                        else -> false
                    }
                }

                windowManager.addView(rootLayout, params)
                floatingView = rootLayout
                Log.i(TAG, "Floating countdown pill shown successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to show floating pill", e)
            }
        }
    }

    private fun toggleExpanded() {
        val container = expandedContainer ?: return
        isExpanded = !isExpanded
        container.visibility = if (isExpanded) View.VISIBLE else View.GONE
    }

    fun updateTime(remainingSeconds: Int, isTimeout: Boolean) {
        mainHandler.post {
            try {
                isTimeoutState = isTimeout
                val formatted = formatTime(Math.abs(remainingSeconds))
                if (isTimeout) {
                    pillTextView?.text = "⚠️ 超时 $formatted"
                    pillTextView?.parent?.let { parent ->
                        (parent as? View)?.background = createPillBackground(isTimeout = true)
                    }
                    timerDetailTextView?.text = "⚠️ 已超时使用：$formatted"
                    timerDetailTextView?.setTextColor(Color.parseColor("#FF5252"))
                } else {
                    pillTextView?.text = "🎯 $formatted"
                    pillTextView?.parent?.let { parent ->
                        (parent as? View)?.background = createPillBackground(isTimeout = false)
                    }
                    timerDetailTextView?.text = "⏳ 剩余时间：$formatted"
                    timerDetailTextView?.setTextColor(Color.parseColor("#81C784"))
                }
            } catch (_: Exception) {}
        }
    }

    fun hide() {
        mainHandler.post {
            hideInternal()
        }
    }

    private fun hideInternal() {
        try {
            floatingView?.let {
                windowManager.removeView(it)
                floatingView = null
            }
            pillTextView = null
            expandedContainer = null
            intentDetailTextView = null
            timerDetailTextView = null
            isExpanded = false
            isTimeoutState = false
        } catch (_: Exception) {}
    }

    private fun createPillBackground(isTimeout: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 50f
            if (isTimeout) {
                setColor(Color.parseColor("#E6C62828")) // 醒目半透橙红
                setStroke(2, Color.parseColor("#FF8A80"))
            } else {
                setColor(Color.parseColor("#E6212121")) // 高级暗色半透
                setStroke(2, Color.parseColor("#424242"))
            }
        }
    }

    private fun createCardBackground(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 32f
            setColor(Color.parseColor("#F21E1E1E"))
            setStroke(2, Color.parseColor("#424242"))
        }
    }

    private fun createButtonBackground(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 24f
            setColor(color)
        }
    }

    private fun formatTime(totalSeconds: Int): String {
        val s = totalSeconds.coerceAtLeast(0)
        val minutes = s / 60
        val seconds = s % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    companion object {
        private const val TAG = "FloatingTimer"
    }
}
