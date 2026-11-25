# Receiver selection (Phase 5)

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

Settings → Receiver (`ReceiverSettingsController`, the receiver card) and the receiver sheets and dialog
(`ui/settings/receiver/`) only call it; they never save receiver settings themselves.

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

### Review fixes (survey/phase5-fix)

- **Live checks.** `connect()` decides from live values (`ActiveCaptureTracker.active.value`,
  `SourceSettings.activeProvider.value`, the coordinator's `activeExternalTarget`, the adapter's link state), not
  from `state` copies that start at defaults. The capture check is repeated under the commit lock: a capture that
  started during the probe ends the attempt in `Failed(CaptureStarted)` with nothing saved or activated until the
  user connects again and confirms.
- **One transaction.** The commit writes connection type, host/port/name, the picked profile + origin and the
  selected source in one DataStore edit (`SettingsRepository.saveExternalTcpReceiver`); a failed write leaves the
  previous receiver saved and active.
- **Saved name.** A request without a name keeps the stored name when host:port is unchanged (reconnect, scan row
  without a hostname) and clears it for another receiver. A fresh, verified receiver report fills a blank saved name;
  a stored name (user-entered) is never overwritten. Scan rows pass the discovered hostname as the name.
- **Repeat taps.** After `Succeeded`, a connect to the same target while the adapter is still `CONNECTING` returns
  `ALREADY_ACTIVE` (no second probe).
- **Reconnect.** `reconnectSaved()` for the receiver that is already the active target (link down) makes the
  adapter reconnect after a successful test (`GnssSourceCoordinator.reconnectExternalTcpProvider`); a plain
  `connect()` to it still keeps the stream.
- **Scan.** `scanNetwork()` runs Emlid discovery only for an Emlid profile or with `emlidScan = true` (the "Scan for
  Emlid receivers" button); it returns false otherwise.
- **Probe cancellation.** Cancelling the TCP probe closes its socket at once (a receiver that accepts and never
  answers is not held for the 5 s window).

## Identity rules

- **Same-address units.** Several receivers can share an address (every Reach hotspot is 192.168.42.1). After a
  switch (commit) or a link reconnect, the device info on hand is fenced off: it identifies nothing and never
  auto-applies a profile until a report arrives afterwards. `ReceiverDeviceManager.restartDevice()` is called at the
  fence, so the device clears its info and polls again at once.

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
  reported serial also replaces the serial-less entry at that host:port; a serial-less entry replaces only the newest
  entry at its host:port, so entries of different serials at one address are never merged). Unknown fields are ignored, invalid
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

## UI (WP P2)

Code: `ui/settings/receiver/` (`ReceiverCardPresenter` + `ReceiverCardBinder`, `ReceiverPickerSheet` +
`ReceiverPickerRows`, `ManualReceiverDialog` + `ManualReceiverInput`, `ReceiverDetailsSheet` +
`ReceiverDetailsPresenter`, `ReceiverUiDeps`); layouts `view_receiver_card`, `sheet_receiver_picker`,
`item_receiver_picker_*`, `dialog_manual_receiver`, `sheet_receiver_details`, `item_receiver_detail_row`.
The presenters are pure and unit-tested; the sheets/dialog are plain fragments that resolve the core through
`ReceiverUiDeps` (the Hilt entry point; tests install a fake), so they restore after rotation.

### Flow

```
Settings > Receiver
+--------------------------------------------------+
| [sat] External receiver                          |
|       Base RS4 Pro                               |
|       192.168.42.1:9001                          |
| Emlid Reach RS4 Pro          [Reported by receiver]
| (Connected) (RTK Fix) (18/24 sats) (Battery 82%) |
| +----------------------------------------------+ |
| | Receiver reports Reach RS4 Pro; profile is   | |   <- mismatch banner (only when it applies)
| | Emlid Reach RS2+.          [Keep] [Use RS4 Pro]| |
| +----------------------------------------------+ |
| [========== Change receiver ==========]          |
| Receiver details                       [Retry]   |   <- Retry only when the saved receiver is down
+--------------------------------------------------+
| Tilt and antenna setup (external)  / Tablet antenna height (tablet GNSS)
| Advanced: mock location
```

