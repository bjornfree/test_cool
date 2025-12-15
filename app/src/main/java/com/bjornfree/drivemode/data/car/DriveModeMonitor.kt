package com.bjornfree.drivemode.data.car

import android.util.Log
import com.bjornfree.drivemode.data.constants.VehiclePropertyConstants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Мониторинг режима вождения через Car API.
 *
 * ПРЕИМУЩЕСТВА над LogcatWatcher:
 * - ✅ Прямое чтение из Car API (без парсинга логов)
 * - ✅ Реактивные уведомления при изменении режима
 * - ✅ Не требует root доступа
 * - ✅ Меньше задержка (нет задержки на logcat)
 * - ✅ Надежнее (не зависит от формата логов)
 *
 * Поддерживаемые режимы:
 * - eco (137)
 * - comfort (138)
 * - sport (139)
 * - adaptive (201)
 *
 * @param carManager singleton для работы с Car API
 */
class DriveModeMonitor(private val carManager: CarPropertyManagerSingleton) {

    companion object {
        private const val TAG = "DriveModeMonitor"
    }

    // Текущий режим вождения (String: "eco", "sport", "comfort", "adaptive", или null)
    private val _currentMode = MutableStateFlow<String?>(null)
    val currentMode: StateFlow<String?> = _currentMode.asStateFlow()

    // Listener для Car API callback
    private var callbackListener: Any? = null

    /**
     * Запускает мониторинг режима вождения.
     * Регистрирует callback в Car API для получения уведомлений об изменениях.
     */
    fun startMonitoring() {
        Log.i(TAG, "Starting drive mode monitoring via Car API...")

        // Читаем текущий режим при старте
        val currentValue = carManager.readIntProperty(VehiclePropertyConstants.DRIVE_MODE)
        if (currentValue != null) {
            val mode = VehiclePropertyConstants.driveModeToString(currentValue)
            if (mode != null) {
                _currentMode.value = mode
                Log.i(TAG, "Initial drive mode: $mode (code=$currentValue)")
            } else {
                Log.w(TAG, "Unknown drive mode code: $currentValue")
            }
        } else {
            Log.w(TAG, "Failed to read initial drive mode - Car API may be unavailable")
        }

        // Регистрируем callback для реактивных обновлений
        callbackListener = carManager.registerIntPropertyCallback(
            propertyId = VehiclePropertyConstants.DRIVE_MODE,
            areaId = 0
        ) { modeCode ->
            handleModeChange(modeCode)
        }

        if (callbackListener != null) {
            Log.i(TAG, "Drive mode callback registered successfully")
        } else {
            Log.w(TAG, "Failed to register drive mode callback - falling back to polling")
        }
    }

    /**
     * Обрабатывает изменение режима вождения.
     *
     * @param modeCode код режима от Car API
     */
    private fun handleModeChange(modeCode: Int) {
        val mode = VehiclePropertyConstants.driveModeToString(modeCode)
        if (mode != null) {
            // Обновляем только если режим изменился
            if (_currentMode.value != mode) {
                _currentMode.value = mode
                Log.i(TAG, "Drive mode changed: $mode (code=$modeCode)")
            }
        } else {
            Log.w(TAG, "Unknown drive mode code received: $modeCode")
        }
    }

    /**
     * Останавливает мониторинг и освобождает ресурсы.
     */
    fun stopMonitoring() {
        Log.i(TAG, "Stopping drive mode monitoring...")

        if (callbackListener != null) {
            carManager.unregisterCallback(callbackListener)
            callbackListener = null
            Log.i(TAG, "Drive mode callback unregistered")
        }

        _currentMode.value = null
    }

    /**
     * Получает текущий режим вождения (для одноразового чтения).
     *
     * @return строка режима ("eco", "sport", etc.) или null
     */
    fun getCurrentModeNow(): String? {
        val value = carManager.readIntProperty(VehiclePropertyConstants.DRIVE_MODE)
        return if (value != null) {
            VehiclePropertyConstants.driveModeToString(value)
        } else {
            null
        }
    }

    /**
     * Проверяет доступность мониторинга режима вождения.
     *
     * @return true если Car API доступен
     */
    fun isAvailable(): Boolean {
        return carManager.isCarApiAvailable()
    }
}
