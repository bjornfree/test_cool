package com.bjornfree.drivemode.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bjornfree.drivemode.R
import com.bjornfree.drivemode.core.DriveModeService
import com.bjornfree.drivemode.presentation.viewmodel.AutoHeatingViewModel
import com.bjornfree.drivemode.presentation.viewmodel.ConsoleViewModel
import com.bjornfree.drivemode.presentation.viewmodel.DiagnosticsViewModel
import com.bjornfree.drivemode.presentation.viewmodel.SettingsViewModel
import com.bjornfree.drivemode.presentation.viewmodel.VehicleInfoViewModel
import com.bjornfree.drivemode.ui.tabs.AutoHeatingTabOptimized
import com.bjornfree.drivemode.ui.tabs.ConsoleTabOptimized
import com.bjornfree.drivemode.ui.tabs.DiagnosticsTabOptimized
import com.bjornfree.drivemode.ui.tabs.VehicleInfoTabOptimized
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel

/**
 * Современный UI для планшета 14" с Material Design 3.
 * Адаптивная раскладка, красивые карточки, приятная типографика.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModernTabletUI() {
    val ctx = LocalContext.current

    // Inject ViewModels через Koin
    val vehicleInfoViewModel: VehicleInfoViewModel = koinViewModel()
    val autoHeatingViewModel: AutoHeatingViewModel = koinViewModel()
    val diagnosticsViewModel: DiagnosticsViewModel = koinViewModel()
    val consoleViewModel: ConsoleViewModel = koinViewModel()
    val settingsViewModel: SettingsViewModel = koinViewModel()

    var selectedTab by remember { mutableIntStateOf(0) }
    var showAbout by remember { mutableStateOf(false) }

    val tabs = listOf(
        TabItem("Бортовой ПК", Icons.Default.Star),      // Информация об авто
        TabItem("Автоподогрев", Icons.Default.Favorite), // Настройки подогрева
        TabItem("Диагностика", Icons.Default.Build),
        TabItem("Консоль", Icons.Default.List),
        TabItem("Настройки", Icons.Default.Settings)
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "DriveMode",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                actions = {
                    IconButton(onClick = {
                        showAbout = true
                    }) {
                        Icon(Icons.Default.Info, "О приложении")
                    }
                }
            )
        }
    ) { paddingValues ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Navigation Rail для планшета
            NavigationRail(
                modifier = Modifier.fillMaxHeight(),
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Spacer(Modifier.height(12.dp))
                tabs.forEachIndexed { index, item ->
                    NavigationRailItem(
                        icon = { Icon(item.icon, contentDescription = item.title) },
                        label = { Text(item.title) },
                        selected = selectedTab == index,
                        onClick = { selectedTab = index }
                    )
                }
            }

            // Основной контент
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
                    .padding(24.dp)
            ) {
                when (selectedTab) {
                    0 -> VehicleInfoTabOptimized(viewModel = vehicleInfoViewModel)
                    1 -> AutoHeatingTabOptimized(viewModel = autoHeatingViewModel)
                    2 -> DiagnosticsTabOptimized(viewModel = diagnosticsViewModel)
                    3 -> ConsoleTabOptimized(viewModel = consoleViewModel)
                    4 -> SettingsTab(viewModel = settingsViewModel)
                }
            }
        }
    }

    // Диалог "О приложении" с QR-кодом
    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            confirmButton = {
                TextButton(onClick = { showAbout = false }) { Text("OK") }
            },
            title = { Text("О приложении") },
            text = {
                // Загружаем bitmap из raw/donate.png
                val donateBitmap = remember(ctx) {
                    try {
                        BitmapFactory.decodeResource(ctx.resources, R.raw.donate)
                    } catch (_: Exception) {
                        null
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Автор: bj0rnfree")
                    Text("Александр Сапожников")
                    Text("Geely Binyue L / Coolray", color = Color.Gray)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Поддержать разработчика",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (donateBitmap != null) {
                        Image(
                            bitmap = donateBitmap.asImageBitmap(),
                            contentDescription = "QR для доната",
                        )
                    } else {
                        Text("QR для доната не удалось загрузить")
                    }
                }
            }
        )
    }
}

data class TabItem(val title: String, val icon: ImageVector)

@Composable
fun SettingsTab(viewModel: SettingsViewModel) {
    val ctx = LocalContext.current

    // Реактивное получение настроек из ViewModel
    val borderEnabled by viewModel.borderEnabled.collectAsState()
    val panelEnabled by viewModel.panelEnabled.collectAsState()
    val metricsBarEnabled by viewModel.metricsBarEnabled.collectAsState()
    val metricsBarPosition by viewModel.metricsBarPosition.collectAsState()
    val metricsBarHeight by viewModel.metricsBarHeight.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()
    val autoDriveModeEnabled by viewModel.autoDriveModeEnabled.collectAsState()
    val selectedDriveMode by viewModel.selectedDriveMode.collectAsState()

    // Локальные состояния для разрешений (проверяются периодически)
    var overlayGranted by remember { mutableStateOf(viewModel.hasSystemAlertWindowPermission()) }
    var batteryOptimized by remember { mutableStateOf(!viewModel.isBatteryOptimizationIgnored()) }
    var serviceRunning by remember { mutableStateOf(false) }

    // Проверяем разрешения периодически
    LaunchedEffect(Unit) {
        while (true) {
            overlayGranted = viewModel.hasSystemAlertWindowPermission()
            batteryOptimized = !viewModel.isBatteryOptimizationIgnored()
            serviceRunning = DriveModeService.isRunning
            delay(2000)
        }
    }

    // Слушаем события показа сообщений из ViewModel
    LaunchedEffect(Unit) {
        viewModel.showMessage.collect { message ->
            android.widget.Toast.makeText(ctx, message, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            "Настройки",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        // Тема приложения
        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Тема приложения",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Кнопка "Авто"
                    FilterChip(
                        selected = themeMode == "auto",
                        onClick = { viewModel.setThemeMode("auto") },
                        label = { Text("Авто") },
                        modifier = Modifier.weight(1f)
                    )

                    // Кнопка "Светлая"
                    FilterChip(
                        selected = themeMode == "light",
                        onClick = { viewModel.setThemeMode("light") },
                        label = { Text("Светлая") },
                        modifier = Modifier.weight(1f)
                    )

                    // Кнопка "Темная"
                    FilterChip(
                        selected = themeMode == "dark",
                        onClick = { viewModel.setThemeMode("dark") },
                        label = { Text("Темная") },
                        modifier = Modifier.weight(1f)
                    )
                }

                Text(
                    when (themeMode) {
                        "auto" -> "Следует системной теме"
                        "light" -> "Всегда светлая тема"
                        "dark" -> "Всегда темная тема"
                        else -> "Следует системной теме"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Оверлеи и интерфейс
        Text(
            "Оверлеи и интерфейс",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )

        // Border Overlay
        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Рамка при смене режима",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "Отображать цветную рамку при переключении режимов вождения",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = borderEnabled,
                    onCheckedChange = { viewModel.setBorderEnabled(it) }
                )
            }
        }

        // Panel Overlay
        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Панель режима вождения",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "Отображать информационную панель с названием текущего режима",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = panelEnabled,
                    onCheckedChange = { viewModel.setPanelEnabled(it) }
                )
            }
        }

        // Metrics Bar
        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Нижняя полоска метрик",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "Отображать полоску с режимом, передачей, скоростью, запасом хода, температурой и давлением в шинах",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = metricsBarEnabled,
                    onCheckedChange = { viewModel.setMetricsBarEnabled(it) }
                )
            }
        }

        // Metrics Bar Position
        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Положение полоски метрик",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Кнопка "Снизу"
                    FilterChip(
                        selected = metricsBarPosition == "bottom",
                        onClick = { viewModel.setMetricsBarPosition("bottom") },
                        label = { Text("Снизу") },
                        modifier = Modifier.weight(1f)
                    )

                    // Кнопка "Сверху"
                    FilterChip(
                        selected = metricsBarPosition == "top",
                        onClick = { viewModel.setMetricsBarPosition("top") },
                        label = { Text("Сверху") },
                        modifier = Modifier.weight(1f)
                    )
                }

                Text(
                    when (metricsBarPosition) {
                        "bottom" -> "Полоска отображается внизу экрана"
                        "top" -> "Полоска отображается вверху экрана"
                        else -> "Положение по умолчанию"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Высота полоски метрик
                Text(
                    "Высота полоски",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Компактная (40dp)
                    FilterChip(
                        selected = metricsBarHeight == 40,
                        onClick = { viewModel.setMetricsBarHeight(40) },
                        label = { Text("Компакт") },
                        modifier = Modifier.weight(1f)
                    )

                    // Стандартная (56dp)
                    FilterChip(
                        selected = metricsBarHeight == 56,
                        onClick = { viewModel.setMetricsBarHeight(56) },
                        label = { Text("Стандарт") },
                        modifier = Modifier.weight(1f)
                    )

                    // Увеличенная (70dp)
                    FilterChip(
                        selected = metricsBarHeight == 70,
                        onClick = { viewModel.setMetricsBarHeight(70) },
                        label = { Text("Большая") },
                        modifier = Modifier.weight(1f)
                    )
                }

                Text(
                    "Высота: ${metricsBarHeight}dp",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Разрешения
        Text(
            "Разрешения приложения",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )

        // Overlay Permission
        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Overlay (Оверлей)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        when (overlayGranted) {
                            true -> "Разрешено ✓"
                            false -> "Запрещено - нужно для отображения режимов вождения"
                            null -> "Проверяем..."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = when (overlayGranted) {
                            true -> MaterialTheme.colorScheme.primary
                            false -> MaterialTheme.colorScheme.error
                            null -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                if (overlayGranted == false) {
                    Button(onClick = {
                        try {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${ctx.packageName}")
                            )
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            ctx.startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(ctx, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Text("Настроить")
                    }
                }
            }
        }

        // Battery Optimization
        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Энергосбережение",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        when (batteryOptimized) {
                            false -> "Исключено из оптимизации ✓"
                            true -> "Оптимизируется - может останавливать сервис"
                            null -> "Проверяем..."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = when (batteryOptimized) {
                            false -> MaterialTheme.colorScheme.primary
                            true -> MaterialTheme.colorScheme.error
                            null -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                if (batteryOptimized == true) {
                    Button(onClick = {
                        try {
                            val intent =
                                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                    data = Uri.parse("package:${ctx.packageName}")
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                            ctx.startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(ctx, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Text("Настроить")
                    }
                }
            }
        }

        // Service Status (только для информации)
        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Статус сервиса",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        if (serviceRunning) "Запущен ✓" else "Остановлен",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (serviceRunning)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
