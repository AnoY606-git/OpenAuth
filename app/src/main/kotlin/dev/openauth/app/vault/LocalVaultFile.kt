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
