package dev.openauth.app

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.openauth.app.backup.*
import dev.openauth.app.drive.*
import dev.openauth.app.vault.DeviceKeyStore
import dev.openauth.app.vault.LocalVaultFile
import dev.openauth.app.vault.VaultRepository
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class OpenAuthApplication : Application(), DriveWorkerOwner {
    lateinit var vault: VaultRepository
        private set
    lateinit var deviceKeys: DeviceKeyStore
        private set
    lateinit var backup: BackupCoordinator
        private set
    lateinit var driveConnection: DriveConnectionController
        private set
    lateinit var driveAuthorization: DriveAuthorization
        private set
    lateinit var driveRestoreBrowser: DriveRestoreBrowser
        private set
    override lateinit var driveUploadEngine: DriveUploadEngine
        private set
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        deviceKeys = DeviceKeyStore(this)
        vault = VaultRepository(LocalVaultFile(File(noBackupFilesDir, "vault.oav")), deviceKeys)
        // Конструкторы не читают ciphertext и не обращаются к Keystore. Загрузка ниже выполняется на IO.
        val queue = EncryptedSnapshotQueue(File(noBackupFilesDir, "snapshot-queue"), AndroidMetadataProtector())
        backup = BackupCoordinator(vault, queue)
        val destinations = DriveDestinationStore(
            AndroidAtomicBytes(File(noBackupFilesDir, "drive-destination")),
            AndroidMetadataProtector("OpenAuth.drive.destination.v1"),
        )
        val status = DriveSyncStatus()
        driveAuthorization = DriveAuthorization(this)
        val client = DriveRestClient()
        val scheduler = DriveBackupScheduler(this)
        driveUploadEngine = DriveUploadEngine(queue, destinations, driveAuthorization, DriveRestRemote(client), status)
        driveConnection = DriveConnectionController(queue, destinations, status, scheduler::enqueue, scheduler::cancel,
            initialSnapshot = { backup.snapshotNow() })
        driveRestoreBrowser = DriveRestoreBrowser(queue, destinations, driveAuthorization, client, status, scheduler::cancel)
        backup.startObserving(applicationScope)
        applicationScope.launch {
            try {
                queue.checkpoint()
                driveConnection.refreshStatus()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { status.publish(null, DriveSyncPhase.FAILED) }
            queue.state.map { value ->
                (value as? BackupState.Pending)?.snapshot?.let { it.configurationId to it.snapshotId }
            }.distinctUntilChanged().collect { pending ->
                if (pending != null) {
                    try {
                        // Присвоение remoteFileId не создаёт новую задачу для того же снимка.
                        // После перезапуска уже готовый ciphertext отправляется и при закрытом vault.
                        if (driveConnection.destination()?.configurationId == pending.first && queue.activeConfigurationId() == pending.first) {
                            scheduler.enqueue(pending.first)
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { status.publish(pending.first, DriveSyncPhase.FAILED) }
                }
            }
        }
        ContextCompat.registerReceiver(this, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_SCREEN_OFF) vault.lock()
            }
        }, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) { vault.lock() }
        })
    }
}
