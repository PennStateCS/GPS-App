# receiver-emlid

Emlid Reach support for SurReal AR, behind the receiver-neutral `ReceiverDevice` contract in
[`:gnss-core`](../gnss-core/README.md). Plain Kotlin/JVM: no Android, Hilt or app types.

## What it covers

Reach **RS2+**, **RS4** and **RS4 Pro**, over the receiver's local HTTP API and socket.io broadcasts.
The position itself always comes from the NMEA stream (the app's TCP source and `NmeaFuser`), not
from this module.

| Data | Source | Cadence | Neutral model |
|---|---|---|---|
| Battery: %, charging, voltage, current, temperature, charger text, USB charger, OTG | `GET /battery`, falling back to `/status` | every 15 s; on failure back off 5 → 15 → 30 s, and hide after 3 failures | `ReceiverBattery` (USB charger and OTG in `details`) |
| Device: name, model, firmware, GNSS chip firmware, serial, uptime, hostname, storage | `GET /info`, falling back to `/status` (several firmware JSON layouts) | every 30 s | `ReceiverDeviceInfo` (chip firmware and raw response in `details`) |
| Corrections: receiving, channel, age, baseline, base position, base satellites, solution | socket.io `broadcast` (`navigation`, `stream_status`) | pushed | `ReceiverCorrections` |

`EmlidReachDevice` runs the three loops for one host per `start(host)`, and `stop()` cancels all of
them and closes the socket. `EmlidMapping` converts the Reach JSON results to the neutral models
without dropping fields: anything without a neutral slot goes in `details`. The HTTP and socket.io
endpoints sit behind `EmlidReachDevice.EmlidApi`, so tests can replace them.

## The ReceiverDevice contract (gnss-core)

```kotlin
interface ReceiverDevice {
    val capabilities: ReceiverCapabilities        // hasBattery, hasDeviceStatus, hasCorrectionsStatus, supportsDiscovery
    fun start(host: String)                       // begin polling; may be called again after stop()
    fun stop()                                    // idempotent; leaves no coroutine or socket running
    val battery: StateFlow<ReceiverBattery?>      // null = unknown / unreachable / unsupported
    val deviceInfo: StateFlow<ReceiverDeviceInfo?>
    val corrections: StateFlow<ReceiverCorrections?>
}
interface ReceiverDiscovery { suspend fun discover(timeoutMs: Long): List<DiscoveredReceiver> }
```

Units are in the names (`voltageV`, `uptimeS`, `ageS`, `baselineKm`), and `null` means unknown. In
the app, `ReceiverDeviceManager` picks the device for the active source and profile and starts it
on the configured host. It also stops the previous device and switches devices when the profile or
host changes. The UI reads only the manager's neutral flows and capability flags.

## Adding another receiver

1. **A module** such as `receiver-ublox`, set up like this one (plain Kotlin JVM, `explicitApi()`,
   `api(project(":gnss-core"))`). In it, implement `ReceiverDevice` for whatever status the receiver
   offers. Log through `GnssLog` (never `android.util.Log`). Add a `ReceiverDiscovery` if it can be
   found on the network; Android-only discovery (NSD, Wi-Fi) goes in the app, like `EmlidDiscovery`.
2. **Proprietary NMEA sentences**: write a `SentenceParser` for each and return them from the
   profile's `extraParsers`. The live registry registers every profile's parsers.
3. **A profile**: add an `ExternalReceiverProfile` entry (app, `settings.model`) with a stable
   `prefKey`, labels, default port, `vendor`, `accuracyClass` (`SURVEY_MULTIBAND`, `SINGLE_FREQUENCY`
   or `DEVICE`; it selects the DOP × UERE fallback) and `capabilities`. Add a `ReceiverVendor` value
   if it is a new manufacturer.
4. **Wiring**: map the vendor to your device (and discovery) in the app's `di/ReceiverModule`. No UI
   change is needed: the toolbar battery, the Settings status rows and the Developer page follow the
   capabilities.

## org.json

The Reach parsers use `org.json`, which Android provides on the platform. This module compiles and
tests against Android's own org.json implementation packaged as a jar
(`com.vaadin.external.google:android-json`, `compileOnly` + `testImplementation`). That way the API
and behaviour match the device, and the APK uses the platform copy instead of bundling a duplicate. socket.io-client's own org.json dependency is
excluded for the same reason.

## Build and test

```
.\gradlew.bat :receiver-emlid:test
```

## Licence

GPL-3.0, like the rest of the repository (see the repository `LICENSE`). Runtime dependencies are
`:gnss-core`, kotlinx-coroutines and socket.io-client (MIT).
