# Reach RS4 Pro tilt compensation and the app's antenna-height reduction

Status: investigation notes (Phase 1, 2026-11-24), updated in Phase 4 WP T (2026-11-24) with the
official Emlid documentation and the geokit-receiver-emlid ETC decoder / tilt model. Nothing here
changes the app's maths.

## What the app reads from the receiver

| Interface | Code | Used for |
|---|---|---|
| NMEA 0183 over TCP (GGA, RMC, GSA, GSV, GST, ZDA, plus Emlid `EBP`/`ETC`) | `geokit-gnss` `link/TcpNmeaSource`, `bus/adapters/NmeaFuser`; Emlid parsers in `geokit-receiver-emlid` `nmea/EmlidNmea` | Every position, height, accuracy and time |
| Reach HTTP / socket.io services | `geokit-receiver-emlid` `EmlidReachDevice` | Battery, device info and correction status only — never positions |

The position the app captures is the GGA latitude/longitude and H + N (h = H + N, confirmed by the reviewer).
`$..ETC` (Emlid Tilt Compensation) sentences are detected and counted for diagnostics
(`EmlidNmeaDiagnostics`, shown by `NmeaStreamDiagnosticsFormatter`). Since Phase 4 WP T geokit-receiver-emlid
also decodes them per the official layout and offers a per-fix tilt state and a position-reference
classification (see "Phase 4 WP T" below); since Phase 4 WP C the app uses them for status and capture rules (see
"Phase 4 WP C" below). No tilt value is applied to any position.

## How the app reduces heights

`AntennaGeometry`: saved points subtract **pole height + phase-centre offset** (ARP → APC) from the
streamed height, so the streamed position is assumed to be the **antenna phase centre**. Live (toolbar)
heights are not reduced.

## What Emlid documents (checked 2026-11-24)

