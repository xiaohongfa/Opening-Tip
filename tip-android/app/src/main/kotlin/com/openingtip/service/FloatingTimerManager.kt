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
import android.widget.ProgressBar
import android.widget.TextView
import com.openingtip.feature.gate.PassiveTimeManager

/**
 * 桌面悬浮灵动胶囊管理器（主动计时与被动屏幕时间双模）：
 * 1. 【被动时间主动统计】：践行“建议每天的被动屏幕时间不要超过 1.5 小时（90分钟）”；
 * 2. 【灵动胶囊展示】：
 *    - 未计时时：展示今日被动消耗与额度（📺 42m / 1.5h）；
 *    - 计时中：展示被动计时进度（📺 被动中 MM:SS）；
 *    - 超过 90 分钟：醒目变红警示（⚠️ 📺 1h35m 超标）；
 *    - 若同时存在专注意图倒计时：协同轮播或组合展示（🎯 MM:SS | 📺 XXm）；
 * 3. 【轻点展开控制面板】：
 *    - 一键【▶ 开始被动时间】/【⏹ 结束被动时间】；
 *    - 实时今日被动累计与 1.5h 额度进度条；
 *    - 意图详情与【🔒 放下手机】/【+1分钟】操作；
 * 4. 【自由拖拽吸边与生命周期安全】：支持手指任意拖动，熄屏/锁屏时自动结算并平滑收起。
 */
class FloatingTimerManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var floatingView: View? = null
    private var pillTextView: TextView? = null
    private var expandedContainer: LinearLayout? = null

    // 被动时间 UI 控件
    private var passiveTitleTv: TextView? = null
    private var passiveStatsTv: TextView? = null
    private var passiveProgressBar: ProgressBar? = null
    private var passiveToggleBtn: Button? = null

    // 专注意图 UI 控件
    private var intentSectionLayout: LinearLayout? = null
    private var intentDetailTextView: TextView? = null
    private var timerDetailTextView: TextView? = null
    private var extendBtn: Button? = null

    private var currentIntentText: String = ""
    private var currentTargetMinutes: Int = 0
    private var isExpanded: Boolean = false
    private var isFocusTimeoutState: Boolean = false
    private var lastRemainingSeconds: Int = 0

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
                currentTargetMinutes = targetDurationMinutes
                onLockAction = onLock
                onExtendAction = onExtend
                isExpanded = false
                isFocusTimeoutState = false
                lastRemainingSeconds = targetDurationMinutes * 60

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
                    background = createPillBackground(PillStyle.NORMAL)
                    elevation = dpToPx(6f).toFloat()
                }

                val pillTv = TextView(context).apply {
                    text = buildPillText()
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
                    setPadding(dpToPx(16f), dpToPx(14f), dpToPx(16f), dpToPx(14f))
                    background = createCardBackground()
                    elevation = dpToPx(10f).toFloat()
                }

                // ========== A. 被动屏幕时间控制板块 ==========
                val pTitleTv = TextView(context).apply {
                    text = "📺 被动屏幕时间 (建议 ≤ 1.5小时)"
                    setTextColor(Color.parseColor("#B0BEC5"))
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                }
                detailLayout.addView(pTitleTv)
                passiveTitleTv = pTitleTv

                val pStatsTv = TextView(context).apply {
                    text = buildPassiveStatsText()
                    setTextColor(Color.WHITE)
                    textSize = 13f
                    setPadding(0, dpToPx(4f), 0, dpToPx(4f))
                }
                detailLayout.addView(pStatsTv)
                passiveStatsTv = pStatsTv

                // 进度条
                val pProgressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = (PassiveTimeManager.TARGET_DAILY_PASSIVE_MS / 1000L / 60L).toInt() // 90 分钟
                    progress = ((PassiveTimeManager.getTodayTotalPassiveMs(context) / 1000L / 60L).toInt()).coerceAtMost(max)
                    progressDrawable = createProgressDrawable()
                }
                detailLayout.addView(pProgressBar, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dpToPx(6f)
                ).apply {
                    topMargin = dpToPx(2f)
                    bottomMargin = dpToPx(10f)
                })
                passiveProgressBar = pProgressBar

                // 开始/结束被动时间控制按钮
                val isTiming = PassiveTimeManager.isPassiveTimingActive()
                val pToggleBtn = Button(context).apply {
                    text = if (isTiming) "⏹ 结束被动时间" else "▶ 开始被动时间"
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                    background = createButtonBackground(
                        if (isTiming) Color.parseColor("#D32F2F") else Color.parseColor("#1976D2")
                    )
                    setOnClickListener {
                        handlePassiveToggle()
                    }
                }
                detailLayout.addView(pToggleBtn, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dpToPx(38f)
                ).apply {
                    bottomMargin = dpToPx(10f)
                })
                passiveToggleBtn = pToggleBtn

                // ========== B. 专注意图板块 (若设置了意图) ==========
                val intentLayout = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    visibility = if (currentIntentText.isNotBlank()) View.VISIBLE else View.GONE
                }

                // 分割线
                val divider = View(context).apply {
                    setBackgroundColor(Color.parseColor("#424242"))
                }
                intentLayout.addView(divider, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dpToPx(1f)
                ).apply {
                    topMargin = dpToPx(4f)
                    bottomMargin = dpToPx(8f)
                })

                val intentTitleTv = TextView(context).apply {
                    text = "🎯 本次专注意图"
                    setTextColor(Color.parseColor("#9E9E9E"))
                    textSize = 11f
                }
                intentLayout.addView(intentTitleTv)

                val intentContentTv = TextView(context).apply {
                    text = currentIntentText
                    setTextColor(Color.WHITE)
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, dpToPx(2f), 0, dpToPx(4f))
                }
                intentLayout.addView(intentContentTv)
                intentDetailTextView = intentContentTv

                val timerTv = TextView(context).apply {
                    text = "⏳ 意图剩余：${formatTime(targetDurationMinutes * 60)}"
                    setTextColor(Color.parseColor("#81C784"))
                    textSize = 12f
                    setPadding(0, 0, 0, dpToPx(8f))
                }
                intentLayout.addView(timerTv)
                timerDetailTextView = timerTv

                detailLayout.addView(intentLayout)
                intentSectionLayout = intentLayout

                // ========== C. 底部操作按钮栏 ==========
                val buttonBar = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }

                val lockBtn = Button(context).apply {
                    text = "🔒 放下手机"
                    textSize = 12f
                    setTextColor(Color.WHITE)
                    background = createButtonBackground(Color.parseColor("#2E7D32"))
                    setOnClickListener {
                        if (PassiveTimeManager.isPassiveTimingActive()) {
                            PassiveTimeManager.stopPassiveTimer(context)
                        }
                        hideInternal()
                        onLockAction?.invoke()
                    }
                }
                buttonBar.addView(lockBtn, LinearLayout.LayoutParams(0, dpToPx(34f), 1f).apply {
                    marginEnd = dpToPx(6f)
                })

                val extBtn = Button(context).apply {
                    text = "+1分钟"
                    textSize = 12f
                    setTextColor(Color.WHITE)
                    visibility = if (targetDurationMinutes > 0) View.VISIBLE else View.GONE
                    background = createButtonBackground(Color.parseColor("#424242"))
                    setOnClickListener {
                        onExtendAction?.invoke()
                    }
                }
                buttonBar.addView(extBtn, LinearLayout.LayoutParams(dpToPx(70f), dpToPx(34f)))
                extendBtn = extBtn

                detailLayout.addView(buttonBar)
                expandedContainer = detailLayout

                rootLayout.addView(pillLayout)
                rootLayout.addView(detailLayout, LinearLayout.LayoutParams(
                    dpToPx(240f),
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
                Log.i(TAG, "Floating pill shown successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to show floating pill", e)
            }
        }
    }

    private fun handlePassiveToggle() {
        val currentlyTiming = PassiveTimeManager.isPassiveTimingActive()
        if (currentlyTiming) {
            PassiveTimeManager.stopPassiveTimer(context)
        } else {
            PassiveTimeManager.startPassiveTimer(context)
        }
        refreshUi()
    }

    private fun toggleExpanded() {
        val container = expandedContainer ?: return
        isExpanded = !isExpanded
        container.visibility = if (isExpanded) View.VISIBLE else View.GONE
        if (isExpanded) {
            refreshUi()
        }
    }

    /**
     * 每秒心跳驱动刷新
     */
    fun updateTime(remainingSeconds: Int, isFocusTimeout: Boolean) {
        lastRemainingSeconds = remainingSeconds
        isFocusTimeoutState = isFocusTimeout
        mainHandler.post {
            refreshUi()
        }
    }

    private fun refreshUi() {
        try {
            val isPassiveTiming = PassiveTimeManager.isPassiveTimingActive()
            val todayTotalMs = PassiveTimeManager.getTodayTotalPassiveMs(context)
            val isExceeded = PassiveTimeManager.isTodayExceeded(context)

            // 1. 更新胶囊文本与背景样式
            pillTextView?.text = buildPillText()
            val pillStyle = when {
                isExceeded || isFocusTimeoutState -> PillStyle.TIMEOUT
                isPassiveTiming -> PillStyle.ACTIVE_PASSIVE
                else -> PillStyle.NORMAL
            }
            pillTextView?.parent?.let { parent ->
                (parent as? View)?.background = createPillBackground(pillStyle)
            }

            // 2. 更新展开卡片中的被动时间组件
            passiveStatsTv?.text = buildPassiveStatsText()
            passiveProgressBar?.let { pb ->
                val minutesUsed = (todayTotalMs / 1000L / 60L).toInt()
                pb.progress = minutesUsed.coerceAtMost(pb.max)
            }

            passiveToggleBtn?.let { btn ->
                if (isPassiveTiming) {
                    val currentSessionMs = PassiveTimeManager.getCurrentSessionElapsedMs()
                    val formatted = PassiveTimeManager.formatTimerDigits(currentSessionMs)
                    btn.text = "⏹ 结束被动时间 ($formatted)"
                    btn.background = createButtonBackground(Color.parseColor("#D32F2F"))
                } else {
                    btn.text = "▶ 开始被动时间"
                    btn.background = createButtonBackground(Color.parseColor("#1976D2"))
                }
            }

            // 3. 更新专注意图文本
            if (currentTargetMinutes > 0) {
                val formattedFocus = formatTime(Math.abs(lastRemainingSeconds))
                if (isFocusTimeoutState) {
                    timerDetailTextView?.text = "⚠️ 意图已超时：$formattedFocus"
                    timerDetailTextView?.setTextColor(Color.parseColor("#FF5252"))
                } else {
                    timerDetailTextView?.text = "⏳ 意图剩余：$formattedFocus"
                    timerDetailTextView?.setTextColor(Color.parseColor("#81C784"))
                }
            }
        } catch (_: Exception) {}
    }

    private fun buildPillText(): String {
        val isPassiveTiming = PassiveTimeManager.isPassiveTimingActive()
        val todayTotalMs = PassiveTimeManager.getTodayTotalPassiveMs(context)
        val isExceeded = PassiveTimeManager.isTodayExceeded(context)

        return if (isPassiveTiming) {
            val currentSessionMs = PassiveTimeManager.getCurrentSessionElapsedMs()
            val timerStr = PassiveTimeManager.formatTimerDigits(currentSessionMs)
            if (isExceeded) {
                "⚠️ 📺 被动超标 $timerStr"
            } else {
                "📺 被动中 $timerStr"
            }
        } else if (currentTargetMinutes > 0) {
            val formatted = formatTime(Math.abs(lastRemainingSeconds))
            val focusPrefix = if (isFocusTimeoutState) "⚠️ 超时 $formatted" else "🎯 $formatted"
            val totalMinutes = todayTotalMs / 1000L / 60L
            if (totalMinutes > 0) {
                "$focusPrefix | 📺 ${totalMinutes}m"
            } else {
                focusPrefix
            }
        } else {
            val totalMinutes = todayTotalMs / 1000L / 60L
            if (isExceeded) {
                val readable = PassiveTimeManager.formatDurationReadable(todayTotalMs)
                "⚠️ 📺 超标 $readable"
            } else if (totalMinutes > 0) {
                "📺 ${totalMinutes}m / 1.5h"
            } else {
                "📺 被动计时"
            }
        }
    }

    private fun buildPassiveStatsText(): String {
        val todayTotalMs = PassiveTimeManager.getTodayTotalPassiveMs(context)
        val readableUsed = PassiveTimeManager.formatDurationReadable(todayTotalMs)
        val isExceeded = PassiveTimeManager.isTodayExceeded(context)
        val remainingMs = PassiveTimeManager.getTodayRemainingMs(context)

        return if (isExceeded) {
            val overMs = todayTotalMs - PassiveTimeManager.TARGET_DAILY_PASSIVE_MS
            val readableOver = PassiveTimeManager.formatDurationReadable(overMs)
            "⚠️ 已用 $readableUsed (超标 $readableOver)！建议放下手机。"
        } else {
            val readableRem = PassiveTimeManager.formatDurationReadable(remainingMs)
            "今日已用：$readableUsed / 1.5h (还剩 $readableRem)"
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
            passiveTitleTv = null
            passiveStatsTv = null
            passiveProgressBar = null
            passiveToggleBtn = null
            intentSectionLayout = null
            intentDetailTextView = null
            timerDetailTextView = null
            extendBtn = null
            isExpanded = false
            isFocusTimeoutState = false
        } catch (_: Exception) {}
    }

    private enum class PillStyle {
        NORMAL, ACTIVE_PASSIVE, TIMEOUT
    }

    private fun createPillBackground(style: PillStyle): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 50f
            when (style) {
                PillStyle.TIMEOUT -> {
                    setColor(Color.parseColor("#E6C62828")) // 醒目半透橙红
                    setStroke(2, Color.parseColor("#FF8A80"))
                }
                PillStyle.ACTIVE_PASSIVE -> {
                    setColor(Color.parseColor("#E61B5E20")) // 醒目半透深绿/青绿
                    setStroke(2, Color.parseColor("#81C784"))
                }
                PillStyle.NORMAL -> {
                    setColor(Color.parseColor("#E6212121")) // 高级暗色半透
                    setStroke(2, Color.parseColor("#424242"))
                }
            }
        }
    }

    private fun createCardBackground(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 28f
            setColor(Color.parseColor("#F21E1E1E"))
            setStroke(2, Color.parseColor("#424242"))
        }
    }

    private fun createButtonBackground(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 20f
            setColor(color)
        }
    }

    private fun createProgressDrawable(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 10f
            setColor(Color.parseColor("#4CAF50"))
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
