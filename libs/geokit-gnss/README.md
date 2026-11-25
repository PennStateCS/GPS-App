# geokit-gnss

`io.github.pennstatecs.geokit:geokit-gnss` · version `GEOKIT_VERSION` (0.1.0-SNAPSHOT) · package `io.github.pennstatecs.geokit.gnss` · Gradle project `:geokit-gnss` in `libs/geokit-gnss`. Part of the [geokit](../README.md) libraries.

Android-free GNSS core for SurReal AR: NMEA 0183 parsing, epoch fusion into position fixes, the
satellite inventory, accuracy estimation, averaged point capture, a TCP NMEA source with automatic
reconnect, NMEA file replay, and receiver antenna geometry. Plain Kotlin/JVM (no Android,
Hilt, Room or app UI types), so it can be reused by other JVM projects and tested on a desktop. It
depends on [`:geokit-core`](../geokit-core/README.md) for the Earth math.

## What it handles

- **NMEA 0183** sentences GGA, RMC, GSA, GSV, ZDA and GST, from any talker (GP, GL, GA, GB, GQ, GN…).
  Checksums are verified, and can be required (`requireChecksum`) so a line cut off when a socket
  drops is rejected.
- **NMEA 4.10** extensions: the GSV signal ID (including the "last SNR omitted" form) and the GSA GNSS
  system ID, so combined `GN` multi-constellation GSA/GSV streams are counted per constellation.
- **Accuracy**: GST error statistics, converted to a 68 % horizontal radius, and MediaTek
  `$..ACCURACY` (e.g. Samsung tablets) as a fallback. If the receiver sends neither, `AccuracyEstimator`
  falls back to DOP × UERE.
