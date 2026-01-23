package com.bjornfree.drivemode.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.bjornfree.drivemode.data.repository.DriveModeRepository
import com.bjornfree.drivemode.data.repository.HeatingControlRepository
import com.bjornfree.drivemode.data.repository.IgnitionStateRepository
import kotlinx.coroutines.*
import org.koin.android.ext.android.inject

/**
 * Сервис для авто-подогрева сидений.
 *
 * РАДИКАЛЬНОЕ УПРОЩЕНИЕ:
 * - Было: 1,322 строки (5+ ответственностей)
 * - Стало: ~250 строк (1 ответственность)
 *
 * Удалено:
 * - ❌ Все методы чтения метрик (175+ строк) → VehicleMetricsRepository
 * - ❌ Мониторинг зажигания (120+ строк) → IgnitionStateRepository
 * - ❌ Диагностические тесты (60+ строк) → DiagnosticsViewModel
 * - ❌ Все константы (80+ строк) → VehiclePropertyConstants
 * - ❌ TireData классы (уже в domain models)
 *
 * Оставлено:
 * - ✅ Управление HVAC сидений
 * - ✅ Слушает HeatingControlRepository
 * - ✅ Foreground service lifecycle
 *
 * Архитектура:
 * HeatingControlRepository → AutoSeatHeatService → Car HVAC API
 * (бизнес-логика)       (исполнитель)        (hardware)
 *
 * @see HeatingControlRepository для логики подогрева
 * @see IgnitionStateRepository для мониторинга зажигания
 * @see VehicleMetricsRepository для чтения температуры
 */
class AutoSeatHeatService : Service() {

    companion object {
        private const val TAG = "AutoSeatHeatService"
        private const val NOTIFICATION_ID = 2
        private const val CHANNEL_ID = "auto_seat_heat_channel"

        // Vehicle property IDs (минимум для HVAC)
        private const val VEHICLE_PROPERTY_HVAC_SEAT_TEMPERATURE = 356517131

        @Volatile
        private var isRunning = false

        @Volatile
        private var serviceInstance: AutoSeatHeatService? = null

        /**
         * Запускает сервис.
         */
        fun start(context: Context) {
            val intent = Intent(context, AutoSeatHeatService::class.java)
            context.startForegroundService(intent)
        }

        /**
         * Перезапускает сервис.
         */
        fun restartService(context: Context) {
            try {
                log("Принудительный перезапуск...")
                context.stopService(Intent(context, AutoSeatHeatService::class.java))
                Thread.sleep(500)
                start(context)
                log("Перезапуск выполнен")
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка перезапуска", e)
            }
        }

        /**
         * Проверяет запущен ли сервис.
         */
        fun isServiceRunning(): Boolean = isRunning && serviceInstance != null

        private fun log(msg: String) {
            Log.i(TAG, msg)
        }
    }

    // Inject repositories через Koin
    private val heatingRepo: HeatingControlRepository by inject()
    private val ignitionRepo: IgnitionStateRepository by inject()
    private val driveModeRepo: DriveModeRepository by inject()

    // Coroutine scope
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var heatingJob: Job? = null

    // Car API objects (для управления HVAC)
    private var carObj: Any? = null
    private var carPropertyManagerObj: Any? = null

    // Трекинг последних установленных нами уровней (для адаптивного режима)
    // Позволяет различать: пользователь изменил вручную VS система хочет изменить уровень
    @Volatile
    private var lastSetDriverLevel: Int? = null
    @Volatile
    private var lastSetPassengerLevel: Int? = null

    // Флаги блокировки автоконтроля для каждого сидения
    // true = пользователь изменил вручную, НЕ ТРОГАТЬ до конца поездки
    @Volatile
    private var driverManuallyChanged: Boolean = false
    @Volatile
    private var passengerManuallyChanged: Boolean = false

    // Отслеживание предыдущего state для детекции изменений настроек в UI
    private var previousMode: com.bjornfree.drivemode.domain.model.HeatingMode? = null
    private var previousAdaptive: Boolean? = null
    private var previousLevel: Int? = null

    override fun onCreate() {
        super.onCreate()
        serviceInstance = this
        isRunning = true

        log("onCreate: Запуск сервиса автоподогрева (REFACTORED)")
        logToConsole("AutoSeatHeatService: Запущен (новая MVVM версия)")

        // Создаем notification и startForeground
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)

        // Инициализируем Car API для управления HVAC
        initializeCarApi()

        // Запускаем логику автоподогрева в фоне (независимо от UI)
        scope.launch {
            try {
                heatingRepo.startAutoHeating()
                log("startAutoHeating: фоновая логика автоподогрева запущена")
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка запуска автоподогрева", e)
                logToConsole("AutoSeatHeatService: ⚠ Ошибка запуска автоподогрева: ${e.message}")
            }
        }

