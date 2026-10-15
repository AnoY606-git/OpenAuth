package dev.openauth.app.vault

import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException

/** Один файл является атомарной единицей: снимок, wrapped DEK и приватное состояние копий. */
class LocalVaultFile(
    file: File,
    private val writeBytes: (FileOutputStream, ByteArray) -> Unit = { stream, bytes -> stream.write(bytes) },
) : VaultStorage {
    private val atomic = AtomicFile(file)

    override fun exists(): Boolean = atomic.baseFile.exists() || File(atomic.baseFile.path + ".bak").exists()

    @Synchronized override fun read(): ByteArray? {
        val stream = try { atomic.openRead() } catch (failure: FileNotFoundException) {
            if (!exists()) return null
            throw failure
        }
        return stream.use {
            val size = it.channel.size()
            if (size > VaultRepository.MAX_FILE_BYTES) throw VaultException(VaultFailure.CORRUPT_STORAGE)
            val bytes = ByteArray(size.toInt())
            var offset = 0
            while (offset < bytes.size) {
                val read = it.read(bytes, offset, bytes.size - offset)
                if (read < 0) throw IOException("Неполное чтение хранилища")
                offset += read
            }
            if (it.read() != -1) throw IOException("Размер хранилища изменился при чтении")
            bytes
        }
    }

    @Synchronized override fun replacePreservingOriginal(expected: ByteArray, replacement: ByteArray, commit: (() -> Unit) -> Unit) {
        require(replacement.size <= VaultRepository.MAX_FILE_BYTES)
        if (read()?.contentEquals(expected) != true) throw VaultException(VaultFailure.LOCKED)
        val directory = File(atomic.baseFile.parentFile, "vault-recovery")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Не удалось сохранить прежнее хранилище")
        // Не перезаписываем сохранённые файлы и ограничиваем занимаемое место.
        if (directory.listFiles()?.size?.let { it >= 3 } != false) throw IOException("Архив восстановления заполнен")
        val preserved = File(directory, "vault-${java.util.UUID.randomUUID()}.oav")
        if (!preserved.createNewFile()) throw IOException("Не удалось сохранить прежнее хранилище")
        val pending = File(atomic.baseFile.parentFile, ".recovery-${java.util.UUID.randomUUID()}.tmp")
        var committed = false
        try {
            FileOutputStream(preserved).use { it.write(expected); it.fd.sync() }
            if (!preserved.readBytes().contentEquals(expected)) throw IOException("Копия не подтверждена")
            syncDirectory(directory)
            syncDirectory(atomic.baseFile.parentFile!!)
            FileOutputStream(pending).use { writeBytes(it, replacement); it.fd.sync() }
            if (!pending.readBytes().contentEquals(replacement)) throw IOException("Запись восстановления не подтверждена")
            // Единственная точка commit. После rename нет операций, способных превратить успех в ошибку.
            if (read()?.contentEquals(expected) != true) throw VaultException(VaultFailure.LOCKED)
            commit {
                java.nio.file.Files.move(pending.toPath(), atomic.baseFile.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                committed = true
            }
        } finally {
            pending.delete()
            if (!committed) preserved.delete()
        }
    }

    private fun syncDirectory(directory: File) {
        val fd = android.system.Os.open(directory.path, android.system.OsConstants.O_RDONLY or android.system.OsConstants.O_CLOEXEC, 0)
        try { android.system.Os.fsync(fd) } finally { android.system.Os.close(fd) }
    }

    @Synchronized override fun writeAtomically(bytes: ByteArray) {
        require(bytes.size <= VaultRepository.MAX_FILE_BYTES)
        val stream = atomic.startWrite()
        try {
            writeBytes(stream, bytes)
            // finishWrite в Android может лишь записать ошибку fsync в log; явно проверяем durable sync до commit.
            stream.fd.sync()
            atomic.finishWrite(stream)
            if (read()?.contentEquals(bytes) != true) throw IOException("Атомарная запись не подтверждена")
        } catch (failure: Throwable) {
            atomic.failWrite(stream)
            throw failure
        }
    }
}
