package com.tiny.dns2ss.dns

import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * Small DNS wire-format helper used by the cache. It deliberately supports only the
 * normal single-question query shape that Android resolvers and dig use.
 */
internal object DnsWire {
    data class Question(
        val nameStart: Int,
        val nameEnd: Int,
        val questionEnd: Int,
        val type: Int,
        val klass: Int,
    )

    data class CacheEntry(
        val message: ByteArray,
        val messageLength: Int,
        val questionStart: Int,
        val questionEnd: Int,
        val ttlOffsets: IntArray,
        val ttlValues: IntArray,
        val ttlSeconds: Int,
        val storedAtMs: Long,
    )

    fun parseQuestion(buffer: ByteArray, length: Int): Question? {
        if (length < 12) return null
        val qdCount = u16(buffer, 4)
        if (qdCount != 1) return null
        var p = 12
        while (p < length) {
            val label = buffer[p].toInt() and 0xff
            if (label == 0) {
                p++
                break
            }
            if ((label and 0xC0) != 0) return null // compressed question names are not cached
            if (label > 63) return null
            p += 1 + label
            if (p > length) return null
        }
        if (p + 4 > length) return null
        val questionEnd = p + 4
        return Question(
            nameStart = 12,
            nameEnd = p,
            questionEnd = questionEnd,
            type = u16(buffer, p),
            klass = u16(buffer, p + 2),
        )
    }

    /**
     * Stable cache key: ID removed, question-name case folded, and EDNS padding/NSID
     * and COOKIE data removed. Android pads queries with a varying length, and dig/
     * resolvers rotate the 8-byte client cookie; either one would defeat every hit.
     */
    fun cacheKey(query: ByteArray, length: Int): String? {
        val question = parseQuestion(query, length) ?: return null
        if (question.questionEnd > length) return null
        val out = ByteArrayOutputStream(length)
        out.write(query[2].toInt() and 0xff)
        out.write(query[3].toInt() and 0xff)

        // Canonical question name, preserving label lengths and ASCII case folding.
        var p = question.nameStart
        while (p < question.nameEnd) {
            val label = query[p].toInt() and 0xff
            out.write(label)
            p++
            if (label == 0) break
            if (label > 63 || p + label > question.nameEnd) return null
            for (i in 0 until label) out.write(lowerAscii(query[p + i]).toInt() and 0xff)
            p += label
        }
        out.write(query, question.questionEnd - 4, 4) // qtype, qclass

        val additional = u16(query, 10)
        p = question.questionEnd
        repeat(additional) {
            val recordStart = p
            val nameEnd = skipName(query, p, length) ?: return null
            if (nameEnd + 10 > length) return null
            val type = u16(query, nameEnd)
            val klass = u16(query, nameEnd + 2)
            val ttl = u32(query, nameEnd + 4)
            val rdLength = u16(query, nameEnd + 8)
            val rdStart = nameEnd + 10
            val end = rdStart + rdLength
            if (end > length) return null

            if (type == TYPE_OPT) {
                out.write(0) // normalized OPT marker
                writeU16(out, type)
                writeU16(out, klass)
                writeU32(out, ttl)
                var option = rdStart
                while (option + 4 <= end) {
                    val code = u16(query, option)
                    val optionLength = u16(query, option + 2)
                    val optionEnd = option + 4 + optionLength
                    if (optionEnd > end) return null
                    if (code != OPT_PADDING && code != OPT_NSID && code != OPT_COOKIE) {
                        writeU16(out, code)
                        writeU16(out, optionLength)
                        out.write(query, option + 4, optionLength)
                    }
                    option = optionEnd
                }
                if (option != end) return null
            } else {
                out.write(query, recordStart, end - recordStart)
            }
            p = end
        }
        if (p != length) out.write(query, p, length - p)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
    }

