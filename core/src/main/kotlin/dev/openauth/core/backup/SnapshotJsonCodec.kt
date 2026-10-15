package dev.openauth.core.backup

import dev.openauth.core.model.EntryGroup
import dev.openauth.core.model.HashAlgorithm
import dev.openauth.core.model.HotpCounterFloor
import dev.openauth.core.model.OtpEntry
import dev.openauth.core.model.OtpSecret
import dev.openauth.core.model.OtpType
import dev.openauth.core.model.VaultSnapshot
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream

/** Общий ограниченный формат данных для локального vault и переносимой копии. */
object SnapshotJsonCodec {
    const val MAX_BYTES: Int = 8 * 1024 * 1024
    private val json = Json { encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = false }

    @OptIn(ExperimentalSerializationApi::class)
    fun encode(snapshot: VaultSnapshot): ByteArray {
        // Сначала отсеиваем явно слишком большие поля, до построения промежуточных DTO.
        var characters = 0L
        snapshot.entries.forEach { characters += it.issuer.length.toLong() + it.accountName.length }
        snapshot.groups.forEach { characters += it.name.length }
        if (characters > MAX_BYTES) limit()
        val data = SnapshotData(snapshot.schemaVersion, snapshot.revision,
            snapshot.entries.map { entry ->
                val secret = entry.secret.copyBytes()
                try {
                    EntryData(entry.id.toString(), entry.issuer, entry.accountName, Base64.getEncoder().encodeToString(secret),
                        entry.type.name, entry.algorithm.name, entry.digits, entry.periodSeconds, entry.nextCounter,
                        entry.groupId?.toString(), entry.favorite, entry.sortIndex)
                } finally { secret.fill(0) }
            }, snapshot.groups.map { GroupData(it.id.toString(), it.name) },
            snapshot.hotpCounterFloors.map { FloorData(it.fingerprint, it.nextCounter) })
        return BoundedOutput().use { output ->
            json.encodeToStream(data, output)
            output.toByteArray()
        }
    }

    fun decode(bytes: ByteArray): VaultSnapshot {
        if (bytes.size > MAX_BYTES) limit()
        checkDepth(bytes)
        try {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            val data = json.decodeFromString<SnapshotData>(text)
            if (data.entries.size > 10000) limit()
            val groups = data.groups.map { EntryGroup(uuid(it.id), it.name) }
            val entries = data.entries.map { entry ->
                if (entry.secret.length !in 16..172) invalid()
                val secret = Base64.getDecoder().decode(entry.secret)
                try {
                    if (Base64.getEncoder().encodeToString(secret) != entry.secret) invalid()
                    OtpEntry(uuid(entry.id), entry.issuer, entry.accountName, OtpSecret.fromBytes(secret),
                        OtpType.valueOf(entry.type), HashAlgorithm.valueOf(entry.algorithm), entry.digits,
                        entry.periodSeconds, entry.nextCounter, entry.groupId?.let(::uuid), entry.favorite, entry.sortIndex)
                } finally { secret.fill(0) }
            }
            return VaultSnapshot(data.schemaVersion, data.revision, entries, groups,
                data.hotpCounterFloors.map { HotpCounterFloor(it.fingerprint, it.nextCounter) })
        } catch (failure: BackupException) { throw failure
        } catch (_: IllegalArgumentException) { invalid()
        } catch (_: java.nio.charset.CharacterCodingException) { invalid() }
    }

    private fun uuid(value: String): UUID {
        val id = UUID.fromString(value)
        if (id.toString() != value) invalid()
        return id
    }

    private fun checkDepth(bytes: ByteArray) {
        var quoted = false
        var escaped = false
        var depth = 0
        for (byte in bytes) {
            val char = byte.toInt().toChar()
            if (quoted) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{', '[' -> if (++depth > 8) invalid()
                '}', ']' -> if (--depth < 0) invalid()
            }
        }
        if (depth != 0 || quoted) invalid()
    }

    private class BoundedOutput : ByteArrayOutputStream() {
        override fun write(value: Int) {
            if (count >= MAX_BYTES) limit()
            super.write(value)
        }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (length > MAX_BYTES - count) limit()
            super.write(bytes, offset, length)
        }
        override fun close() { buf.fill(0); reset() }
    }

    private fun limit(): Nothing = throw BackupException(BackupFailure.LIMIT_EXCEEDED)
    private fun invalid(): Nothing = throw BackupException(BackupFailure.INVALID_FORMAT)

    @OptIn(ExperimentalSerializationApi::class)
    @Serializable private data class SnapshotData(
        val schemaVersion: Int, val revision: Long,
        @Serializable(with = EntryListSerializer::class) val entries: List<EntryData>,
        @Serializable(with = GroupListSerializer::class) val groups: List<GroupData>,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        @Serializable(with = FloorListSerializer::class) val hotpCounterFloors: List<FloorData> = emptyList(),
    )
    @Serializable private data class EntryData(
        val id: String, val issuer: String, val accountName: String, val secret: String,
        val type: String, val algorithm: String, val digits: Int, val periodSeconds: Int,
        val nextCounter: Long, val groupId: String?, val favorite: Boolean, val sortIndex: Long,
    )
    @Serializable private data class GroupData(val id: String, val name: String)
    @Serializable private data class FloorData(val fingerprint: String, val nextCounter: Long)

    /** Stop before materializing an unbounded list of DTOs, not after decoding the whole array. */
    private open class BoundedListSerializer<T>(private val element: KSerializer<T>, private val maximum: Int) : KSerializer<List<T>> {
        private val delegate = ListSerializer(element)
        override val descriptor = delegate.descriptor
        override fun serialize(encoder: Encoder, value: List<T>) {
            if (value.size > maximum) limit()
            delegate.serialize(encoder, value)
        }
        @OptIn(ExperimentalSerializationApi::class)
        override fun deserialize(decoder: Decoder): List<T> {
            val input = decoder.beginStructure(descriptor)
            val result = ArrayList<T>()
            if (input.decodeSequentially()) {
                val size = input.decodeCollectionSize(descriptor)
                if (size !in 0..maximum) limit()
                repeat(size) { result.add(input.decodeSerializableElement(descriptor, it, element)) }
            } else while (true) {
                val index = input.decodeElementIndex(descriptor)
                if (index == CompositeDecoder.DECODE_DONE) break
                if (result.size >= maximum) limit()
                if (index != result.size) invalid()
                result.add(input.decodeSerializableElement(descriptor, index, element))
            }
            input.endStructure(descriptor)
            return result
        }
    }
    private object EntryListSerializer : BoundedListSerializer<EntryData>(EntryData.serializer(), 10_000)
    private object GroupListSerializer : BoundedListSerializer<GroupData>(GroupData.serializer(), VaultSnapshot.MAX_GROUPS)
    private object FloorListSerializer : BoundedListSerializer<FloorData>(FloorData.serializer(), VaultSnapshot.MAX_HOTP_COUNTER_FLOORS)
}
