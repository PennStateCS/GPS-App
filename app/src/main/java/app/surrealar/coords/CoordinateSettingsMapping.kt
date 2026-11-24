package app.surrealar.coords

import app.surrealar.settings.model.CoordinateSystemSettings
import app.surrealar.settings.model.HorizontalSystem
import app.surrealar.settings.model.LinearUnit
import app.surrealar.settings.model.OutputDatum
import app.surrealar.settings.model.VerticalReference
import io.github.pennstatecs.geokit.convert.GeodeticDatum
import io.github.pennstatecs.geokit.convert.LengthUnit
import io.github.pennstatecs.geokit.convert.OutputSystem
import io.github.pennstatecs.geokit.convert.TargetSystem
import io.github.pennstatecs.geokit.geoid.GeoidModelId
import io.github.pennstatecs.geokit.projection.SpcsZones
import io.github.pennstatecs.geokit.convert.VerticalReference as GeoVerticalReference

/*
 * Settings → Coordinates (persisted app enums) as geokit-core's neutral conversion types. The app
 * enums keep their prefKeys and labels; only the meaning is mapped here.
 */

fun LinearUnit.toLengthUnit(): LengthUnit = when (this) {
    LinearUnit.METERS -> LengthUnit.METRE
    LinearUnit.US_SURVEY_FEET -> LengthUnit.US_SURVEY_FOOT
    LinearUnit.INTERNATIONAL_FEET -> LengthUnit.INTERNATIONAL_FOOT
}

fun OutputDatum.toGeodeticDatum(): GeodeticDatum = when (this) {
    OutputDatum.WGS84 -> GeodeticDatum.WGS84_ITRF2014
    OutputDatum.NAD83_2011 -> GeodeticDatum.NAD83_2011
    OutputDatum.ITRF2020 -> GeodeticDatum.ITRF2020
}

/**
 * The geokit vertical reference the engine computes. For the global-model heights ([globalGeoidModel]
 * non-null) the engine is not asked for a height at all: [CoordinateConverter] computes
 * H = h − N(model) itself from the WGS84 (ITRF2014) ellipsoidal height, so this returns ELLIPSOIDAL only
 * as a placeholder that is always replaced (never a second geoid correction).
 */
fun VerticalReference.toGeoVerticalReference(): GeoVerticalReference = when (this) {
    VerticalReference.ELLIPSOIDAL -> GeoVerticalReference.ELLIPSOIDAL
    VerticalReference.NAVD88_GEOID18 -> GeoVerticalReference.GEOID18_ORTHOMETRIC
    VerticalReference.RECEIVER_MSL -> GeoVerticalReference.RECEIVER_MSL
    VerticalReference.EGM2008, VerticalReference.EGM96 -> GeoVerticalReference.ELLIPSOIDAL
}

/** The global geoid model of an orthometric-height choice (EGM2008 / EGM96), null for the others. */
fun VerticalReference.globalGeoidModel(): GeoidModelId? = when (this) {
    VerticalReference.EGM2008 -> GeoidModelId.EGM2008
    VerticalReference.EGM96 -> GeoidModelId.EGM96
    VerticalReference.ELLIPSOIDAL, VerticalReference.NAVD88_GEOID18, VerticalReference.RECEIVER_MSL -> null
}

/**
 * The horizontal system to convert into. State Plane uses the saved zone, or Pennsylvania North
 * ([CoordinateSystemSettings.DEFAULT_SPCS_ZONE_EPSG]) if the saved EPSG is not a known zone.
 */
fun CoordinateSystemSettings.toTargetSystem(): TargetSystem = when (horizontal) {
    HorizontalSystem.GEOGRAPHIC -> TargetSystem.Geographic
    HorizontalSystem.UTM -> TargetSystem.UtmAuto
    HorizontalSystem.STATE_PLANE -> TargetSystem.StatePlane(
        if (SpcsZones.byEpsg(spcsZoneEpsg) != null) spcsZoneEpsg else CoordinateSystemSettings.DEFAULT_SPCS_ZONE_EPSG)
}

/** The full output system ([OutputSystem.effectiveDatum] agrees with [CoordinateSystemSettings.effectiveDatum]). */
fun CoordinateSystemSettings.toOutputSystem(): OutputSystem = OutputSystem(
    horizontal = toTargetSystem(),
    datum = datum.toGeodeticDatum(),
    unit = unit.toLengthUnit(),
    vertical = vertical.toGeoVerticalReference(),
)
