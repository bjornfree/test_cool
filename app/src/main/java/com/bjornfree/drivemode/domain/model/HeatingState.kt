package com.bjornfree.drivemode.domain.model

/**
 * Состояние системы автоподогрева сидений.
 *
 * Используется для передачи текущего состояния подогрева
 * между Repository и ViewModel/UI.
 */
data class HeatingState(
    /**
     * Включен ли подогрев сейчас.
     */
    val isActive: Boolean = false,

    /**
     * Режим автоподогрева.
     * Возможные значения: "off", "driver", "passenger", "both"
     */
    val mode: HeatingMode = HeatingMode.OFF,

    /**
     * Адаптивный режим включен.
     * Если true - уровень подогрева зависит от температуры.
     * Если false - фиксированный уровень.
     */
    val adaptiveHeating: Boolean = false,

    /**
     * Уровень подогрева (0-3).
     * 0 = off, 1 = low, 2 = medium, 3 = high
     * Используется когда adaptiveHeating = false
     */
    val heatingLevel: Int = 2,

    /**
     * Причина включения/выключения подогрева.
     */
    val reason: String? = null,

    /**
     * Текущая температура в салоне (если доступна).
     */
    val currentTemp: Float? = null,

    /**
     * Температурный порог для автоподогрева (°C).
     */
    val temperatureThreshold: Int = 15,

    /**
     * Timestamp последнего изменения состояния.
     */
    val timestamp: Long = System.currentTimeMillis(),

    /**
     * Обнаружено ручное управление пользователем.
     * true = пользователь вручную изменил настройки HVAC
     */
    val manualOverrideDetected: Boolean = false,

    /**
     * Уровень HVAC установленный пользователем вручную (если обнаружен).
     * null = ручного изменения не было
     */
    val manualDriverLevel: Int? = null,
    val manualPassengerLevel: Int? = null,

    /**
     * Timestamp последнего ручного вмешательства.
     * Используется для окна "тишины" после ручного изменения.
     */
    val lastManualOverrideTime: Long = 0L,

    /**
     * Рекомендуемый уровень от автоподогрева (для отображения в UI).
     */
    val recommendedLevel: Int = 0,

    /**
     * Первичная установка HVAC завершена.
     * false = сервис еще ни разу не устанавливал уровни
     * true = хотя бы одна установка была выполнена
     * Используется чтобы не считать начальное состояние (0/0/0/0) ручным вмешательством.
     */
    val initialSetupComplete: Boolean = false,

    /**
     * Текущие уровни HVAC для каждого сиденья (для отображения в UI).
     * null = данные еще не считаны
     */
    val currentDriverLevel: Int? = null,
    val currentPassengerLevel: Int? = null,

    /**
     * Время активации подогрева (для таймера автоотключения).
     * 0 = таймер не активен
     * >0 = timestamp когда был активирован подогрев (авто или вручную)
     */
    val heatingActivatedAt: Long = 0L
) {
    /**
     * Проверяет что подогрев должен быть активен на основе температуры.
     * @return true если температура ниже порога
     */
    fun shouldActivateByTemp(): Boolean {
        return currentTemp != null && currentTemp < temperatureThreshold
    }

    /**
     * Проверяет активно ли окно "тишины" после ручного вмешательства.
     * @param silenceWindowMs длительность окна тишины в миллисекундах
     * @return true если окно активно
     */
    fun isInManualOverrideWindow(silenceWindowMs: Long = 5 * 60 * 1000): Boolean {
        if (lastManualOverrideTime == 0L) return false
        val elapsed = System.currentTimeMillis() - lastManualOverrideTime
        return elapsed < silenceWindowMs
    }
}

/**
 * Enum для режимов автоподогрева.
 * Совместимо со старой версией (driver/passenger/both/off).
 */
enum class HeatingMode(val key: String, val displayName: String) {
    /**
     * Подогрев выключен.
     */
    OFF("off", "Выключен"),

    /**
     * Подогрев только водительского сиденья.
     */
    DRIVER("driver", "Водитель"),

    /**
     * Подогрев только пассажирского сиденья.
     */
    PASSENGER("passenger", "Пассажир"),

    /**
     * Подогрев обоих сидений.
     */
    BOTH("both", "Оба");

    companion object {
        /**
         * Получить режим по строковому ключу.
         * @param key строковый ключ режима
         * @return HeatingMode или OFF если не найдено
         */
        fun fromKey(key: String?): HeatingMode {
            return values().firstOrNull { it.key == key } ?: OFF
        }
    }
}