    /**
     * Removes an EDNS option from the additional OPT record. Used to keep a cached
     * response from replaying a server cookie bound to a different client cookie.
     */
    fun stripOptOption(response: ByteArray, length: Int, optionCode: Int): ByteArray {
        val copy = response.copyOf(length)
        if (length < 12) return copy
        val question = parseQuestion(response, length) ?: return copy
        var p = question.questionEnd
        val anCount = u16(response, 6)
        val nsCount = u16(response, 8)
        val arCount = u16(response, 10)
        repeat(anCount + nsCount) {
            val nameEnd = skipName(response, p, length) ?: return copy
            if (nameEnd + 10 > length) return copy
            val rdLength = u16(response, nameEnd + 8)
            p = nameEnd + 10 + rdLength
            if (p > length) return copy
        }
        repeat(arCount) {
            val nameEnd = skipName(response, p, length) ?: return copy
            if (nameEnd + 10 > length) return copy
            val type = u16(response, nameEnd)
            val rdLengthOffset = nameEnd + 8
            val rdLength = u16(response, rdLengthOffset)
            val rdStart = nameEnd + 10
            val rdEnd = rdStart + rdLength
            if (rdEnd > length) return copy
            if (type == TYPE_OPT) {
                val filtered = ByteArrayOutputStream(rdLength)
                var option = rdStart
                var removed = 0
                while (option + 4 <= rdEnd) {
                    val code = u16(response, option)
                    val optionLength = u16(response, option + 2)
                    val optionEnd = option + 4 + optionLength
                    if (optionEnd > rdEnd) return copy
                    if (code == optionCode) {
                        removed += 4 + optionLength
                    } else {
                        filtered.write(response, option, 4 + optionLength)
                    }
                    option = optionEnd
                }
                if (option != rdEnd) return copy
                if (removed == 0) return copy
                val newRd = filtered.toByteArray()
                val out = ByteArray(length - removed)
                System.arraycopy(response, 0, out, 0, rdStart)
                System.arraycopy(newRd, 0, out, rdStart, newRd.size)
                System.arraycopy(response, rdEnd, out, rdStart + newRd.size, length - rdEnd)
                out[rdLengthOffset] = ((newRd.size ushr 8) and 0xff).toByte()
                out[rdLengthOffset + 1] = (newRd.size and 0xff).toByte()
                return out
            }
            p = rdEnd
        }
        return copy
    }

