package coredevices.pebble.battery

import kotlin.time.Instant

sealed interface DecodedHeartbeat {
    data class Known(
        val version: Int,
        val timestamp: Instant,
        val buildId: String,
        val numbers: Map<String, Double>,
        val texts: Map<String, String>,
    ) : DecodedHeartbeat {
        fun number(name: String): Double? = numbers[name]
    }

    /** A record whose version or size no layout in [HeartbeatLayouts] describes; kept raw. */
    data class Unknown(val version: Int?, val size: Int) : DecodedHeartbeat
}

class HeartbeatLayout(
    val version: Int,
    val size: Int,
    val fields: List<Field>,
) {
    enum class Kind { Unsigned, Signed, Text }

    data class Field(
        val name: String,
        val offset: Int,
        val size: Int,
        val kind: Kind,
        val scaleOffset: Int? = null,
    )

    companion object {
        private val sizeLine = Regex("""native_heartbeat_record \((\d+) bytes\)""")
        private val fieldLine = Regex("""^\s*(\d+)\s+(\d+)\s+(\S+(?: char\d+)?)\s+(.+?)\s*$""")
        private val scalePrefix = "↳ scale for "

        /** Parses the output of the firmware's `tools/analytics_heartbeat_layout.py`. */
        fun parse(version: Int, text: String): HeartbeatLayout {
            val size = sizeLine.find(text)?.groupValues?.get(1)?.toInt()
                ?: throw IllegalArgumentException("No record size in layout")
            val fields = mutableListOf<Field>()
            for (line in text.lineSequence()) {
                val m = fieldLine.matchEntire(line) ?: continue
                val (offsetStr, sizeStr, type, name) = m.destructured
                val offset = offsetStr.toInt()
                val fieldSize = sizeStr.toInt()
                require(offset + fieldSize <= size) { "Field $name outside the record" }
                if (name.startsWith(scalePrefix)) {
                    val scaled = name.removePrefix(scalePrefix)
                    val i = fields.indexOfLast { it.name == scaled }
                    require(i >= 0 && type == "unsigned16") { "Bad scale field for $scaled" }
                    fields[i] = fields[i].copy(scaleOffset = offset)
                    continue
                }
                fields += Field(name, offset, fieldSize, kindOf(type, fieldSize), null)
            }
            require(fields.any { it.name == "version" } && fields.any { it.name == "timestamp" }) {
                "Layout has no header"
            }
            return HeartbeatLayout(version, size, fields)
        }

        private fun kindOf(type: String, size: Int): Kind = when {
            type.startsWith("char[") -> Kind.Text
            type == "unsigned char8" || type == "unsigned${size * 8}" -> Kind.Unsigned
            type == "signed char8" || type == "signed${size * 8}" -> Kind.Signed
            else -> throw IllegalArgumentException("Unknown field type $type")
        }.also {
            require(it == Kind.Text || size in setOf(1, 2, 4, 8)) { "Unsupported size $size for $type" }
        }
    }
}

class HeartbeatDecoder(private val layouts: List<HeartbeatLayout> = HeartbeatLayouts.known) {
    fun decode(record: ByteArray): DecodedHeartbeat {
        val version = record.firstOrNull()?.toInt()?.and(0xff)
        val layout = layouts.firstOrNull { it.version == version && it.size == record.size }
            ?: return DecodedHeartbeat.Unknown(version, record.size)
        var timestamp = 0L
        var buildId = ""
        val numbers = mutableMapOf<String, Double>()
        val texts = mutableMapOf<String, String>()
        for (field in layout.fields) {
            when (field.name) {
                "version" -> continue
                "timestamp" -> timestamp = record.readInt(field.offset, field.size, signed = false)
                "build_id" -> buildId = record.toHex(field.offset, field.size)
                else -> when (field.kind) {
                    HeartbeatLayout.Kind.Text -> texts[field.name] = record.readText(field.offset, field.size)
                    else -> {
                        val raw = record.readInt(field.offset, field.size, field.kind == HeartbeatLayout.Kind.Signed)
                        val scale = field.scaleOffset?.let { record.readInt(it, 2, signed = false) } ?: 0L
                        numbers[field.name] = if (scale != 0L) raw.toDouble() / scale else raw.toDouble()
                    }
                }
            }
        }
        return DecodedHeartbeat.Known(
            version = layout.version,
            timestamp = Instant.fromEpochSeconds(timestamp),
            buildId = buildId,
            numbers = numbers,
            texts = texts,
        )
    }
}

private fun ByteArray.readInt(offset: Int, size: Int, signed: Boolean): Long {
    var value = 0L
    for (i in size - 1 downTo 0) {
        value = (value shl 8) or (this[offset + i].toLong() and 0xff)
    }
    if (signed && size < 8) {
        val shift = 64 - size * 8
        value = (value shl shift) shr shift
    }
    return value
}

private fun ByteArray.readText(offset: Int, size: Int): String {
    val end = (offset until offset + size).firstOrNull { this[it] == 0.toByte() } ?: (offset + size)
    return decodeToString(offset, end)
}

private fun ByteArray.toHex(offset: Int, size: Int): String = buildString {
    for (i in offset until offset + size) {
        val b = this@toHex[i].toInt() and 0xff
        append(HEX[b shr 4]).append(HEX[b and 0xf])
    }
}

private const val HEX = "0123456789abcdef"
