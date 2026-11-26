package app.surrealar.gnss.source

import app.surrealar.coords.toFixDatum
import app.surrealar.settings.model.ReceiverDatum
import io.github.pennstatecs.geokit.gnss.datum.FixDatumNormalizer
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.Provider
import kotlinx.coroutines.flow.StateFlow

/**
 * The External receiver datum the GNSS feed normalises with, and which datum normalised each fix the
 * feed published.
 *
 * [normalise] is the [app.surrealar.gnss.bus.FixSwitchboard] fix transform (wired in GnssModule). It
 * reads [datum] once per fix, converts with geokit's [FixDatumNormalizer] exactly as before (NAD83(2011)
 * receiver output → WGS84; ITRF2014 unchanged; internal fixes untouched) and records the datum it used
 * against that output instance. A capture asks [datumOf] for every fix it averages, so it can accept only
 * fixes normalised under the datum its occupation is bound to.
 *
 * Why per-fix tags instead of a settle delay: the datum is picked up per fix, from a `stateIn` of the
 * settings DataStore, so after the user changes the setting the feed switches a few milliseconds later,
 * and up to 64 already-normalised fixes can still sit in the switchboard's buffer. A tag says which datum
 * really converted a fix; a time rule can only guess. Fix carries no datum field and geokit-gnss stays
 * untouched, so the record lives here, keyed by instance identity.
 *
 * The record is bounded ([capacity] most recent fixes): captures look fixes up as they arrive, far within
 * that window. A fix that is not in the record (evicted, or never from this feed) reads as unknown (null) and
 * is never averaged into an external capture. A fix whose [normalise] throws is never published at all: the
 * switchboard drops it and withdraws the live position (fail closed), so no untagged raw fix reaches consumers.
 */
class ReceiverFeedDatum(
    /** Settings → Coordinates → External receiver datum, as the feed reads it for each fix. */
    val datum: StateFlow<ReceiverDatum>,
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    /** Identity key: two equal fixes normalised under different datums must not share a tag. */
    private class Key(val fix: Fix) {
        override fun equals(other: Any?) = other is Key && other.fix === fix
        override fun hashCode() = System.identityHashCode(fix)
    }

    private val applied = object : LinkedHashMap<Key, ReceiverDatum>(capacity * 2) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, ReceiverDatum>?) = size > capacity
    }

    /**
     * Normalises a non-internal [fix] to WGS84 under the current [datum] and records that datum for the
     * returned instance. Internal (device) fixes are returned unchanged and not recorded: they are
     * always WGS84. If the conversion throws, nothing is recorded (the exception propagates).
     */
    fun normalise(fix: Fix): Fix {
        if (fix.provider == Provider.INTERNAL) return fix
        val used = datum.value   // read once: the recorded datum is exactly the one applied
        val out = FixDatumNormalizer.toWgs84(fix, used.toFixDatum())
        synchronized(applied) { applied[Key(out)] = used }
        return out
    }

    /** The receiver datum that normalised [fix] in [normalise]; null when unknown. */
    fun datumOf(fix: Fix): ReceiverDatum? = synchronized(applied) { applied[Key(fix)] }

    companion object {
        /** Fixes remembered: twice the switchboard's 64-fix buffer. */
        const val DEFAULT_CAPACITY = 128
    }
}
