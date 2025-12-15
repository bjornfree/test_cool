package com.bjornfree.drivemode.presentation.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bjornfree.drivemode.data.preferences.PreferencesManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel для SettingsTab (Настройки).
 *
 * Управляет настройками приложения:
 * - Demo mode (тестирование без реального автомобиля)
 * - Border overlay (включен/выключен)
 * - Panel overlay (включен/выключен)
 * - Статистика запусков
 *
 * РЕАКТИВНОСТЬ: ViewModel подписывается на изменения настроек из PreferencesManager
 * через Flow, что позволяет UI автоматически обновляться при изменениях из сервиса.
 *
 * @param context Application context для проверки permissions
 * @param prefsManager для сохранения настроек
 */
class SettingsViewModel(
    private val context: Context,
    private val prefsManager: PreferencesManager
) : ViewModel() {

    /**
     * Demo mode (для тестирования без автомобиля).
     * РЕАКТИВНО подписан на demoModeFlow.
     */
    private val _demoMode = MutableStateFlow(prefsManager.demoMode)
    val demoMode: StateFlow<Boolean> = _demoMode.asStateFlow()

    /**
     * Border overlay включен.
     * РЕАКТИВНО подписан на overlaySettingsFlow.
     */
    private val _borderEnabled = MutableStateFlow(prefsManager.borderEnabled)
    val borderEnabled: StateFlow<Boolean> = _borderEnabled.asStateFlow()

    /**
     * Panel overlay включен.
     * РЕАКТИВНО подписан на overlaySettingsFlow.
     */
    private val _panelEnabled = MutableStateFlow(prefsManager.panelEnabled)
    val panelEnabled: StateFlow<Boolean> = _panelEnabled.asStateFlow()

    /**
     * Нижняя полоска метрик включена.
     * РЕАКТИВНО подписан на overlaySettingsFlow.
     */
    private val _metricsBarEnabled = MutableStateFlow(prefsManager.metricsBarEnabled)
    val metricsBarEnabled: StateFlow<Boolean> = _metricsBarEnabled.asStateFlow()

    /**
     * Положение полоски метрик ("bottom" или "top").
     * РЕАКТИВНО подписан на overlaySettingsFlow.
     */
    private val _metricsBarPosition = MutableStateFlow(prefsManager.metricsBarPosition)
    val metricsBarPosition: StateFlow<String> = _metricsBarPosition.asStateFlow()

    /**
     * Количество запусков приложения.
     */
    private val _launchCount = MutableStateFlow(prefsManager.launchCount)
    val launchCount: StateFlow<Int> = _launchCount.asStateFlow()

    /**
     * Режим темы приложения.
     * "auto" = следует системной теме (по умолчанию)
     * "light" = всегда светлая тема
     * "dark" = всегда темная тема
     * РЕАКТИВНО подписан на themeModeFlow.
     */
    private val _themeMode = MutableStateFlow(prefsManager.themeMode)
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    /**
     * События для показа сообщений пользователю (например Toast).
     */
    private val _showMessage = MutableSharedFlow<String>()
    val showMessage: SharedFlow<String> = _showMessage.asSharedFlow()

    init {
        // РЕАКТИВНАЯ подписка на изменения overlay настроек из PreferencesManager
        // Если сервис изменит настройки (например, сбросит при отсутствии разрешения)
        // UI автоматически обновится
        viewModelScope.launch {
            prefsManager.overlaySettingsFlow.collect { settings ->
                _borderEnabled.value = settings.borderEnabled
                _panelEnabled.value = settings.panelEnabled
                _metricsBarEnabled.value = settings.metricsBarEnabled
                _metricsBarPosition.value = settings.metricsBarPosition
            }
        }

        // РЕАКТИВНАЯ подписка на изменения темы
        viewModelScope.launch {
            prefsManager.themeModeFlow.collect { theme ->
                _themeMode.value = theme
            }
        }

        // РЕАКТИВНАЯ подписка на изменения demo mode
        viewModelScope.launch {
            prefsManager.demoModeFlow.collect { demo ->
                _demoMode.value = demo
            }
        }
    }

    /**
     * Переключает demo mode.
     */
    fun toggleDemoMode() {
        val newValue = !_demoMode.value
        prefsManager.demoMode = newValue
        _demoMode.value = newValue
    }

    /**
     * Устанавливает demo mode.
     *
     * @param enabled true для включения demo mode
     */
    fun setDemoMode(enabled: Boolean) {
        prefsManager.demoMode = enabled
        _demoMode.value = enabled
    }

    /**
     * Переключает border overlay.
     */
    fun toggleBorderOverlay() {
        val newValue = !_borderEnabled.value
        prefsManager.borderEnabled = newValue
        _borderEnabled.value = newValue
    }

    /**
     * Устанавливает border overlay.
     * Проверяет разрешение перед включением.
     *
     * @param enabled true для включения border
     */
    fun setBorderEnabled(enabled: Boolean) {
        // Если включаем - проверяем разрешение
        if (enabled && !hasSystemAlertWindowPermission()) {
            // Разрешения нет - показываем сообщение и не включаем
            viewModelScope.launch {
                _showMessage.emit("Требуется разрешение на отображение поверх других окон")
            }
            return
        }
        prefsManager.borderEnabled = enabled
        _borderEnabled.value = enabled
    }

    /**
     * Переключает panel overlay.
     */
    fun togglePanelOverlay() {
        val newValue = !_panelEnabled.value
        setPanelEnabled(newValue)
    }

    /**
     * Устанавливает panel overlay.
     * Проверяет разрешение перед включением.
     *
     * @param enabled true для включения panel
     */
    fun setPanelEnabled(enabled: Boolean) {
        // Если включаем - проверяем разрешение
        if (enabled && !hasSystemAlertWindowPermission()) {
            viewModelScope.launch {
                _showMessage.emit("Требуется разрешение на отображение поверх других окон")
            }
            return
        }
        prefsManager.panelEnabled = enabled
        _panelEnabled.value = enabled
    }

    /**
     * Переключает нижнюю полоску метрик.
     */
    fun toggleMetricsBar() {
        val newValue = !_metricsBarEnabled.value
        setMetricsBarEnabled(newValue)
    }

    /**
     * Устанавливает отображение нижней полоски метрик.
     * Проверяет разрешение перед включением.
     *
     * @param enabled true для включения полоски
     */
    fun setMetricsBarEnabled(enabled: Boolean) {
        // Если включаем - проверяем разрешение
        if (enabled && !hasSystemAlertWindowPermission()) {
            viewModelScope.launch {
                _showMessage.emit("Требуется разрешение на отображение поверх других окон")
            }
            return
        }
        prefsManager.metricsBarEnabled = enabled
        _metricsBarEnabled.value = enabled
    }

    /**
     * Устанавливает положение полоски метрик.
     *
     * @param position "bottom" или "top"
     */
    fun setMetricsBarPosition(position: String) {
        prefsManager.metricsBarPosition = position
        _metricsBarPosition.value = position
    }

    /**
     * Устанавливает режим темы приложения.
     *
     * @param mode "auto", "light", или "dark"
     */
    fun setThemeMode(mode: String) {
        prefsManager.themeMode = mode
        _themeMode.value = mode
    }

    /**
     * Сбрасывает все настройки к значениям по умолчанию.
     */
    fun resetToDefaults() {
        setDemoMode(false)
        setBorderEnabled(false)  // По умолчанию выключен
        setPanelEnabled(false)   // По умолчанию выключен
        setMetricsBarEnabled(false)  // По умолчанию выключен
        setThemeMode("auto")
    }

    /**
     * Очищает все данные (включая статистику).
     */
    fun clearAllData() {
        prefsManager.clearAll()
        // Значения обновятся автоматически через реактивные подписки
        // Не нужно вручную устанавливать _*.value
    }

    /**
     * Проверяет наличие системных permissions.
     * Используется для отображения статуса в UI.
     */
    fun hasSystemAlertWindowPermission(): Boolean {
        return android.provider.Settings.canDrawOverlays(context)
    }

    /**
     * Проверяет игнорируются ли батарейные оптимизации.
     */
    fun isBatteryOptimizationIgnored(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        return pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false
    }
}
