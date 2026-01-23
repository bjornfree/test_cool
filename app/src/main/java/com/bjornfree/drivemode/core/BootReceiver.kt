package com.bjornfree.drivemode.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.ExistingPeriodicWorkPolicy
import java.util.concurrent.TimeUnit

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        // Можно дать настройку в префах: включен ли автозапуск
        val prefs = context.getSharedPreferences("drivemode_prefs", Context.MODE_PRIVATE)
        val autoStart = prefs.getBoolean("autostart_on_boot", true)

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // Используем WorkManager для надежного запуска с задержкой
                val startServicesWork = OneTimeWorkRequestBuilder<StartServicesWorker>()
                    .setInitialDelay(4, TimeUnit.SECONDS)
                    .build()

                WorkManager.getInstance(context).enqueue(startServicesWork)

                // Запускаем периодический watchdog для проверки и перезапуска сервисов
                // Используем uniqueWork чтобы не создавать дубликаты при множественных перезагрузках
                // Каждые 5 минут для быстрого обнаружения падений после долгой стоянки
                val watchdogWork = PeriodicWorkRequestBuilder<ServiceWatchdogWorker>(
                    5, TimeUnit.MINUTES
                ).build()

                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    "ServiceWatchdogWork",
                    ExistingPeriodicWorkPolicy.KEEP, // Не перезаписываем если уже есть
                    watchdogWork
                )
            }
        }
    }
}

/**
 * Worker для запуска сервисов после перезагрузки.
 * WorkManager гарантирует выполнение даже если процесс BroadcastReceiver завершится.
 */
class StartServicesWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {
        return try {
            // Стартуем сервис визуализации режима
            val driveModeIntent = Intent(applicationContext, DriveModeService::class.java)
            applicationContext.startForegroundService(driveModeIntent)

            // Стартуем сервис автоподогрева сидений
            val heaterIntent = Intent(applicationContext, AutoSeatHeatService::class.java)
            applicationContext.startForegroundService(heaterIntent)

            // Стартуем сервис мониторинга параметров автомобиля
            VehicleMetricsService.start(applicationContext)

            DriveModeService.logConsole("BootReceiver: services started via WorkManager")
            Result.success()
        } catch (e: Exception) {
            DriveModeService.logConsole("BootReceiver: error starting services: ${e.javaClass.simpleName}: ${e.message}")
            Result.failure()
        }
    }
}

/**
 * Watchdog Worker для периодической проверки состояния сервисов.
 * Перезапускает упавшие сервисы автоматически.
 *
 * УЛУЧШЕНИЯ v2:
 * - Проверка здоровья repositories (не только сервисов)
 * - Перезапуск сервисов если repository не работает корректно
 * - Детальное логирование для отладки
 */
class ServiceWatchdogWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {
        return try {
            DriveModeService.logConsole("🐕 Watchdog: начало проверки...")
            var issuesFound = 0

            // Watchdog ВСЕГДА работает и проверяет сервисы 24/7
            // Настройка autostart_on_boot влияет только на запуск при загрузке системы

            // ========================================
            // 1. Проверяем AutoSeatHeatService
            // ========================================
            val autoHeaterRunning = AutoSeatHeatService.isServiceRunning()
            if (!autoHeaterRunning) {
                DriveModeService.logConsole("Watchdog: ⚠ AutoSeatHeatService не работает, перезапускаем...")
                issuesFound++
                try {
                    AutoSeatHeatService.start(applicationContext)
                    DriveModeService.logConsole("Watchdog: ✓ AutoSeatHeatService перезапущен")
                } catch (e: Exception) {
                    DriveModeService.logConsole("Watchdog: ✗ Не удалось перезапустить AutoSeatHeatService: ${e.message}")
                }
            } else {
                DriveModeService.logConsole("Watchdog: ✓ AutoSeatHeatService работает")
            }

            // ========================================
            // 2. Проверяем DriveModeService
            // ========================================
            val driveModeRunning = DriveModeService.isRunning
            if (!driveModeRunning) {
                DriveModeService.logConsole("Watchdog: ⚠ DriveModeService не работает, перезапускаем...")
                issuesFound++
                try {
                    val intent = Intent(applicationContext, DriveModeService::class.java)
                    applicationContext.startForegroundService(intent)
                    DriveModeService.logConsole("Watchdog: ✓ DriveModeService перезапущен")
                } catch (e: Exception) {
                    DriveModeService.logConsole("Watchdog: ✗ Не удалось перезапустить DriveModeService: ${e.message}")
                }
            } else {
                DriveModeService.logConsole("Watchdog: ✓ DriveModeService работает")
            }

            // ========================================
            // 3. Проверяем VehicleMetricsService
            // ========================================
            val metricsRunning = VehicleMetricsService.isServiceRunning()
            if (!metricsRunning) {
                DriveModeService.logConsole("Watchdog: ⚠ VehicleMetricsService не работает, перезапускаем...")
                issuesFound++
                try {
                    VehicleMetricsService.start(applicationContext)
                    DriveModeService.logConsole("Watchdog: ✓ VehicleMetricsService перезапущен")
                } catch (e: Exception) {
                    DriveModeService.logConsole("Watchdog: ✗ Не удалось перезапустить VehicleMetricsService: ${e.message}")
                }
            } else {
                DriveModeService.logConsole("Watchdog: ✓ VehicleMetricsService работает")
            }

            // ========================================
            // 4. ГЛУБОКАЯ ПРОВЕРКА: Здоровье repositories
            // ========================================
            // Проверяем что repositories внутри сервисов действительно работают
            // Это критично после долгого простоя (машина стояла выключена несколько часов/дней)

            // Получаем IgnitionStateRepository из Koin
            try {
                val koin = org.koin.core.context.GlobalContext.get()
                val ignitionRepo = koin.get<com.bjornfree.drivemode.data.repository.IgnitionStateRepository>()

                val isHealthy = ignitionRepo.isHealthy()
                if (!isHealthy) {
                    DriveModeService.logConsole("Watchdog: ⚠ IgnitionStateRepository не здорова!")
                    DriveModeService.logConsole("Watchdog: ${ignitionRepo.getMonitoringStatus()}")
                    issuesFound++

                    // Перезапускаем AutoSeatHeatService чтобы перезапустить мониторинг
                    DriveModeService.logConsole("Watchdog: Перезапускаем AutoSeatHeatService для восстановления мониторинга...")
                    try {
                        AutoSeatHeatService.restartService(applicationContext)
                        DriveModeService.logConsole("Watchdog: ✓ Мониторинг зажигания перезапущен")
                    } catch (e: Exception) {
                        DriveModeService.logConsole("Watchdog: ✗ Ошибка перезапуска: ${e.message}")
                    }
                } else {
                    DriveModeService.logConsole("Watchdog: ✓ IgnitionStateRepository здорова")
                }
            } catch (e: Exception) {
                DriveModeService.logConsole("Watchdog: ⚠ Не удалось проверить здоровье repositories: ${e.message}")
            }

            // ========================================
            // ИТОГ
            // ========================================
            if (issuesFound == 0) {
                DriveModeService.logConsole("🐕 Watchdog: ✓ Все сервисы работают нормально")
            } else {
                DriveModeService.logConsole("🐕 Watchdog: ⚠ Найдено и исправлено проблем: $issuesFound")
            }

            Result.success()
        } catch (e: Exception) {
            DriveModeService.logConsole("Watchdog: ✗ Ошибка проверки: ${e.javaClass.simpleName}: ${e.message}")
            Result.retry()
        }
    }
}
