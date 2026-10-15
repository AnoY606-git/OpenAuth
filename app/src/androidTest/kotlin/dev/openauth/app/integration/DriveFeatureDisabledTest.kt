package dev.openauth.app.integration

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import dev.openauth.app.drive.DriveBackupScheduler
import dev.openauth.app.drive.DriveBackupWorker
import dev.openauth.app.drive.DriveUploadEngine
import dev.openauth.app.drive.DriveWorkerOwner
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise production entry points; the independent Drive engine tests stay enabled. */
@RunWith(AndroidJUnit4::class)
class DriveFeatureDisabledTest {
    @Test fun queuedWorkerCompletesWithoutAccessingItsUploadEngine() = runBlocking {
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()), DriveWorkerOwner {
            override fun getApplicationContext(): Context = this
            override val driveUploadEngine: DriveUploadEngine
                get() = error("Disabled worker must not access authorization or upload dependencies")
        }
        val worker = TestListenableWorkerBuilder<DriveBackupWorker>(context)
            .setInputData(workDataOf(DriveBackupWorker.CONFIGURATION_KEY to UUID.randomUUID().toString()))
            .build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
    }

    @Test fun schedulerDoesNotCreateCloudWorkWhileFeatureIsDisabled() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val work = WorkManager.getInstance(context)
        val configuration = UUID.randomUUID()
        val name = "openauth-drive-ciphertext-$configuration"
        try {
            DriveBackupScheduler(context).enqueue(configuration)
            assertTrue("Disabled Drive must not create background work",
                work.getWorkInfosForUniqueWork(name).get(10, TimeUnit.SECONDS).isEmpty())
        } finally {
            work.cancelUniqueWork(name).result.get(10, TimeUnit.SECONDS)
        }
    }
}
