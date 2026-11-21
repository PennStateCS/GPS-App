# gnss-core

Android-free GNSS core for SurReal AR: NMEA 0183 parsing, epoch fusion into position fixes, the
satellite inventory, accuracy estimation and averaged point capture. Plain Kotlin/JVM (no Android,
Hilt, Room or app UI types), so it can be reused by other JVM projects and tested on a desktop. It
depends on [`:geo-core`](../geo-core/README.md) for the Earth math.

## What it handles

- **NMEA 0183** sentences GGA, RMC, GSA, GSV, ZDA and GST, from any talker (GP, GL, GA, GB, GQ, GN…).
  Checksums are verified, and can be required (`requireChecksum`) so a line cut off when a socket
  drops is rejected.
- **NMEA 4.10** extensions: the GSV signal ID (including the "last SNR omitted" form) and the GSA GNSS
  system ID, so combined `GN` multi-constellation GSA/GSV streams are counted per constellation.
- **Accuracy**: GST error statistics, converted to a 68 % horizontal radius, and MediaTek
  `$..ACCURACY` (e.g. Samsung tablets) as a fallback. If the receiver sends neither, `AccuracyEstimator`
  falls back to DOP × UERE.
- **Emlid** proprietary EBP (base position) and ETC (timestamp / orientation) sentences, counted for
  diagnostics only. They never change a fix.
- **Epoch alignment**: GST and GSA arrive after the GGA of the same epoch on Emlid receivers. The
  fuser holds the fix for at most 300 ms so the same epoch's accuracy and DOP are attached. GGA
  quality 0 is never published, and a ZDA whose time doesn't match the epoch is ignored.

Conventions: units in names (`latDeg`, `altMslM`, `diffAgeS`), `null` means unknown (never 0).
`Fix.hAccM` is a **68 % horizontal radius**; vertical accuracy and per-axis standard deviations are **1σ**.

| Package | Contents |
|---|---|
| `app.surrealar.gnss.nmea.parse` | `NmeaRegistry`, `DefaultNmeaRegistry`, `SentenceParser` and one parser per sentence |
| `app.surrealar.gnss.nmea.sentence` | `GGA`, `RMC`, `GSA`, `GSV`, `ZDA`, `GST`, `ACCURACY`, `EBP`, `ETC` |
| `app.surrealar.gnss.bus.adapters` | `NmeaFuser` (+ `FuserTimingStats`, `NmeaCustomStats`), `GsvMessage`, source contracts (`NmeaSource`, `Startable`, `RawNmeaProvider`) |
| `app.surrealar.gnss.model` | `Fix`, `RtkStatus`, `Provider`, `TimestampSource`, `SkySnapshot`, `SatInfo`, `SkyGeometry`, `Constellation`, `FixSnapshot` |
| `app.surrealar.gnss.satellites` | `SatelliteInventory` (smoothed SNR, used flags, stale eviction → `SkySnapshot`) |
| `app.surrealar.gnss.accuracy` | `AccuracyEstimator`, `UereTable` |
| `app.surrealar.gnss.capture` | `ObservationSession` (averaging), `AveragingPolicy`, `CapturePreset`, `CaptureResult`, `FixFreshnessGate`, `GnssCaptureSettings` |
| `app.surrealar.gnss.log` | `GnssLog`, `GnssLogger` |

## Feeding it

```kotlin
val registry = DefaultNmeaRegistry.create(verifyChecksum = true, requireChecksum = true)
val sky = SatelliteInventory()
val fuser = NmeaFuser(
    provider = Provider.RS2_TCP,
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

## Logging

The module never logs by itself. Install a `GnssLogger` once at startup, before any source starts:

```kotlin
GnssLog.install(object : GnssLogger {
    override val enabled = true                      // produce throttled debug traces
    override fun d(tag: String, message: String) = println("D/$tag: $message")
    override fun i(tag: String, message: String) = println("I/$tag: $message")
    override fun w(tag: String, message: String, throwable: Throwable?) = println("W/$tag: $message")
    override fun e(tag: String, message: String, throwable: Throwable?) = println("E/$tag: $message")
    override fun diagnostic(tag: String, message: String) = println("DIAG/$tag: $message")
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

## Receivers it is tested against

- **Emlid Reach RS4**: real captures in `src/test/resources/nmea/`. `reach_nmea_capture.txt` is the
  standard stream (RTK Fixed, GST, EBP, timestamp-only ETC). `reach_nmea_withimu.txt` adds ETC
  orientation payloads.
- **Emlid Reach RS2+**: `reach_nmea_rs2_capture.txt` (RTK Float, GST, EBP, no ETC). All three
  captures send separate GP/GL/GA/GB GSA.
- **Samsung SM-X940** tablet internal GNSS sentences: decimal SNR in GSV, no GST,
  `$GNACCURACY`. These are covered in the GSV, fuser and accuracy tests.

The fixtures are also used by the app's own replay and logging tests (the app adds this folder as
a test-resource directory).

## Using it from another Gradle project

For now, use either a composite build or a git submodule. `gnss-core` depends on `geo-core`, so
include both.

```kotlin
// settings.gradle.kts of the other project (composite build)
includeBuild("../GPS-App") {
    dependencySubstitution {
        substitute(module("app.surrealar:gnss-core")).using(project(":gnss-core"))
        substitute(module("app.surrealar:geo-core")).using(project(":geo-core"))
    }
}
// build.gradle.kts
dependencies { implementation("app.surrealar:gnss-core") }
```

Or add this repository as a submodule and include `:geo-core` and `:gnss-core` with their
`projectDir` pointing into it. Publishing through JitPack or GitHub Packages may come later; it is
not set up yet.

## Build and test

```
.\gradlew.bat :gnss-core:build     # compiles and runs the module's tests on its own
```

## Licence

GPL-3.0, like the rest of the repository (see the repository `LICENSE`). Runtime dependencies are
`:geo-core` and kotlinx-coroutines-core (Apache-2.0).
