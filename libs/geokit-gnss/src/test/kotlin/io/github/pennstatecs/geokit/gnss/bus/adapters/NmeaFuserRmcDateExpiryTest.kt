package io.github.pennstatecs.geokit.gnss.bus.adapters

import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.Provider
import io.github.pennstatecs.geokit.gnss.model.TimestampSource
import io.github.pennstatecs.geokit.gnss.nmea.parse.DefaultNmeaRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The RMC date expires like the ZDA date: a stream that stops sending RMC must not keep stamping
 * fixes with an old date forever — after 10 s the date falls back to the device's.
 */
class NmeaFuserRmcDateExpiryTest {

    private var nowMs = 1_000_000L
    private val fixes = mutableListOf<Fix>()
    private val fuser = NmeaFuser(
        Provider.EXTERNAL_TCP, DefaultNmeaRegistry.create(verifyChecksum = false),
        onFix = { fixes += it }, onGsv = {}, clock = { nowMs },
    )

    private fun gga(t: String) = fuser.accept("\$GNGGA,$t,4118.4448800,N,07601.0300517,W,4,22,0.8,395.539,M,-33.145,M,1.0,0001")
    private fun rmc(t: String) = fuser.accept("\$GNRMC,$t,A,4118.4448800,N,07601.0300517,W,0.0,,050120,,,A")   // 5 Jan 2020

    private fun dateOf(f: Fix): LocalDate = f.timeUtc.atZone(ZoneOffset.UTC).toLocalDate()

    @Test fun `a fresh RMC supplies the date`() {
        rmc("172424.00"); gga("172424.00"); fuser.flush()
        assertEquals(TimestampSource.GNSS_PROVIDER, fixes.last().timestampSource)
        assertEquals(LocalDate.of(2020, 1, 5), dateOf(fixes.last()))
    }

    @Test fun `RMC within 10 s still supplies the date`() {
        rmc("172424.00"); gga("172424.00")
        nowMs += 9_000
        gga("172433.00"); fuser.flush()
        assertEquals(TimestampSource.GNSS_PROVIDER, fixes.last().timestampSource)
        assertEquals(LocalDate.of(2020, 1, 5), dateOf(fixes.last()))
    }

    @Test fun `after RMC stops for more than 10 s the device date is used`() {
        rmc("172424.00"); gga("172424.00")
        nowMs += 11_000
        gga("172435.00"); fuser.flush()
        assertEquals(TimestampSource.DEVICE, fixes.last().timestampSource)
        assertEquals(LocalDate.now(ZoneOffset.UTC), dateOf(fixes.last()))
    }
}
