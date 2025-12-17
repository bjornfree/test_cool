package com.bjornfree.drivemode.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.car.VehiclePropertyIds
import android.content.Context
import android.content.Intent
import android.os.Build
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
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

            // КРИТИЧНО: Проверяем окно "тишины" после ручного вмешательства
            if (state.isInManualOverrideWindow()) {
                val elapsed = (System.currentTimeMillis() - state.lastManualOverrideTime) / 1000
                log("⏸ Окно ручного управления активно (${elapsed}сек) - пропускаем автоконтроль")
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

            // Читаем текущие уровни HVAC для всех сидений
            val currentDriverLevel = getCurrentHVACLevel(1)
            val currentPassengerLevel = getCurrentHVACLevel(4)

            // КРИТИЧНО: Проверяем на ручное вмешательство ТОЛЬКО после первичной установки!
            // При первом запуске текущие уровни могут быть любыми (часто 0/0), это не вмешательство.
            // ТАКЖЕ пропускаем проверку если настройки только что изменены программно (НЕ ручное вмешательство).
            if (state.initialSetupComplete && !state.settingsJustChanged) {
                var manualOverrideDetected = false
                var manualDriverLevel: Int? = null
                var manualPassengerLevel: Int? = null

                for (area in allSeatAreas) {
                    val expectedLevel = if (areas.contains(area)) hvacLevel else 0
                    val currentLevel = if (area == 1) currentDriverLevel else currentPassengerLevel

                    if (currentLevel != null && currentLevel != expectedLevel) {
                        // Обнаружено ручное изменение!
                        manualOverrideDetected = true
                        if (area == 1) {
                            manualDriverLevel = currentLevel
                        } else if (area == 4) {
                            manualPassengerLevel = currentLevel
                        }

                        val areaName = if (area == 1) "Водитель" else "Пассажир"
                        log("👤 $areaName: обнаружено ручное изменение (текущий=$currentLevel, ожидалось=$expectedLevel)")
                        logToConsole("AutoSeatHeatService: 👤 $areaName ручной уровень $currentLevel (авто рекомендует $expectedLevel)")
                    }
                }

                // Если обнаружено ручное вмешательство - НЕ ТРОГАЕМ настройки!
                if (manualOverrideDetected) {
                    log("🚫 Обнаружено ручное управление - автоконтроль приостановлен на 5 минут")
                    logToConsole("AutoSeatHeatService: 🚫 Обнаружено ручное управление - автоподогрев в режиме ожидания")

                    // Уведомляем Repository о ручном вмешательстве (с текущими уровнями для UI)
                    heatingRepo.notifyManualOverride(
                        driverLevel = manualDriverLevel,
                        passengerLevel = manualPassengerLevel,
                        currentDriverLevel = currentDriverLevel,
                        currentPassengerLevel = currentPassengerLevel
                    )
                    return
                }
            } else if (state.settingsJustChanged) {
                log("⚙ Настройки изменены программно - пропускаем проверку на ручное вмешательство")
            } else {
                log("⚙ Первичная установка - пропускаем проверку на ручное вмешательство")
            }

            // Безопасно устанавливаем уровни (нет ручного вмешательства)
            for (area in allSeatAreas) {
                val levelForArea = if (areas.contains(area)) hvacLevel else 0
                try {
                    setIntPropertyMethod.invoke(
                        carPropertyManagerObj,
                        VEHICLE_PROPERTY_HVAC_SEAT_TEMPERATURE,
                        area,
                        levelForArea
                    )
                } catch (e: Exception) {
                    log("⚠ Area $area не поддерживается: ${e.message}")
                    // Не падаем, продолжаем для других area
                }
            }

            // Уведомляем Repository что установка выполнена успешно
            // Передаем текущие (только что установленные) уровни для UI
            val finalDriverLevel = if (areas.contains(1)) hvacLevel else 0
            val finalPassengerLevel = if (areas.contains(4)) hvacLevel else 0

            heatingRepo.notifySetupComplete(
                driverLevel = finalDriverLevel,
                passengerLevel = finalPassengerLevel
            )

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Автоподогрев сидений",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

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

    private fun log(msg: String) {
        Log.i(TAG, msg)
    }
}
