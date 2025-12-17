package com.bjornfree.drivemode.data.constants

import com.ecarx.xui.adaptapi.car.vehicle.IDriveMode

/**
 * Централизованные константы и enum для режимов вождения.
 * Объединяет все форматы кодов в одном месте.
 *
 * ИСТОЧНИКИ ДАННЫХ:
 * 1. ECarX AdaptAPI (IDriveMode.DRIVE_MODE_SELECTION_*)
 * 2. Car API Property (значения возвращаемые из авто)
 * 3. Внутренние строковые ключи ("sport", "comfort" и т.д.)
 *
 * Использование:
 * ```kotlin
 * // Получить режим по ECarX коду
 * val mode = DriveMode.fromECarXCode(570491139) // SPORT
 *
 * // Получить режим по строковому ключу
 * val mode = DriveMode.fromKey("sport") // SPORT
 *
 * // Получить ECarX код для установки
 * val code = DriveMode.SPORT.ecarxCode // 570491139
 *
 * // Получить название для UI
 * val name = DriveMode.SPORT.displayName // "Sport"
 * ```
 */
enum class DriveMode(
    /**
     * ECarX AdaptAPI код для установки режима.
     * Используется в ICarFunction.setFunctionValue()
     */
    val ecarxCode: Int,

    /**
     * Название для отображения в UI.
     */
    val displayName: String,

    /**
     * Внутренний строковый ключ (используется в логах и DriveModeMonitor).
     */
    val key: String,

    /**
     * Краткое описание режима.
     */
    val description: String
) {
    /**
     * Режим SPORT - максимальная производительность.
     * - ECarX код: 570491139 (DRIVE_MODE_SELECTION_DYNAMIC)
     * - Спортивная езда, острые реакции
     * - Повышенный расход топлива
     */
    SPORT(
        ecarxCode = IDriveMode.DRIVE_MODE_SELECTION_DYNAMIC,
        displayName = "Sport",
        key = "sport",
        description = "Максимальная производительность"
    ),

    /**
     * Режим ECO - экономичная езда.
     * - ECarX код: 570491137 (DRIVE_MODE_SELECTION_ECO)
     * - Минимальный расход топлива
     * - Плавные реакции
     */
    ECO(
        ecarxCode = IDriveMode.DRIVE_MODE_SELECTION_ECO,
        displayName = "Eco",
        key = "eco",
        description = "Экономичная езда"
    ),

    /**
     * Режим COMFORT - комфортная езда.
     * - ECarX код: 570491138 (DRIVE_MODE_SELECTION_COMFORT)
     * - Баланс между производительностью и комфортом
     * - Универсальный режим
     */
    COMFORT(
        ecarxCode = IDriveMode.DRIVE_MODE_SELECTION_COMFORT,
        displayName = "Comfort",
        key = "comfort",
        description = "Комфортная езда"
    ),

    /**
     * Режим ADAPTIVE - адаптивный режим.
     * - ECarX код: 570491201 (DRIVE_MODE_SELECTION_ADAPTIVE)
     * - Автоматически подстраивается под стиль вождения
     * - Анализирует действия водителя
     */
    ADAPTIVE(
        ecarxCode = IDriveMode.DRIVE_MODE_SELECTION_ADAPTIVE,
        displayName = "Adaptive",
        key = "adaptive",
        description = "Адаптивный режим"
    ),

    /**
     * UNKNOWN - неизвестный режим.
     * Используется когда получено неожиданное значение.
     */
    UNKNOWN(
        ecarxCode = -1,
        displayName = "Unknown",
        key = "unknown",
        description = "Неизвестный режим"
    );

    companion object {
        /**
         * Получить режим по ECarX коду.
         *
         * @param code ECarX код (570491139, 570491137, и т.д.)
         * @return DriveMode или UNKNOWN если код неизвестен
         *
         * @example
         * ```kotlin
         * val mode = DriveMode.fromECarXCode(570491139) // SPORT
         * ```
         */
        fun fromECarXCode(code: Int): DriveMode {
            return values().firstOrNull { it.ecarxCode == code } ?: UNKNOWN
        }

        /**
         * Получить режим по строковому ключу.
         *
         * @param key строковый ключ ("sport", "eco", "comfort", "adaptive")
         * @return DriveMode или UNKNOWN если ключ неизвестен
         *
         * @example
         * ```kotlin
         * val mode = DriveMode.fromKey("sport") // SPORT
         * val mode2 = DriveMode.fromKey("normal") // UNKNOWN
         * ```
         */
        fun fromKey(key: String?): DriveMode {
            if (key == null) return UNKNOWN

            return when (key.lowercase()) {
                "sport", "dynamic" -> SPORT
                "eco", "economy" -> ECO
                "comfort", "normal" -> COMFORT
                "adaptive", "auto" -> ADAPTIVE
                else -> UNKNOWN
            }
        }

        /**
         * Список всех режимов для выбора (без UNKNOWN).
         */
        val selectableModes: List<DriveMode> = listOf(SPORT, ECO, COMFORT, ADAPTIVE)

        /**
         * Получить все ECarX коды.
         */
        val allECarXCodes: List<Int> = selectableModes.map { it.ecarxCode }

        /**
         * Получить все строковые ключи.
         */
        val allKeys: List<String> = selectableModes.map { it.key }
    }

    /**
     * Проверка что это не UNKNOWN режим.
     */
    fun isValid(): Boolean = this != UNKNOWN

    /**
     * Преобразование в строку для логирования.
     */
    override fun toString(): String {
        return "$displayName (код: $ecarxCode, ключ: $key)"
    }
}

/**
 * ID функции ECarX для установки режима вождения.
 * Используется в setCarFunctionValue(DRIVE_MODE_FUNCTION_ID, mode.ecarxCode)
 */
const val DRIVE_MODE_FUNCTION_ID = IDriveMode.DM_FUNC_DRIVE_MODE_SELECT // 570491136
