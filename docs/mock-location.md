# Android mock location: accuracy and provenance

`AndroidMockLocationPublisher` (`gnss/mock`) republishes external receiver fixes as Android mock GPS
locations (Settings toggle, or automatically while the AR screen is open). Coordinates are passed through
unchanged; this page covers the accuracy fields and their limits.

## Horizontal accuracy

Android requires `Location.accuracy` to be set and greater than 0 (`setTestProviderLocation` rejects a
location without it), so every mock location carries a value, and the extras say where it came from:

| `extras["app.surrealar.accuracySource"]` | When | Value |
|---|---|---|
| `reported` | The receiver reported a usable horizontal accuracy (finite, > 0) | The receiver's 68 % radius, unchanged |
| `estimated` | No receiver accuracy, or an unusable one (NaN, ≤ 0, infinite) | max(DOP × UERE, per-status floor); the floor alone without HDOP |

Conservative floors for an estimate (m, 68 % radius): RTK fixed **0.10**, RTK float **0.5**, DGPS **1.5**,
anything else **5.0**.

Rationale: an RTK status without a receiver accuracy does not verify the actual error (a wrong integer fix,
wrong base coordinates or multipath all still report "fixed"). ARCore and other location clients weight a
location by its accuracy, so an optimistic estimate (DOP × UERE gives about 1 cm for a good RTK fix) would
make them trust the position more than the receiver itself vouched for. Before this change an RTK fixed
fix without accuracy was published at 0.05 m, or about 1 cm from DOP × UERE, with nothing marking it as an
estimate. A floor costs little: a client that sees 0.10 m instead of 0.02 m still treats the location as
precise; it only stops treating it as proven to the centimetre.

The diagnostics log the same distinction: `MOCK_LOCATION_INJECTED ... accuracy=reported|estimated`, and
`MockInjectionStatus.lastInjectedHAccM` keeps the real receiver value (null when none) with
`lastInjectedHAccWasFallback` set for estimates.

## Altitude and vertical accuracy

- The altitude is WGS84 ellipsoidal, lowered from the antenna to the tablet camera.
- It is withheld (with its vertical accuracy) while the receiver positions are, or may be, the RS4
  tilt-compensated pole tip (Phase 4, `docs/geodesy/phase4-position-references.md`).
- It is also withheld while the receiver's vertical accuracy is an invalid marker (e.g. Emlid 999 m when
  receiver height corrections are interrupted; `VerticalAccuracyValidity`): that epoch has no usable height.
- `verticalAccuracyMeters` is set only from a valid receiver value. It is never estimated, and a NaN, ≤ 0 or
  999 m value is never passed on.

## Android limitations

- Android has no standard field for accuracy provenance. `accuracySource` is an app-specific extra; most
  clients (including ARCore) ignore extras, so to them an estimate is indistinguishable from a measurement.
  This is why the estimates are conservative.
- `Location.accuracy` is defined as a 68 % horizontal radius; the receiver's value is passed through as
  such. Vertical accuracy is 1σ.
- A mock location replaces the device GPS for every app while publishing is on. When the receiver goes
  silent, or its fixes cannot be normalised (the switchboard then publishes no live position), the test
  provider is removed so the last position does not stay standing as the device's location.
- ARCore fuses the location with camera and VPS data; a mock location does not set ARCore's pose and does
  not make AR rendering centimetre-accurate.