- The RS4 Pro has IMU tilt compensation (datasheet: RTK + 2 mm + 0.3 mm/°); it needs an RTK FIX and an
  aligned IMU. ([product page](https://emlid.com/reachrs4pro/), [Emlid blog](https://blog.emlid.com/imu-tilt-compensation-in-surveying/))
- The RS4 NMEA specification lists GGA/RMC/VTG/ZDA/GST and the custom `ETC` message "with tilt
  compensation data". ([NMEA format](https://docs.emlid.com/reachrs4/specifications/nmea-format/))
- For the Reach RX2, Emlid Flow has a toggle to **stream the compensated position** to external
  software, and says the antenna height must be set **only in Emlid Flow** — entering it again in the
  third-party app "can cause discrepancies". ([RX2 mock location](https://docs.emlid.com/reachrx2/integration/mock-location/),
  [RX2 tilt quickstart](https://docs.emlid.com/reachrx2/quickstart/tilt-compensation/))
- Other vendors (e.g. SparkFun RTK) rewrite all outgoing NMEA to the pole tip during tilt compensation.
  ([SparkFun](https://docs.sparkfun.com/SparkFun_RTK_Everywhere_Firmware/menu_tilt/))

## Assessment

| Receiver setting | What GGA most likely reports | Effect with today's app |
|---|---|---|
| Tilt compensation off | Antenna phase centre | Correct (current workflow) |
| Tilt on, compensated-position streaming on (if the RS4 Pro offers it as the RX2 does) | Pole tip, with Emlid Flow's antenna height already applied | **Double correction**: the app subtracts pole + PCO again → saved heights ≈ 2 m low; horizontal is the pole tip (fine) |
| Tilt on, streaming of raw position | Antenna phase centre of a *tilted* pole | Horizontal error ≈ pole height × sin(tilt) (e.g. 2 m × sin 5° ≈ 17 cm) and a small height error |

**Update (Phase 4 WP T):** the RS4 changelog (firmware 34.4) settles the principle: NMEA is compensated
only when compensated output is enabled in Emlid Flow; otherwise non-compensated. Whether the TCP output
offers that toggle is still open — see "The ten questions" below.

**Not established (Phase 1 wording):** whether the RS4 Pro's TCP NMEA output (the interface this app uses) carries the
compensated or the raw position, and whether that depends on an Emlid Flow toggle. The documentation found
is for the RX2; the RS4 Pro page does not say. The `reach_nmea_withimu` fixture contains ETC sentences but
no plumb-vs-tilted comparison, so it cannot settle the question.

## Recommendation

1. **Now (Phase 1):** conditional warning in Receiver settings — if tilt compensation or compensated-
   position streaming is on, the receiver may already report the pole tip and the pole height entered in
   the app would be subtracted twice; until verified, turn tilt compensation off and level the pole.
   Antenna geometry is unchanged.
2. **Field test (no code):** on a known mark, record GGA with the pole plumb and then tilted ~15° with tilt
   compensation on and off (and with the streaming toggle on/off if present). Compare GGA position and
   height against the mark and against the app's pole height.
3. **Phase 2 options, depending on the result:** an in-app warning when `ETC` sentences are seen in the
   stream (detection already exists); or a "receiver reports pole tip" profile option that skips the
   app's pole reduction. No new tilt mathematics either way.

## Phase 4 WP T: official documentation (retrieved 2026-11-24)

Sources, all retrieved 2026-11-24 (none of the pages shows a last-updated date):

| # | Source | Version information |
|---|---|---|
| S1 | RS4 NMEA format: https://docs.emlid.com/reachrs4/specifications/nmea-format/ | none on the page |
| S2 | ETC developer document linked from S1: https://files.emlid.com/docs/ETC_rev.3_RS4.pdf | "Message rev. 1, Document rev. 3" |
| S3 | RS4 tilt quickstart: https://docs.emlid.com/reachrs4/rtk-quickstart/tilt-compensation/ | none |
| S4 | RS4 changelog: https://docs.emlid.com/reachrs4/changelogs/ | firmware 34.4 (2026-04-23), 34.5 (2026-05-29), 34.7 (2026-08-31), 34.10 (2026-11-20) |
| S5 | ArcGIS Field Maps (Android): https://docs.emlid.com/reachrs4/integration/arcgis-field-maps-android/ | "firmware version 34.4 or later"; orthometric output 34.10 + Emlid Flow 15.16 |
| S6 | Mock location: https://docs.emlid.com/reachrs4/integration/mock-location/ | 34.10 / Flow 15.16 for orthometric heights |
| S7 | TcpGPS: https://docs.emlid.com/reachrs4/integration/tcpgps/ | Emlid Flow 9.2+, TcpGPS 2.6+, Android 7.0+ |
| S8 | RS2/RS2+ NMEA format: https://docs.emlid.com/reachrs2/specifications/nmea-format/ | none |

### ETC layout (S1, S2)

`$GNETC,<UTC time-of-fix>,<State>,<Notification>,<Heading>,<Tilt Direction>,<Tilt Value>,<n-axis-acc>,<e-axis-acc>,<u-axis-acc>*<Checksum>`

| Field | Values |
|---|---|
| UTC time-of-fix | `hhmmss.ss` (empty when there is no GNSS solution) |
| State | `""` off, `0` fatal error, `10` setup, `20` alignment, `30` compensating |
| Notification | `""` tilt is off, `0` none, `10` fast motion, `20` bad gnss, `30` filter fault |
| Heading | degrees [0, 360]; S2: "Value only provided in compensating state" (S1 leaves that note off for heading) |
| Tilt direction | degrees [0, 360], compensating only |
| Tilt value | degrees [0, 180], compensating only |
| n/e/u accuracy coefficients | float, compensating only; S2: multiply by the antenna height to get the tilt accuracy per axis in mm |

Rates (S1): ETC defaults to 5 Hz and its rate cannot be changed ("change the frequency for all of them
except for the ETC message"). S2: "ETC frequency is always the same as the GGA frequency. You can rely on
the timestamps to determine which message corresponds to which."

The six S1 examples carry correct checksums (`*77 *58 *59 *5A *5E *5F`). S2 prints the same examples with
different checksums (`*7E *6D *3B *5A *4C *2H`); all but `*5A` are wrong (`*2H` is not even hex), so the
S2 examples fail checksum verification. The tests pin this.

State notes from S2: a fatal error cannot be recovered without a reboot; setup runs once after every boot;
alignment happens at every boot, and "Sometimes during normal operation, the receiver will return to the
alignment state". Notifications explain why, e.g. "having a bad GNSS solution for a while can revert the
tilt engine back into alignment".

### The ten questions

C = confirmed by the cited source. A = an assumption or something undocumented, which needs a receiver test.

1. **Are GGA/RMC compensated to the pole tip when IMU tilt is on?** C, with a condition. S4, firmware 34.4:
   "If the output format is set to NMEA with compensation enabled, the transmitted position will also be
   tilt-compensated." … "In all other cases, the output will be non-compensated." S5/S6: "To send
   compensated NMEA messages, you need to enable it in Emlid Flow." So turning tilt on is **not enough**.
   An Emlid Flow output setting decides, and that setting does not show in the NMEA stream. S2 describes
   the other mode: GGA is the "RTK rover's non-compensated coordinates" and the integrator combines GGA and
   ETC. TcpGPS (S7) works this way, with the antenna height entered in TcpGPS.
2. **Does it depend on firmware, streaming configuration or mode?** C: compensated NMEA needs firmware
   34.4 or later (S4, S5). Before 34.7, GGA and RMC "reported different positions when tilt compensation
   was enabled" (fixed in S4 34.7), so compensated output from older firmware cannot be trusted. It is set
   per output (S4, S5, S6). A: whether the **TCP** position output, which this app reads, offers the
   compensation toggle. Every guide sets it up over Bluetooth (S5, S6, S7).
3. **Horizontal, vertical or both?** C (in substance): S3 says the software determines "the position of
   the pole tip on the ground". S5/S6 say to set the antenna height only in Emlid Flow, and "Setting it in
   third-party apps as well may cause discrepancies", which means the compensated height is already at the
   ground. S2's formulas shift e, n and u. A: the vertical behaviour of the streamed GGA should still be
   checked on a mark.
4. **Where is the pole height configured?** C: for compensated output, only in Emlid Flow ("You only need
   to enter the pole height. Emlid Flow automatically applies the antenna phase center offset", S5/S6, with
   a toggle for a quick release). For non-compensated output, the third-party app applies it (S5: Field
   Maps needs pole + 95 mm; S7: TcpGPS).
5. **APC or ARP offsets internally?** Partly C: Emlid Flow adds the phase-centre offset itself (S5/S6), and
   S5 gives 95 mm for the RS4 / RS4 Pro in Field Maps. S2 has overlapping text layers at this point: the raw
   text reads "The bottom to ARP height for Reach RS3 is 0.134 meters", and the rendered layer seems to add
   an RS4/RS4 Pro value of 0.095 m. A: no source says whether non-compensated GGA is the APC (the app's
   assumption) or the ARP. The app's offsets (`EmlidReachModel`) are unchanged.
6. **Compensation while FIX is lost?** C: "Tilt compensation can only be applied if you have the FIX
   solution". It "may be temporarily lost, for example, after a brief loss of RTK FIX", and also when the
   receiver stays still for a while. Collection and stakeout are blocked until it is restored (S3). A:
   undocumented what the compensated GGA holds during the loss (the raw antenna, the last pole tip, or
   nothing).
7. **Invalidation and recovery timing?** C, but only in general terms: setup once per boot; alignment
   needs a figure-eight motion until a sound plays (S3); 34.5 "Delivered a faster tilt alignment process"
   (S4); a loss returns the engine to alignment (S2). A: no durations are documented.
8. **Can ETC identify the state for a specific position?** C: every ETC carries the UTC time-of-fix, and
   S2 says the timestamps pair ETC with GGA. A: an ETC without a time (`$GNETC,,,,,,,,,`, no GNSS solution)
   cannot be paired.
9. **Timestamps and rates?** C: ETC runs at the GGA frequency, 5 Hz by default, and its rate cannot be
   changed by the user (S1, S2). In the fixtures, every GGA has exactly one ETC with the same time, sent
   right after it (885/885 and 898/898).
10. **RS4 vs RS4 Pro vs RS2+?** C: S3, S5 and S6 cover "Reach RS4 / RS4 Pro" together for IMU tilt. S8
    (RS2/RS2+) lists no ETC and no IMU; it mentions "ETC" once, apparently copied from the RS4 page. A:
    reseller pages agree that the RS4 and RS4 Pro share the IMU and the Pro adds cameras. Emlid's own
    specifications page refused automated fetches.

### What geokit-receiver-emlid implements

- `nmea/EtcFields` and `ETC.decoded` follow the layout above and never throw. A malformed sentence has
  `malformedReason` set and no values. Malformed means: a field count other than 8, an undocumented code,
  an out-of-range or non-numeric value, compensating without all six values, values outside compensating,
  state and notification disagreeing about "off", or a bad time. The registry still checks checksums.
  `ETC`'s raw constructor and `toString` are unchanged, so the goldens are too.
- `tilt/EmlidTiltState`: OFF, INITIALIZING (10), ALIGNING (20), ACTIVE (30), UNAVAILABLE (0), UNKNOWN.
  `EmlidTiltReading` adds the notification (`hasWarning`), time tag, heading, tilt direction and value,
  and the coefficients.
- `tilt/EmlidTiltTracker`: an ETC belongs to a fix only if |ETC time − GGA time| ≤ 0.05 s (safe across
  midnight) **and** the two receive times are no more than 1 s apart. With no ETC for 2 s the stream
  counts as stale. Anything else is UNKNOWN with a `TiltMatch` reason (NO_ETC_SEEN, ETC_STREAM_STALE,
  NO_FIX_TIME, NOT_MATCHED, AMBIGUOUS, MALFORMED). The latest ETC is never carried over to a newer fix.
  Associate a fix only after its epoch's ETC has arrived, since the ETC follows the GGA.
- `tilt/EmlidPositionReference.classify(model, compensatedOutput, firmware, tilt)`:
  - RS2/RS2+ without ETC → ANTENNA (an ETC on such a profile → UNKNOWN).
  - Matched ETC says OFF → ANTENNA.
  - Output declared DISABLED → ANTENNA with `antennaMayBeTilted`.
  - Output ENABLED + firmware ≥ 34.7 + matched ACTIVE → RECEIVER_COMPENSATED_POLE_TIP.
  - Everything else → UNKNOWN. An ETC on its own never yields a pole tip.

### What the app must do (later package)

1. Feed the ETCs to the tracker (the fuser's extra parsers already parse them; `onSentence` also works
   from the line hook). Associate each captured fix through its GGA time, and reset the tracker when the
   source changes.
2. Add a receiver-profile setting "Compensated NMEA output" (ENABLED / DISABLED / UNKNOWN, default
   UNKNOWN), and pass the firmware from `ReceiverDeviceInfo.firmware`.
3. RECEIVER_COMPENSATED_POLE_TIP → **never** apply the app's pole-height or phase-centre reduction. Record
   the reference with the point.
4. ANTENNA → today's maths. When `antennaMayBeTilted` is set, warn that the pole must be plumb, because the
   streamed position is the antenna of a pole that may be tilted.
5. UNKNOWN → keep today's maths and show "position interpretation uncertain" with the reason.
6. Never apply ETC tilt values to positions in the app. S2's GGA+ETC method would correct an
   already-compensated stream a second time.

### Phase 4 WP C: what the app does now (2026-11-25)

Items 1–6 above are implemented (app `receiver/tilt/`, `CaptureViewModel`), with one stricter rule than item 5:

1. `ReceiverTiltMonitor` reads ETC lines from the TCP stream's line hook (own checksum-verified ETC parser; geokit-gnss
   parsing and fusion untouched) into one `EmlidTiltTracker`, reset on source / profile / connection change. Each fix
   is paired with its own epoch's ETC (waiting up to 1 s for it; never an older ETC).
2. Settings → Receiver → "Compensated NMEA output (Emlid Flow)": Unknown (default) / Enabled / Disabled, DataStore
   key `external_compensated_nmea_output`; firmware from the device manager's device info. Shown for RS4 / RS4 Pro only,
   with the live tilt state (off / initializing / aligning / active / temporarily unavailable), tilt angle + heading,
   warnings and the position reference with a one-line explanation; same on the Developer page.
3. Pole-tip occupations are stored with no pole-height / PCO reduction (`captureMethod` `EXTERNAL_GNSS_POLE_TIP`,
   `antennaHeightM` null).
4. Antenna epochs: today's maths; `antennaMayBeTilted` explains "keep the pole plumb".
5. **Stricter than WP T:** an UNKNOWN epoch that *may* be compensated (geokit `mayBeCompensated`, or — app rule — the
   setting is still Unknown while the ETC shows the tilt engine on) is never reduced and never averaged; if only such
   epochs arrive the capture stops with "Receiver may be sending tilt-compensated positions; set 'Compensated NMEA
   output' in Settings → Receiver." UNKNOWN epochs with no sign of the tilt engine keep today's maths ("uncertain").
6. ETC tilt values are never applied to any position. Mixed references, tilt loss and interpretation-changing setting
   changes stop the occupation (table in [phase4-position-references.md](phase4-position-references.md)).

### Phase 4 WP C review follow-ups (2026-11-25)

- **ETC pairing uses the fix's arrival time.** The switchboard's fix transform stamps every published fix with its
  arrival time (`ReceiverTiltMonitor.recordArrival`, identity-keyed, last 128 fixes; the fix itself is unchanged).
  The capture and the status path pair a fix with its ETC by that time and wait for a missing ETC only until
  arrival + 1 s, so a fix processed late never waits. Before, pairing used the processing time: after two dropped
  ETCs every later fix looked later than its ETC, waited again, and the capture never recovered. Captures do not wait
  at all where the ETC cannot change the reference (RS2+ / generic, output Disabled).
- **Firmware is the last known value** per receiver host + profile (`FirmwareMemory`): a failed 30 s device-info poll
  (firmware null) no longer turns pole-tip epochs into "firmware unknown". A capture also uses the firmware read at
  its start. If a pole-tip occupation still loses the firmware it stops with "the receiver firmware could not be
  confirmed …" (`firmware_unknown`), not with a tilt-loss message.
- **Correction base id (all receivers, RS2+ included):** empty and all-zero ids ("0", "0000") are ignored; a different
  id must be carried by 3 consecutive epochs (or last 1 s of GNSS time) before it counts. Until then its epochs are not
  averaged; if the old id returns the change is logged as transient. A persistent change stops an occupation that has
  averaged epochs (before that it rebinds).
- **RS4 / RS4 Pro with "Compensated NMEA output" Unknown** keeps today's reduction for antenna epochs (unchanged by
  request), but the capture dialog shows, for the whole occupation and without a dismiss: "RS4: set 'Compensated
  NMEA output' in Settings → Receiver — if Emlid Flow streams compensated positions, heights would be reduced
  twice." Saving such a point writes a diagnostics line (event log + captures.log: "Capture saved with unconfirmed
  position reference id=…"). No schema change, so the point's Details screen does not show it.
- **Unreadable receiver profile** at capture start: treated as an IMU-capable receiver with the output Unknown
  (never a pole tip; excluded when the ETC shows the tilt engine on; otherwise today's maths), with a warning.
- Mock location: when the altitude is withheld (possible pole tip), the vertical accuracy is withheld too.

### Phase 4 stabilization WP A: unconfirmed reference workflow (2026-11-25)

Supersedes the "no schema change, so Details does not show it" note above. Exploratory captures are still allowed;
the maths is unchanged (antenna reduction once, never a second pole-height subtraction).

- **Acknowledgment on Save.** A completed RS4 / RS4 Pro capture with "Compensated NMEA output" Unknown (or an
  unreadable receiver profile) is not saved until the user answers "Position reference not confirmed": the dialog says
  the antenna reduction was applied and that, if Emlid Flow streamed compensated positions, the position is the pole tip
  and the height is reduced twice. Buttons: **Save as unconfirmed** / **Open receiver settings** (discards the result:
  it was classified under Unknown, so it must be captured again) / **Cancel** (keeps the result). Both save paths ask:
  the capture dialog shows the dialog, and `CaptureViewModel.save` refuses (`REFERENCE_ACK_REQUIRED_ERROR`, logged)
  without `acknowledgeUnconfirmedReference()`.
- **Remembered for the occupation only** (simplest safe choice): Restart / the next capture asks again. A verified
  setting (Enabled or Disabled) never asks; RS2+ / generic / internal captures never ask. Any change of profile, host,
  firmware or the setting needs a new occupation (running ones stop), so an acknowledgment never carries over to
  another configuration. A completed result keeps the settings it was captured with.
- **Persisted** as `captureMethod = EXTERNAL_GNSS_REFERENCE_UNCONFIRMED` (appended enum value, stored by name like
  `EXTERNAL_GNSS_POLE_TIP`; `antennaHeightM` = pole + PCO; no Room schema change). Point Details: Capture method
  "External GNSS (position reference unconfirmed; antenna reduction applied)" and a **Position reference** row first
  under Provenance (next to the coordinate epoch): "Antenna (pole height applied)" / "Receiver pole tip (compensated,
  no pole height applied) …" / "Unconfirmed — may be reduced twice …". CSV: a "Position reference" column is appended
  when the file has an external capture; JSON backup: export-only `positionReference` = `antenna` /
  `receiver_pole_tip` / `unconfirmed`. Older app versions read the new value as an unknown capture method (data-health
  warning) and keep it.

### Needs a receiver test (RS4 / RS4 Pro, firmware ≥ 34.7, on a known mark)

- Does the TCP position output offer "compensated NMEA"? Does ETC keep streaming when it is on?
- With it on and ACTIVE: GGA horizontal position and height against the mark, plumb and at about 15° tilt.
  Does RMC agree?
- With it on, during ALIGNING or after a FIX loss: what does GGA contain?
- With it off: is GGA the APC or the ARP (compare with pole + 95 mm)?
- How long do alignment and recovery take? Does "temporarily lost" show up as state 20 in ETC?

## Update 2026-11-25: acknowledgment scope and CSV option

- The save acknowledgment is required only for genuine uncertainty: RS4 / RS4 Pro with the output setting Unknown
  when an averaged epoch was "antenna (uncertain)" (ETC "tilt off" counts as antenna, no question), or an unreadable
  profile whose stream carried ETC. An unreadable profile with a conventional stream (no ETC, e.g. RS2+) is a normal
  capture again. HTTP receiver identification is never a condition.
- The CSV "Position reference" column is an explicit export option (default off); see
  [phase4-position-references.md](phase4-position-references.md).
- Model or firmware never implies that tilt compensation or orthometric streaming is enabled.
