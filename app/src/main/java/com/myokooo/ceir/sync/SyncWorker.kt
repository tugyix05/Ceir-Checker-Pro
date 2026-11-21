package com.myokooo.ceir.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.myokooo.ceir.CloudHistorySync
import com.myokooo.ceir.SupabaseManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Item 13 — encrypted cloud sync (public-key design, 2026-11-21).
 *
 * Auto-sync ALWAYS: uploads are encrypted with the RSA PUBLIC key, so
 * this worker needs no pass and no unlock. Any user's phone syncs from
 * the first scan. When setup hasn't happened yet (no public key), rows
 * stay pending and the next run retries. Nothing plaintext is ever
 * uploaded. Bee unlock gates VIEWING cloud history only.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            if (!SupabaseManager.isConfigured) return@withContext Result.success()
            CloudHistorySync.uploadPending(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "sync_scans"
        const val PERIODIC_WORK_NAME = "sync_scans_periodic"

        /** One-shot upload (after a CEIR check / CSV import). */
        fun scheduleNow(context: Context) {
            try {
                val request = OneTimeWorkRequestBuilder<SyncWorker>()
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .build()
                WorkManager.getInstance(context).enqueueUniqueWork(
                    WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    request
                )
            } catch (e: Exception) {
                // Never crash the caller on scheduler failure.
            }
        }

        /** Background auto-sync, every 15 min (WorkManager minimum), network required. */
        fun schedulePeriodic(context: Context) {
            try {
                val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .build()
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    PERIODIC_WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
            } catch (e: Exception) {
                // Never crash the caller on scheduler failure.
            }
        }
    }
}
