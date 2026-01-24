package com.bjornfree.drivemode.ui.theme

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.bjornfree.drivemode.R

class ModePanelOverlayController(
    private val appContext: Context
) {

    private val prefs = appContext.getSharedPreferences("mode_panel_overlay", Context.MODE_PRIVATE)

    companion object {
        private var currentInstance: ModePanelOverlayController? = null

        private const val PREF_GRAVITY = "gravity"
        private const val PREF_X = "x"
        private const val PREF_Y = "y"
        private const val PREF_ENABLED = "enabled"

        private const val PANEL_BG_COLOR = 0xE6000000.toInt()
        private const val PANEL_STROKE_COLOR_DEFAULT = 0xFFFFFFFF.toInt()
    }

    private val wm: WindowManager =
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val density: Float = appContext.resources.displayMetrics.density
    private val screenWidth: Int = appContext.resources.displayMetrics.widthPixels

    private var overlayEnabled: Boolean = prefs.getBoolean(PREF_ENABLED, true)

    private var layoutParams: WindowManager.LayoutParams? = null

    private var initialX: Int = 0
    private var initialY: Int = 0
    private var initialTouchX: Float = 0f
    private var initialTouchY: Float = 0f

    private var container: FrameLayout? = null
    private var panelRoot: LinearLayout? = null
    private var iconContainer: FrameLayout? = null
    private var tvLetter: TextView? = null
    private var tvTitle: TextView? = null
    private var tvSubtitle: TextView? = null
    private var extendedContainer: LinearLayout? = null

    private var currentState: PanelState = PanelState.COMPACT

    private var autoCollapseRunnable: Runnable? = null

    private val modeColors: Map<String, Int> = mapOf(
        "eco" to 0xFF4CAF50.toInt(),
        "comfort" to 0xFF2196F3.toInt(),
        "sport" to 0xFFFF1744.toInt(),
        "adaptive" to 0xFF7E57C2.toInt()
    )

    private val modeSubtitles: Map<String, String> = mapOf(
        "eco" to "Экономичный расход",
        "sport" to "Максимальная отзывчивость",
        "comfort" to "Сбалансированный режим",
        "adaptive" to "Режим подстраивается под стиль"
    )

    private val modeIconRes: Map<String, Int> = mapOf(
        "eco" to R.drawable.eco,
        "comfort" to R.drawable.comfort,
        "sport" to R.drawable.sport,
        "adaptive" to R.drawable.adaptive
    )

    private enum class PanelState {
        COMPACT,
        EXPANDED
    }

    init {
        currentInstance?.destroy()
        currentInstance = this
    }

    fun isOverlayEnabled(): Boolean = overlayEnabled

    fun setOverlayEnabled(enabled: Boolean) {
        overlayEnabled = enabled
        prefs.edit()
            .putBoolean(PREF_ENABLED, enabled)
            .apply()

        if (!enabled) {
            destroy()
        } else {
            showCompact()
        }
    }

    fun showMode(mode: String) {
        if (!overlayEnabled) return
        ensureAttached()

        container?.visibility = View.VISIBLE

        val modeLower = mode.lowercase()
        val baseColor = modeColors[modeLower] ?: PANEL_STROKE_COLOR_DEFAULT

        val letter = when (modeLower) {
            "eco" -> "E"
            "sport" -> "S"
            "comfort" -> "C"
            "adaptive" -> "A"
            else -> mode.take(1).uppercase()
        }
        val title = when (modeLower) {
            "eco" -> "ECO"
            "sport" -> "SPORT"
            "comfort" -> "COMFORT"
            "adaptive" -> "ADAPTIVE"
            else -> mode.uppercase()
        }
        val subtitle = modeSubtitles[modeLower] ?: ""

        tvLetter?.text = letter
        tvTitle?.text = title
        tvSubtitle?.text = subtitle

        val iconResId = modeIconRes[modeLower]
        if (iconResId != null) {
            iconContainer?.background = ContextCompat.getDrawable(appContext, iconResId)
            tvLetter?.visibility = View.GONE
        } else {
            tvLetter?.visibility = View.VISIBLE
            val iconBg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(baseColor)
                setStroke((2f * density).toInt(), PANEL_STROKE_COLOR_DEFAULT)
            }
            iconContainer?.background = iconBg
        }

        panelRoot?.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 24f * density
            setColor(PANEL_BG_COLOR)
            setStroke((2f * density).toInt(), baseColor)
        }

        expandPanel()

        val root = panelRoot ?: return
        autoCollapseRunnable?.let { root.removeCallbacks(it) }
        autoCollapseRunnable = Runnable {
            collapsePanel()
        }
        root.postDelayed(autoCollapseRunnable!!, 3000L)
    }

    fun collapsePanel() {
        if (currentState == PanelState.COMPACT) return

        currentState = PanelState.COMPACT
        val ext = extendedContainer ?: return

        ext.animate()
            .alpha(0f)
            .setDuration(200L)
            .withEndAction {
                ext.visibility = View.GONE
            }
            .start()
    }

    fun hide() {
        val root = panelRoot
        autoCollapseRunnable?.let { root?.removeCallbacks(it) }
        autoCollapseRunnable = null
        container?.visibility = View.GONE
    }

    fun showCompact(initialMode: String = "comfort") {
        showMode(initialMode)
        collapsePanel()
    }

    fun destroy() {
        val root = panelRoot
        autoCollapseRunnable?.let { runnable ->
            root?.removeCallbacks(runnable)
        }
        autoCollapseRunnable = null

        val c = container

        layoutParams = null
        container = null
        panelRoot = null
        iconContainer = null
        tvLetter = null
        tvTitle = null
        tvSubtitle = null
        extendedContainer = null

        if (c != null) {
            try {
                wm.removeView(c)
            } catch (_: Throwable) {
            }
        }

        if (currentInstance === this) {
            currentInstance = null
        }
    }

    private fun expandPanel() {
        if (currentState == PanelState.EXPANDED) return
        currentState = PanelState.EXPANDED

        val ext = extendedContainer ?: return

        ext.visibility = View.VISIBLE
        ext.alpha = 0f
        ext.translationX = 6f * density
        ext.translationY = 4f * density
        ext.scaleX = 0.9f
        ext.scaleY = 0.9f

        ext.animate()
            .alpha(1f)
            .translationX(0f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(220L)
            .start()
    }

    private fun ensureAttached() {
        if (container != null && panelRoot != null && container?.windowToken != null) {
            return
        }
        if (!overlayEnabled) return

        // Check if overlay permission is granted
        if (!Settings.canDrawOverlays(appContext)) {
            return
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            val defaultGravity = Gravity.BOTTOM or Gravity.END
            val margin = (12f * density).toInt()
            val defaultX = margin
            val defaultY = margin

            gravity = prefs.getInt(PREF_GRAVITY, defaultGravity)
            x = prefs.getInt(PREF_X, defaultX)
            y = prefs.getInt(PREF_Y, defaultY)
        }

        layoutParams = lp

        val rootContainer = FrameLayout(appContext).apply {
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val panel = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(
                (8f * density).toInt(),
                (8f * density).toInt(),
                (12f * density).toInt(),
                (8f * density).toInt()
            )
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 24f * density
                setColor(PANEL_BG_COLOR)
            }
        }

        val iconSize = (56f * density).toInt()
        val iconFrame = FrameLayout(appContext).apply {
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
        }

        val letterView = TextView(appContext).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            text = "C"
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        iconFrame.addView(letterView)

        val extended = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = (8f * density).toInt()
            }
        }

        val titleView = TextView(appContext).apply {
            text = "COMFORT"
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        }

        val subtitleView = TextView(appContext).apply {
            text = "Сбалансированный режим"
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 14f
        }

        extended.addView(titleView)
        extended.addView(subtitleView)
        extended.visibility = View.GONE

        panel.addView(iconFrame)
        panel.addView(extended)

        rootContainer.addView(panel)

        try {
            wm.addView(rootContainer, layoutParams ?: lp)
        } catch (e: Exception) {
            // Failed to add overlay - likely permission issue
            android.util.Log.e("ModePanelOverlay", "Failed to add overlay", e)
            return
        }

        container = rootContainer
        panelRoot = panel
        iconContainer = iconFrame
        tvLetter = letterView
        tvTitle = titleView
        tvSubtitle = subtitleView
        extendedContainer = extended

        panel.setOnTouchListener { _, event ->
            val lpCurrent = layoutParams ?: return@setOnTouchListener false
            val rootView = container ?: return@setOnTouchListener false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = lpCurrent.x
                    initialY = lpCurrent.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()

                    val isStart = (lpCurrent.gravity and Gravity.START) == Gravity.START
                    val isEnd = (lpCurrent.gravity and Gravity.END) == Gravity.END

                    lpCurrent.x = when {
                        isStart -> initialX + dx
                        isEnd -> initialX - dx
                        else -> initialX - dx
                    }

                    lpCurrent.y = initialY - dy

                    layoutParams = lpCurrent
                    wm.updateViewLayout(rootView, lpCurrent)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val lpNow = layoutParams ?: return@setOnTouchListener false
                    val rootViewNow = container ?: return@setOnTouchListener false

                    val rootWidth = rootViewNow.width
                    if (rootWidth > 0 && screenWidth > 0) {
                        val isEndGravity = (lpNow.gravity and Gravity.END) == Gravity.END
                        val absoluteLeft = if (isEndGravity) {
                            screenWidth - lpNow.x - rootWidth
                        } else {
                            lpNow.x
                        }

                        val panelCenterX = absoluteLeft + rootWidth / 2f
                        val stickToLeft = panelCenterX < screenWidth / 2f

                        if (stickToLeft) {
                            lpNow.gravity = Gravity.BOTTOM or Gravity.START
                            lpNow.x = absoluteLeft.coerceAtLeast(0)
                        } else {
                            lpNow.gravity = Gravity.BOTTOM or Gravity.END
                            val fromRight = (screenWidth - absoluteLeft - rootWidth).coerceAtLeast(0)
                            lpNow.x = fromRight
                        }

                        layoutParams = lpNow
                        wm.updateViewLayout(rootViewNow, lpNow)

                        prefs.edit()
                            .putInt(PREF_GRAVITY, lpNow.gravity)
                            .putInt(PREF_X, lpNow.x)
                            .putInt(PREF_Y, lpNow.y)
                            .apply()
                    }

                    true
                }
                else -> false
            }
        }
    }
}

