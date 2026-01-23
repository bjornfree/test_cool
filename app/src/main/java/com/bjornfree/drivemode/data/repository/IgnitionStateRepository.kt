package com.bjornfree.drivemode.data.repository

import android.util.Log
import com.bjornfree.drivemode.data.car.CarPropertyManagerSingleton
import com.bjornfree.drivemode.data.constants.VehiclePropertyConstants
import com.bjornfree.drivemode.data.preferences.PreferencesManager
import com.bjornfree.drivemode.domain.model.IgnitionState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Repository для мониторинга состояния зажигания.
 *
 * КОНСОЛИДАЦИЯ:
 * - Заменяет дублированную логику мониторинга зажигания из:
 *   - DriveModeService.startIgnitionMonitoring() (lines 530-605)
 *   - AutoHeaterService.startIgnitionMonitoring() (lines 332-404)
 * - Единый source of truth для состояния зажигания
 * - Автоматическое сохранение истории состояний
 *
 * УЛУЧШЕНИЯ v2:
 * - Callback-based мониторинг для мгновенного реагирования
 * - Автоматический fallback на polling если callback не работает
 * - Heartbeat для проверки живости мониторинга
 *
 * @param carManager singleton для чтения свойств
 * @param prefsManager для сохранения истории
 */
class IgnitionStateRepository(
    private val carManager: CarPropertyManagerSingleton,
    private val prefsManager: PreferencesManager
) {
    companion object {
        private const val TAG = "IgnitionStateRepo"
        private const val POLL_INTERVAL_MS = 2500L  // Опрос каждые 2.5 секунды (fallback)
        private const val MAX_CONSECUTIVE_ERRORS = 5
        private const val CALLBACK_TIMEOUT_MS = 10_000L  // Таймаут для проверки что callback работает
        private const val HEARTBEAT_INTERVAL_MS = 30_000L  // Проверка живости каждые 30 секунд
    }

    // Реактивный state
    private val _ignitionState = MutableStateFlow(IgnitionState.UNKNOWN)
    val ignitionState: StateFlow<IgnitionState> = _ignitionState.asStateFlow()

    // Coroutine scope
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null
    private var heartbeatJob: Job? = null

    @Volatile
    private var isMonitoring = false

    @Volatile
    private var usingCallback = false

    @Volatile
    private var callbackListener: Any? = null

    private var consecutiveErrors = 0

    // Heartbeat для проверки живости
    @Volatile
    private var lastEventTimestamp = 0L

    @Volatile
    private var lastHeartbeatCheck = 0L

    /**
     * Запускает мониторинг состояния зажигания.
     * Сначала пытается использовать callback для мгновенного реагирования.
     * Если callback не работает или не поддерживается - fallback на polling.
     */
    fun startMonitoring() {
        if (isMonitoring) {
            Log.d(TAG, "Monitoring already running")
            return
        }

        Log.i(TAG, "🚗 Starting ignition monitoring...")
        isMonitoring = true
        lastEventTimestamp = System.currentTimeMillis()
        lastHeartbeatCheck = System.currentTimeMillis()

        // Загружаем последнее известное состояние из preferences
        val lastState = prefsManager.lastIgnitionState
        if (lastState != -1) {
            _ignitionState.value = IgnitionState.fromRaw(lastState)
            Log.d(TAG, "Restored last ignition state: $lastState")
        }

        // ПРИОРИТЕТ 1: Пытаемся использовать callback для мгновенного реагирования
        val callbackSuccess = tryStartCallbackMonitoring()

        if (callbackSuccess) {
            Log.i(TAG, "✓ Using CALLBACK-based monitoring (instant response)")

            // Запускаем heartbeat для проверки что callback работает
            startHeartbeat()
        } else {
            Log.w(TAG, "⚠ Callback not supported, using POLLING fallback (2.5s delay)")
            startPollingMonitoring()
        }

        Log.i(TAG, "Ignition monitoring started (mode: ${if (usingCallback) "CALLBACK" else "POLLING"})")
    }

    /**
     * Пытается запустить мониторинг через callback.
     * @return true если callback успешно зарегистрирован
     */
    private fun tryStartCallbackMonitoring(): Boolean {
        return try {
            Log.d(TAG, "Attempting to register ignition callback...")

            callbackListener = carManager.registerIntPropertyCallback(
                propertyId = VehiclePropertyConstants.VEHICLE_PROPERTY_IGNITION_STATE,
                areaId = 0
            ) { rawState ->
                // Callback вызывается при изменении состояния зажигания
                onIgnitionStateChanged(rawState)
            }

            if (callbackListener != null) {
                usingCallback = true

                // Читаем текущее состояние один раз для инициализации
                val currentState = readIgnitionState()
                if (currentState != null) {
                    onIgnitionStateChanged(currentState)
                }

                Log.i(TAG, "✓ Callback registered successfully for ignition monitoring")
                true
            } else {
                Log.w(TAG, "✗ registerIntPropertyCallback returned null")
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "✗ Failed to register callback: ${e.message}", e)
            false
        }
    }

    /**
     * Запускает polling-based мониторинг (fallback).
     */
    private fun startPollingMonitoring() {
        usingCallback = false

        monitorJob = scope.launch {
            while (isActive && isMonitoring) {
                try {
                    val rawState = readIgnitionState()

                    if (rawState != null) {
                        // Успешно прочитали
                        consecutiveErrors = 0
                        onIgnitionStateChanged(rawState)
                    } else {
                        // Ошибка чтения
                        consecutiveErrors++
                        if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                            Log.w(TAG, "Failed to read ignition state $consecutiveErrors times in a row")
                        }
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "Error in ignition monitoring", e)
                    consecutiveErrors++
                }

                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * Обработчик изменения состояния зажигания (вызывается из callback или polling).
     * @param rawState сырое состояние зажигания
     */
    private fun onIgnitionStateChanged(rawState: Int) {
        lastEventTimestamp = System.currentTimeMillis()

        val newState = IgnitionState.fromRaw(rawState)
        val previousState = _ignitionState.value

        // Проверяем изменение состояния
        if (newState.rawState != previousState.rawState) {
            Log.i(TAG, "🚗 Ignition state changed: ${previousState.stateName} -> ${newState.stateName}")

            // Сохраняем в preferences
            prefsManager.saveIgnitionState(rawState, newState.isOff)

            // Обновляем state
            _ignitionState.value = newState
        }
    }

    /**
     * Запускает heartbeat для проверки живости мониторинга.
     * Если callback перестал работать - переключается на polling.
     */
    private fun startHeartbeat() {
        heartbeatJob = scope.launch {
            while (isActive && isMonitoring && usingCallback) {
                delay(HEARTBEAT_INTERVAL_MS)

                val now = System.currentTimeMillis()
                val timeSinceLastEvent = now - lastEventTimestamp

                // Если за последние 60 секунд не было ни одного события - проверяем вручную
                if (timeSinceLastEvent > 60_000) {
                    Log.d(TAG, "❤ Heartbeat: no events for ${timeSinceLastEvent / 1000}s, checking manually...")

                    try {
                        val currentState = readIgnitionState()
                        if (currentState != null) {
                            // Проверяем изменилось ли состояние
                            val expectedState = _ignitionState.value.rawState
                            if (currentState != expectedState) {
                                Log.w(TAG, "⚠ Heartbeat: callback missed state change! Expected=$expectedState, Actual=$currentState")
                                Log.w(TAG, "⚠ Switching to POLLING mode...")

                                // Callback не работает - переключаемся на polling
                                switchToPolling()
                                return@launch
                            } else {
                                // Состояние не изменилось - callback просто не срабатывает т.к. нет изменений
                                Log.d(TAG, "❤ Heartbeat OK: state unchanged (${_ignitionState.value.stateName})")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "❤ Heartbeat check failed: ${e.message}")
                    }
                }

                lastHeartbeatCheck = now
            }
        }
    }

    /**
     * Переключается с callback на polling mode.
     */
    private fun switchToPolling() {
        Log.w(TAG, "Switching from CALLBACK to POLLING mode...")

        // Отменяем callback
        try {
            carManager.unregisterCallback(callbackListener)
            callbackListener = null
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering callback", e)
        }

        usingCallback = false
        heartbeatJob?.cancel()
        heartbeatJob = null

        // Запускаем polling
        startPollingMonitoring()

        Log.i(TAG, "✓ Switched to POLLING mode")
    }

    /**
     * Останавливает мониторинг.
     */
    fun stopMonitoring() {
        Log.i(TAG, "Stopping ignition monitoring...")
        isMonitoring = false

        // Останавливаем polling job если запущен
        monitorJob?.cancel()
        monitorJob = null

        // Останавливаем heartbeat job если запущен
        heartbeatJob?.cancel()
        heartbeatJob = null

        // Отменяем callback если был зарегистрирован
        if (callbackListener != null) {
            try {
                carManager.unregisterCallback(callbackListener)
                Log.d(TAG, "Callback unregistered")
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering callback", e)
            }
            callbackListener = null
        }

        usingCallback = false
    }

    /**
     * Читает сырое состояние зажигания.
     * @return raw ignition state или null при ошибке
     */
    private fun readIgnitionState(): Int? {
        return carManager.readIntProperty(VehiclePropertyConstants.VEHICLE_PROPERTY_IGNITION_STATE)
    }

    /**
     * Получает текущее состояние синхронно.
     * @return текущее IgnitionState
     */
    fun getCurrentState(): IgnitionState = _ignitionState.value

    /**
     * Проверяет был ли "свежий старт".
     * @return true если зажигание было выключено достаточно долго
     */
    fun isFreshStart(): Boolean = prefsManager.isFreshStart()

    /**
     * Проверяет здоровье мониторинга.
     * Используется Watchdog'ом для проверки что мониторинг действительно работает.
     *
     * @return true если мониторинг работает нормально
     */
    fun isHealthy(): Boolean {
        if (!isMonitoring) {
            Log.w(TAG, "Health check FAILED: monitoring not active")
            return false
        }

        val now = System.currentTimeMillis()
        val timeSinceLastEvent = now - lastEventTimestamp

        // Если прошло больше 5 минут без событий - подозрительно
        if (timeSinceLastEvent > 5 * 60 * 1000) {
            Log.w(TAG, "Health check WARNING: no events for ${timeSinceLastEvent / 1000}s")

            // Проверяем вручную - может состояние просто не менялось
            try {
                val currentState = readIgnitionState()
                if (currentState != null) {
                    val expectedState = _ignitionState.value.rawState
                    if (currentState != expectedState) {
                        Log.e(TAG, "Health check FAILED: missed state change! Expected=$expectedState, Actual=$currentState")
                        return false
                    } else {
                        // Состояние не изменилось - всё нормально
                        Log.d(TAG, "Health check OK: state stable (${_ignitionState.value.stateName})")
                        return true
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Health check FAILED: error reading state", e)
                return false
            }
        }

        Log.d(TAG, "Health check OK: last event ${timeSinceLastEvent / 1000}s ago")
        return true
    }

    /**
     * Получает статус мониторинга для отладки.
     * @return строка с информацией о состоянии мониторинга
     */
    fun getMonitoringStatus(): String {
        val now = System.currentTimeMillis()
        val timeSinceEvent = (now - lastEventTimestamp) / 1000
        val mode = if (usingCallback) "CALLBACK" else "POLLING"

        return buildString {
            append("Ignition Monitoring Status:\n")
            append("  Mode: $mode\n")
            append("  Active: $isMonitoring\n")
            append("  Current State: ${_ignitionState.value.stateName}\n")
            append("  Last Event: ${timeSinceEvent}s ago\n")
            if (usingCallback) {
                val timeSinceHeartbeat = (now - lastHeartbeatCheck) / 1000
                append("  Last Heartbeat: ${timeSinceHeartbeat}s ago\n")
            }
            append("  Consecutive Errors: $consecutiveErrors\n")
        }
    }

    /**
     * Освобождает ресурсы.
     */
    fun release() {
        stopMonitoring()
        scope.cancel()
        Log.i(TAG, "IgnitionStateRepository released")
    }
}