- **Change receiver** opens `ReceiverPickerSheet` (half-expanded below 840 dp; expanded, at most 640 dp wide and
  centred from 840 dp):
  `Tablet GNSS` · `On this network` (scan status, "Last scanned …", Scan / Rescan, results labelled
  "Emlid Reach (model confirmed after connecting)") · `Previously connected` (name, host:port, last reported model
  "(reported by receiver)" or the profile "(set by you)", last connected, Forget) · `Connect manually…`.
  Rows show Active / Testing… / Not found in last scan / Failed: reason. A tap calls `connect()` (recent row: with
  the profile it was connected with; scan row: profile unchanged) or `selectInternal()`; the sheet stays open and
  follows the state. During a capture the user is asked "Switching receivers stops the current capture. Switch?"
  (`NEEDS_CAPTURE_CONFIRMATION` → `connect(..., confirmCaptureStop = true)`; Tablet GNSS asks the same before
  `selectInternal`). Duplicate taps are ignored by the core. Dismissing never cancels or disconnects.
- **Network scan** starts on open only when the profile is an Emlid one; otherwise the user taps "Scan for Emlid
  receivers" (a Generic NMEA user never triggers the Emlid sweep implicitly).
- **Connect manually…** (`ManualReceiverDialog`): host/IP (IPv4 or host name), receiver type (Generic NMEA TCP,
  RS2+, RS4, RS4 Pro), TCP port (starts at the saved port or the type's default; a type change applies the new
  default only while the port is a default — `ExternalReceiverProfile.portForProfileChange`). "Test connection" calls
  `ReceiverConnectionController.test()` (same probe, nothing saved or switched, result inline); "Save & connect"
  calls `connect()` with the chosen type, which the core saves as the user's profile (never a direct settings write).
- **Receiver details** (`ReceiverDetailsSheet`): Receiver (source, model, profile, serial, firmware, battery),
  RTK corrections (status, age — the receiver's report or the NMEA differential age — baseline), Coordinates and
  heights (receiver output datum, height shown, receiver H model "Unknown"), Antenna and tilt (pole height,
  phase-centre offset, compensated NMEA output with "Firmware alone does not show whether tilt compensation or
  compensated NMEA output is turned on. Set it to match Emlid Flow."), Troubleshooting (connection, last error,
  NMEA rate from `DiagnosticsService.diagnosticData.linesPerSecond`), buttons Copy summary (no host, port, name or
  credentials), Copy address (explicit), Open diagnostics (Developer page), Disconnect receiver. Every row is tagged
  Reported by receiver / Set by you / Default / From an earlier receiver report / Unknown. Receiver reports are used
  only while that receiver is the active external source.
- **Automatic profile change notice:** `MainActivity` injects the core (so it starts with the app) and shows
  "Profile set to … (reported by receiver)." for `ProfileAutoApplied` on whichever screen is open.
- **Toolbar battery / SOL** and the capture dialog's "Open receiver settings" still open Settings → Receiver; the card
  at the top shows status and battery, and "Receiver details" is one tap away.
- Accessibility: chips have content descriptions ("Connection state: Connected", "Fix quality: RTK Fix",
  satellites in words); connection-state changes are announced; buttons and rows are ≥ 48 dp; colours are theme roles
  (primary/tertiary/error containers), so light and dark both work.

### Changes to the existing Settings → Receiver page

- Removed from the page: the Position Source radio (Internal GPS / External GNSS), the External Receiver Setup's
  profile dropdown, host/port fields, Connect and Disconnect buttons, the status rows, the device-info panel with its
  Refresh button and self-test grid, and the background diagnostic probe. Their functions moved: source switch and
  connect → picker; host/port/profile → manual dialog; status → card; device info → details; Disconnect → details.
- Kept unchanged below the card: Compensated NMEA output (RS4 / RS4 Pro) and tilt status, pole height, camera
  distance, phase-centre offset, the pole-height notice, tablet antenna height, mock location and its error banner.
- No setting key, value or default changed. The page no longer writes `external_tcp_name` from the receiver's mDNS
  name or device info (the card shows the receiver-reported name from the core instead).
- Unchanged behaviour: once per Settings screen, with External saved, the saved receiver is reconnected (test before
  switch) unless the user already acted on the page or a test is running; with tablet GNSS saved, the device provider
  is made active.

### Device-only checks

Checklist section 13 in [geodesy/tablet-acceptance-checklist.md](geodesy/tablet-acceptance-checklist.md): sheet
sizes on the SM-X940 in both orientations, scan / mDNS on the tablet and after a Wi-Fi change, real Emlid model
strings and the mismatch banner / automatic notice, TalkBack and dark theme, capture confirmation, Generic NMEA making
no Emlid requests.

## Limitations

- Real `/info` model strings per Emlid firmware are not confirmed on hardware; unmatched strings keep the manual
  profile.
- The probe opens a second TCP client to the receiver while a live stream may be running; Emlid accepts several
  clients, other receivers may not.
- "Keep" dismissals are not persisted (the banner can return after a restart while the profile still differs).
- The picker rebuilds its rows when something shown changes; a TalkBack user can lose focus on a status change.
