# geokit-receiver-emlid

`io.github.pennstatecs.geokit:geokit-receiver-emlid` · version `GEOKIT_VERSION` (0.1.0-SNAPSHOT) · package `io.github.pennstatecs.geokit.receiver.emlid` · Gradle project `:geokit-receiver-emlid` in `libs/geokit-receiver-emlid`. Part of the [geokit](../README.md) libraries.

Emlid Reach support for SurReal AR, behind the receiver-neutral `ReceiverDevice` contract in
[`:geokit-gnss`](../geokit-gnss/README.md). Plain Kotlin/JVM: no Android, Hilt or app types.

## What it covers

Reach **RS2+**, **RS4** and **RS4 Pro**, over the receiver's local HTTP API and socket.io broadcasts,
plus finding a Reach on the network and each model's antenna phase-centre offset. The position itself
always comes from the NMEA stream (geokit-gnss's `TcpNmeaSource` and `NmeaFuser`), not from this module.

| Data | Source | Cadence | Neutral model |
|---|---|---|---|
| Battery: %, charging, voltage, current, temperature, charger text, USB charger, OTG | `GET /battery`, falling back to `/status` | every 15 s; on failure back off 5 → 15 → 30 s, and hide after 3 failures | `ReceiverBattery` (USB charger and OTG in `details`) |
| Device: name, model, firmware, GNSS chip firmware, serial, uptime, hostname, storage | `GET /info`, falling back to `/status` (several firmware JSON layouts) | every 30 s | `ReceiverDeviceInfo` (chip firmware and raw response in `details`) |
| Corrections: receiving, channel, age, baseline, base position, base satellites, solution | socket.io `broadcast` (`navigation`, `stream_status`) | pushed | `ReceiverCorrections` |

`EmlidReachDevice` runs the three loops for one host per `start(host)`, and `stop()` cancels all of
them and closes the socket. `EmlidMapping` converts the Reach JSON results to the neutral models
without dropping fields: anything without a neutral slot goes in `details`. The HTTP and socket.io
endpoints sit behind `EmlidReachDevice.EmlidApi`, so tests can replace them.

### Antenna phase-centre offsets

`EmlidReachModel` lists each model's L1 phase-centre height above the antenna reference point (the
bottom of the receiver), for geokit-gnss's `AntennaGeometry`:

| Model | `phaseCenterOffsetM` | Source |
|---|---|---|
| `RS2`, `RS2_PLUS` | 0.134 | Emlid RS2/RS2+ manual |
| `RS4` | 0.095 | Emlid RS4 specs (L1) |
| `RS4_PRO` | null (unknown) | not published separately; the user enters it |

### Finding a Reach on the network

`ReachNetworkScan` is a JVM `ReceiverDiscovery`: an HTTP sweep of the local subnet (port 80, 250 ms
connect / 300 ms read, 32 hosts at a time; a page mentioning "reach", "emlid" or "rs2" counts), then a
probe of ports 5000 and 9001 on each hit. Give it the device's address and netmask
(`localNetwork = { ip to mask }`); a missing or wider-than-/20 mask is treated as /24, and a range over
512 hosts is cut to the first /24. `Ipv4Subnet` holds the address maths (including
`littleEndianIntToString` for Android's `DhcpInfo`).

A host with mDNS runs that first and reuses the pieces, so both phases report alike:
`validateHttpService`, `probeBothPorts`, `sweep(ip, mask, found, onNew)` and one shared `ReachDeviceSet`
(one entry per IP, later finds merged in, each new or changed device logged once). In SurReal AR,
`ReachDiscoveryHelper` does mDNS with NsdManager and falls back to `sweep`. Results convert to the
neutral `DiscoveredReceiver` with `ReachDevice.toDiscoveredReceiver(profileKey)`.

## The ReceiverDevice contract (geokit-gnss)

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
   `api(project(":geokit-gnss"))`). In it, implement `ReceiverDevice` for whatever status the receiver
   offers. Log through `GnssLog` (never `android.util.Log`), and connection-style lines through a
   `LinkLog` the host passes in. Add a JVM `ReceiverDiscovery` if it can be found on the network (like
   `ReachNetworkScan`); only the Android-specific part (NSD/mDNS, reading the Wi-Fi address) goes in the
   app, like `ReachDiscoveryHelper` / `EmlidDiscovery`. List each model's antenna phase-centre offset
   in the module (like `EmlidReachModel`; null when unknown).
2. **Proprietary NMEA sentences**: write a `SentenceParser` for each and return them from the
   profile's `extraParsers`. The live registry registers every profile's parsers.
3. **A profile**: add an `ExternalReceiverProfile` entry (app, `settings.model`) with a stable
   `prefKey`, labels, default port, `vendor`, `accuracyClass` (`SURVEY_MULTIBAND`, `SINGLE_FREQUENCY`
   or `DEVICE`; it selects the DOP × UERE fallback), `phaseCenterOffsetM` (read from your module's
   model table, not typed in) and `capabilities`. Add a `ReceiverVendor` value if it is a new
   manufacturer. The NMEA stream needs nothing new: geokit-gnss's `TcpNmeaSource` and
   `ReconnectingNmeaAdapter` work for any receiver that streams NMEA over TCP.
4. **Wiring**: map the vendor to your device (and discovery) in the app's `di/ReceiverModule`. No UI
   change is needed: the toolbar battery, the Settings status rows and the Developer page follow the
   capabilities.

## org.json

The Reach parsers and socket.io-client / engine.io-client use `org.json`, which Android provides on
the platform.

- **Compile and test**: against Android's own org.json implementation packaged as a jar
  (`com.vaadin.external.google:android-json`, `compileOnly` + `testImplementation`), so the API and
  behaviour match the device. `org.json:json` is kept off the test runtime classpath so the tests see
  only that one copy.
- **Runtime on a plain JVM**: the module declares `org.json:json:20090211` as `runtimeOnly` (the
  version socket.io-client 1.0.1's POM asks for; its own transitive copy is excluded), so a desktop
  consumer gets org.json without adding it and doesn't fail with `NoClassDefFoundError`.
- **Android**: exclude `org.json:json` from the app's configurations so the APK uses the platform copy
  instead of bundling a duplicate. SurReal AR does this in `app/build.gradle.kts`:

  ```kotlin
  configurations.configureEach { exclude(group = "org.json", module = "json") }
  ```

## Build and test

```
.\gradlew.bat :geokit-receiver-emlid:test
```

## Licence

GPL-3.0, like the rest of the repository (see the repository `LICENSE`). Runtime dependencies are
`:geokit-gnss`, kotlinx-coroutines, socket.io-client (MIT) and, on a plain JVM, org.json (JSON License).
