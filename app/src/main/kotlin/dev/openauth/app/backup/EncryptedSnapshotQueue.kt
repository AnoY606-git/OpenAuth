package dev.openauth.app.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Один экземпляр на процесс, методы вызываются на IO. Публикация metadata следует после fsync ciphertext. */
class EncryptedSnapshotQueue(
    private val directory: File,
    private val protector: MetadataProtector,
    private val metadata: AtomicBytes = AndroidAtomicBytes(File(directory, "metadata")),
) {
    private data class Model(val source: UUID, val configuration: UUID?, val records: List<PendingSnapshot>, val newestId: UUID?, val uploaded: PendingSnapshot?)
    private val mutableState = MutableStateFlow<BackupState>(BackupState.NeverCreated)
    val state = mutableState.asStateFlow()
    private var model: Model? = null
    private val claims = mutableSetOf<SnapshotClaim>()

    @Synchronized fun sourceId(): UUID = load().source
    @Synchronized fun activeConfigurationId(): UUID? = load().configuration
    @Synchronized fun setActiveConfiguration(configurationId: UUID?) {
        val before = load()
        if (before.configuration == configurationId) return
        save(before.copy(configuration = configurationId, newestId = null, records = before.records.filter { claimed(it.snapshotId) }, uploaded = null))
        cleanup()
    }
    @Synchronized fun latest(): PendingSnapshot? = load().let { current -> current.records.singleOrNull { it.configurationId == current.configuration && it.snapshotId == current.newestId } }
    @Synchronized fun checkpoint(): SnapshotCheckpoint? {
        latest()?.let { return SnapshotCheckpoint(it, false) }
        return load().uploaded?.takeIf { it.snapshotId == load().newestId }?.let { SnapshotCheckpoint(it, true) }
    }
    @Synchronized fun enqueue(
        ciphertext: ByteArray, revision: Long, generationId: String, createdAtEpochSeconds: Long,
        snapshotId: UUID = UUID.randomUUID(), expectedConfigurationId: UUID? = activeConfigurationId(),
    ): PendingSnapshot {
        val before = load()
        val configuration = before.configuration ?: throw BackupFlowException(BackupFlowFailure.NOT_CONFIGURED)
        if (configuration != expectedConfigurationId) throw BackupFlowException(BackupFlowFailure.DESTINATION_CHANGED)
        require(ciphertext.size in 176..16 * 1024 * 1024 && revision >= 0 && createdAtEpochSeconds >= 0)
        require(generationId.length in 1..128)
        if (before.records.size >= 17) throw BackupFlowException(BackupFlowFailure.BUSY)
        directory.mkdirs()
        val file = File(directory, "$snapshotId.oab")
        if (!file.createNewFile()) throw BackupFlowException(BackupFlowFailure.STORAGE)
        try {
            FileOutputStream(file).use { it.write(ciphertext); it.fd.sync() }
            val record = PendingSnapshot(snapshotId, before.source, revision, createdAtEpochSeconds, configuration, file, null, generationId)
            save(before.copy(records = before.records.filter { claimed(it.snapshotId) } + record, newestId = snapshotId))
            cleanup()
            return record
        } catch (failure: Exception) {
            if (model?.records?.none { it.snapshotId == snapshotId } != false) file.delete()
            throw failure
        }
    }
    @Synchronized fun claim(configurationId: UUID): SnapshotClaim? {
        if (load().configuration != configurationId) return null
        if (claims.size >= 16) throw BackupFlowException(BackupFlowFailure.BUSY)
        val record = latest() ?: return null
        // Один upload одного снимка одновременно; повтор берёт тот же persisted remote id после close.
        if (claimed(record.snapshotId)) return null
        if (!record.file.isFile || record.file.length() !in 176L..16L * 1024 * 1024) throw BackupFlowException(BackupFlowFailure.STORAGE)
        return SnapshotClaim(this, record).also { claims.add(it) }
    }
    @Synchronized fun assignRemoteFileId(claim: SnapshotClaim, remoteFileId: String): PendingSnapshot {
        validate(claim)
        require(remoteFileId.matches(Regex("[A-Za-z0-9_-]{1,256}")))
        val old = load().records.single { it.snapshotId == claim.snapshot.snapshotId }
        if (old.remoteFileId != null && old.remoteFileId != remoteFileId) throw BackupFlowException(BackupFlowFailure.STORAGE)
        val updated = old.copy(remoteFileId = remoteFileId)
        save(load().copy(records = load().records.map { if (it.snapshotId == old.snapshotId) updated else it }))
        claim.update(updated)
        return updated
    }
    @Synchronized fun markUploaded(claim: SnapshotClaim) {
        validate(claim)
        val record = load().records.single { it.snapshotId == claim.snapshot.snapshotId }
        if (record.remoteFileId == null) throw BackupFlowException(BackupFlowFailure.STORAGE)
        val previous = load().uploaded
        val uploaded = if (record.snapshotId == load().newestId) record else previous
        save(load().copy(records = load().records.filterNot { it.snapshotId == record.snapshotId }, uploaded = uploaded))
        cleanup()
    }
    @Synchronized internal fun release(claim: SnapshotClaim) {
        if (!claims.remove(claim)) return
        // Освобождение lease не должно маскировать ошибку worker; следующая запись повторит уборку.
        try {
            val before = load(); val latest = latest()?.snapshotId
            save(before.copy(records = before.records.filter { it.snapshotId == latest || claimed(it.snapshotId) }))
            cleanup()
        } catch (_: Exception) { /* Шифротекст остаётся для безопасного повтора. */ }
    }
    private fun validate(claim: SnapshotClaim) {
        if (claim !in claims || claim.snapshot.configurationId != load().configuration) throw BackupFlowException(BackupFlowFailure.DESTINATION_CHANGED)
        if (load().records.none { it.snapshotId == claim.snapshot.snapshotId }) throw BackupFlowException(BackupFlowFailure.STORAGE)
    }
    private fun claimed(id: UUID) = claims.any { it.snapshot.snapshotId == id }
    private fun load(): Model {
        model?.let { return it }
        directory.mkdirs()
        val encrypted = metadata.read()
        if (encrypted == null) {
            // При потере metadata существующие снимки не превращаются в новую установку молча.
            if (directory.listFiles()?.any { it.extension == "oab" } == true) throw BackupFlowException(BackupFlowFailure.STORAGE)
            return Model(UUID.randomUUID(), null, emptyList(), null, null).also { save(it) }
        }
        val plain = try { protector.open(encrypted) } catch (_: Exception) { throw BackupFlowException(BackupFlowFailure.STORAGE) }
        try {
            if (plain.size > AndroidMetadataProtector.MAX_METADATA_BYTES) throw IOException()
            val parsed = DataInputStream(ByteArrayInputStream(plain)).use { input ->
                if (input.readInt() != 1) throw IOException()
                val source = input.uuid(); val configuration = if (input.readBoolean()) input.uuid() else null
                val newest = if (input.readBoolean()) input.uuid() else null
                val count = input.readInt(); if (count !in 0..17) throw IOException()
                val records = List(count) { input.record(source) }
                val uploaded = if (input.readBoolean()) input.record(source) else null
                if (input.read() != -1 || records.map { it.snapshotId }.toSet().size != records.size) throw IOException()
                Model(source, configuration, records.filter { it.snapshotId == newest }, newest, uploaded)
            }
            model = parsed
            publishState(parsed)
            cleanup()
            return parsed
        } catch (_: Exception) { throw BackupFlowException(BackupFlowFailure.STORAGE)
        } finally { plain.fill(0) }
    }
    private fun save(next: Model) {
        val bytes = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { out ->
            out.writeInt(1); out.uuid(next.source); out.writeBoolean(next.configuration != null); next.configuration?.let { out.uuid(it) }
            out.writeBoolean(next.newestId != null); next.newestId?.let { out.uuid(it) }
            out.writeInt(next.records.size); next.records.forEach { out.record(it) }
            out.writeBoolean(next.uploaded != null); next.uploaded?.let { out.record(it) }
        } }.toByteArray()
        try { metadata.writeAtomically(protector.seal(bytes)); model = next; publishState(next) }
        finally { bytes.fill(0) }
    }
    private fun publishState(next: Model) {
        val pending = next.records.singleOrNull { it.snapshotId == next.newestId && it.configurationId == next.configuration }
        val uploaded = next.uploaded?.takeIf { it.snapshotId == next.newestId && it.configurationId == next.configuration }
        mutableState.value = when {
            pending != null -> BackupState.Pending(pending)
            uploaded != null -> BackupState.Uploaded(uploaded)
            else -> BackupState.NeverCreated
        }
    }
    private fun cleanup() {
        val keep = load().records.mapTo(HashSet()) { it.file.name }
        claims.forEach { keep.add(it.snapshot.file.name) }
        directory.listFiles()?.filter { it.extension == "oab" && it.name !in keep }?.forEach { it.delete() }
    }
    private fun DataOutputStream.uuid(value: UUID) { writeLong(value.mostSignificantBits); writeLong(value.leastSignificantBits) }
    private fun DataInputStream.uuid() = UUID(readLong(), readLong())
    private fun DataOutputStream.record(value: PendingSnapshot) {
        uuid(value.snapshotId); uuid(value.configurationId); writeLong(value.revision); writeLong(value.createdAtEpochSeconds)
        writeUTF(value.generationId); writeBoolean(value.remoteFileId != null); value.remoteFileId?.let(::writeUTF)
    }
    private fun DataInputStream.record(source: UUID): PendingSnapshot {
        val id = uuid(); val configuration = uuid(); val revision = readLong(); val time = readLong(); val generation = readUTF()
        val remote = if (readBoolean()) readUTF() else null
        if (revision < 0 || time < 0 || generation.length !in 1..128 || (remote != null && !remote.matches(Regex("[A-Za-z0-9_-]{1,256}")))) throw IOException()
        return PendingSnapshot(id, source, revision, time, configuration, File(directory, "$id.oab"), remote, generation)
    }
}

class SnapshotClaim internal constructor(private val owner: EncryptedSnapshotQueue, snapshot: PendingSnapshot) : AutoCloseable {
    var snapshot: PendingSnapshot = snapshot
        private set
    internal fun update(snapshot: PendingSnapshot) { this.snapshot = snapshot }
    override fun close() = owner.release(this)
}
