package dev.openauth.core.importing

import dev.openauth.core.model.HashAlgorithm
import dev.openauth.core.model.OtpEntry
import dev.openauth.core.model.OtpSecret
import dev.openauth.core.model.OtpType
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64
import java.util.Collections
import java.util.UUID

/** Проверенная часть переноса. Идентификаторы записей назначаются только полному batch. */
class MigrationPart internal constructor(
    val version: Int,
    val batchSize: Int,
    val batchIndex: Int,
    val batchId: Int,
    accounts: List<MigrationAccount>,
) {
    internal val accounts: List<MigrationAccount> = Collections.unmodifiableList(accounts.toList())
    val entryCount: Int get() = accounts.size
}

internal class MigrationAccount(
    val issuer: String,
    val name: String,
    val secret: OtpSecret,
    val type: OtpType,
    val algorithm: HashAlgorithm,
    val digits: Int,
    val counter: Long,
) {
    fun entry(id: UUID, sortIndex: Long): OtpEntry = try {
        OtpEntry(id, issuer, name, secret, type, algorithm, digits, 30, counter, null, false, sortIndex)
    } catch (_: IllegalArgumentException) {
        fail(ImportFailure.INVALID_MIGRATION)
    }
}

/** Схема Google migration проверена по Aegis и qistoph/otp_export; код написан независимо. */
object GoogleMigrationDecoder {
    fun parse(uri: String): MigrationPart {
        val parsed = parseBoundedUri(uri)
        if (!parsed.scheme.equals("otpauth-migration", true) || !parsed.rawAuthority.equals("offline", true) || !parsed.rawPath.isNullOrEmpty()) fail(ImportFailure.INVALID_URI)
        val parameters = parseQuery(parsed.rawQuery)
        val encoded = parameters["data"] ?: fail(ImportFailure.INVALID_MIGRATION)
        val bytes = try { Base64.getDecoder().decode(encoded) } catch (_: IllegalArgumentException) { fail(ImportFailure.INVALID_MIGRATION) }
        try {
            return parsePayload(ProtoReader(bytes))
        } finally {
            bytes.fill(0)
        }
    }

    /** Проверяет комплектность до вызова newId, поэтому частичный перенос не выдаёт записи. */
    fun assemble(parts: List<MigrationPart>, newId: () -> UUID): List<OtpEntry> {
        if (parts.isEmpty()) fail(ImportFailure.INCOMPLETE_BATCH)
        if (parts.size > 10_000) fail(ImportFailure.TOO_MANY_ENTRIES)
        val first = parts.first()
        val indices = HashSet<Int>()
        var count = 0
        for (part in parts) {
            if (part.version != first.version || part.batchSize != first.batchSize || part.batchId != first.batchId) fail(ImportFailure.BATCH_CONFLICT)
            if (!indices.add(part.batchIndex)) fail(ImportFailure.DUPLICATE_PART)
            count += part.entryCount
            if (count > 10_000) fail(ImportFailure.TOO_MANY_ENTRIES)
        }
        if (parts.size != first.batchSize) fail(ImportFailure.INCOMPLETE_BATCH)
        val result = ArrayList<OtpEntry>(count)
        val ids = HashSet<UUID>()
        for (part in parts.sortedBy { it.batchIndex }) {
            for (account in part.accounts) {
                val id = newId()
                if (!ids.add(id)) fail(ImportFailure.DUPLICATE_ID)
                result += account.entry(id, result.size.toLong())
            }
        }
        return Collections.unmodifiableList(result)
    }

    private fun parsePayload(reader: ProtoReader): MigrationPart {
        var version = 0
        var size = 0
        var index = 0
        var id = 0
        val seen = HashSet<Int>()
        val accounts = mutableListOf<MigrationAccount>()
        while (reader.hasRemaining()) {
            val tag = reader.tag()
            when (tag.field) {
                1 -> {
                    tag.expect(2)
                    if (accounts.size == 10_000) fail(ImportFailure.TOO_MANY_ENTRIES)
                    accounts += parseAccount(reader.message())
                }
                2, 3, 4, 5 -> {
                    tag.expect(0)
                    if (!seen.add(tag.field)) invalid()
                    val value = reader.int32()
                    when (tag.field) { 2 -> version = value; 3 -> size = value; 4 -> index = value; 5 -> id = value }
                }
                else -> reader.skip(tag)
            }
        }
        if (version != 1) fail(ImportFailure.UNSUPPORTED_VERSION)
        if (size !in 1..10_000 || index !in 0 until size || accounts.isEmpty()) invalid()
        return MigrationPart(version, size, index, id, accounts)
    }

