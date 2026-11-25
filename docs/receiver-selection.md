# Receiver selection (Phase 5 core)

How SurReal AR tests, switches, identifies and remembers external GNSS receivers. Code:
`app/src/main/java/app/surrealar/receiver/selection/` (`ReceiverConnectionController`, `ReceiverIdentityRules`,
`ReceiverSelectionModels`, `ReceiverProbe`), `settings/model/ProfileOrigin.kt`, `settings/model/RecentReceiver.kt`,
`ui/capture/ActiveCaptureTracker.kt`.

## One subsystem, reused pieces

`ReceiverConnectionController` is a Hilt singleton (built in `di/ReceiverModule`) on the app scope. Closing a
screen, sheet or dialog never cancels an attempt or disconnects a receiver. It adds no connection path of its own:

- `GnssSourceCoordinator` is still the only place that flips the active provider;
- the live socket stays with the external adapter (geokit `TcpNmeaSource` / `ReconnectingNmeaAdapter`, unchanged);
- identification comes only from `ReceiverDeviceManager.deviceInfo` (Emlid HTTP device info);
- NMEA parsing and the geokit link are untouched.

The old Settings → Receiver screen (`ReceiverSettingsController`) delegates connect, disconnect and device-GNSS
switches to it.

## State model

`state: StateFlow<ReceiverSelectionState>`:

| Field | Meaning |
|---|---|
| `selectedSource`, `savedTarget`, `savedName`, `profile`, `profileOrigin` | what is persisted |
| `activeProvider`, `link` | what is actually streaming (the active provider can lag the selected source) |
| `attempt` | `Idle` → `Testing` → (`Switching` → `Succeeded`) or `Failed(error)` |
| `identity`, `mismatch` | what the receiver reported; whether it disagrees with the profile |
| `captureActive`, `recent`, `scan` | capture flag, recent receivers, network scan |

`events` carries one-off notices (`ProfileAutoApplied`) for a snackbar.

## Test before switch

`connect(ConnectRequest(host, port, profile?, name), confirmCaptureStop)`:

1. Probe: a TCP NMEA sniff on its own short-lived socket (4 s connect, up to 5 s of lines) and, **for Emlid profiles
   only**, the Emlid HTTP API (`/info`, `/status`) in parallel. A Generic NMEA receiver gets no HTTP or Socket.IO
   request (the Emlid device and its Socket.IO link only run for Emlid profiles, through `ReceiverDeviceManager`).
2. Only when the sniff receives NMEA: save connection type → host/port[/name] → profile picked for this connection
   (origin user) → selected source External, then activate the external provider (the coordinator's order).
3. Anything else changes nothing and ends in `Failed`: `Unreachable`, `HttpOnly` ("Receiver found but NMEA stream
   not available on port N"), `NoData`, `NotNmea`, `Unexpected`.

Attempts: a repeat request for the target being tested is ignored (`DUPLICATE_IGNORED`); the active, streaming
receiver needs no test (`ALREADY_ACTIVE`); a request for another target supersedes the running attempt. A superseded
attempt never saves or activates: it is checked under a lock when it would commit, and once committed it activates
only if device GNSS was not selected after it. Settings writes are serialised by a mutex. `selectInternal()` /
`disconnect()` cancel the attempt and make device GNSS active synchronously (mock publishing off).

## Identity rules

- A model is verified only when the active receiver at the saved host explicitly reports it
  (`ReceiverDeviceInfo.model`, mapped with `EmlidReachModel.fromReportedModel`: RS4 Pro before RS4, RS2+). Discovery
  names, IP addresses and user-selected profiles are never proof. A plain "RS2", unknown or blank model, info for
  another host, or device GNSS active = not identified, and the manual profile stays.
- The profile is replaced automatically (origin `receiver`) only when the stored origin is not `user`, no capture
  is running, and the model is verified for the current host. Otherwise `mismatch` is set for a banner
  (`applyReportedProfile(confirmCaptureStop)` / `keepCurrentProfile()`). "Keep" makes the profile the user's and hides
  that report for the rest of the process.
- Identification never changes host or port and never implies a feature: firmware or model does not mean tilt
  compensation, compensated NMEA output or orthometric streaming is enabled. `external_compensated_nmea_output` is
  never written automatically.

## Persistence

New keys only; existing keys, values and defaults are unchanged.

- `external_receiver_profile_origin`: `user` / `receiver` / `default`. **Existing installs have no origin key:** if a
  profile key is already stored, the origin reads `user` (an explicit pre-Phase 5 choice is never overridden
  automatically); with no profile stored it reads `default`. Unknown tokens read `user`. A profile written without
  an origin (the old profile selector, `setExternalReceiverSettings` with a changed profile) is recorded as `user`.
- `external_recent_receivers`: JSON list, newest first, at most 8 (`host`, `port`, `profileKey`, `name`, `model`,
  `serial`, `firmware`, `lastConnectedMs`). One entry per receiver: by serial when reported, else host:port (a
  reported serial also replaces the serial-less entry at that host:port). Unknown fields are ignored, invalid
  entries dropped, corrupt JSON reads as empty. Added on every successful connect; model/serial/firmware only from
  the receiver's own report. Not shown in the diagnostic settings snapshot (it holds other receivers' hosts).

## Capture safety

- `ActiveCaptureTracker.active` is true while a `CaptureViewModel` occupation runs; cleared on complete/timeout,
  cancel, interruption and `onCleared` (dialog dismissed).
- `connect` returns `NEEDS_CAPTURE_CONFIRMATION` when a capture runs and the request would change the receiver or
  profile; the UI asks "Switching receivers stops the current capture" and calls again with `confirmCaptureStop`.
- The Phase 4 watchers still stop the capture when a setting changes; Phase 5 adds the receiver **port** to
  `watchExternalSetup` (host, profile and compensated output were already watched). A profile change is a capture
  stop, so it is never applied automatically during a capture.

## Behaviour changes for the existing screen

- Generic NMEA profiles no longer get an Emlid HTTP probe.
- A connection is saved and activated only when NMEA arrives; HTTP-only, connected-but-silent and non-NMEA ports
  used to be saved and activated, now they show an error and nothing changes (the previous receiver keeps running).
- Choosing External with a saved receiver saves External only after that receiver streams NMEA.
- Attempts survive the Settings view being recreated (they used to be cancelled with it).
- Network discovery labels hits "Emlid Reach (model confirmed after connecting)" with no profile (it used to guess
  RS2+).

## Limitations

- Real `/info` model strings per Emlid firmware are not confirmed on hardware; unmatched strings keep the manual
  profile.
- The probe opens a second TCP client to the receiver while a live stream may be running; Emlid accepts several
  clients, other receivers may not.
- "Keep" dismissals are not persisted (the banner can return after a restart while the profile still differs).
- The picker, manual dialog and receiver details UI are a separate work package; this is the core API only.