data class DrivingStatusOverlayState(
    val modeTitle: String? = null,
    val gear: String? = null,
    val speedKmh: Int? = null,
    val rangeKm: Int? = null,
    val cabinTempC: Float? = null,
    val ambientTempC: Float? = null,
    val tirePressureFrontLeft: Int? = null,
    val tirePressureFrontRight: Int? = null,
    val tirePressureRearLeft: Int? = null,
    val tirePressureRearRight: Int? = null
)

/**
 * Callback для изменения настроек overlay из UI.
 */
interface OverlaySettingsCallback {
    fun onHorizontalPaddingChanged(padding: Int)
    fun onHeightChanged(height: Int)
    fun onPositionChanged(position: String)  // "top" или "bottom"
    fun onMetricVisibilityChanged(metric: String, visible: Boolean)
}

class DrivingStatusOverlayController(
    private val appContext: Context,
    initialPosition: String = "bottom",
    private val settingsCallback: OverlaySettingsCallback? = null
) {

    companion object {
        @Volatile
        private var currentInstance: DrivingStatusOverlayController? = null

        /**s
         * Получить текущий активный instance.
         * ВСЕГДА используй этот метод вместо прямого обращения к переменной.
         */
        fun getInstance(): DrivingStatusOverlayController? = currentInstance

        // Glass morphism colors (iOS 26 style)
        private const val GLASS_BG_DARK = 0x66000000  // 40% черный
        private const val GLASS_BG_LIGHT = 0x66FFFFFF  // 40% белый
        private const val GLASS_BORDER_DARK = 0x33FFFFFF  // 20% белый border
        private const val GLASS_BORDER_LIGHT = 0x33000000  // 20% черный border

        private const val COLOR_TEXT_DARK_PRIMARY = 0xFFFFFFFF.toInt()
        private const val COLOR_TEXT_DARK_SECONDARY = 0xCCFFFFFF.toInt()
        private const val COLOR_TEXT_LIGHT_PRIMARY = 0xFF1E1E1E.toInt()
        private const val COLOR_TEXT_LIGHT_SECONDARY = 0xFF666666.toInt()

        private const val COLOR_MODE_SPORT = 0xFFFF1744.toInt()
        private const val COLOR_MODE_ECO = 0xFF4CAF50.toInt()
        private const val COLOR_MODE_COMFORT = 0xFF2196F3.toInt()
        private const val COLOR_MODE_ADAPTIVE = 0xFF9C27B0.toInt()

        // Temp colors (dark theme)
        private const val TEMP_DARK_VERY_COLD = 0xFF00E5FF.toInt()
        private const val TEMP_DARK_COLD = 0xFF40C4FF.toInt()
        private const val TEMP_DARK_COOL = 0xFF00E676.toInt()
        private const val TEMP_DARK_COMFORT = 0xFFFFAB00.toInt()
        private const val TEMP_DARK_WARM = 0xFFFF6D00.toInt()
        private const val TEMP_DARK_HOT = 0xFFFF1744.toInt()

        // Temp colors (light theme)
        private const val TEMP_LIGHT_VERY_COLD = 0xFF00838F.toInt()
        private const val TEMP_LIGHT_COLD = 0xFF0277BD.toInt()
        private const val TEMP_LIGHT_COOL = 0xFF00897B.toInt()
        private const val TEMP_LIGHT_COMFORT = 0xFFEF6C00.toInt()
        private const val TEMP_LIGHT_WARM = 0xFFD84315.toInt()
        private const val TEMP_LIGHT_HOT = 0xFFC62828.toInt()

        // Tire colors (dark theme)
        private const val TIRE_DARK_LOW = 0xFFFF1744.toInt()
        private const val TIRE_DARK_HIGH = 0xFFFFAB00.toInt()
        private const val TIRE_DARK_NORMAL = 0xFF00E676.toInt()

        // Tire colors (light theme)
        private const val TIRE_LIGHT_LOW = 0xFFC62828.toInt()
        private const val TIRE_LIGHT_HIGH = 0xFFEF6C00.toInt()
        private const val TIRE_LIGHT_NORMAL = 0xFF00897B.toInt()

        private const val LONG_PRESS_HIDE_TIMEOUT_MS = 1000L
        private const val TEMP_HIDE_DURATION_MS = 5000L
    }
    private fun ensureMainThread() {
        check(Looper.getMainLooper().thread == Thread.currentThread()) {
            "DrivingStatusOverlayController must be used from Main thread"
        }
    }

    /**
     * Removes the overlay view from WindowManager and clears view references.
     * Does NOT touch currentInstance.
     */
    private fun detachView() {
        val c = container
        if (c != null) {
            try {
                try {
                    wm.removeView(c)
                } catch (_: Throwable) {
                    wm.removeViewImmediate(c)
                }
            } catch (_: Throwable) {
            }
        }

        layoutParams = null
        container = null
        rootRow = null
        tvMode = null
        tvGear = null
        tvSpeed = null
        tvRange = null
        tvTemps = null
        tvTires = null
        attached = false
    }
    private fun hideTemporarily() {
        val c = container ?: return

        c.visibility = View.GONE

        // Вернем панель через TEMP_HIDE_DURATION_MS, если она все еще включена
        c.postDelayed({
            if (enabled && container === c) {
                c.visibility = View.VISIBLE
            }
        }, TEMP_HIDE_DURATION_MS)
    }

    private fun setupLongPressToHide(targetView: View) {
        var longPressRunnable: Runnable? = null
        var longPressTriggered = false

        // GestureDetector для обработки двойного тапа
        val gestureDetector = android.view.GestureDetector(
            appContext,
            object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    // Двойной тап - запускаем приложение
                    try {
                        val intent = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)
                        intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        appContext.startActivity(intent)
                        android.util.Log.i("DrivingStatusOverlay", "Двойной тап: запуск приложения")
                    } catch (e: Exception) {
                        android.util.Log.e("DrivingStatusOverlay", "Ошибка запуска приложения", e)
                    }
                    return true
                }
            }
        )

        targetView.setOnTouchListener { v, event ->
            // Сначала проверяем двойной тап через GestureDetector
            val gestureHandled = gestureDetector.onTouchEvent(event)

            // Обработка долгого нажатия
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    longPressTriggered = false
                    longPressRunnable?.let { v.removeCallbacks(it) }

                    longPressRunnable = Runnable {
                        longPressTriggered = true
                        showSettingsOverlay()
                    }
                    v.postDelayed(longPressRunnable!!, LONG_PRESS_HIDE_TIMEOUT_MS)
                    true
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    longPressRunnable?.let { v.removeCallbacks(it) }
                    val consumed = longPressTriggered || gestureHandled
                    longPressRunnable = null
                    consumed
                }
                else -> gestureHandled
            }
        }
    }

    private val wm: WindowManager =
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val density: Float = appContext.resources.displayMetrics.density

    private var layoutParams: WindowManager.LayoutParams? = null
    private var container: FrameLayout? = null
    private var rootRow: LinearLayout? = null

    // По умолчанию не показываем overlay, пока внешний слой (Service/ViewModel)
    // явно не применит настройку из prefs через setEnabled().
    // Это предотвращает ситуацию: разрешение на overlay выдали → следующий updateStatus() прикрепляет view,
    // даже если ползунок в настройках был выключен.
    private var enabled: Boolean = false

    // Флаг: была ли хотя бы один раз применена настройка enabled из внешнего слоя.
    private var enabledInitialized: Boolean = false

    private var tvMode: TextView? = null
    private var tvGear: TextView? = null
    private var tvSpeed: TextView? = null
    private var tvRange: TextView? = null
    private var tvTemps: TextView? = null
    private var tvTires: TextView? = null

    private var attached: Boolean = false

    private var position: String = initialPosition
    private var height: Int = 56  // Высота в dp (по умолчанию 56dp)
    private var horizontalPadding: Int = 0   // Горизонтальный отступ от краёв в dp (уменьшает ширину)

    private var isDarkTheme: Boolean = true
    private var bgColor: Int = GLASS_BG_DARK
    private var textColor: Int = COLOR_TEXT_DARK_PRIMARY
    private var textSecondaryColor: Int = COLOR_TEXT_DARK_SECONDARY

    // Видимость отдельных метрик (по умолчанию все включены)
    private var showRange: Boolean = true
    private var showGear: Boolean = true
    private var showSpeed: Boolean = true
    private var showMode: Boolean = true
    private var showTemps: Boolean = true
    private var showTires: Boolean = true

    // Settings overlay (показывается по long press)
    private var settingsContainer: FrameLayout? = null
    private var settingsLayoutParams: WindowManager.LayoutParams? = null
    private var settingsVisible: Boolean = false
    private var paddingSeekBar: SeekBar? = null
    private var paddingValueText: TextView? = null
    private var heightSeekBar: SeekBar? = null
    private var heightValueText: TextView? = null
    private var autoHideSettingsRunnable: Runnable? = null
    private val SETTINGS_AUTO_HIDE_MS = 10000L  // Авто-скрытие через 10 секунд бездействия

    /**
     * Вычисляет размер текста в зависимости от высоты плашки.
     * Для компактного режима (40dp) уменьшаем текст на ~30%,
     * для увеличенного (70dp) увеличиваем на ~15%.
     */
    private fun calculateTextSize(baseSize: Float): Float {
        return when {
            height <= 40 -> baseSize * 0.70f  // Компакт: -30%
            height >= 70 -> baseSize * 1.15f  // Большая: +15%
            else -> baseSize                   // Стандарт: без изменений
        }
    }

    init {
        android.util.Log.w("DrivingStatusOverlay", ">>> INIT: Создание нового instance (позиция: $position)")
        if (currentInstance != null) {
            android.util.Log.w("DrivingStatusOverlay", ">>> INIT: Найден предыдущий instance, уничтожаем...")
            currentInstance?.destroy()
        }
        currentInstance = this
        android.util.Log.w("DrivingStatusOverlay", ">>> INIT: currentInstance обновлен")
    }

    fun setEnabled(isEnabled: Boolean) {
        ensureMainThread()
        android.util.Log.w("DrivingStatusOverlay", ">>> setEnabled: вызван (текущее=$enabled, новое=$isEnabled)")
        enabledInitialized = true
        if (enabled == isEnabled) {
            android.util.Log.w("DrivingStatusOverlay", ">>> setEnabled: состояние не изменилось, return")
            return
        }
        enabled = isEnabled
        if (!enabled) {
            android.util.Log.w("DrivingStatusOverlay", ">>> setEnabled: отключаем (detach view, instance сохраняем)")
            detachView()
            android.util.Log.w("DrivingStatusOverlay", ">>> setEnabled: отключено, но currentInstance сохранен")
        } else {
            android.util.Log.w("DrivingStatusOverlay", ">>> setEnabled: включено")
        }
    }

    fun ensureVisible() {
        ensureMainThread()
        if (!enabledInitialized || !enabled) return
        ensureAttached()
    }

    fun setDarkTheme(dark: Boolean) {
        ensureMainThread()
        if (isDarkTheme == dark) return

        isDarkTheme = dark
        if (dark) {
            bgColor = GLASS_BG_DARK
            textColor = COLOR_TEXT_DARK_PRIMARY
            textSecondaryColor = COLOR_TEXT_DARK_SECONDARY
        } else {
            bgColor = GLASS_BG_LIGHT
            textColor = COLOR_TEXT_LIGHT_PRIMARY
            textSecondaryColor = COLOR_TEXT_LIGHT_SECONDARY
        }

        rootRow?.background = createBackgroundDrawable()
        tvMode?.setTextColor(textColor)
        tvGear?.setTextColor(textColor)
        tvSpeed?.setTextColor(textColor)
        tvRange?.setTextColor(textColor)
        tvTemps?.setTextColor(textSecondaryColor)
        tvTires?.setTextColor(textSecondaryColor)
    }

    fun setPosition(newPosition: String) {
        ensureMainThread()
        android.util.Log.w("DrivingStatusOverlay", ">>> setPosition: вызван (текущая=$position, новая=$newPosition)")

        if (position == newPosition) {
            android.util.Log.w("DrivingStatusOverlay", ">>> setPosition: позиция не изменилась, return")
            return
        }

        val wasAttached = attached
        android.util.Log.w("DrivingStatusOverlay", ">>> setPosition: wasAttached=$wasAttached")

        // Detach existing view (do NOT destroy instance)
        detachView()

        position = newPosition
        android.util.Log.w("DrivingStatusOverlay", ">>> setPosition: позиция обновлена на $position")

        if (wasAttached) {
            android.util.Log.w("DrivingStatusOverlay", ">>> setPosition: пересоздаем overlay")
            ensureAttached()
        }
    }

    fun setHeight(newHeight: Int) {
        ensureMainThread()
        android.util.Log.w("DrivingStatusOverlay", ">>> setHeight: вызван (текущая=$height, новая=$newHeight)")

        if (height == newHeight) {
            android.util.Log.w("DrivingStatusOverlay", ">>> setHeight: высота не изменилась, return")
            return
        }

        val wasAttached = attached
        android.util.Log.w("DrivingStatusOverlay", ">>> setHeight: wasAttached=$wasAttached")

        // Detach existing view (do NOT destroy instance)
        detachView()

        height = newHeight
        android.util.Log.w("DrivingStatusOverlay", ">>> setHeight: высота обновлена на $height dp")

        if (wasAttached) {
            android.util.Log.w("DrivingStatusOverlay", ">>> setHeight: пересоздаем overlay")
            ensureAttached()
        }
    }

    fun setHorizontalPadding(newPadding: Int) {
        ensureMainThread()
        android.util.Log.w("DrivingStatusOverlay", ">>> setHorizontalPadding: вызван (текущая=$horizontalPadding, новая=$newPadding)")

        if (horizontalPadding == newPadding) {
            android.util.Log.w("DrivingStatusOverlay", ">>> setHorizontalPadding: отступ не изменился, return")
            return
        }

        horizontalPadding = newPadding
        android.util.Log.w("DrivingStatusOverlay", ">>> setHorizontalPadding: отступ обновлен на $horizontalPadding dp")

        // Обновляем ширину панели если уже прикреплены
        updateLayoutWidth()
    }

    private fun updateLayoutWidth() {
        val lp = layoutParams ?: return
        val c = container ?: return

        val screenWidth = appContext.resources.displayMetrics.widthPixels
        val paddingPx = (horizontalPadding * density).toInt()
        val newWidth = screenWidth - (paddingPx * 2)

        lp.width = newWidth.coerceAtLeast((200 * density).toInt())  // Минимум 200dp
        try {
            wm.updateViewLayout(c, lp)
            android.util.Log.w("DrivingStatusOverlay", ">>> updateLayoutWidth: width=${lp.width}px, padding=${paddingPx}px")
        } catch (e: Throwable) {
            android.util.Log.w("DrivingStatusOverlay", ">>> updateLayoutWidth: ошибка - ${e.message}")
        }
    }

    /**
     * Устанавливает видимость конкретной метрики.
     */
    fun setMetricVisibility(metric: String, visible: Boolean) {
        ensureMainThread()
        when (metric) {
            "range" -> showRange = visible
            "gear" -> showGear = visible
            "speed" -> showSpeed = visible
            "mode" -> showMode = visible
            "temps" -> showTemps = visible
            "tires" -> showTires = visible
        }
        updateMetricsVisibility()
    }

    /**
     * Устанавливает видимость всех метрик сразу.
     */
    fun setAllMetricsVisibility(
        range: Boolean, gear: Boolean, speed: Boolean,
        mode: Boolean, temps: Boolean, tires: Boolean
    ) {
        ensureMainThread()
        showRange = range
        showGear = gear
        showSpeed = speed
        showMode = mode
        showTemps = temps
        showTires = tires
        updateMetricsVisibility()
    }

    private fun updateMetricsVisibility() {
        // Скрываем/показываем и перераспределяем weight
        val visibleCount = listOf(showRange, showGear, showSpeed, showMode, showTemps, showTires).count { it }
        if (visibleCount == 0) return

        val baseWeight = 1f

        tvRange?.let { tv ->
            tv.visibility = if (showRange) View.VISIBLE else View.GONE
            (tv.layoutParams as? LinearLayout.LayoutParams)?.weight = if (showRange) baseWeight else 0f
        }
        tvGear?.let { tv ->
            tv.visibility = if (showGear) View.VISIBLE else View.GONE
            (tv.layoutParams as? LinearLayout.LayoutParams)?.weight = if (showGear) baseWeight * 0.5f else 0f
        }
        tvSpeed?.let { tv ->
            tv.visibility = if (showSpeed) View.VISIBLE else View.GONE
            (tv.layoutParams as? LinearLayout.LayoutParams)?.weight = if (showSpeed) baseWeight * 1.1f else 0f
        }
        tvMode?.let { tv ->
            tv.visibility = if (showMode) View.VISIBLE else View.GONE
            (tv.layoutParams as? LinearLayout.LayoutParams)?.weight = if (showMode) baseWeight * 0.9f else 0f
        }
        tvTemps?.let { tv ->
            tv.visibility = if (showTemps) View.VISIBLE else View.GONE
            (tv.layoutParams as? LinearLayout.LayoutParams)?.weight = if (showTemps) baseWeight * 1.2f else 0f
        }
        tvTires?.let { tv ->
            tv.visibility = if (showTires) View.VISIBLE else View.GONE
            (tv.layoutParams as? LinearLayout.LayoutParams)?.weight = if (showTires) baseWeight * 1.5f else 0f
        }

        rootRow?.requestLayout()
    }

    // ========== Settings Overlay ==========

    private fun showSettingsOverlay() {
        if (settingsVisible) {
            hideSettingsOverlay()
            return
        }

        if (!Settings.canDrawOverlays(appContext)) return

        android.util.Log.w("DrivingStatusOverlay", ">>> showSettingsOverlay: показываем настройки")

        val panelHeight = (320f * density).toInt()  // Увеличили для всех элементов
        val panelWidth = (300f * density).toInt()

        val lp = WindowManager.LayoutParams(
            panelWidth,
            panelHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        settingsLayoutParams = lp

        val rootContainer = FrameLayout(appContext).apply {
            setBackgroundColor(Color.TRANSPARENT)
        }

        // ScrollView для прокрутки если не влезает
        val scrollView = ScrollView(appContext).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            isVerticalScrollBarEnabled = false
        }

        val panel = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )

            val padding = (16f * density).toInt()
            setPadding(padding, padding, padding, padding)

            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 20f * density
                setColor(if (isDarkTheme) 0xF0000000.toInt() else 0xF0FFFFFF.toInt())
                setStroke(
                    (1f * density).toInt(),
                    if (isDarkTheme) GLASS_BORDER_DARK else GLASS_BORDER_LIGHT
                )
            }
        }

        val textPrimary = if (isDarkTheme) COLOR_TEXT_DARK_PRIMARY else COLOR_TEXT_LIGHT_PRIMARY
        val textSecondary = if (isDarkTheme) COLOR_TEXT_DARK_SECONDARY else COLOR_TEXT_LIGHT_SECONDARY

        // ===== ВЫСОТА ПАНЕЛИ =====
        val heightTitle = TextView(appContext).apply {
            text = "Высота панели"
            setTextColor(textPrimary)
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        panel.addView(heightTitle)

        val heightValue = TextView(appContext).apply {
            text = "${height} dp"
            setTextColor(textSecondary)
            textSize = 12f
        }
        heightValueText = heightValue
        panel.addView(heightValue)

        val heightSeek = SeekBar(appContext).apply {
            min = 40
            max = 80
            progress = height
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        heightValueText?.text = "$progress dp"
                        setHeight(progress)
                        settingsCallback?.onHeightChanged(progress)
                        resetAutoHideTimer()
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) { resetAutoHideTimer() }
                override fun onStopTrackingTouch(seekBar: SeekBar?) { resetAutoHideTimer() }
            })
        }
        heightSeekBar = heightSeek
        panel.addView(heightSeek)

        // ===== ПОЗИЦИЯ =====
        val positionTitle = TextView(appContext).apply {
            text = "Позиция"
            setTextColor(textPrimary)
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            val marginTop = (12f * density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = marginTop }
        }
        panel.addView(positionTitle)

        val positionRow = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val btnBottom = TextView(appContext).apply {
            text = if (position == "bottom") "● СНИЗУ" else "○ Снизу"
            setTextColor(if (position == "bottom") 0xFF4CAF50.toInt() else textSecondary)
            textSize = 13f
            typeface = if (position == "bottom") Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            gravity = Gravity.CENTER
            val paddingH = (16f * density).toInt()
            val paddingV = (8f * density).toInt()
            setPadding(paddingH, paddingV, paddingH, paddingV)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                if (position != "bottom") {
                    setPosition("bottom")
                    settingsCallback?.onPositionChanged("bottom")
                    // Обновляем UI кнопок
                    text = "● СНИЗУ"
                    setTextColor(0xFF4CAF50.toInt())
                    typeface = Typeface.DEFAULT_BOLD
                    (positionRow.getChildAt(1) as? TextView)?.apply {
                        text = "○ Сверху"
                        setTextColor(textSecondary)
                        typeface = Typeface.DEFAULT
                    }
                    resetAutoHideTimer()
                }
            }
        }

        val btnTop = TextView(appContext).apply {
            text = if (position == "top") "● СВЕРХУ" else "○ Сверху"
            setTextColor(if (position == "top") 0xFF4CAF50.toInt() else textSecondary)
            textSize = 13f
            typeface = if (position == "top") Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            gravity = Gravity.CENTER
            val paddingH = (16f * density).toInt()
            val paddingV = (8f * density).toInt()
            setPadding(paddingH, paddingV, paddingH, paddingV)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                if (position != "top") {
                    setPosition("top")
                    settingsCallback?.onPositionChanged("top")
                    // Обновляем UI кнопок
                    text = "● СВЕРХУ"
                    setTextColor(0xFF4CAF50.toInt())
                    typeface = Typeface.DEFAULT_BOLD
                    (positionRow.getChildAt(0) as? TextView)?.apply {
                        text = "○ Снизу"
                        setTextColor(textSecondary)
                        typeface = Typeface.DEFAULT
                    }
                    resetAutoHideTimer()
                }
            }
        }

        positionRow.addView(btnBottom)
        positionRow.addView(btnTop)
        panel.addView(positionRow)

        // ===== БОКОВЫЕ ОТСТУПЫ =====
        val paddingTitle = TextView(appContext).apply {
            text = "Боковые отступы"
            setTextColor(textPrimary)
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            val marginTop = (12f * density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = marginTop }
        }
        panel.addView(paddingTitle)

        val paddingValue = TextView(appContext).apply {
            text = "${horizontalPadding} dp"
            setTextColor(textSecondary)
            textSize = 12f
        }
        paddingValueText = paddingValue
        panel.addView(paddingValue)

        val paddingSeek = SeekBar(appContext).apply {
            max = 200
            progress = horizontalPadding
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        paddingValueText?.text = "$progress dp"
                        setHorizontalPadding(progress)
                        settingsCallback?.onHorizontalPaddingChanged(progress)
                        resetAutoHideTimer()
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) { resetAutoHideTimer() }
                override fun onStopTrackingTouch(seekBar: SeekBar?) { resetAutoHideTimer() }
            })
        }
        paddingSeekBar = paddingSeek
        panel.addView(paddingSeek)

        // ===== МЕТРИКИ (чекбоксы) =====
        val metricsTitle = TextView(appContext).apply {
            text = "Метрики"
            setTextColor(textPrimary)
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            val marginTop = (12f * density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = marginTop }
        }
        panel.addView(metricsTitle)

        // Контейнер для чекбоксов в 2 ряда
        val checkboxRow1 = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val checkboxRow2 = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Создаём чекбоксы
        fun createMetricCheckbox(label: String, metric: String, checked: Boolean): CheckBox {
            return CheckBox(appContext).apply {
                text = label
                isChecked = checked
                setTextColor(textSecondary)
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
                setOnCheckedChangeListener { _, isChecked ->
                    setMetricVisibility(metric, isChecked)
                    settingsCallback?.onMetricVisibilityChanged(metric, isChecked)
                    resetAutoHideTimer()
                }
            }
        }

        // Ряд 1: КМ, ПЕР, СКР
        checkboxRow1.addView(createMetricCheckbox("КМ", "range", showRange))
        checkboxRow1.addView(createMetricCheckbox("ПЕР", "gear", showGear))
        checkboxRow1.addView(createMetricCheckbox("СКР", "speed", showSpeed))

        // Ряд 2: РЕЖ, °C, ШИН
        checkboxRow2.addView(createMetricCheckbox("РЕЖ", "mode", showMode))
        checkboxRow2.addView(createMetricCheckbox("°C", "temps", showTemps))
        checkboxRow2.addView(createMetricCheckbox("ШИН", "tires", showTires))

        panel.addView(checkboxRow1)
        panel.addView(checkboxRow2)

        // ===== КНОПКА ЗАКРЫТЬ =====
        val closeBtn = TextView(appContext).apply {
            text = "[ ЗАКРЫТЬ ]"
            setTextColor(if (isDarkTheme) 0xFFFF6B6B.toInt() else 0xFFD32F2F.toInt())
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            val marginTop = (16f * density).toInt()
            val paddingV = (10f * density).toInt()
            setPadding(0, paddingV, 0, paddingV)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = marginTop }
            setOnClickListener { hideSettingsOverlay() }
        }
        panel.addView(closeBtn)

        scrollView.addView(panel)
        rootContainer.addView(scrollView)

        // Обработка тапа вне панели
        rootContainer.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                hideSettingsOverlay()
                true
            } else {
                resetAutoHideTimer()
                false
            }
        }

        try {
            wm.addView(rootContainer, lp)
            settingsContainer = rootContainer
            settingsVisible = true
            startAutoHideTimer()
            android.util.Log.w("DrivingStatusOverlay", ">>> showSettingsOverlay: настройки показаны")
        } catch (e: Exception) {
            android.util.Log.e("DrivingStatusOverlay", ">>> showSettingsOverlay: ошибка", e)
        }
    }

    private fun hideSettingsOverlay() {
        autoHideSettingsRunnable?.let { settingsContainer?.removeCallbacks(it) }
        autoHideSettingsRunnable = null

        val c = settingsContainer
        if (c != null) {
            try {
                wm.removeView(c)
            } catch (_: Throwable) {}
        }
        settingsContainer = null
        settingsLayoutParams = null
        settingsVisible = false
        paddingSeekBar = null
        paddingValueText = null
        heightSeekBar = null
        heightValueText = null
        android.util.Log.w("DrivingStatusOverlay", ">>> hideSettingsOverlay: настройки скрыты")
    }

    private fun startAutoHideTimer() {
        autoHideSettingsRunnable?.let { settingsContainer?.removeCallbacks(it) }
        autoHideSettingsRunnable = Runnable { hideSettingsOverlay() }
        settingsContainer?.postDelayed(autoHideSettingsRunnable!!, SETTINGS_AUTO_HIDE_MS)
    }

    private fun resetAutoHideTimer() {
        autoHideSettingsRunnable?.let { settingsContainer?.removeCallbacks(it) }
        autoHideSettingsRunnable = Runnable { hideSettingsOverlay() }
        settingsContainer?.postDelayed(autoHideSettingsRunnable!!, SETTINGS_AUTO_HIDE_MS)
    }

    fun updateStatus(state: DrivingStatusOverlayState) {
        ensureMainThread()
        if (!enabledInitialized || !enabled) return

        if (!Settings.canDrawOverlays(appContext)) {
            return
        }

        // Если windowToken == null, view мог стать "сиротой" (процесс/окно пересоздалось),
        // но сам overlay может оставаться в WindowManager. Сначала пробуем удалить.
        val orphan = container
        if (orphan != null && orphan.windowToken == null) {
            detachView()
        }

        try {
            ensureAttached()
        } catch (e: Throwable) {
            // Если ошибка создания overlay, очищаем состояние для следующей попытки
            android.util.Log.e("DrivingStatusOverlay", "Failed to attach overlay", e)
            attached = false
            container = null
            return
        }

        val modeText = state.modeTitle?.takeIf { it.isNotBlank() } ?: "—"
        tvMode?.text = modeText
        tvMode?.setTextColor(getModeColor(state.modeTitle))

        tvGear?.text = state.gear?.takeIf { it.isNotBlank() } ?: "—"
        tvSpeed?.text = state.speedKmh?.let { "$it км/ч" } ?: "— км/ч"
        tvRange?.text = state.rangeKm?.let { "$it км" } ?: "— км"

        updateTempsTextWithColor(state)
        updateTiresTextWithColor(state)
    }

    fun destroy() {
        ensureMainThread()
        android.util.Log.w("DrivingStatusOverlay", ">>> DESTROY: вызван (attached=$attached, position=$position)")

        // Скрываем настройки если открыты
        hideSettingsOverlay()

        detachView()

        if (currentInstance === this) {
            currentInstance = null
            android.util.Log.w("DrivingStatusOverlay", ">>> DESTROY: currentInstance = null")
        } else {
            android.util.Log.w("DrivingStatusOverlay", ">>> DESTROY: WARNING - это не currentInstance!")
        }
    }

    private fun ensureAttached() {
        android.util.Log.w("DrivingStatusOverlay", ">>> ensureAttached: вызван (position=$position, attached=$attached, enabled=$enabled, container=$container)")

        // КРИТИЧНО: Проверяем attached СНАЧАЛА, не проверяя windowToken
        // windowToken может быть null сразу после addView, но это не значит что view не добавлен
        if (attached && container != null) {
            android.util.Log.w("DrivingStatusOverlay", ">>> ensureAttached: уже прикреплен (attached=true, container!=null), return")
            return
        }

        if (!enabledInitialized || !enabled) {
            android.util.Log.w("DrivingStatusOverlay", ">>> ensureAttached: не инициализирован/не включен, return")
            return
        }

        if (!Settings.canDrawOverlays(appContext)) {
            android.util.Log.w("DrivingStatusOverlay", ">>> ensureAttached: нет разрешения, return")
            return
        }

        android.util.Log.w("DrivingStatusOverlay", ">>> ensureAttached: СОЗДАЕМ overlay в позиции $position с высотой $height dp")

        val heightPx = (height.toFloat() * density).toInt()

        // Вычисляем ширину с учётом горизонтальных отступов
        val screenWidth = appContext.resources.displayMetrics.widthPixels
        val paddingPx = (horizontalPadding * density).toInt()
        val panelWidth = (screenWidth - (paddingPx * 2)).coerceAtLeast((200 * density).toInt())

        val lp = WindowManager.LayoutParams(
            panelWidth,
            heightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = if (position == "top") {
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            } else {
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            }
            x = 0
            y = 0
        }

        layoutParams = lp

        val rootContainer = FrameLayout(appContext).apply {
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val row = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )

            val horizontalPadding = (16f * density).toInt()
            val verticalPadding = (10f * density).toInt()
            setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)

            background = createBackgroundDrawable()
        }

        tvRange = TextView(appContext).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1.0f
            )
            maxLines = 1
            text = "— км"
            setTextColor(textColor)
            textSize = calculateTextSize(20f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
        }

        tvGear = TextView(appContext).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                0.5f
            )
            maxLines = 1
            text = "—"
            setTextColor(textColor)
            textSize = calculateTextSize(26f)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            gravity = Gravity.CENTER
        }

        tvSpeed = TextView(appContext).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1.1f
            )
            maxLines = 1
            text = "— км/ч"
            setTextColor(textColor)
            textSize = calculateTextSize(22f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
        }

        tvMode = TextView(appContext).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                0.9f
            )
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            text = "—"
            setTextColor(textColor)
            textSize = calculateTextSize(19f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
        }

        tvTemps = TextView(appContext).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1.2f
            )
            maxLines = 1
            text = "—° / —°"
            setTextColor(textSecondaryColor)
            textSize = calculateTextSize(18f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
        }

        tvTires = TextView(appContext).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1.5f
            )
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            text = "—"
            setTextColor(textSecondaryColor)
            textSize = calculateTextSize(17f)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
        }

        row.addView(tvRange)
        row.addView(tvGear)
        row.addView(tvSpeed)
        row.addView(tvMode)
        row.addView(tvTemps)
        row.addView(tvTires)

        setupLongPressToHide(row)

        rootContainer.addView(row)

        try {
            wm.addView(rootContainer, layoutParams ?: lp)
            android.util.Log.w("DrivingStatusOverlay", ">>> ensureAttached: overlay УСПЕШНО создан в позиции $position")
        } catch (e: Throwable) {
            android.util.Log.e("DrivingStatusOverlay", ">>> ensureAttached: ОШИБКА создания overlay", e)
            return
        }

        container = rootContainer
        rootRow = row
        attached = true
        android.util.Log.w("DrivingStatusOverlay", ">>> ensureAttached: attached = true")
    }

    private fun getModeColor(mode: String?): Int {
        return when (mode?.lowercase()) {
            "sport" -> COLOR_MODE_SPORT
            "eco" -> COLOR_MODE_ECO
            "comfort" -> COLOR_MODE_COMFORT
            "adaptive" -> COLOR_MODE_ADAPTIVE
            else -> textColor
        }
    }

    private fun getTempColor(tempC: Float?): Int {
        if (tempC == null) return textSecondaryColor

        return if (isDarkTheme) {
            when {
                tempC < 0 -> TEMP_DARK_VERY_COLD
                tempC < 10 -> TEMP_DARK_COLD
                tempC < 18 -> TEMP_DARK_COOL
                tempC < 24 -> TEMP_DARK_COMFORT
                tempC < 30 -> TEMP_DARK_WARM
                else -> TEMP_DARK_HOT
            }
        } else {
            when {
                tempC < 0 -> TEMP_LIGHT_VERY_COLD
                tempC < 10 -> TEMP_LIGHT_COLD
                tempC < 18 -> TEMP_LIGHT_COOL
                tempC < 24 -> TEMP_LIGHT_COMFORT
                tempC < 30 -> TEMP_LIGHT_WARM
                else -> TEMP_LIGHT_HOT
            }
        }
    }

    private fun getTirePressureColor(pressure: Int?): Int {
        if (pressure == null) return textSecondaryColor

        return if (isDarkTheme) {
            when {
                pressure < 200 -> TIRE_DARK_LOW
                pressure > 280 -> TIRE_DARK_HIGH
                else -> TIRE_DARK_NORMAL
            }
        } else {
            when {
                pressure < 200 -> TIRE_LIGHT_LOW
                pressure > 280 -> TIRE_LIGHT_HIGH
                else -> TIRE_LIGHT_NORMAL
            }
        }
    }

    private fun updateTempsTextWithColor(state: DrivingStatusOverlayState) {
        val cabin = state.cabinTempC
        val ambient = state.ambientTempC

        val cabinText = cabin?.toInt()?.let { "$it°" } ?: "—°"
        val ambientText = ambient?.toInt()?.let { "$it°" } ?: "—°"

        val cabinColor = getTempColor(cabin)
        val ambientColor = getTempColor(ambient)

        val fullText = "IN $cabinText / OUT $ambientText"
        val spannable = android.text.SpannableString(fullText)

        val cabinStart = fullText.indexOf(cabinText)
        if (cabinStart >= 0) {
            spannable.setSpan(
                android.text.style.ForegroundColorSpan(cabinColor),
                cabinStart,
                cabinStart + cabinText.length,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        val ambientStart = fullText.indexOf(ambientText, cabinStart + cabinText.length)
        if (ambientStart >= 0) {
            spannable.setSpan(
                android.text.style.ForegroundColorSpan(ambientColor),
                ambientStart,
                ambientStart + ambientText.length,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        tvTemps?.text = spannable
    }

    private fun updateTiresTextWithColor(state: DrivingStatusOverlayState) {
        val fl = state.tirePressureFrontLeft
        val fr = state.tirePressureFrontRight
        val rl = state.tirePressureRearLeft
        val rr = state.tirePressureRearRight

        fun format(value: Int?): String =
            value?.let {
                val bar = it / 100.0
                String.format(java.util.Locale.US, "%.1f", bar)
            } ?: "-"

        val flText = format(fl)
        val frText = format(fr)
        val rlText = format(rl)
        val rrText = format(rr)

        val fullText = "↖ $flText ↗ $frText │ ↙ $rlText ↘ $rrText"
        val spannable = android.text.SpannableString(fullText)

        val flColor = getTirePressureColor(fl)
        val frColor = getTirePressureColor(fr)
        val rlColor = getTirePressureColor(rl)
        val rrColor = getTirePressureColor(rr)

        var start = 0
        var end = start + 2 + flText.length
        spannable.setSpan(
            android.text.style.ForegroundColorSpan(flColor),
            start,
            end,
            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        start = fullText.indexOf("↗", end)
        if (start >= 0) {
            end = start + 2 + frText.length
            spannable.setSpan(
                android.text.style.ForegroundColorSpan(frColor),
                start,
                end,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        start = fullText.indexOf("↙", end)
        if (start >= 0) {
            end = start + 2 + rlText.length
            spannable.setSpan(
                android.text.style.ForegroundColorSpan(rlColor),
                start,
                end,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        start = fullText.indexOf("↘", end)
        if (start >= 0) {
            end = start + 2 + rrText.length
            spannable.setSpan(
                android.text.style.ForegroundColorSpan(rrColor),
                start,
                end,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        tvTires?.text = spannable
    }

    private fun createBackgroundDrawable(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE

            // Glass morphism: увеличенное скругление (20dp вместо 16dp)
            val radius = 20f * density
            cornerRadii = if (position == "top") {
                floatArrayOf(
                    0f, 0f,
                    0f, 0f,
                    radius, radius,
                    radius, radius
                )
            } else {
                floatArrayOf(
                    radius, radius,
                    radius, radius,
                    0f, 0f,
                    0f, 0f
                )
            }

            // Glass morphism: полупрозрачный фон
            setColor(bgColor)

            // Glass morphism: тонкая обводка для эффекта стекла
            setStroke(
                (1f * density).toInt(),
                if (isDarkTheme) GLASS_BORDER_DARK else GLASS_BORDER_LIGHT
            )
        }
    }
}