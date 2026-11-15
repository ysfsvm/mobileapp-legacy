package coredevices.pebble.battery

import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.io.encoding.Base64
import kotlin.time.Instant

/** All kept records as a JSON array: decoded values where the format is known, and always the raw record. */
fun batteryExportJson(records: List<BatteryRecord>): String = buildJsonArray {
    for (r in records) {
        addJsonObject {
            put("serial", r.entity.serial)
            put("phone_fw_version", r.entity.fwVersion)
            put("received_at", Instant.fromEpochMilliseconds(r.entity.receivedAt).toString())
            put("tz_offset_minutes", r.entity.tzOffsetMinutes)
            put("size", r.entity.payload.size)
            when (val d = r.decoded) {
                is DecodedHeartbeat.Known -> {
                    put("format", "known")
                    put("version", d.version)
                    put("timestamp", d.timestamp.toString())
                    put("build_id", d.buildId)
                    putJsonObject("metrics") { d.numbers.forEach { (k, v) -> put(k, v) } }
                    putJsonObject("texts") { d.texts.forEach { (k, v) -> put(k, v) } }
                }
                is DecodedHeartbeat.Unknown -> {
                    put("format", "unknown")
                    put("version", d.version)
                }
            }
            put("raw", Base64.encode(r.entity.payload))
        }
    }
}.toString()
