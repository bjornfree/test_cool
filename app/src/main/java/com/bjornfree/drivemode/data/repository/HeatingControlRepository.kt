package com.bjornfree.drivemode.data.repository

import android.util.Log
import com.bjornfree.drivemode.data.preferences.PreferencesManager
import com.bjornfree.drivemode.domain.model.HeatingMode
import com.bjornfree.drivemode.domain.model.HeatingState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Repository для управления автоподогревом сидений.
 *
 * Извлекает чистую бизнес-логику подогрева из AutoSeatHeatService.
 * Принимает решения о включении/выключении подогрева на основе:
 * - Состояния зажигания (from IgnitionStateRepository)
 * - Температуры в салоне (from VehicleMetricsRepository)
 * - Настроек пользователя (from PreferencesManager)
 *
 * @param prefsManager для настроек подогрева
 * @param ignitionRepo для мониторинга зажигания
 * @param metricsRepo для температуры салона
 */
class HeatingControlRepository(
    private val prefsManager: PreferencesManager,
    private val ignitionRepo: IgnitionStateRepository,
    private val metricsRepo: VehicleMetricsRepository
) {
    companion object {
        private const val TAG = "HeatingControlRepo"
    }

    // Реактивный state
    private val _heatingState = MutableStateFlow(HeatingState())
    val heatingState: StateFlow<HeatingState> = _heatingState.asStateFlow()

    // Coroutine scope
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var controlJob: Job? = null

    @Volatile
    private var isRunning = false

    // Флаг что решение о подогреве уже принято при этом включении зажигания
    private var heatingDecisionMade = false

    // Отслеживаем время активации подогрева для автоотключения
    // Работает для ЛЮБОГО подогрева (автоматического и ручного)
    // По истечении таймера подогрев ОТКЛЮЧАЕТСЯ и остается выключенным
    private var heatingActivatedAt: Long = 0L

    // Флаг что подогрев был отключен таймером
    // Остается true до выключения зажигания или ручного изменения
    private var disabledByTimer: Boolean = false

    // Отслеживаем последнее состояние для обновления только при изменении
    private var lastActiveState: Boolean = false
    private var lastRecommendedLevel: Int = 0
    private var lastMode: HeatingMode = HeatingMode.OFF

    /**
     * Сбрасывает внутреннее решение и таймер автоподогрева.
     * Используется при смене настроек, чтобы логика пересчиталась как при новом запуске.
     */
    private fun resetDecisionState(reason: String) {
        Log.d(TAG, "Resetting heating decision state: $reason")
        heatingDecisionMade = false
        heatingActivatedAt = 0L
        disabledByTimer = false

        // ВАЖНО: Смена настроек (prefs) меняет "ожидаемое" значение HVAC.
        // Устанавливаем settingsJustChanged=true чтобы следующая проверка НЕ считала
        // изменение настроек ручным вмешательством.
        // Сбрасываем первичную установку и окно ручного управления.
        val prev = _heatingState.value
        _heatingState.value = prev.copy(
            initialSetupComplete = false,
            manualOverrideDetected = false,
            manualDriverLevel = null,
            manualPassengerLevel = null,
            lastManualOverrideTime = 0L,
            currentDriverLevel = null,
            currentPassengerLevel = null,
            settingsJustChanged = true  // КРИТИЧНО: Блокируем детекцию вмешательства
        )
    }

    /**
     * Запускает логику автоподогрева.
     * Слушает изменения зажигания и температуры, принимает решения.
     */
    fun startAutoHeating() {
        if (isRunning) {
            Log.d(TAG, "Auto heating already running")
            return
        }

        Log.i(TAG, "Starting auto heating control...")
        isRunning = true

        // КРИТИЧЕСКИ ВАЖНО: Запускаем мониторинг зажигания и метрик!
        ignitionRepo.startMonitoring()
        metricsRepo.startMonitoring()

        controlJob = scope.launch {
            // РЕАКТИВНО комбинируем потоки зажигания, метрик И настроек подогрева
            // Теперь любое изменение настроек мгновенно применяется без polling!
            combine(
                ignitionRepo.ignitionState,
                metricsRepo.vehicleMetrics,
                prefsManager.seatHeatingSettingsFlow
            ) { ignition, metrics, settings ->
                Triple(ignition, metrics, settings)
            }.collect { (ignition, metrics, settings) ->

                val currentMode = HeatingMode.fromKey(settings.seatAutoHeatMode)
                val isAdaptive = settings.adaptiveHeating
                val threshold = settings.temperatureThreshold
                val checkOnce = settings.checkTempOnceOnStartup
                val autoOffTimerMinutes = settings.autoOffTimerMinutes
                val temperatureSource = settings.temperatureSource

                // Выбираем источник температуры
                val tempToCheck = if (temperatureSource == "ambient") {
                    metrics.ambientTemperature
                } else {
                    metrics.cabinTemperature
                }
                val cabinTemp = metrics.cabinTemperature // Сохраняем для отображения

                // Логика зажигания используется только через ignition.isOn и heatingDecisionMade

                // При выключении зажигания всегда сбрасываем решение и состояние таймера
                if (!ignition.isOn) {
                    heatingDecisionMade = false
                    heatingActivatedAt = 0L
                    disabledByTimer = false // Сбрасываем блокировку таймера для следующего запуска

                    // КРИТИЧНО: Сбрасываем флаги при выключении зажигания
                    // Это гарантирует что при следующем запуске двигателя мы НЕ будем
                    // считать начальное состояние HVAC (обычно 0/0) ручным вмешательством
                    val prevState = _heatingState.value
                    if (prevState.initialSetupComplete || prevState.settingsJustChanged) {
                        _heatingState.value = prevState.copy(
                            initialSetupComplete = false,
                            currentDriverLevel = null,
                            currentPassengerLevel = null,
                            settingsJustChanged = false
                        )
                        Log.d(TAG, "Ignition OFF: reset state flags for next start")
                    }
                }

                // Проверяем таймер автоотключения
                val isTimerExpired = if (autoOffTimerMinutes > 0 && heatingActivatedAt > 0 && !disabledByTimer) {
                    val elapsedMinutes = (System.currentTimeMillis() - heatingActivatedAt) / 60_000
                    elapsedMinutes >= autoOffTimerMinutes
                } else {
                    false
                }

                // Если таймер истек - ОТКЛЮЧАЕМ подогрев и блокируем его включение
                if (isTimerExpired) {
                    Log.i(TAG, "⏱ Timer expired ($autoOffTimerMinutes min) - DISABLING heating")

                    disabledByTimer = true
                    heatingActivatedAt = 0L

                    // Сбрасываем окно тишины (если было ручное вмешательство)
                    val prevState = _heatingState.value
                    if (prevState.manualOverrideDetected || prevState.settingsJustChanged) {
                        _heatingState.value = prevState.copy(
                            manualOverrideDetected = false,
                            manualDriverLevel = null,
                            manualPassengerLevel = null,
                            lastManualOverrideTime = 0L,
                            settingsJustChanged = false
                        )
                    }

                    Log.d(TAG, "Heating disabled by timer - will stay OFF until ignition cycle or manual change")
                }

                // Решение по температуре (адаптив/порог) с учётом режима "проверка только при запуске"
                val previousActive = _heatingState.value.isActive
                val baseTempDecision: Boolean =
                    if (!checkOnce || !heatingDecisionMade) {
                        val decision = if (isAdaptive) {
                            // ПРИОРИТЕТ 1: Адаптивный режим - порог температуры игнорируется
                            if (tempToCheck == null) {
                                false // Температура недоступна - на всякий случай выключаем
                            } else {
                                tempToCheck <= 10f // Адаптивный порог жестко закодирован
                            }
                        } else {
                            // ПРИОРИТЕТ 2: Обычный режим - проверяем температурный порог
                            if (tempToCheck == null) {
                                false // Температура недоступна - НЕ включаем
                            } else {
                                tempToCheck < threshold // Используем настраиваемый порог
                            }
                        }
                        // В режиме "проверка только при запуске" фиксируем решение
                        if (checkOnce && ignition.isOn && tempToCheck != null && !heatingDecisionMade) {
                            heatingDecisionMade = true
                        }
                        decision
                    } else {
                        // В режиме однократной проверки сохраняем предыдущее решение по температуре
                        previousActive
                    }

                // Финальное решение о подогреве
                val shouldBeActive =
                    if (disabledByTimer) {
                        // Подогрев был отключен таймером - остается выключенным
                        false
                    } else if (currentMode == HeatingMode.OFF) {
                        // Режим выключен - ничего не делаем
                        false
                    } else if (!ignition.isOn) {
                        // Зажигание выключено - подогрев не нужен
                        false
                    } else {
                        // Режим driver/passenger/both + зажигание ON
                        baseTempDecision
                    }

                // Отслеживаем активацию подогрева для таймера
                val wasActive = previousActive
                if (shouldBeActive && !wasActive) {
                    // Подогрев только что активировался
                    if (autoOffTimerMinutes > 0) {
                        // Таймер включен — запоминаем момент активации
                        heatingActivatedAt = System.currentTimeMillis()
                        Log.d(TAG, "Heating activated, timer started (auto-off in $autoOffTimerMinutes min)")
                    } else {
                        // Таймер отключен (0 минут) — не держим лишнее состояние
                        heatingActivatedAt = 0L
                        Log.d(TAG, "Heating activated, auto-off timer disabled (0 min)")
                    }
                } else if (!shouldBeActive && wasActive) {
                    // Подогрев деактивировался - всегда сбрасываем таймер
                    heatingActivatedAt = 0L
                    Log.d(TAG, "Heating deactivated, timer reset")
                }

                // Вычисляем рекомендуемый уровень (даже если подогрев не активен)
                val recommendedLevel = if (isAdaptive && tempToCheck != null) {
                    when {
                        tempToCheck <= 3f -> 3
                        tempToCheck < 6f -> 2
                        tempToCheck <= 10f -> 1
                        else -> 0
                    }
                } else {
                    settings.heatingLevel
                }

                // Проверяем изменилось ли решение
                val stateChanged = shouldBeActive != lastActiveState ||
                                   recommendedLevel != lastRecommendedLevel ||
                                   currentMode != lastMode

                // Обновляем state ТОЛЬКО при изменении решения
                if (stateChanged || !isRunning) {
                    val tempSourceLabel = if (temperatureSource == "ambient") "наружная" else "салон"
                    val reason = when {
                        disabledByTimer -> "[Таймер] Отключено по таймеру ($autoOffTimerMinutes мин)"
                        !ignition.isOn -> "Зажигание выключено"
                        currentMode == HeatingMode.OFF -> "Режим: выключен"
                        isAdaptive && tempToCheck == null -> "[Адаптив] Температура ($tempSourceLabel) недоступна → выключено"
                        isAdaptive && tempToCheck != null && tempToCheck <= 10f -> "[Адаптив] Температура ($tempSourceLabel) ${tempToCheck.toInt()}°C <= 10°C → включено"
                        isAdaptive && tempToCheck != null -> "[Адаптив] Температура ($tempSourceLabel) ${tempToCheck.toInt()}°C  10°C → выключено"
                        tempToCheck == null -> "[Порог] Температура ($tempSourceLabel) недоступна → выключено"
                        tempToCheck != null && tempToCheck < threshold -> "[Порог] Температура ($tempSourceLabel) ${tempToCheck.toInt()}°C < ${threshold}°C → включено"
                        tempToCheck != null -> "[Порог] Температура ($tempSourceLabel) ${tempToCheck.toInt()}°C ≥ ${threshold}°C → выключено"
                        else -> "Неизвестное состояние"
                    }

                    // Сохраняем предыдущие значения ручного управления (если были)
                    val prevState = _heatingState.value

                    _heatingState.value = HeatingState(
                        isActive = shouldBeActive,
                        mode = currentMode,
                        adaptiveHeating = isAdaptive,
                        heatingLevel = settings.heatingLevel,
                        reason = reason,
                        currentTemp = tempToCheck ?: cabinTemp,
                        temperatureThreshold = threshold,
                        recommendedLevel = recommendedLevel,
                        // Сохраняем информацию о ручном управлении
                        manualOverrideDetected = prevState.manualOverrideDetected,
                        manualDriverLevel = prevState.manualDriverLevel,
                        manualPassengerLevel = prevState.manualPassengerLevel,
                        lastManualOverrideTime = prevState.lastManualOverrideTime,
                        // Передаем время активации для UI (показ таймера)
                        heatingActivatedAt = heatingActivatedAt
                    )

                    // Обновляем отслеживание состояния
                    lastActiveState = shouldBeActive
                    lastRecommendedLevel = recommendedLevel
                    lastMode = currentMode

                    if (shouldBeActive) {
                        Log.d(TAG, "✅ Heating ACTIVE (уровень $recommendedLevel): $reason")
                    } else {
                        Log.d(TAG, "❌ Heating INACTIVE: $reason")
                    }
                } else {
                    // Состояние не изменилось - пропускаем обновление
                    Log.d(TAG, "↻ State unchanged - skipping HVAC update")
                }
            }
        }

        Log.i(TAG, "Auto heating control started")
    }

    /**
     * Останавливает логику автоподогрева.
     */
    fun stopAutoHeating() {
        Log.i(TAG, "Stopping auto heating control...")
        isRunning = false
        controlJob?.cancel()
        controlJob = null

        // Деактивируем подогрев, сохраняя остальные поля состояния
        _heatingState.value = _heatingState.value.copy(
            isActive = false,
            reason = "Stopped"
        )
    }

    /**
     * Изменяет режим автоподогрева.
     * @param mode новый режим (off/adaptive/always)
     */
    fun setMode(mode: HeatingMode) {
        Log.i(TAG, "Setting heating mode to: ${mode.key}")
        prefsManager.seatAutoHeatMode = mode.key
        // Считаем это как смену сценария – пересчитываем условия заново
        resetDecisionState("mode changed to ${mode.key}")
    }

    /**
     * Изменяет температурный порог.
     * @param threshold новый порог в °C
     */
    fun setTemperatureThreshold(threshold: Int) {
        Log.i(TAG, "Setting temperature threshold to: $threshold°C")
        prefsManager.temperatureThreshold = threshold
        // Новый порог – пересчитываем условия включения
        resetDecisionState("threshold changed to $threshold")
    }

    /**
     * Получает текущий режим подогрева.
     */
    fun getCurrentMode(): HeatingMode {
        return HeatingMode.fromKey(prefsManager.seatAutoHeatMode)
    }

    /**
     * Получает текущий температурный порог.
     */
    fun getTemperatureThreshold(): Int {
        return prefsManager.temperatureThreshold
    }

    /**
     * Проверяет включен ли адаптивный режим.
     */
    fun isAdaptiveEnabled(): Boolean {
        return prefsManager.adaptiveHeating
    }

    /**
     * Включает/выключает адаптивный режим.
     * @param enabled true для адаптивного режима
     */
    fun setAdaptiveHeating(enabled: Boolean) {
        Log.i(TAG, "Setting adaptive heating to: $enabled")
        prefsManager.adaptiveHeating = enabled
        // Адаптивный режим напрямую влияет на решение – пересчитываем
        resetDecisionState("adaptive changed to $enabled")
    }

    /**
     * Устанавливает уровень подогрева (0-3).
     * @param level уровень подогрева (0=off, 1=low, 2=medium, 3=high)
     */
    fun setHeatingLevel(level: Int) {
        Log.i(TAG, "Setting heating level to: $level")
        prefsManager.heatingLevel = level
    }

    /**
     * Получает текущий уровень подогрева.
     */
    fun getHeatingLevel(): Int {
        return prefsManager.heatingLevel
    }

    /**
     * Включает/выключает режим "проверка температуры только при запуске".
     * @param enabled true для проверки только при запуске
     */
    fun setCheckTempOnceOnStartup(enabled: Boolean) {
        Log.i(TAG, "Setting checkTempOnceOnStartup to: $enabled")
        prefsManager.checkTempOnceOnStartup = enabled
        // Меняем стратегию принятия решения – пересчитываем как при новом запуске
        resetDecisionState("check-once flag changed to $enabled")
    }

    /**
     * Проверяет включен ли режим "проверка только при запуске".
     */
    fun isCheckTempOnceOnStartup(): Boolean {
        return prefsManager.checkTempOnceOnStartup
    }

    /**
     * Устанавливает таймер автоотключения подогрева.
     * @param minutes время в минутах (0 = всегда, 1-20 = автоотключение)
     */
    fun setAutoOffTimer(minutes: Int) {
        Log.i(TAG, "Setting auto-off timer to: $minutes minutes")
        prefsManager.autoOffTimerMinutes = minutes
        // Сбрасываем таймер и заставляем логику пересчитаться с новым значением
        heatingActivatedAt = 0L
        resetDecisionState("auto-off timer changed to $minutes")
    }

    /**
     * Получает текущую настройку таймера автоотключения.
     * @return время в минутах (0 = всегда)
     */
    fun getAutoOffTimer(): Int {
        return prefsManager.autoOffTimerMinutes
    }

    /**
     * Устанавливает источник температуры для условия включения подогрева.
     * @param source "cabin" или "ambient"
     */
    fun setTemperatureSource(source: String) {
        Log.i(TAG, "Setting temperature source to: $source")
        prefsManager.temperatureSource = source
        // Источник температуры поменялся – пересчитываем решение
        resetDecisionState("temperature source changed to $source")
    }

    /**
     * Получает текущий источник температуры.
     * @return "cabin" или "ambient"
     */
    fun getTemperatureSource(): String {
        return prefsManager.temperatureSource
    }

    /**
     * Уведомляет Repository о ручном вмешательстве пользователя.
     * Активирует окно "тишины" на 5 минут.
     * ПЕРЕЗАПУСКАЕТ таймер автоотключения (начинает отсчет заново).
     * СБРАСЫВАЕТ блокировку disabledByTimer (пользователь явно хочет контроль).
     * @param driverLevel уровень водителя установленный вручную (для отображения)
     * @param passengerLevel уровень пассажира установленный вручную (для отображения)
     * @param currentDriverLevel текущий уровень HVAC водителя
     * @param currentPassengerLevel текущий уровень HVAC пассажира
     */
    fun notifyManualOverride(
        driverLevel: Int?,
        passengerLevel: Int?,
        currentDriverLevel: Int? = null,
        currentPassengerLevel: Int? = null
    ) {
        Log.i(TAG, "Manual override detected: driver=$driverLevel, passenger=$passengerLevel")

        // КРИТИЧНО: Сбрасываем блокировку таймера - пользователь явно хочет управлять!
        disabledByTimer = false

        // КРИТИЧНО: ПЕРЕЗАПУСКАЕМ таймер автоотключения (не сбрасываем, а начинаем заново)!
        // Таймер работает для ЛЮБОГО подогрева (авто или ручного).
        // По истечении времени подогрев ОТКЛЮЧИТСЯ.
        val autoOffTimerMinutes = prefsManager.autoOffTimerMinutes
        if (autoOffTimerMinutes > 0) {
            heatingActivatedAt = System.currentTimeMillis()
            Log.d(TAG, "Manual override: RESTARTING auto-off timer ($autoOffTimerMinutes min)")
        } else {
            heatingActivatedAt = 0L
            Log.d(TAG, "Manual override: timer disabled (0 min)")
        }

        // Обновляем state с информацией о ручном вмешательстве
        _heatingState.value = _heatingState.value.copy(
            manualOverrideDetected = true,
            manualDriverLevel = driverLevel,
            manualPassengerLevel = passengerLevel,
            lastManualOverrideTime = System.currentTimeMillis(),
            currentDriverLevel = currentDriverLevel ?: driverLevel,
            currentPassengerLevel = currentPassengerLevel ?: passengerLevel,
            heatingActivatedAt = heatingActivatedAt // Обновляем время активации (таймер перезапущен)
        )
    }

    /**
     * Уведомляет Repository что первичная установка HVAC завершена успешно.
     * После этого начнется проверка на ручное вмешательство.
     * @param driverLevel текущий уровень HVAC водителя
     * @param passengerLevel текущий уровень HVAC пассажира
     */
    fun notifySetupComplete(driverLevel: Int, passengerLevel: Int) {
        Log.i(TAG, "Initial HVAC setup complete: driver=$driverLevel, passenger=$passengerLevel")

        // Обновляем state с флагом завершения первичной установки
        _heatingState.value = _heatingState.value.copy(
            initialSetupComplete = true,
            currentDriverLevel = driverLevel,
            currentPassengerLevel = passengerLevel,
            settingsJustChanged = false  // Сбрасываем флаг после успешной установки
        )
    }

    /**
     * Возобновляет автоконтроль после ручного вмешательства.
     * Сбрасывает окно "тишины" и заставляет систему пересчитать состояние.
     * СБРАСЫВАЕТ блокировку disabledByTimer если была.
     */
    fun resumeAutoControl() {
        Log.i(TAG, "Resuming auto control after manual override")

        // Сохраняем текущие уровни HVAC перед сбросом
        val currentDriver = _heatingState.value.currentDriverLevel
        val currentPassenger = _heatingState.value.currentPassengerLevel

        _heatingState.value = _heatingState.value.copy(
            manualOverrideDetected = false,
            manualDriverLevel = null,
            manualPassengerLevel = null,
            lastManualOverrideTime = 0L,
            // Сохраняем текущие уровни - они нужны для UI
            currentDriverLevel = currentDriver,
            currentPassengerLevel = currentPassenger,
            // НЕ устанавливаем settingsJustChanged=true здесь, т.к. resetDecisionState() сделает это
        )

        // Сбрасываем решение чтобы логика пересчиталась
        // resetDecisionState также сбросит disabledByTimer
        resetDecisionState("auto control resumed")
    }

    /**
     * Освобождает ресурсы.
     */
    fun release() {
        stopAutoHeating()
        scope.cancel()
        Log.i(TAG, "HeatingControlRepository released")
    }
}
