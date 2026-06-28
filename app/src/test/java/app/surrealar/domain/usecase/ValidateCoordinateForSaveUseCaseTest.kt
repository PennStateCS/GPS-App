package app.surrealar.domain.usecase

import app.surrealar.domain.model.Coordinate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidateCoordinateForSaveUseCaseTest {

    private val validate = ValidateCoordinateForSaveUseCase()

    private fun coord(
        lat: Double = 41.0,
        lon: Double = -76.0,
        alt: Double = 100.0,
        modelScale: Double? = null,
        modelYawDeg: Double? = null,
    ) = Coordinate(
        id = "c1", name = "P", latitude = lat, longitude = lon, altitude = alt,
        timestamp = 1_000L, icon = "ic_pin", color = 0,
        modelScale = modelScale, modelYawDeg = modelYawDeg,
    )

    @Test fun validCoordinate_passes() = assertTrue(validate(coord()).isValid)

    @Test fun outOfRangeLatLon_rejected() {
        assertFalse(validate(coord(lat = 200.0)).isValid)
        assertFalse(validate(coord(lon = 999.0)).isValid)
    }

    @Test fun nullIsland_rejectedByDefault_butAllowedWhenRequested() {
        val zero = coord(lat = 0.0, lon = 0.0)
        assertFalse(validate(zero).isValid)
        val allowed = validate(zero, allowNullIsland = true)
        assertTrue(allowed.errors.none { it.contains("0,0") })
    }

    @Test fun nonFiniteAltitude_rejected() =
        assertFalse(validate(coord(alt = Double.NaN)).isValid)

    @Test fun nonPositiveModelScale_rejected() {
        assertFalse(validate(coord(modelScale = 0.0)).isValid)
        assertFalse(validate(coord(modelScale = -1.0)).isValid)
        assertTrue(validate(coord(modelScale = 2.0)).isValid)
    }

    @Test fun nonFinitePlacement_rejected() =
        assertFalse(validate(coord(modelYawDeg = Double.POSITIVE_INFINITY)).isValid)
}
