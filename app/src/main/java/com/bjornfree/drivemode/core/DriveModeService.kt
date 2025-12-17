package com.bjornfree.drivemode.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.bjornfree.drivemode.data.car.DriveModeMonitor
import com.bjornfree.drivemode.data.repository.DriveModeRepository
import com.bjornfree.drivemode.data.repository.IgnitionStateRepository
import com.bjornfree.drivemode.ui.theme.BorderOverlayController
import com.bjornfree.drivemode.ui.theme.ModePanelOverlayController
import com.bjornfree.drivemode.data.repository.VehicleMetricsRepository
import com.bjornfree.drivemode.ui.theme.DrivingStatusOverlayController
import com.bjornfree.drivemode.ui.theme.DrivingStatusOverlayState
import com.bjornfree.drivemode.data.constants.DriveMode as DriveModeEnum
import kotlinx.coroutines.*
import org.koin.android.ext.android.inject

/**
 * Сервис для мониторинга режимов вождения.
 *
 * MVVM Архитектура с Repository Pattern:
 * - Service → Repository → Car API
 * - Чистое разделение ответственностей
 * - ~300 строк (vs 693 строк в старой версии)
 *
 * КОМПОНЕНТЫ:
 * - ✅ DriveModeMonitor (Car API - без logcat парсинга!)
 * - ✅ Управление overlays (border + panel + metrics bar)
 * - ✅ Foreground service lifecycle
 * - ✅ Не требует root доступа
 *
 * Архитектура:
 * DriveModeMonitor (Car API) → DriveModeService → Overlays
 *                                  ↓
 *          IgnitionStateRepository (состояние зажигания)
 *          DriveModeRepository (логирование)
 *          VehicleMetricsRepository (метрики автомобиля)
 *
 * @see DriveModeMonitor для мониторинга режимов через Car API
 * @see IgnitionStateRepository для мониторинга зажигания
 * @see DriveModeRepository для консоли логов
 */
class DriveModeService : Service() {

    // Канонические режимы движения
    private enum class DriveMode(val key: String) {
        SPORT("sport"),
        COMFORT("comfort"),
        ECO("eco"),
        ADAPTIVE("adaptive");

        companion object {
            fun fromKeyOrNull(raw: String?): DriveMode? = when (raw?.lowercase()) {
                "sport" -> SPORT
                "comfort", "normal" -> COMFORT
                "eco" -> ECO
                "adaptive" -> ADAPTIVE
                else -> null
            }
        }
    }

    companion object {
        private const val TAG = "DriveModeService"

        @Volatile
        var isRunning: Boolean = false

        @Volatile
        var isWatching: Boolean = false

        @Volatile
        private var instance: DriveModeService? = null

        /**
         * Проверяет статус сервиса мониторинга режимов.
         */
        @JvmStatic
        fun getServiceStatus(): Boolean {
            return isRunning && instance != null
        }

        /**
         * Перезапускает сервис мониторинга режимов.
         */
        @JvmStatic
        fun restartService(context: Context) {
            try {
                Log.i(TAG, "Принудительный перезапуск...")
                context.stopService(Intent(context, DriveModeService::class.java))
                Thread.sleep(500)
                context.startForegroundService(
                    Intent(
                        context,
                        DriveModeService::class.java
                    )
                )
                Log.i(TAG, "Перезапущен успешно")
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка перезапуска", e)
            }
        }

        /**
         * Логирует сообщение в консоль приложения.
         * Используется другими сервисами и компонентами.
         */
        @JvmStatic
        fun logConsole(msg: String) {
            instance?.logConsoleInternal(msg) ?: run {
                Log.d(TAG, "logConsole (before service start): $msg")
            }
        }
    }

    // Inject repositories через Koin
    private val driveModeRepo: DriveModeRepository by inject()
    private val ignitionRepo: IgnitionStateRepository by inject()
    private val vehicleMetricsRepo: VehicleMetricsRepository by inject()
    private val prefsManager: com.bjornfree.drivemode.data.preferences.PreferencesManager by inject()
    private val driveModeMonitor: DriveModeMonitor by inject()
    private val carPropertyManager: com.bjornfree.drivemode.data.car.CarPropertyManagerSingleton by inject()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var borderOverlay: BorderOverlayController
    private lateinit var modePanelOverlayController: ModePanelOverlayController
    private lateinit var drivingStatusOverlay: DrivingStatusOverlayController