        // Запускаем слушатель состояния подогрева
        startHeatingListener()

        log("onCreate: Сервис запущен успешно")
    }

    /**
     * Инициализирует Car API для управления HVAC сидений.
     * Используем reflection для доступа к android.car.Car
     */
    private fun initializeCarApi() {
        try {
            val carClass = Class.forName("android.car.Car")
            val createCarMethod = carClass.getMethod("createCar", Context::class.java)
            carObj = createCarMethod.invoke(null, applicationContext)

            val getCarManagerMethod = carClass.getMethod("getCarManager", String::class.java)
            carPropertyManagerObj = getCarManagerMethod.invoke(carObj, "property")

            log("Car API инициализирован для HVAC управления")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка инициализации Car API", e)
            logToConsole("AutoSeatHeatService: ⚠ Не удалось инициализировать Car API")
        }
    }

    /**
     * Запускает слушатель состояния подогрева из Repository.
     * Когда HeatingControlRepository решает что нужен подогрев - включаем HVAC.
     */
    private fun startHeatingListener() {
        heatingJob = scope.launch {
            heatingRepo.heatingState.collect { state ->
                // Проверяем изменились ли настройки в UI
                val settingsChanged = (previousMode != null && previousMode != state.mode) ||
                                      (previousAdaptive != null && previousAdaptive != state.adaptiveHeating) ||
                                      (previousLevel != null && previousLevel != state.heatingLevel)

                if (settingsChanged) {
                    log("⚙️ Изменение настроек в UI - СБРОС блокировок для активных сидений")
                    logToConsole("AutoSeatHeatService: ⚙️ Настройки изменены в UI - возобновление автоконтроля")
                    resetManualOverrideForMode(state.mode)
                }

                // Сохраняем текущие настройки для следующей проверки
                previousMode = state.mode
                previousAdaptive = state.adaptiveHeating
                previousLevel = state.heatingLevel

                // Применяем состояние
                if (state.isActive) {
                    activateSeatHeating(state)
                } else {
                    deactivateSeatHeating(state)
                }
            }
        }

    }

    /**
     * Читает текущий уровень HVAC для конкретного сиденья.
     * @param area ID области (1 = водитель, 4 = пассажир)
     * @return текущий уровень подогрева (0-3) или null если ошибка
     */
    private fun getCurrentHVACLevel(area: Int): Int? {
        return try {
            if (carPropertyManagerObj == null) return null

            val managerClass = carPropertyManagerObj!!.javaClass
            val getIntPropertyMethod = managerClass.getMethod(
                "getIntProperty",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )

            val result = getIntPropertyMethod.invoke(
                carPropertyManagerObj,
                VEHICLE_PROPERTY_HVAC_SEAT_TEMPERATURE,
                area
            )

            result as? Int
        } catch (e: Exception) {
            log("Не удалось прочитать HVAC уровень для area $area: ${e.message}")
            null
        }
    }

    /**
     * Активирует подогрев сидений через Car HVAC API.
     * @param state состояние подогрева с информацией о режиме и уровне
     */
    private fun activateSeatHeating(state: com.bjornfree.drivemode.domain.model.HeatingState) {
        try {
            if (carPropertyManagerObj == null) {
                log("Car Property Manager недоступен")
                return
            }

            // Определяем уровень подогрева
            val hvacLevel = if (state.adaptiveHeating) {
                // Адаптивный режим - уровень зависит от температуры
                val temp = state.currentTemp
                when {
                    temp == null -> 2                    // Нет данных - средний
                    temp <= 0f -> 3                     // ≤ 0°C - максимальный
                    temp < 5f -> 2                      // < 5°C - средний
                    temp < 10f -> 1                     // < 10°C - низкий
                    else -> 0                           // ≥ 10°C - выключено
                }
            } else {
                // Фиксированный уровень из настроек
                state.heatingLevel
            }

            // Определяем area ID на основе режима
            val areas = when (state.mode.key) {
                "driver" -> listOf(1)      // Только водитель
                "passenger" -> listOf(4)   // Только пассажир
                "both" -> listOf(1, 4)     // Оба
                else -> emptyList()
            }

            if (areas.isEmpty()) {
                log("Режим OFF - пропускаем активацию")
                return
            }

            val managerClass = carPropertyManagerObj!!.javaClass
            val setIntPropertyMethod = managerClass.getMethod(
                "setIntProperty",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )

            // Полный список сидений, которыми управляем (водитель и пассажир)
            val allSeatAreas = listOf(1, 4)

            // УМНАЯ ЛОГИКА ДО КОНЦА ПОЕЗДКИ:
            // Если пользователь руками изменил сидение - БЛОКИРУЕМ его до:
            //   1. Выключения зажигания (конец поездки)
            //   2. Изменения настроек в UI (resetManualOverride)

            // Читаем текущие уровни HVAC
            val currentDriverLevel = getCurrentHVACLevel(1)
            val currentPassengerLevel = getCurrentHVACLevel(4)

            // Вычисляем что мы хотим установить
            val expectedDriver = if (areas.contains(1)) hvacLevel else 0
            val expectedPassenger = if (areas.contains(4)) hvacLevel else 0

            // Проверяем каждое сидение отдельно (раздельная блокировка)

            // --- ВОДИТЕЛЬ ---
            if (!driverManuallyChanged) {
                // Сравниваем с ПОСЛЕДНИМ УСТАНОВЛЕННЫМ НАМИ уровнем
                val driverChanged = currentDriverLevel != null &&
                                    lastSetDriverLevel != null &&
                                    currentDriverLevel != lastSetDriverLevel

                if (driverChanged) {
                    // Пользователь изменил вручную - БЛОКИРУЕМ до конца поездки
                    driverManuallyChanged = true
                    log("🚫 Водитель: обнаружено ручное изменение ($lastSetDriverLevel → $currentDriverLevel)")
                    log("   БЛОКИРОВКА до конца поездки или изменения настроек в UI")
                    logToConsole("AutoSeatHeatService: 🚫 Водитель изменен вручную - заблокирован до конца поездки")
                }
            }

            // --- ПАССАЖИР ---
            if (!passengerManuallyChanged) {
                val passengerChanged = currentPassengerLevel != null &&
                                       lastSetPassengerLevel != null &&
                                       currentPassengerLevel != lastSetPassengerLevel

                if (passengerChanged) {
                    passengerManuallyChanged = true
                    log("🚫 Пассажир: обнаружено ручное изменение ($lastSetPassengerLevel → $currentPassengerLevel)")
                    log("   БЛОКИРОВКА до конца поездки или изменения настроек в UI")
                    logToConsole("AutoSeatHeatService: 🚫 Пассажир изменен вручную - заблокирован до конца поездки")
                }
            }

            // Если ОБА сидения заблокированы - ничего не делаем
            if (driverManuallyChanged && passengerManuallyChanged) {
                log("⏸ Оба сидения заблокированы - пропускаем установку")
                return
            }

            // Устанавливаем ТОЛЬКО НЕ ЗАБЛОКИРОВАННЫЕ сидения
            log("✓ Применение настроек: режим=${state.mode.displayName}, уровень=$hvacLevel")

            var appliedCount = 0

            for (area in allSeatAreas) {
                // Проверяем блокировку для этого сидения
                val isBlocked = (area == 1 && driverManuallyChanged) ||
                                (area == 4 && passengerManuallyChanged)

                if (isBlocked) {
                    val areaName = if (area == 1) "Водитель" else "Пассажир"
                    log("  $areaName: ПРОПУЩЕНО (заблокировано вручную)")
                    continue
                }

                val levelForArea = if (areas.contains(area)) hvacLevel else 0
                try {
                    setIntPropertyMethod.invoke(
                        carPropertyManagerObj,
                        VEHICLE_PROPERTY_HVAC_SEAT_TEMPERATURE,
                        area,
                        levelForArea
                    )
                    val areaName = if (area == 1) "Водитель" else "Пассажир"
                    log("  $areaName: уровень $levelForArea ✓")
                    appliedCount++

                    // Сохраняем последний установленный уровень для этого сидения
                    if (area == 1) {
                        lastSetDriverLevel = levelForArea
                    } else if (area == 4) {
                        lastSetPassengerLevel = levelForArea
                    }
                } catch (e: Exception) {
                    log("⚠ Area $area не поддерживается: ${e.message}")
                }
            }

            if (appliedCount > 0) {
                val blockedText = buildString {
                    if (driverManuallyChanged) append(" [Водитель заблокирован]")
                    if (passengerManuallyChanged) append(" [Пассажир заблокирован]")
                }
                logToConsole("AutoSeatHeatService: ✓ Подогрев установлен (${state.mode.displayName}, уровень $hvacLevel)$blockedText")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка активации подогрева", e)
            logToConsole("AutoSeatHeatService: ⚠ Ошибка активации подогрева: ${e.message}")
        }
    }

    /**
     * Деактивирует подогрев сидений.
     * @param state состояние подогрева с информацией о режиме
     */
    private fun deactivateSeatHeating(state: com.bjornfree.drivemode.domain.model.HeatingState) {
        try {
            if (carPropertyManagerObj == null) return

            // Определяем area ID на основе режима
            val areas = when (state.mode.key) {
                "driver" -> listOf(1)      // Только водитель
                "passenger" -> listOf(4)   // Только пассажир
                "both" -> listOf(1, 4)     // Оба
                else -> listOf(1, 4)       // OFF - выключаем все
            }

            val managerClass = carPropertyManagerObj!!.javaClass
            val setIntPropertyMethod = managerClass.getMethod(
                "setIntProperty",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )

            // Выключаем подогрев для всех нужных сидений
            for (area in areas) {
                try {
                    setIntPropertyMethod.invoke(
                        carPropertyManagerObj,
                        VEHICLE_PROPERTY_HVAC_SEAT_TEMPERATURE,
                        area,
                        0  // 0 = off
                    )
                } catch (e: Exception) {
                    // Не падаем, продолжаем для других area
                }
            }

            // Сбрасываем трекинг и блокировки
            // При следующем включении зажигания - чистый старт
            resetAllManualOverrides()

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка деактивации подогрева", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        log("onStartCommand")
        return START_STICKY // Перезапуск после убийства
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        log("onDestroy: Остановка сервиса")
        logToConsole("AutoSeatHeatService: Остановлен")

        isRunning = false
        serviceInstance = null

        // Останавливаем логику автоподогрева в репозитории
        try {
            heatingRepo.stopAutoHeating()
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка остановки автоподогрева", e)
        }

        // Останавливаем слушатель
        heatingJob?.cancel()
        scope.cancel()

        // Отключаемся от Car API
        disconnectCarApi()

        super.onDestroy()
    }

    /**
     * Отключается от Car API.
     */
    private fun disconnectCarApi() {
        try {
            carObj?.let { car ->
                val carClass = car.javaClass
                val disconnectMethod = carClass.getMethod("disconnect")
                disconnectMethod.invoke(car)
                log("Car API отключен")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка отключения Car API", e)
        } finally {
            carObj = null
            carPropertyManagerObj = null
        }
    }

    /**
     * Создает notification для foreground service.
     */
    private fun buildNotification(): Notification {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Автоподогрев сидений",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Автоподогрев активен")
            .setContentText("Мониторинг температуры и зажигания")
            .setSmallIcon(android.R.drawable.ic_menu_preferences)
            .setOngoing(true)
            .build()
    }

    /**
     * Логирование в консоль через DriveModeRepository.
     */
    private fun logToConsole(msg: String) {
        scope.launch {
            driveModeRepo.logConsole(msg)
        }
    }

    /**
     * Сбрасывает блокировки ручного управления ИЗБИРАТЕЛЬНО на основе режима.
     * Если режим "Водитель" - сбрасывает ТОЛЬКО водителя.
     * Если "Пассажир" - ТОЛЬКО пассажира.
     * Если "Оба" или "OFF" - оба сидения.
     *
     * @param mode текущий режим подогрева
     */
    private fun resetManualOverrideForMode(mode: com.bjornfree.drivemode.domain.model.HeatingMode) {
        when (mode.key) {
            "driver" -> {
                // Режим "Водитель" - сбрасываем ТОЛЬКО водителя
                driverManuallyChanged = false
                lastSetDriverLevel = null
                log("Блокировка сброшена: ВОДИТЕЛЬ (режим: ${mode.displayName})")
                logToConsole("  → Водитель: автоконтроль возобновлен")
            }
            "passenger" -> {
                // Режим "Пассажир" - сбрасываем ТОЛЬКО пассажира
                passengerManuallyChanged = false
                lastSetPassengerLevel = null
                log("Блокировка сброшена: ПАССАЖИР (режим: ${mode.displayName})")
                logToConsole("  → Пассажир: автоконтроль возобновлен")
            }
            "both", "off" -> {
                // Режим "Оба" или "OFF" - сбрасываем оба
                driverManuallyChanged = false
                passengerManuallyChanged = false
                lastSetDriverLevel = null
                lastSetPassengerLevel = null
                log("Блокировки сброшены: ОБА СИДЕНИЯ (режим: ${mode.displayName})")
                logToConsole("  → Водитель и пассажир: автоконтроль возобновлен")
            }
        }
    }

    /**
     * Сбрасывает блокировки для всех сидений (при выключении зажигания).
     */
    private fun resetAllManualOverrides() {
        driverManuallyChanged = false
        passengerManuallyChanged = false
        lastSetDriverLevel = null
        lastSetPassengerLevel = null
        log("Блокировки сброшены для ВСЕХ сидений (зажигание выключено)")
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
    }
}