- **Vendor sentences** through extra parsers (see [Proprietary sentences](#proprietary-sentences)):
  counted per tag with the latest of each, for diagnostics only. They never change a fix. This module
  knows no vendor; Emlid's EBP/ETC live in [geokit-receiver-emlid](../geokit-receiver-emlid/README.md).
- **Epoch alignment**: GST and GSA arrive after the GGA of the same epoch on Emlid receivers. The
  fuser holds the fix for at most 300 ms so the same epoch's accuracy and DOP are attached. GGA
  quality 0 is never published, and a ZDA whose time doesn't match the epoch is ignored.

Conventions: units in names (`latDeg`, `altMslM`, `diffAgeS`), `null` means unknown (never 0).
`Fix.hAccM` is a **68 % horizontal radius**; vertical accuracy and per-axis standard deviations are **1σ**.

| Package | Contents |
|---|---|
| `io.github.pennstatecs.geokit.gnss.nmea.parse` | `NmeaRegistry`, `DefaultNmeaRegistry` (standard parsers + `extraParsers`), `SentenceParser`, `NmeaFields` (field helpers for vendor parsers) and one parser per sentence |
| `io.github.pennstatecs.geokit.gnss.nmea.sentence` | `NmeaSentence`, `FlaggedNmeaSentence`, `GGA`, `RMC`, `GSA`, `GSV`, `ZDA`, `GST`, `ACCURACY` |
| `io.github.pennstatecs.geokit.gnss.bus.adapters` | `NmeaFuser` (+ `FuserTimingStats`, `ExtraSentenceStats`), `GsvMessage`, source contracts (`NmeaSource`, `Startable`, `RawNmeaProvider`, `SourceAdapter`, `SkyProvider`) |
| `io.github.pennstatecs.geokit.gnss.link` | `TcpNmeaSource` (one TCP connection per start, dead-link detection, GST hold ticker), `ReconnectingNmeaAdapter` (exponential-backoff reconnect, health checks, sky, per-fix stamping hook), `LinkLog` / `GnssLinkLog` / `LinkLogLevel` (connection lines with keyed throttles) |
| `io.github.pennstatecs.geokit.gnss.replay` | `NmeaLineSource`, `ReplayAdapter` (lines → `NmeaFuser` → fixes tagged `Provider.REPLAY`, and sky) |
| `io.github.pennstatecs.geokit.gnss.receiver` | `ReceiverDevice`, `ReceiverDiscovery`, `DiscoveredReceiver`, neutral status models, `AccuracyClass`, `ReceiverCapabilities`, `AntennaGeometry` (pole / camera heights + phase-centre offset) |
| `io.github.pennstatecs.geokit.gnss.model` | `Fix`, `RtkStatus`, `Provider`, `TimestampSource`, `SkySnapshot`, `SatInfo`, `SkyGeometry`, `Constellation`, `FixSnapshot` |
| `io.github.pennstatecs.geokit.gnss.satellites` | `SatelliteInventory` (smoothed SNR, used flags, stale eviction → `SkySnapshot`) |
| `io.github.pennstatecs.geokit.gnss.accuracy` | `AccuracyEstimator`, `UereTable`, `VerticalAccuracyValidity` (invalid markers such as Emlid's 999 m) |
| `io.github.pennstatecs.geokit.gnss.height` | `ReceiverHeights` (receiver H with model unknown, N, h = H + N trust status; see docs/geodesy/rs4-nmea-heights.md) |
| `io.github.pennstatecs.geokit.gnss.datum` | `FixDatum` (`ITRF2014`, `NAD83_2011`), `FixDatumNormalizer` (receiver output → WGS84 / ITRF2014) |
| `io.github.pennstatecs.geokit.gnss.capture` | `ObservationSession` (averaging), `AveragingPolicy`, `CapturePreset`, `CaptureResult`, `FixFreshnessGate`, `GnssCaptureSettings` |
| `io.github.pennstatecs.geokit.gnss.log` | `GnssLog`, `GnssLogger`, `NmeaDebugSampler` (periodic raw-sentence samples in debug traces) |

## Feeding it

```kotlin
val registry = DefaultNmeaRegistry.create(verifyChecksum = true, requireChecksum = true)
val sky = SatelliteInventory()
val fuser = NmeaFuser(
    provider = Provider.EXTERNAL_TCP,
    registry = registry,
    onFix = { fix -> println("${fix.latDeg}, ${fix.lonDeg} ${fix.rtkStatus} ±${fix.hAccM} m") },
    onGsv = { gsv -> val snapshot = sky.consume(gsv) /* satellites in view */ },
)

socket.bufferedReader().forEachLine(fuser::accept)   // one reader thread, in stream order
// From a ~100 ms timer: release a fix held for its GST once 300 ms have passed.
fuser.flushIfStale()
// At the end of finite input (a log file):
fuser.flush()
```

Threading: feed `accept` from one thread in stream order. The fuser's state is guarded by a lock,
so `flushIfStale`, `flush`, `reset` and the stats getters may run on other threads. `onFix` and
`onGsv` are called synchronously while that lock is held, so keep them short and non-blocking.
`NmeaRegistry.parse` is thread-safe. `SatelliteInventory`, `FixFreshnessGate` and
`ObservationSession` are not: use each from one collector.

## Proprietary sentences

The fuser uses GGA, RMC, GSA, GSV, ZDA, GST and `$..ACCURACY`. A receiver's own sentences plug in as
extra parsers, from the receiver's module:

1. A sentence type implementing `NmeaSentence` (its `tag` is the part after the two-letter talker,
   e.g. `"EBP"` for `$GNEBP`). Implement `FlaggedNmeaSentence` instead if some property should be
   remembered for the whole stream (`diagnosticFlags`, e.g. "had an orientation payload").
2. A `SentenceParser` for it. `NmeaFields.latitude` / `longitude` / `finiteDouble` read fields exactly
   like the standard parsers.
3. Hand the parsers, keyed by tag, to `DefaultNmeaRegistry.create(extraParsers = …)` (an extra parser
   with a standard tag replaces the standard one) and to `ReplayAdapter(extraParsers = …)` so recorded
   logs replay the same way.

Unregistered sentences are dropped by the registry. Every registered sentence that doesn't feed a fix
is recorded by the fuser, under its lock: `NmeaFuser.extraSentenceStats` (also
`NmeaSource.nmeaExtraStats()`) is an `ExtraSentenceStats` snapshot with `countByTag`, `latestByTag`
and `flagsByTag` (the union of flags seen), plus `count(tag)`, `seen(tag)`, `latest<T>(tag)` and
`flagSeen(tag, flag)`. `reset()` clears it. A receiver module usually adds a typed view on top
(geokit-receiver-emlid: `EmlidNmea.parsers` and `EmlidNmeaDiagnostics.from(stats)`).

## Connecting to a receiver over TCP

`TcpNmeaSource` makes one connection per `start()` and reads until the receiver closes it, two read
timeouts pass in silence (about 10 s: a half-open link), or `stop()` is called. `ReconnectingNmeaAdapter`
owns it: it waits up to 10 s for the first data, then checks every second that the link is up and that
data arrived in the last 30 s, and reconnects with backoff 1 s doubling to 30 s (+ up to 0.5 s jitter).

```kotlin
val log = GnssLinkLog()   // or your own LinkLog; share one between the source and the adapter
val source = TcpNmeaSource(
    scope = scope,
    endpoint = { "192.168.42.1" to 9001 },          // asked on every attempt; null = not configured
    registry = DefaultNmeaRegistry.create(verifyChecksum = true, requireChecksum = true),
    onLine = { line -> /* raw sentence, before parsing */ },
    linkLog = log,
)
val adapter = ReconnectingNmeaAdapter(
    scope = scope, nmea = source, inv = SatelliteInventory(),
    stampFix = { fix -> fix.copy(receiverLabel = "Emlid Reach RS2+") },   // optional
    linkLog = log,
)
adapter.start()
scope.launch { adapter.fixes.collect { fix -> /* ... */ } }
scope.launch { adapter.connectionState.collect { state -> /* CONNECTING, CONNECTED, ... */ } }
adapter.reconnectNow("receiver changed")   // drop and reconnect, e.g. after a new host is saved
adapter.stop()
```

Replay works the same way: `ReplayAdapter(scope).apply { load(myLineSource); start() }`, where
`myLineSource` is any `NmeaLineSource` (a file, a test resource). Its fixes are `Provider.REPLAY`.

## Receiver datum

A receiver on an RTK network in the US usually outputs NAD83(2011), about 1–1.5 m from WGS84. Map
SDKs, ARCore and stored captures want WGS84, so normalise each fix once as it enters the feed:

```kotlin
val wgs84Fix = FixDatumNormalizer.toWgs84(fix, FixDatum.NAD83_2011)   // ITRF2014 returns the same Fix
```

The conversion is the inverse ITRF2014 → NAD83(2011) Helmert from geokit-core at the fix's own epoch
(decimal year of `timeUtc`); converting back for display round-trips to < 0.1 mm. The ellipsoidal
height moves with the position (null stays null); the receiver's MSL height is kept and the geoid
separation recomputed. SurReal AR applies it to external receivers when Settings → Coordinates →
External Receiver Datum is NAD83(2011).

## Antenna geometry

`AntennaGeometry(poleHeightM, cameraBelowArpM, phaseCenterOffsetM)` gives `captureOffsetM` (mark →
phase centre, subtracted from a captured height) and `antennaAboveCameraM` (for AR placement). Heights
are measured to the antenna reference point; the phase-centre offset per receiver model comes from
the receiver module (Emlid: `EmlidReachModel` in geokit-receiver-emlid).

## Logging

The module never logs by itself. Install a `GnssLogger` once at startup, before any source starts:

```kotlin
GnssLog.install(object : GnssLogger {
    override val enabled = true                      // produce throttled debug traces
    override fun d(tag: String, message: String) = println("D/$tag: $message")
    override fun i(tag: String, message: String) = println("I/$tag: $message")
    override fun w(tag: String, message: String, throwable: Throwable?) = println("W/$tag: $message")
    override fun e(tag: String, message: String, throwable: Throwable?) = println("E/$tag: $message")
    override fun diagnostic(tag: String, message: String, level: DiagnosticLevel, throwable: Throwable?) =
        println("DIAG-$level/$tag: $message")
    private val last = java.util.concurrent.ConcurrentHashMap<String, Long>()
    override fun isDue(key: String, intervalMs: Long): Boolean {
        val now = System.nanoTime() / 1_000_000
        val prev = last[key]
        return (prev == null || now - prev >= intervalMs).also { if (it) last[key] = now }
    }
    override fun trace(area: String, message: String) = println("[$area] $message")
})
```

Without one, everything goes to `GnssLogger.NoOp`. `diagnostic` is for events the host keeps in an
exportable log, and never carries coordinates. Debug traces (`GnssLog.every`) are inline and throttled
per key, so they cost one volatile read when disabled. In SurReal AR, `AndroidGnssLogger` sends the
calls to logcat, the diagnostics log and the debug-only `GnssDebug` tag.

The TCP source, the reconnecting adapter and replay also take a `LinkLog` for their connection and
replay lines (connect, retry, dead link, replay started). The default `GnssLinkLog` writes them through
`GnssLog.diagnostic*` with its own throttles; SurReal AR passes `DiagnosticsLinkLog`, which writes to
its diagnostics logger.

## Receivers it is tested against

- **Emlid Reach RS4**: real captures in `src/test/resources/nmea/`. `reach_nmea_capture.txt` is the
  standard stream (RTK Fixed, GST, EBP, timestamp-only ETC). `reach_nmea_withimu.txt` adds ETC
  orientation payloads. Here they replay without the Emlid parsers (EBP/ETC are just unknown);
  geokit-receiver-emlid's tests replay the same files with them.
- **Emlid Reach RS2+**: `reach_nmea_rs2_capture.txt` (RTK Float, GST, EBP, no ETC). All three
  captures send separate GP/GL/GA/GB GSA.
- **Samsung SM-X940** tablet internal GNSS sentences: decimal SNR in GSV, no GST,
  `$GNACCURACY`. These are covered in the GSV, fuser and accuracy tests.

`ReplayAdapterTest` replays them here. `NmeaFixtureGoldenTest` pins the complete fused output of each
capture (every `Fix`, GSV epoch and sky snapshot, hashed, plus the final timing stats) to
`src/test/resources/golden/`; regenerate with `GEOKIT_WRITE_GOLDEN=1` only after an intended change. The app's logging tests use them too (the app adds this
folder as a test-resource directory).

## Using it from another Gradle project

For now, include the geokit build (`libs/` of a checkout or submodule of this repository) as a
composite build; it needs no Android SDK. `geokit-core` comes along transitively. See
[Using geokit from another Gradle project](../README.md#using-geokit-from-another-gradle-project).

```kotlin
// settings.gradle.kts of the other project
includeBuild("GPS-App/libs")
// build.gradle.kts
dependencies { implementation("io.github.pennstatecs.geokit:geokit-gnss") }
```

Publishing through JitPack or GitHub Packages may come later; it is not set up yet.

## Build and test

```
cd libs
..\gradlew.bat :geokit-gnss:build    # compiles and runs the module's tests, without Android
```

From the repository root: `.\gradlew.bat :libs:geokit-gnss:test`.

## Licence

GPL-3.0, like the rest of the repository (see the repository `LICENSE`). Runtime dependencies are
`:geokit-core` and kotlinx-coroutines-core (Apache-2.0).