    private var statusOverlayJob: Job? = null
    private var themeMonitorJob: Job? = null
    private var overlaySettingsMonitorJob: Job? = null

    private fun requireMainThread() {
        check(Looper.getMainLooper().thread == Thread.currentThread()) {
            "DriveModeService overlay calls must be on Main thread"
        }
    }

    // Jobs для асинхронных операций
    private var watchJob: Job? = null
    private var ignitionStateJob: Job? = null
    private var watchStartElapsedMs: Long = 0L

    // Дедупликация событий режимов (от дребезга)
    private val MIN_EVENT_INTERVAL_MS = 100L
    private var lastShownMode: String? = null
    private var lastShownAt: Long = 0L
    private val DEDUP_WINDOW_MS = 300L

    // Дедупликация повторяющихся логов
    private var lastLoggedMessage: String? = null
    private val logLock = Any()

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        instance = this
        log("Режимы: сервис запущен")
        log("Режимы: источник данных - Car API (прямое чтение)")

        // Проверяем статус ICarFunction для управления режимом вождения
        try {
            val hasICarFunction = carPropertyManager.isICarFunctionAvailable()
            if (hasICarFunction) {
                log("Управление режимом: ECarX ICarFunction доступен ✓")
                Log.i(TAG, "ICarFunction is available for drive mode control")
            } else {
                log("Управление режимом: ВНИМАНИЕ - ECarX ICarFunction недоступен")
                log("  → Функции управления режимом вождения не будут работать")
                log("  → Проверьте логи DriveModeApplication для деталей инициализации")
                Log.w(TAG, "ICarFunction is NOT available - drive mode control will not work")
            }
        } catch (e: Exception) {
            log("Управление режимом: ошибка проверки ICarFunction - ${e.message}")
            Log.e(TAG, "Error checking ICarFunction availability", e)
        }

        // Подписываемся на состояние зажигания из Repository
        subscribeToIgnitionState()