    private fun parseAccount(reader: ProtoReader): MigrationAccount {
        var secret: OtpSecret? = null
        var name = ""
        var issuer = ""
        var algorithm: HashAlgorithm? = null
        var digits: Int? = null
        var type: OtpType? = null
        var counter = 0L
        val seen = HashSet<Int>()
        while (reader.hasRemaining()) {
            val tag = reader.tag()
            if (tag.field in 1..7 && !seen.add(tag.field)) invalid()
            when (tag.field) {
                1 -> {
                    tag.expect(2)
                    val bytes = reader.bytes()
                    try {
                        if (bytes.size !in 10..128) fail(ImportFailure.INVALID_SECRET)
                        secret = OtpSecret.fromBytes(bytes)
                    } finally { bytes.fill(0) }
                }
                2 -> { tag.expect(2); name = reader.text() }
                3 -> { tag.expect(2); issuer = reader.text() }
                4 -> {
                    tag.expect(0)
                    algorithm = when (reader.int32()) { 1 -> HashAlgorithm.SHA1; 2 -> HashAlgorithm.SHA256; 3 -> HashAlgorithm.SHA512; else -> fail(ImportFailure.UNSUPPORTED_PARAMETER) }
                }
                5 -> {
                    tag.expect(0)
                    digits = when (reader.int32()) { 1 -> 6; 2 -> 8; else -> fail(ImportFailure.UNSUPPORTED_PARAMETER) }
                }
                6 -> {
                    tag.expect(0)
                    type = when (reader.int32()) { 1 -> OtpType.HOTP; 2 -> OtpType.TOTP; else -> fail(ImportFailure.UNSUPPORTED_PARAMETER) }
                }
                7 -> { tag.expect(0); counter = reader.varint(); if (counter < 0) invalid() }
                else -> reader.skip(tag)
            }
        }
        if (name.isEmpty() || name.any { it.isISOControl() } || issuer.any { it.isISOControl() }) invalid()
        if (issuer.isNotEmpty() && name.startsWith("$issuer:")) name = name.substring(issuer.length + 1).trimStart(' ')
        if (name.isEmpty()) invalid()
        val checkedType = type ?: fail(ImportFailure.UNSUPPORTED_PARAMETER)
        val checkedAlgorithm = algorithm ?: fail(ImportFailure.UNSUPPORTED_PARAMETER)
        if (checkedType == OtpType.HOTP && checkedAlgorithm != HashAlgorithm.SHA1) fail(ImportFailure.UNSUPPORTED_PARAMETER)
        // Счётчик TOTP не участвует в вычислении и не переносит скрытое состояние HOTP.
        if (checkedType == OtpType.TOTP && counter != 0L) invalid()
        return MigrationAccount(issuer, name, secret ?: fail(ImportFailure.MISSING_SECRET), checkedType, checkedAlgorithm, digits ?: fail(ImportFailure.UNSUPPORTED_PARAMETER), counter)
    }
}

private fun invalid(): Nothing = fail(ImportFailure.INVALID_MIGRATION)

private data class ProtoTag(val field: Int, val wire: Int) {
    fun expect(expected: Int) { if (wire != expected) invalid() }
}

/** Читает только внутри заданного среза; неизвестные поля не выделяют память. */
private class ProtoReader(private val data: ByteArray, private var position: Int = 0, private val end: Int = data.size) {
    fun hasRemaining(): Boolean = position < end

    fun tag(): ProtoTag {
        val value = varint()
        if (value !in 1..0xffff_ffffL) invalid()
        val field = (value ushr 3).toInt()
        val wire = (value and 7).toInt()
        if (field == 0 || wire > 5) invalid()
        return ProtoTag(field, wire)
    }

    fun varint(): Long {
        var result = 0L
        for (index in 0..9) {
            if (position >= end) invalid()
            val byte = data[position++].toInt() and 255
            if (index == 9 && byte > 1) invalid()
            result = result or ((byte and 127).toLong() shl (index * 7))
            if (byte and 128 == 0) return result
        }
        invalid()
    }

    fun int32(): Int {
        val value = varint()
        if (value !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) invalid()
        return value.toInt()
    }

    private fun length(): Int {
        val value = varint()
        if (value < 0 || value > (end - position).toLong()) invalid()
        return value.toInt()
    }

    fun message(): ProtoReader {
        val length = length()
        val result = ProtoReader(data, position, position + length)
        position += length
        return result
    }

    fun bytes(): ByteArray {
        val length = length()
        val result = data.copyOfRange(position, position + length)
        position += length
        return result
    }

    fun text(): String {
        val length = length()
        val value = try {
            UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data, position, length)).toString()
        } catch (_: CharacterCodingException) { invalid() }
        position += length
        return value
    }

    fun skip(currentTag: ProtoTag, depth: Int = 0) {
        when (currentTag.wire) {
            0 -> varint()
            1 -> advance(8)
            2 -> advance(length())
            3 -> {
                if (depth >= 8) invalid()
                while (true) {
                    val nested = tag()
                    if (nested.wire == 4) {
                        if (nested.field != currentTag.field) invalid()
                        break
                    }
                    skip(nested, depth + 1)
                }
            }
            4 -> invalid()
            5 -> advance(4)
            else -> invalid()
        }
    }

    private fun advance(length: Int) {
        if (length > end - position) invalid()
        position += length
    }
}