    private fun writeU16(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 8) and 0xff)
        out.write(value and 0xff)
    }

    private fun writeU32(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 24) and 0xff)
        out.write((value ushr 16) and 0xff)
        out.write((value ushr 8) and 0xff)
        out.write(value and 0xff)
    }

    fun analyzeResponse(query: ByteArray, response: ByteArray, length: Int, nowMs: Long): CacheEntry? {
        val queryQuestion = parseQuestion(query, query.size) ?: return null
        if (length < 12) return null
        val qdCount = u16(response, 4)
        if (qdCount != 1) return null
        val flags = u16(response, 2)
        if ((flags and 0x0200) != 0) return null // truncated
        val rcode = flags and 0x000f
        if (rcode != 0 && rcode != 3) return null

        val responseQuestion = parseQuestion(response, length) ?: return null
        if (responseQuestion.nameEnd - responseQuestion.nameStart != queryQuestion.nameEnd - queryQuestion.nameStart) {
            return null
        }

        var p = responseQuestion.questionEnd
        val anCount = u16(response, 6)
        val nsCount = u16(response, 8)
        val arCount = u16(response, 10)
        val ttlOffsets = ArrayList<Int>()
        val ttlValues = ArrayList<Int>()
        var minTtl = Int.MAX_VALUE
        var soaTtl = -1

        for (section in 0 until 3) {
            val count = when (section) {
                0 -> anCount
                1 -> nsCount
                else -> arCount
            }
            for (i in 0 until count) {
                val nameEnd = skipName(response, p, length) ?: return null
                if (nameEnd + 10 > length) return null
                val type = u16(response, nameEnd)
                val ttlOffset = nameEnd + 4
                val ttl = u32(response, ttlOffset)
                val rdLength = u16(response, nameEnd + 8)
                p = nameEnd + 10 + rdLength
                if (p > length) return null
                if (section <= 1 && type != TYPE_OPT) {
                    ttlOffsets.add(ttlOffset)
                    ttlValues.add(ttl)
                    if (ttl < minTtl) minTtl = ttl
                    if (section == 1 && type == TYPE_SOA) soaTtl = ttl
                }
            }
        }

        val negative = rcode == 3 || (rcode == 0 && anCount == 0)
        val ttl = if (negative) {
            if (soaTtl < 0) return null
            soaTtl
        } else {
            if (anCount == 0 || minTtl == Int.MAX_VALUE) return null
            minTtl
        }
        if (ttl <= 0) return null
        if (ttl > MAX_CACHE_TTL_SECONDS) return null

        return CacheEntry(
            message = response.copyOf(length),
            messageLength = length,
            questionStart = responseQuestion.nameStart,
            questionEnd = responseQuestion.nameEnd,
            ttlOffsets = ttlOffsets.toIntArray(),
            ttlValues = ttlValues.toIntArray(),
            ttlSeconds = ttl,
            storedAtMs = nowMs,
        )
    }

    /**
     * Returns a copy of the cached response with a new ID, the current question-name
     * case, and decremented TTLs.
     */
    fun buildCachedResponse(query: ByteArray, entry: CacheEntry, nowMs: Long): ByteArray? {
        val elapsedMs = nowMs - entry.storedAtMs
        if (elapsedMs < 0) return null
        val elapsedSeconds = (elapsedMs / 1000).toInt()
        if (elapsedSeconds >= entry.ttlSeconds) return null
        if (query.size < entry.questionEnd || query.size < entry.questionStart) return null
        val response = entry.message.copyOf(entry.messageLength)
        response[0] = query[0]
        response[1] = query[1]
        val queryNameLength = entry.questionEnd - entry.questionStart
        if (query.size >= 12 + queryNameLength) {
            System.arraycopy(query, 12, response, entry.questionStart, queryNameLength)
        }
        for (i in entry.ttlOffsets.indices) {
            val remaining = (entry.ttlValues[i] - elapsedSeconds).coerceAtLeast(0)
            writeU32(response, entry.ttlOffsets[i], remaining)
        }
        return response
    }

    private fun skipName(buffer: ByteArray, start: Int, length: Int): Int? {
        var p = start
        while (p < length) {
            val label = buffer[p].toInt() and 0xff
            if (label == 0) return p + 1
            if ((label and 0xC0) == 0xC0) {
                if (p + 1 >= length) return null
                return p + 2
            }
            if (label > 63) return null
            p += 1 + label
            if (p > length) return null
        }
        return null
    }

    private fun lowerAscii(value: Byte): Byte {
        val v = value.toInt() and 0xff
        return if (v in 'A'.code..'Z'.code) (v + 32).toByte() else value
    }

    private fun u16(buffer: ByteArray, offset: Int): Int =
        ((buffer[offset].toInt() and 0xff) shl 8) or (buffer[offset + 1].toInt() and 0xff)

    private fun u32(buffer: ByteArray, offset: Int): Int =
        ((buffer[offset].toInt() and 0xff) shl 24) or
            ((buffer[offset + 1].toInt() and 0xff) shl 16) or
            ((buffer[offset + 2].toInt() and 0xff) shl 8) or
            (buffer[offset + 3].toInt() and 0xff)

    private fun writeU32(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = ((value ushr 24) and 0xff).toByte()
        buffer[offset + 1] = ((value ushr 16) and 0xff).toByte()
        buffer[offset + 2] = ((value ushr 8) and 0xff).toByte()
        buffer[offset + 3] = (value and 0xff).toByte()
    }

    private const val TYPE_SOA = 6
    private const val TYPE_OPT = 41
    const val OPT_COOKIE = 10
    private const val OPT_NSID = 3
    private const val OPT_PADDING = 12
    private const val MAX_CACHE_TTL_SECONDS = 86_400
}
