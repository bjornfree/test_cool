package com.bjornfree.drivemode

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.ExistingPeriodicWorkPolicy
import java.util.concurrent.TimeUnit
import com.bjornfree.drivemode.core.DriveModeService
import com.bjornfree.drivemode.ui.theme.DriveModeTheme
import com.bjornfree.drivemode.core.AutoSeatHeatService
import com.bjornfree.drivemode.core.ServiceWatchdogWorker
import com.bjornfree.drivemode.core.VehicleMetricsService
import com.bjornfree.drivemode.ui.ModernTabletUI

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Стартуем ForegroundService'ы, чтобы при открытии приложения всё сразу работало
        try {
            startForegroundService(Intent(this, DriveModeService::class.java))
            startForegroundService(Intent(this, AutoSeatHeatService::class.java))
            startForegroundService(Intent(this, VehicleMetricsService::class.java))
        } catch (_: IllegalStateException) {
            startService(Intent(this, DriveModeService::class.java))
            startService(Intent(this, AutoSeatHeatService::class.java))
            startService(Intent(this, VehicleMetricsService::class.java))
        }

        // Запускаем периодический watchdog для автоматического перезапуска сервисов
        startServiceWatchdog()

        val prefs = getSharedPreferences("drivemode_prefs", Context.MODE_PRIVATE)
        val launches = prefs.getInt("launch_count", 0) + 1
        prefs.edit().putInt("launch_count", launches).apply()
        val shouldAutoplayAbout = launches >= 3

        enableEdgeToEdge()
        setContent {
            DriveModeTheme {
                // Используем новый современный UI для планшета
                ModernTabletUI()
            }
        }
    }

    /**
     * Запускает периодический watchdog для проверки и перезапуска сервисов.
     * Использует uniqueWork чтобы не создавать дубликаты.
     */
    private fun startServiceWatchdog() {
        try {
            val watchdogWork = PeriodicWorkRequestBuilder<ServiceWatchdogWorker>(
                15, TimeUnit.MINUTES
            ).build()

            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "ServiceWatchdogWork",
                ExistingPeriodicWorkPolicy.KEEP, // Не перезаписываем если уже есть
                watchdogWork
            )

            DriveModeService.logConsole("MainActivity: ServiceWatchdog scheduled (periodic 15 min)")
        } catch (e: Exception) {
            DriveModeService.logConsole("MainActivity: Failed to schedule watchdog: ${e.message}")
        }
    }
}