        // Запускаем foreground notification
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                1,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(1, notification)
        }

        // КРИТИЧНО: Агрессивная очистка любых предыдущих overlay инстансов
        // Это необходимо чтобы избежать дублирования overlay при рестарте сервиса
        try {
            // Получаем доступ к singleton через companion object
            val drivingStatusClass = Class.forName("com.bjornfree.drivemode.ui.theme.DrivingStatusOverlayController")
            val companionField = drivingStatusClass.getDeclaredField("Companion")
            companionField.isAccessible = true
            val companion = companionField.get(null)

            val currentInstanceField = companion.javaClass.getDeclaredField("currentInstance")
            currentInstanceField.isAccessible = true
            val existingInstance = currentInstanceField.get(companion)

            if (existingInstance != null) {
                log("Найден предыдущий overlay instance, уничтожаем...")
                val destroyMethod = existingInstance.javaClass.getDeclaredMethod("destroy")
                destroyMethod.invoke(existingInstance)
                log("Предыдущий overlay instance уничтожен")
            }
        } catch (e: Exception) {
            log("Очистка предыдущего overlay instance: ${e.message}")
        }

        // Инициализируем overlays
        borderOverlay = BorderOverlayController(applicationContext)
        modePanelOverlayController = ModePanelOverlayController(applicationContext)
        // КРИТИЧНО: Передаем сохраненную позицию при создании, чтобы избежать дублирования
        drivingStatusOverlay = DrivingStatusOverlayController(
            applicationContext,
            initialPosition = prefsManager.metricsBarPosition
        )
        log("UI: граница, панель и нижняя полоса статуса инициализированы")

        // НЕ создаем overlay здесь! Реактивная подписка сделает это автоматически
        // при первом collect (получит текущие настройки и применит их)
        log("Ожидание реактивной подписки для применения настроек overlay...")

        // Запускаем мониторинг метрик автомобиля
        vehicleMetricsRepo.startMonitoring()
        log("Метрики: мониторинг запущен")

        // РЕАКТИВНАЯ подписка на изменения темы (без polling!)
        themeMonitorJob = scope.launch {
            prefsManager.themeModeFlow.collect { themeMode ->
                val isDark = when (themeMode) {
                    "dark" -> true
                    "light" -> false
                    else -> {
                        // auto - проверяем системную тему
                        val uiMode = applicationContext.resources.configuration.uiMode
                        (uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                                android.content.res.Configuration.UI_MODE_NIGHT_YES
                    }
                }
                withContext(Dispatchers.Main.immediate) {
                    requireMainThread()
                    drivingStatusOverlay.setDarkTheme(isDark)
                }
                log("Тема: ${themeMode} (dark=${isDark})")
            }
        }

        // РЕАКТИВНАЯ подписка на изменения настроек overlay (без polling!)
        overlaySettingsMonitorJob = scope.launch {
            var isFirstCollect = true  // Флаг первого вызова
            var lastMetricsBarEnabled = false  // Инициализируем как false
            var lastMetricsBarPosition = ""
            var lastBorderEnabled = false
            var lastPanelEnabled = false

            // Подписываемся на изменения настроек через Flow
            prefsManager.overlaySettingsFlow.collect { settings ->
                val hasOverlayPermission = Settings.canDrawOverlays(applicationContext)

                // ========== METRICS BAR POSITION (СНАЧАЛА!) ==========
                // КРИТИЧНО: Устанавливаем позицию ДО enabled, чтобы избежать пересоздания

                if (settings.metricsBarPosition != lastMetricsBarPosition || isFirstCollect) {
                    withContext(Dispatchers.Main.immediate) {
                        requireMainThread()
                        drivingStatusOverlay.setPosition(settings.metricsBarPosition)
                    }
                    log("Metrics bar position: ${settings.metricsBarPosition}")
                    lastMetricsBarPosition = settings.metricsBarPosition
                }

                // ========== METRICS BAR ENABLED ==========

                if (settings.metricsBarEnabled != lastMetricsBarEnabled || isFirstCollect) {
                    if (settings.metricsBarEnabled) {
                        withContext(Dispatchers.Main.immediate) {
                            requireMainThread()
                            drivingStatusOverlay.setEnabled(true)
                        }
                        try {
                            withContext(Dispatchers.Main.immediate) {
                                requireMainThread()
                                drivingStatusOverlay.ensureVisible()
                            }
                            log("Metrics bar: включена (позиция: ${settings.metricsBarPosition})")
                        } catch (e: Exception) {
                            log("Ошибка отображения metrics bar: ${e.message}")
                        }
                    } else {
                        if (lastMetricsBarEnabled) {
                            withContext(Dispatchers.Main.immediate) {
                                requireMainThread()
                                drivingStatusOverlay.setEnabled(false)
                            }
                            log("Metrics bar: выключена")
                        }
                    }
                    lastMetricsBarEnabled = settings.metricsBarEnabled
                }

                // ========== BORDER OVERLAY ==========

                if (settings.borderEnabled != lastBorderEnabled || isFirstCollect) {
                    if (settings.borderEnabled) {
                        log("Border overlay: включена")
                    } else {
                        borderOverlay.hide()
                        log("Border overlay: выключена")
                    }
                    lastBorderEnabled = settings.borderEnabled
                }

                // ========== PANEL OVERLAY ==========

                if (settings.panelEnabled != lastPanelEnabled || isFirstCollect) {
                    modePanelOverlayController.setOverlayEnabled(settings.panelEnabled)
                    log("Panel overlay: ${if (settings.panelEnabled) "включена" else "выключена"}")
                    lastPanelEnabled = settings.panelEnabled
                }

                // Сбрасываем флаг первого вызова в конце
                isFirstCollect = false
            }
        }


        // Подписка на метрики автомобиля для обновления нижней полосы статуса
        statusOverlayJob = scope.launch {
            vehicleMetricsRepo.vehicleMetrics.collect { m ->
                val tire = m.tirePressure

                // Читаем последний известный режим для компактного текста
                val modeTitle = lastShownMode?.let { key ->
                    when (DriveMode.fromKeyOrNull(key)) {
                        DriveMode.SPORT -> "SPORT"
                        DriveMode.COMFORT -> "COMFORT"
                        DriveMode.ECO -> "ECO"
                        DriveMode.ADAPTIVE -> "ADAPTIVE"
                        null -> null
                    }
                }

                val state = DrivingStatusOverlayState(
                    modeTitle = modeTitle,
                    gear = m.gear,
                    speedKmh = m.speed?.toInt(),
                    rangeKm = (m.fuel?.rangeKm ?: m.rangeRemaining)?.toInt(),
                    cabinTempC = m.cabinTemperature,
                    ambientTempC = m.ambientTemperature,
                    tirePressureFrontLeft = tire?.frontLeft?.pressure,
                    tirePressureFrontRight = tire?.frontRight?.pressure,
                    tirePressureRearLeft = tire?.rearLeft?.pressure,
                    tirePressureRearRight = tire?.rearRight?.pressure
                )

                drivingStatusOverlay.updateStatus(state)
            }
        }

        // Запускаем мониторинг режима вождения через Car API (работаем 24/7)
        log("Drive Mode: запускаем мониторинг через Car API (работаем 24/7)")
        startWatchLoop()

        // Проверяем разрешения только для логирования (не открываем диалоги автоматически)
        ensureOverlayPermissionTip()
    }

    /**
     * Подписывается на состояние зажигания из IgnitionStateRepository.
     * Вся логика мониторинга зажигания теперь в Repository!
     */
    private fun subscribeToIgnitionState() {
        ignitionStateJob = scope.launch {
            ignitionRepo.ignitionState.collect { state ->
                if (state.isOn) {
                    log("ignition: состояние = ${state.stateName}")

                    // АВТОУСТАНОВКА РЕЖИМА ПРИ СТАРТЕ АВТО
                    if (prefsManager.autoDriveModeEnabled) {
                        val selectedModeCode = prefsManager.selectedDriveMode
                        val mode = DriveModeEnum.fromECarXCode(selectedModeCode)

                        log("Автоустановка режима: попытка установить $mode")
                        Log.i(TAG, "Auto-applying drive mode: $mode")

                        // Выполняем в IO dispatcher т.к. retry-логика использует Thread.sleep()
                        withContext(Dispatchers.IO) {
                            try {
                                // Читаем текущий режим ДО установки
                                try {
                                    val currentCode = carPropertyManager.getDriveModeSelection()
                                    val currentMode = DriveModeEnum.fromECarXCode(currentCode)
                                    log("Текущий режим ДО автоустановки: $currentMode")
                                    Log.i(TAG, "Current drive mode BEFORE auto-apply: $currentMode")
                                } catch (e: Exception) {
                                    log("Не удалось прочитать текущий режим: ${e.message}")
                                }

                                // Используем метод с retry-логикой и встроенной проверкой
                                val success = carPropertyManager.setCarFunctionValueWithRetry(
                                    com.ecarx.xui.adaptapi.car.vehicle.IDriveMode.DM_FUNC_DRIVE_MODE_SELECT,
                                    selectedModeCode
                                )

                                if (success) {
                                    // Метод setCarFunctionValueWithRetry уже проверил что значение установилось
                                    log("Автоустановка режима: ${mode.displayName} ПОДТВЕРЖДЕН ✓")
                                    Log.i(TAG, "Drive mode auto-applied and CONFIRMED: $mode")
                                } else {
                                    log("Автоустановка режима: ОШИБКА - не удалось установить ${mode.displayName} после 3 попыток")
                                    log("Возможные причины: автомобиль в движении, неподходящие условия, функция недоступна")
                                    Log.e(TAG, "Failed to auto-apply drive mode after retries: $mode")
                                }
                            } catch (e: Exception) {
                                log("Автоустановка режима: ИСКЛЮЧЕНИЕ - ${e.message}")
                                Log.e(TAG, "Exception during auto-apply drive mode", e)
                            }
                        }
                    } else {
                        log("Автоустановка режима: выключена в настройках")
                    }

                    // При включении зажигания, если сервис уже знает последний режим,
                    // сразу показываем его (мониторинг Car API работает постоянно)
                    val last = lastShownMode
                    if (last != null) {
                        Log.i(TAG, "ignition: re-show last known drive mode on ignition ON: $last")
                        val driveMode = DriveMode.fromKeyOrNull(last)
                        if (driveMode != null) {
                            onDriveModeDetected(driveMode)
                        }
                    }
                } else if (state.isOff) {
                    log("ignition: состояние = ${state.stateName}")
                    // Работаем 24/7 - не останавливаем мониторинг!
                } else {
                    // Intermediate or unknown state
                    log("ignition: состояние = ${state.stateName}")
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Позволяем тестировать без logcat: если пришёл mode через broadcast/intent — сразу показываем
        val modeRaw = intent?.getStringExtra("mode")
        val driveMode = DriveMode.fromKeyOrNull(modeRaw)
        Log.i(TAG, "onStartCommand: modeRaw=$modeRaw, mapped=$driveMode")
        if (driveMode != null) {
            scope.launch(Dispatchers.Main) { onDriveModeDetected(driveMode) }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        log("DriveModeService: onTaskRemoved - перезапускаем сервис")

        // Перезапускаем сервис при удалении задачи
        val restartIntent = Intent(applicationContext, DriveModeService::class.java)
        applicationContext.startForegroundService(restartIntent)
    }

    override fun onDestroy() {
        // Останавливаем мониторинг режима вождения через Car API
        try {
            driveModeMonitor.stopMonitoring()
        } catch (_: Exception) {
        }

        watchJob?.cancel()
        ignitionStateJob?.cancel()
        statusOverlayJob?.cancel()
        themeMonitorJob?.cancel()
        overlaySettingsMonitorJob?.cancel()


        isRunning = false
        instance = null
        isWatching = false
        log("Режимы: сервис остановлен")

        super.onDestroy()
        scope.cancel()

        try {
            borderOverlay.destroy()
        } catch (_: Exception) {
        }

        try {
            modePanelOverlayController.destroy()
        } catch (_: Exception) {
        }

        try {
            drivingStatusOverlay.destroy()
        } catch (_: Exception) {
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Запускает мониторинг режима вождения через Car API.
     * НОВАЯ РЕАЛИЗАЦИЯ: Прямое чтение из Car API вместо парсинга logcat.
     *
     * ПРЕИМУЩЕСТВА:
     * - ✅ Не требует root доступа
     * - ✅ Меньше задержка (нет парсинга логов)
     * - ✅ Надёжнее (не зависит от формата логов)
     * - ✅ Меньше CPU (нет обработки каждой строки logcat)
     */
    private fun startWatchLoop() {
        watchStartElapsedMs = SystemClock.elapsedRealtime()

        // Отменяем предыдущий watch-job
        watchJob?.cancel()

        // Запускаем мониторинг через Car API
        log("Drive Mode: запуск мониторинга через Car API...")
        driveModeMonitor.startMonitoring()

        // Подписываемся на изменения режима
        watchJob = scope.launch {
            isWatching = true

            if (driveModeMonitor.isAvailable()) {
                log("Drive Mode: Car API доступен, мониторинг запущен")
            } else {
                log("Drive Mode: WARNING - Car API недоступен (возможно эмулятор)")
            }

            driveModeMonitor.currentMode.collect { mode ->
                if (mode != null) {
                    log("Drive Mode: изменение режима → $mode (через Car API)")
                    Log.i(TAG, "Drive mode changed: $mode (via Car API)")

                    withContext(Dispatchers.Main.immediate) {
                        onModeDetected(mode)
                    }
                }
            }
        }
    }

    /**
     * Обработка обнаруженного режима вождения.
     */
    private fun onDriveModeDetected(mode: DriveMode) {
        val key = mode.key
        val now = SystemClock.elapsedRealtime()
        if (lastShownMode == key && (now - lastShownAt) < DEDUP_WINDOW_MS) {
            Log.d(TAG, "Skip duplicate mode within window: $key")
            return
        }
        lastShownMode = key
        lastShownAt = now

        Log.i(TAG, "onDriveModeDetected: $key -> show overlays")

        // Обновляем текущий режим в Repository
        scope.launch {
            driveModeRepo.setCurrentMode(key)
        }

        try {
            if (Settings.canDrawOverlays(applicationContext)) {
                // Проверяем настройки перед показом каждого оверлея
                if (prefsManager.borderEnabled) {
                    borderOverlay.showMode(key)
                }
                if (prefsManager.panelEnabled) {
                    modePanelOverlayController.showMode(key)
                }
            } else {
                log("overlay skipped: нет разрешения на overlay")
            }
        } catch (e: Exception) {
            log("overlay error: ${e.javaClass.simpleName}: ${e.message}")
            Log.e(TAG, "Overlay error", e)
            try {
                borderOverlay.hide()
            } catch (_: Exception) {
            }
        }

        try {
            updateNotification(key)
        } catch (e: Exception) {
            log("notification error: \${e.javaClass.simpleName}: \${e.message}")
            Log.e(TAG, "Notification error", e)
        }
    }

    private fun onModeDetected(mode: String) {
        val driveMode = DriveMode.fromKeyOrNull(mode)
        if (driveMode == null) {
            log("onModeDetected: unknown mode='$mode', ignore")
            return
        }
        onDriveModeDetected(driveMode)
    }

    /**
     * Логирование в консоль через DriveModeRepository.
     */
    private fun logConsoleInternal(msg: String) {
        scope.launch {
            driveModeRepo.logConsole(msg)
        }
    }

    /**
     * Локальная функция для логирования (вызывает logConsoleInternal).
     */
    private fun log(msg: String) {
        logConsoleInternal(msg)
    }

    /**
     * Логирует сообщение только если оно отличается от предыдущего.
     */
    private fun logConsoleOnce(msg: String) {
        synchronized(logLock) {
            val messageKey = msg.substringBefore(":")
            if (lastLoggedMessage != messageKey) {
                logConsole(msg)
                lastLoggedMessage = messageKey
            }
        }
    }

    /**
     * Сбрасывает состояние дедупликации.
     */
    private fun resetLogState(component: String) {
        synchronized(logLock) {
            if (lastLoggedMessage != null) {
                log("$component: ✓ Операция возобновлена успешно")
                lastLoggedMessage = null
            }
        }
    }

    private fun buildNotification(): Notification {
        val chId = "drive_mode_service"
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(chId, "DriveMode", NotificationManager.IMPORTANCE_MIN)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                ch
            )
        }
        return NotificationCompat.Builder(this, chId)
            .setContentTitle("DriveMode: сервис активен")
            .setContentText("Отслеживание режимов по логам системы")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(mode: String) {
        val chId = "drive_mode_service"
        val text = "Режим: ${mode.uppercase()}"
        val n = NotificationCompat.Builder(this, chId)
            .setContentTitle("DriveMode: сервис активен")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    1,
                    n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(1, n)
            }
        } catch (e: Exception) {
            log("startForeground error: \${e.javaClass.simpleName}")
            Log.e(TAG, "startForeground error", e)
        }
    }

    private fun ensureOverlayPermissionTip() {
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Overlay permission NOT granted")
        } else {
            Log.i(TAG, "Overlay permission granted")
        }
    }

    private fun ensureKeepAliveWhitelist() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                Log.w(TAG, "Requested ignore battery optimizations")
            }
        } catch (e: Exception) {
            log("keepAliveWhitelist error: \${e.javaClass.simpleName}")
        }
    }

    private fun showErrorNotification(message: String) {
        try {
            val chId = "drive_mode_errors"
            if (Build.VERSION.SDK_INT >= 26) {
                val ch = NotificationChannel(
                    chId,
                    "DriveMode Errors",
                    NotificationManager.IMPORTANCE_HIGH
                )
                (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .createNotificationChannel(ch)
            }

            val notification = NotificationCompat.Builder(this, chId)
                .setContentTitle("DriveMode: Ошибка")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()

            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(999, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show error notification", e)
        }
    }
}
