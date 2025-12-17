package com.bjornfree.drivemode.ui.tabs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bjornfree.drivemode.domain.model.HeatingMode
import com.bjornfree.drivemode.presentation.viewmodel.AutoHeatingViewModel
import com.bjornfree.drivemode.ui.components.*
import com.bjornfree.drivemode.ui.theme.AdaptiveColors
import com.bjornfree.drivemode.ui.theme.AppTheme

/**
 * Оптимизированный AutoHeatingTab
 *
 * ОПТИМИЗАЦИИ:
 * - Минималистичный дизайн с премиальными компонентами
 * - Меньше вложенности Layout
 * - Четкая визуальная иерархия
 * - Использует PremiumSwitch, PremiumSlider и т.д.
 *
 * СОКРАЩЕНИЕ: 140 строк → ~100 строк (30%)
 */
@Composable
fun AutoHeatingTabOptimized(viewModel: AutoHeatingViewModel) {
    // Состояние из ViewModel
    val heatingState by viewModel.heatingState.collectAsState()
    val currentMode by viewModel.currentMode.collectAsState()
    val tempThreshold by viewModel.temperatureThreshold.collectAsState()
    val adaptiveHeating by viewModel.adaptiveHeating.collectAsState()
    val heatingLevel by viewModel.heatingLevel.collectAsState()
    val checkTempOnceOnStartup by viewModel.checkTempOnceOnStartup.collectAsState()
    val autoOffTimer by viewModel.autoOffTimer.collectAsState()
    val temperatureSource by viewModel.temperatureSource.collectAsState()
    val cabinTemp by viewModel.cabinTemperature.collectAsState()
    val ambientTemp by viewModel.ambientTemperature.collectAsState()

    // Доступные режимы
    val availableModes = viewModel.getAvailableModes()

    // Локальное состояние для принудительного обновления таймеров каждую секунду
    var tickState by remember { mutableStateOf(0L) }

    // КРИТИЧНО: Обновляем UI каждую секунду для таймеров
    LaunchedEffect(
        heatingState.heatingActivatedAt,
        heatingState.lastManualOverrideTime,
        heatingState.manualOverrideDetected,
        autoOffTimer
    ) {
        // Запускаем периодическое обновление только если есть активные таймеры
        val hasAutoOffTimer = autoOffTimer > 0 && heatingState.heatingActivatedAt > 0
        val hasManualOverrideTimer = heatingState.manualOverrideDetected

        if (hasAutoOffTimer || hasManualOverrideTimer) {
            while (true) {
                kotlinx.coroutines.delay(1000) // Обновляем каждую секунду
                tickState = System.currentTimeMillis()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(AppTheme.Spacing.Medium),
        verticalArrangement = Arrangement.spacedBy(AppTheme.Spacing.Medium)
    ) {
        // ============ СТАТУС ============
        PremiumCard {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(AppTheme.Spacing.Small)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Состояние подогрева
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (heatingState.isActive) "ПОДОГРЕВ АКТИВЕН" else "ПОДОГРЕВ ВЫКЛЮЧЕН",
                            fontSize = AppTheme.Typography.HeadlineMedium.first,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            color = if (heatingState.isActive) AdaptiveColors.success else AdaptiveColors.textSecondary
                        )

                        Spacer(modifier = Modifier.height(AppTheme.Spacing.Small))

                        if (heatingState.currentTemp != null) {
                            Text(
                                text = "Температура: ${heatingState.currentTemp?.toInt()}°C",
                                fontSize = AppTheme.Typography.BodyLarge.first,
                                color = AdaptiveColors.textPrimary
                            )
                        }

                        if (heatingState.reason != null) {
                            Text(
                                text = heatingState.reason ?: "",
                                fontSize = AppTheme.Typography.BodyMedium.first,
                                color = AdaptiveColors.textSecondary
                            )
                        }

                        // Таймер автоотключения (если активен)
                        if (autoOffTimer > 0 && heatingState.heatingActivatedAt > 0) {
                            // tickState обновляется каждую секунду через LaunchedEffect
                            val currentTime = if (tickState > 0) System.currentTimeMillis() else System.currentTimeMillis()
                            val timerRemaining = (autoOffTimer * 60) - ((currentTime - heatingState.heatingActivatedAt) / 1000)
                            if (timerRemaining > 0) {
                                Spacer(modifier = Modifier.height(AppTheme.Spacing.ExtraSmall))
                                Text(
                                    text = "⏱ Автоотключение через ${timerRemaining / 60}:${String.format(java.util.Locale.getDefault(), "%02d", timerRemaining % 60)}",
                                    fontSize = AppTheme.Typography.BodySmall.first,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                    color = AdaptiveColors.primary
                                )
                            }
                        }
                    }

                    // Индикатор
                    StatusIndicator(
                        isActive = heatingState.isActive,
                        activeText = "ВКЛ",
                        inactiveText = "ВЫКЛ"
                    )
                }

                // Текущие уровни HVAC (если доступны)
                if (heatingState.currentDriverLevel != null || heatingState.currentPassengerLevel != null) {
                    PremiumDivider()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(AppTheme.Spacing.Medium)
                    ) {
                        Text(
                            text = "Текущее состояние HVAC:",
                            fontSize = AppTheme.Typography.BodyMedium.first,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                            color = AdaptiveColors.textSecondary,
                            modifier = Modifier.weight(1f)
                        )

                        // Водитель
                        if (heatingState.currentDriverLevel != null) {
                            Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                                Text(
                                    text = "Водитель",
                                    fontSize = AppTheme.Typography.LabelSmall.first,
                                    color = AdaptiveColors.textSecondary
                                )
                                Text(
                                    text = "Ур. ${heatingState.currentDriverLevel}",
                                    fontSize = AppTheme.Typography.BodyLarge.first,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                    color = AdaptiveColors.textPrimary
                                )
                            }
                        }

                        // Пассажир
                        if (heatingState.currentPassengerLevel != null) {
                            Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                                Text(
                                    text = "Пассажир",
                                    fontSize = AppTheme.Typography.LabelSmall.first,
                                    color = AdaptiveColors.textSecondary
                                )
                                Text(
                                    text = "Ур. ${heatingState.currentPassengerLevel}",
                                    fontSize = AppTheme.Typography.BodyLarge.first,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                    color = AdaptiveColors.textPrimary
                                )
                            }
                        }

                        // Рекомендуемый уровень
                        Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                            Text(
                                text = "Авто",
                                fontSize = AppTheme.Typography.LabelSmall.first,
                                color = AdaptiveColors.textSecondary
                            )
                            Text(
                                text = "Ур. ${heatingState.recommendedLevel}",
                                fontSize = AppTheme.Typography.BodyLarge.first,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                color = AdaptiveColors.primary
                            )
                        }
                    }
                }
            }
        }

        // ============ ОТКЛЮЧЕНО ТАЙМЕРОМ ============
        if (!heatingState.isActive && heatingState.reason?.contains("[Таймер]") == true) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                ),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(AppTheme.Sizes.CardCornerRadius)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(AppTheme.Spacing.Medium),
                    verticalArrangement = Arrangement.spacedBy(AppTheme.Spacing.Small)
                ) {
                    Text(
                        text = "⏱ Подогрев отключен по таймеру",
                        fontSize = AppTheme.Typography.HeadlineSmall.first,
                        fontWeight = AppTheme.Typography.HeadlineSmall.second,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )

                    Text(
                        text = heatingState.reason ?: "",
                        fontSize = AppTheme.Typography.BodyMedium.first,
                        color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
                    )

                    Text(
                        text = "Подогрев останется выключенным до выключения зажигания или ручного изменения.",
                        fontSize = AppTheme.Typography.BodySmall.first,
                        color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.6f)
                    )

                    // Кнопка возобновления автоконтроля (сбросит блокировку таймера)
                    Button(
                        onClick = { viewModel.resumeAutoControl() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AdaptiveColors.primary
                        )
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(AppTheme.Spacing.Small))
                        Text("Включить автоподогрев снова")
                    }
                }
            }
        }

        // ============ РУЧНОЕ УПРАВЛЕНИЕ ============
        if (heatingState.manualOverrideDetected) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                ),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(AppTheme.Sizes.CardCornerRadius)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(AppTheme.Spacing.Medium),
                    verticalArrangement = Arrangement.spacedBy(AppTheme.Spacing.Small)
                ) {
                    Text(
                        text = "👤 Обнаружено ручное управление",
                        fontSize = AppTheme.Typography.HeadlineSmall.first,
                        fontWeight = AppTheme.Typography.HeadlineSmall.second,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )

                    // Показываем установленные вручную уровни
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(AppTheme.Spacing.Medium)
                    ) {
                        if (heatingState.manualDriverLevel != null) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Водитель",
                                    fontSize = AppTheme.Typography.BodySmall.first,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                                )
                                Text(
                                    text = "Уровень ${heatingState.manualDriverLevel}",
                                    fontSize = AppTheme.Typography.BodyLarge.first,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }

                        if (heatingState.manualPassengerLevel != null) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Пассажир",
                                    fontSize = AppTheme.Typography.BodySmall.first,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                                )
                                Text(
                                    text = "Уровень ${heatingState.manualPassengerLevel}",
                                    fontSize = AppTheme.Typography.BodyLarge.first,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                    }

                    Text(
                        text = "Авто рекомендует: Уровень ${heatingState.recommendedLevel}",
                        fontSize = AppTheme.Typography.BodyMedium.first,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                    )

                    // Показываем оставшееся время окна тишины И таймера
                    // tickState обновляется каждую секунду через LaunchedEffect
                    val currentTime = if (tickState > 0) System.currentTimeMillis() else System.currentTimeMillis()
                    val silenceRemaining = 5 * 60 - (currentTime - heatingState.lastManualOverrideTime) / 1000

                    if (silenceRemaining > 0) {
                        Text(
                            text = "⏸ Окно тишины: ${silenceRemaining / 60}:${String.format(java.util.Locale.getDefault(), "%02d", silenceRemaining % 60)}",
                            fontSize = AppTheme.Typography.BodySmall.first,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                        )
                    }

                    // Объяснение условий автоподогрева
                    Text(
                        text = buildString {
                            append("ℹ️ После возврата автоконтроля подогрев включится только если ")
                            if (adaptiveHeating) {
                                append("температура ")
                                when (temperatureSource) {
                                    "ambient" -> append("снаружи")
                                    else -> append("в салоне")
                                }
                                append(" < 10°C")
                            } else {
                                append("температура ")
                                when (temperatureSource) {
                                    "ambient" -> append("снаружи")
                                    else -> append("в салоне")
                                }
                                append(" < ${tempThreshold}°C")
                            }

                            // Показываем текущую температуру для контекста
                            val currentTemp = if (temperatureSource == "ambient") ambientTemp else cabinTemp
                            if (currentTemp != null) {
                                append(". Сейчас: ${currentTemp.toInt()}°C")
                            }

                            // Упоминание таймера
                            if (autoOffTimer > 0) {
                                append(". Таймер $autoOffTimer мин работает и для ручного режима.")
                            }
                        },
                        fontSize = AppTheme.Typography.BodySmall.first,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f),
                        lineHeight = AppTheme.Typography.BodySmall.first * 1.3f
                    )

                    // Кнопка возобновления автоконтроля
                    Button(
                        onClick = { viewModel.resumeAutoControl() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AdaptiveColors.primary
                        )
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(AppTheme.Spacing.Small))
                        Text("Возобновить автоматический контроль")
                    }
                }
            }
        }

        // ============ НАСТРОЙКИ ============
        Section(title = "Настройки") {
            PremiumCard {
                Column(verticalArrangement = Arrangement.spacedBy(AppTheme.Spacing.Small)) {
                    // Режим работы (driver/passenger/both/off)
                    ModeSelector(
                        modes = availableModes,
                        selectedMode = currentMode,
                        onModeSelect = { viewModel.setHeatingMode(it) }
                    )

                    PremiumDivider()

                    // Адаптивный режим
                    PremiumSwitch(
                        checked = adaptiveHeating,
                        onCheckedChange = { viewModel.setAdaptiveHeating(it) },
                        label = "Адаптивный режим",
                        subtitle = if (adaptiveHeating)
                            "Автоматический выбор уровня по температуре"
                        else
                            "Фиксированный уровень подогрева"
                    )

                    PremiumDivider()

                    // Проверка только при запуске
                    PremiumSwitch(
                        checked = checkTempOnceOnStartup,
                        onCheckedChange = { viewModel.setCheckTempOnceOnStartup(it) },
                        label = "Проверка только при запуске",
                        subtitle = if (checkTempOnceOnStartup)
                            "Подогрев включается/выключается один раз при запуске двигателя"
                        else
                            "Постоянный мониторинг температуры"
                    )

                    PremiumDivider()

                    // Источник температуры для условия
                    TemperatureSourceSelector(
                        source = temperatureSource,
                        cabinTemp = cabinTemp,
                        ambientTemp = ambientTemp,
                        onSourceChange = { viewModel.setTemperatureSource(it) }
                    )

                    PremiumDivider()

                    // Таймер автоотключения
                    AutoOffTimerSelector(
                        timerMinutes = autoOffTimer,
                        onTimerChange = { viewModel.setAutoOffTimer(it) }
                    )

                    // Порог температуры (если не адаптивный режим)
                    if (!adaptiveHeating) {
                        PremiumDivider()

                        PremiumSlider(
                            value = tempThreshold.toFloat(),
                            onValueChange = { viewModel.setTemperatureThreshold(it.toInt()) },
                            label = "Температурный порог",
                            valueRange = 0f..30f,
                            valueLabel = "${tempThreshold}°C"
                        )

                        PremiumDivider()

                        // Уровень подогрева
                        HeatingLevelSelector(
                            level = heatingLevel,
                            onLevelChange = { viewModel.setHeatingLevel(it) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Селектор режима работы (driver/passenger/both/off)
 */
@Composable
private fun ModeSelector(
    modes: List<HeatingMode>,
    selectedMode: HeatingMode,
    onModeSelect: (HeatingMode) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.Spacing.Small)) {
        Text(
            text = "Режим работы",
            fontSize = AppTheme.Typography.BodyLarge.first,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            color = AdaptiveColors.textPrimary
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppTheme.Spacing.Small)
        ) {
            modes.forEach { mode ->
                FilterChip(
                    selected = mode == selectedMode,
                    onClick = { onModeSelect(mode) },
                    label = { Text(mode.displayName) },
                    modifier = Modifier.weight(1f),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AdaptiveColors.primary,
                        selectedLabelColor = androidx.compose.ui.graphics.Color.White  // Белый текст на синем фоне
                    )
                )
            }
        }
    }
}

/**
 * Селектор уровня подогрева (0-3)
 */
@Composable
private fun HeatingLevelSelector(
    level: Int,
    onLevelChange: (Int) -> Unit
) {
    val levelNames = listOf("Выкл", "Низкий", "Средний", "Высокий")

    PremiumSlider(
        value = level.toFloat(),
        onValueChange = { onLevelChange(it.toInt()) },
        label = "Уровень подогрева",
        valueRange = 0f..3f,
        steps = 2,
        valueLabel = levelNames.getOrElse(level) { "?" }
    )
}

/**
 * Селектор источника температуры (cabin/ambient).
 * Показывает текущие температуры в реальном времени.
 */
@Composable
private fun TemperatureSourceSelector(
    source: String,
    cabinTemp: Float?,
    ambientTemp: Float?,
    onSourceChange: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.Spacing.Small)) {
        Text(
            text = "Источник температуры",
            fontSize = AppTheme.Typography.BodyLarge.first,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            color = AdaptiveColors.textPrimary
        )

        Text(
            text = "Какую температуру использовать для условия включения подогрева",
            fontSize = AppTheme.Typography.BodySmall.first,
            color = AdaptiveColors.textSecondary
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppTheme.Spacing.Small)
        ) {
            FilterChip(
                selected = source == "cabin",
                onClick = { onSourceChange("cabin") },
                label = {
                    Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                        Text("В салоне")
                        if (cabinTemp != null) {
                            Text(
                                text = "${cabinTemp.toInt()}°C",
                                fontSize = AppTheme.Typography.LabelSmall.first,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                            )
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = AdaptiveColors.primary,
                    selectedLabelColor = androidx.compose.ui.graphics.Color.White  // Белый текст на синем фоне
                )
            )

            FilterChip(
                selected = source == "ambient",
                onClick = { onSourceChange("ambient") },
                label = {
                    Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                        Text("Наружная")
                        if (ambientTemp != null) {
                            Text(
                                text = "${ambientTemp.toInt()}°C",
                                fontSize = AppTheme.Typography.LabelSmall.first,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                            )
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = AdaptiveColors.primary,
                    selectedLabelColor = androidx.compose.ui.graphics.Color.White  // Белый текст на синем фоне
                )
            )
        }
    }
}

/**
 * Селектор таймера автоотключения (0-20 минут)
 */
@Composable
private fun AutoOffTimerSelector(
    timerMinutes: Int,
    onTimerChange: (Int) -> Unit
) {
    val timerLabel = if (timerMinutes == 0) {
        "Всегда работает"
    } else {
        "$timerMinutes мин"
    }

    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.Spacing.Small)) {
        PremiumSlider(
            value = timerMinutes.toFloat(),
            onValueChange = { onTimerChange(it.toInt()) },
            label = "Автоотключение подогрева",
            valueRange = 0f..20f,
            valueLabel = timerLabel
        )

        Text(
            text = if (timerMinutes == 0)
                "Подогрев работает пока включено зажигание или до ручного изменения"
            else
                "Через $timerMinutes мин после активации подогрев ОТКЛЮЧИТСЯ и останется выключенным до конца поездки или ручного изменения",
            fontSize = AppTheme.Typography.BodySmall.first,
            color = AdaptiveColors.textSecondary,
            lineHeight = AppTheme.Typography.BodySmall.first * 1.3f
        )
    }
}
