package app.surrealar.settings.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * A receiver that connected successfully (Phase 5 "Previously connected"), persisted in `external_recent_receivers`.
 *
 * [model], [serial] and [firmware] are only ever what the receiver itself reported (Emlid device info); they are
 * null for a receiver that never reported them (e.g. Generic NMEA). [profileKey] is the profile it was connected
 * with ([ExternalReceiverProfile.prefKey]).
 */
data class RecentReceiver(
    val host: String,
    val port: Int,
    val profileKey: String? = null,
    val name: String? = null,
    val model: String? = null,
    val serial: String? = null,
    val firmware: String? = null,
    val lastConnectedMs: Long = 0L,
) {
    /** host:port with a case-insensitive host (the fallback identity when no serial was reported). */
    val endpointKey: String get() = "${host.trim().lowercase(java.util.Locale.ROOT)}:$port"
}

/**
 * JSON codec and list rules for `external_recent_receivers`:
 * - newest first, at most [MAX] entries;
 * - one entry per receiver: by [RecentReceiver.serial] when the receiver reported one, else by host:port; an entry
 *   with a serial also replaces a serial-less entry at the same host:port (the same receiver before it reported);
 *   a serial-less entry replaces only the newest entry at its host:port and never merges entries of different serials;
 * - unknown JSON fields are ignored, invalid entries (blank host, port outside 1..65535) are dropped, and corrupt
 *   JSON reads as an empty list (never an exception).
 */
object RecentReceiversCodec {
    const val MAX = 8

    fun decode(json: String?): List<RecentReceiver> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val host = o.optString("host", "").trim()
                val port = o.optInt("port", -1)
                if (host.isEmpty() || port !in 1..65535) return@mapNotNull null
                RecentReceiver(
                    host = host,
                    port = port,
                    profileKey = o.str("profileKey"),
                    name = o.str("name"),
                    model = o.str("model"),
                    serial = o.str("serial"),
                    firmware = o.str("firmware"),
                    lastConnectedMs = o.optLong("lastConnectedMs", 0L),
                )
            }.let(::normalize)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun encode(list: List<RecentReceiver>): String {
        val arr = JSONArray()
        normalize(list).forEach { r ->
            arr.put(JSONObject().apply {
                put("host", r.host)
                put("port", r.port)
                r.profileKey?.let { put("profileKey", it) }
                r.name?.let { put("name", it) }
                r.model?.let { put("model", it) }
                r.serial?.let { put("serial", it) }
                r.firmware?.let { put("firmware", it) }
                put("lastConnectedMs", r.lastConnectedMs)
            })
        }
        return arr.toString()
    }

    /**
     * [list] with [entry] first: an existing entry for the same receiver is replaced, keeping the fields [entry]
     * does not know (e.g. the reported model after a reconnect that has not been identified yet).
     */
    fun upsert(list: List<RecentReceiver>, entry: RecentReceiver): List<RecentReceiver> {
        val same = if (entry.serial != null) {
            // By serial; a serial-less entry at the same host:port is this receiver before it reported.
            list.filter { (it.serial != null && it.serial == entry.serial) || (it.serial == null && it.endpointKey == entry.endpointKey) }
        } else {
            // No serial: several receivers can share one address (every Reach hotspot is 192.168.42.1). Replace only
            // the newest entry at this host:port; entries with other serials there are never merged into it.
            listOfNotNull(list.firstOrNull { it.endpointKey == entry.endpointKey })
        }
        val previous = same.firstOrNull { it.serial != null && it.serial == entry.serial } ?: same.firstOrNull()
        val merged = if (previous == null) entry else entry.copy(
            profileKey = entry.profileKey ?: previous.profileKey,
            name = entry.name ?: previous.name,
            model = entry.model ?: previous.model.takeIf { previous.serial == entry.serial || entry.serial == null },
            serial = entry.serial ?: previous.serial.takeIf { previous.endpointKey == entry.endpointKey },
            firmware = entry.firmware ?: previous.firmware.takeIf { previous.serial == entry.serial || entry.serial == null },
        )
        return normalize(listOf(merged) + (list - same.toSet()))
    }

    private fun duplicate(a: RecentReceiver, b: RecentReceiver): Boolean = when {
        a.serial != null || b.serial != null -> a.serial == b.serial
        else -> a.endpointKey == b.endpointKey
    }

    /**
     * Dedupe (first wins), cap at [MAX]. Two entries with different serials are never duplicates, even at the same
     * host:port; a serial-less entry is dropped only after another serial-less one at the same host:port.
     */
    private fun normalize(list: List<RecentReceiver>): List<RecentReceiver> {
        val out = ArrayList<RecentReceiver>()
        for (r in list) {
            if (out.none { duplicate(it, r) }) out += r
            if (out.size == MAX) break
        }
        return out
    }

    private fun JSONObject.str(key: String): String? =
        if (has(key) && !isNull(key)) optString(key, "").trim().takeIf { it.isNotEmpty() } else null
}
