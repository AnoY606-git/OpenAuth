package dev.openauth.app.drive

import android.content.Context
import androidx.work.*
import java.util.UUID
import java.util.concurrent.TimeUnit

class DriveBackupScheduler(context: Context) {
    private val work = WorkManager.getInstance(context.applicationContext)
    fun enqueue(configurationId: UUID) {
        if (!DriveFeature.enabled) return
        val request = OneTimeWorkRequestBuilder<DriveBackupWorker>()
            .setInputData(workDataOf(DriveBackupWorker.CONFIGURATION_KEY to configurationId.toString()))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG).build()
        // KEEP теряет изменение между последней проверкой очереди и завершением worker.
        // APPEND_OR_REPLACE сохраняет следующий запуск и восстанавливается после failed/cancelled цепочки.
        work.enqueueUniqueWork("$TAG-$configurationId", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
    fun cancel() { work.cancelAllWorkByTag(TAG) }
    companion object { private const val TAG = "openauth-drive-ciphertext" }
}
