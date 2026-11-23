package app.surrealar.coords

import app.surrealar.settings.model.ReceiverDatum
import io.github.pennstatecs.geokit.gnss.datum.FixDatum

/**
 * Settings → Coordinates → External Receiver Datum as geokit-gnss's [FixDatum]. The feed normalises
 * external receiver output with [io.github.pennstatecs.geokit.gnss.datum.FixDatumNormalizer] (see GnssModule).
 */
fun ReceiverDatum.toFixDatum(): FixDatum = when (this) {
    ReceiverDatum.ITRF2014 -> FixDatum.ITRF2014
    ReceiverDatum.NAD83_2011 -> FixDatum.NAD83_2011
}
