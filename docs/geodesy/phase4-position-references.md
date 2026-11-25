# Phase 4 WP C — position references: camera, antenna, receiver pole tip, survey mark

Status: review of the app as of `survey/phase4-c` (2026-11-25). Describes which physical point each position in
the app refers to, how capture, AR, map, distances, stakeout and exports use it, and what changed in WP C.
No GNSS, datum, antenna or AR positioning maths was changed; WP C only decides *which interpretation* an epoch
has and refuses or stops where the interpretation is uncertain.

## The four points

```
            ┌───────────┐  APC  antenna phase centre (what GGA reports when NOT tilt-compensated)
            │  receiver │  ↑ phase-centre offset (PCO: RS2+ 134 mm, RS4 95 mm, RS4 Pro: user-entered)
            └─────┬─────┘  ARP  bottom of the receiver (what users tape to)
   tablet ▣───────┤        ↕ cameraBelowReceiverM (ARP → camera lens, signed)
   camera         │        ↕ poleHeightM (mark → ARP)
                  │
   ───────────────●─────── survey mark / pole tip (what a saved point should be)
```

| Symbol | Meaning | Where it comes from |
|---|---|---|
| APC | Antenna phase centre | Non-compensated GGA (app assumption, unchanged since Phase 1) |
| ARP | Receiver bottom | APC − PCO (`AntennaSetup`, `AntennaGeometry`) |
| Camera | Tablet camera lens | ARP − cameraBelowReceiverM; APC − `antennaAboveCameraM` |
| Mark | Pole tip on the survey mark | APC − (poleHeight + PCO) = `captureOffsetM`; or the receiver's own compensated GGA |

The receiver-compensated pole tip (RS4 / RS4 Pro, IMU tilt + "compensated NMEA output" in Emlid Flow) is the mark,
computed by the receiver with **Emlid Flow's** pole height, not the app's. See [rs4-pro-tilt.md](rs4-pro-tilt.md).

## Per subsystem

| Subsystem | Input position | Physical point | WP C change |
|---|---|---|---|
| **Capture, external, antenna epochs** (RS2+, RS4 tilt off / output disabled) | Averaged WGS84 fixes | APC → stored altitude = h − (pole + PCO) = mark; `antennaHeightM` = pole + PCO | none (unchanged maths) |
| **Capture, external, receiver pole-tip epochs** (RS4 / RS4 Pro, output ENABLED, firmware ≥ 34.7, ETC compensating) | Averaged WGS84 fixes | Mark (receiver-compensated) → stored as streamed; `antennaHeightM` null; `captureMethod` = `EXTERNAL_GNSS_POLE_TIP` | **new**: the app never subtracts pole height / PCO from these |
| **Capture, possibly compensated epochs** | — | Unknown (APC or pole tip) | **new**: never averaged; the occupation stops if nothing else arrives (10 s) |
| **Capture, unconfirmed reference** (RS4 / RS4 Pro, output Unknown, or profile unreadable; no ETC showing tilt on) | Averaged WGS84 fixes | Treated as APC → same reduction as antenna epochs; `antennaHeightM` = pole + PCO; `captureMethod` = `EXTERNAL_GNSS_REFERENCE_UNCONFIRMED` | **Stabilization WP A**: saved only after "Save as unconfirmed" (per occupation); Details / CSV / JSON say "Unconfirmed — may be reduced twice" |
| **Capture, internal** | Device GNSS | Tablet antenna → stored h − internalAntennaHeightM | none |
| **Live toolbar / Details preview** | Live fix | APC (or pole tip with compensated output) — never reduced | none (documented) |
| **AR mock location** (`AndroidMockLocationPublisher`) | Live external fix | Altitude = h_APC − antennaAboveCamera = camera; lat/lon = antenna | **new**: altitude withheld while the receiver's positions are (or may be) the pole tip (lat/lon still published) |
| **AR vs GNSS check** (`ArVsGnss`) | ARCore camera pose vs fix lowered to the camera | Camera | **new**: no vertical comparison for pole-tip fixes |
| **ARCore anchors / model placement** | Stored coordinates (mark) as WGS84 lat/lon/ellipsoidal height, or terrain/rooftop anchors per the AR altitude mode | Mark (or terrain) | none |
| **Map markers** | Stored WGS84 lat/lon | Mark | none |
| **Live map position** | Live fix lat/lon | APC (or pole tip) | none |
| **Distances / bearings** | `Geodesic` on stored WGS84 lat/lon / live fix | Mark ↔ mark, or mark ↔ live antenna | none |
| **Stakeout** (`StakeoutController`, `StakeoutGuidance`) | Target: stored WGS84 lat/lon; live fix lat/lon; horizontal only | Mark vs live APC (plumb pole assumed) or live pole tip | none |
| **Exports** (CSV, JSON backup) | Stored values converted on export | Mark | `captureMethod` `EXTERNAL_GNSS_POLE_TIP` carried in the backup |

### Why the pole-tip representation uses `captureMethod` (no schema change)

A receiver-compensated capture is a different *acquisition method*, which is exactly what `captureMethod` records
("how the position was acquired"). The alternatives were worse: `antennaHeightM = 0.0` is already written as null
(meaning "no offset"), so 0 cannot be told apart from "none"; notes are user text; `sourceDevice` is the profile label
that other code matches; the v14 provenance columns describe frames, not the physical point. So:

- `captureMethod = "EXTERNAL_GNSS_POLE_TIP"` (`CaptureMethod.EXTERNAL_GNSS_POLE_TIP`, stored by enum name, round-trips
  through the DB mapper and the JSON backup, recognised by the data-health check);
- `antennaHeightM = null` (the app applied no offset);
- `provider` stays `external-tcp`, so list badges, filters and exports treat it as any external capture.
Details shows "External GNSS (receiver tilt-compensated pole tip; app pole height not applied)". An older app
version reading such a backup sees an unknown capture method (data-health warning) and keeps the value.

The unconfirmed case (stabilization WP A) uses the same mechanism: `captureMethod = "EXTERNAL_GNSS_REFERENCE_UNCONFIRMED"`
(appended `CaptureMethod` value; antenna reduction applied, reference unconfirmed), `antennaHeightM` = pole + PCO,
provider `external-tcp`. `PositionReferenceKind.of(captureMethod, externalProvider)` derives the display / export
value (antenna / receiver pole tip / unconfirmed); readers that only check the provider (badges, list, filters,
stakeout, AR, map) are unaffected. Point Details shows a compact **Position reference** row first under Provenance
(with the coordinate epoch next to it and the beta height model above), the CSV appends a "Position reference" column
when the file contains an external capture (files without one are byte-identical to before), and the JSON backup
writes an export-only `positionReference` key (captureMethod stays the restored source).

## Capture rules (CaptureViewModel)

RS4 / RS4 Pro external occupations bind the position reference of their first admitted epoch: antenna group
(antenna or "uncertain antenna", both today's maths) or receiver pole tip. Implemented by
`receiver/tilt/CaptureReferenceGate` with epochs classified by `ReceiverTiltMonitor` + `PositionReferencePolicy`
(geokit `EmlidPositionReference.classify` + one conservative app rule: with the setting still Unknown, a tilt engine
that is on makes the epoch "may be compensated").

| Event during an occupation | Effect on interpretation | Action |
|---|---|---|
| Antenna ↔ pole tip (or antenna → may be compensated with a matched ETC) | Mixed references | **Stop** (`position_reference_changed`) |
| Tilt state lost in a pole-tip occupation (ETC no longer compensating, or ETC stream stale) | Pole tip no longer guaranteed | **Stop** (`tilt_state_lost`) |
| One ETC missing / late in a pole-tip occupation | Single epoch unknown | Exclude that epoch, continue |
| Pole-tip epoch with a receiver tilt warning | Degraded compensation | Exclude, continue |
| Only possibly-compensated epochs for 10 s | Cannot get valid epochs | **Stop** with "Receiver may be sending tilt-compensated positions; set 'Compensated NMEA output' in Settings → Receiver." |
| Receiver profile changed | PCO and tilt rules change; probably another receiver | **Stop** (all external captures) |
| Receiver host changed | Another receiver | **Stop** (all external captures) |
| "Compensated NMEA output" changed | Reference interpretation changes | **Stop** (RS4 / RS4 Pro) |
| Firmware no longer known ≥ 34.7 in a pole-tip occupation (rare: the last known firmware and the value at the start are kept) | Pole tip can't be confirmed | **Stop** (`firmware_unknown`, "the receiver firmware could not be confirmed …") |
| Correction base station id changed (both ids reported; empty / all-zero ids ignored) for ≥ 3 epochs or ≥ 1 s | RTK solution refers to another base | **Stop** (all external captures); shorter flickers: those epochs excluded, logged as transient |
| Antenna / pole-height / PCO setting changed | Epochs unchanged; reduction is bound to the start snapshot | Continue (logged) |
| Disconnect / reconnect of the same receiver | Status only; tilt tracker is reset, so epochs stay unknown until ETC returns (excluded / stale rule above) | Continue |
| External datum or GNSS source changed (Phase 3/4) | Frame / source mix | Stop (unchanged) |

A completed result is never invalidated by a later change. Every stop shows its message and a **Restart capture**
button; stops are counted by reason for the diagnostic report. RS2+ and generic NMEA captures are not classified
(no wait, no ETC logic) — only the profile / host / base-station stops apply to them, which need an actual change of
setup.

## Display systems never change the WGS84 positions

Settings → Coordinates (State Plane, UTM, NAD83(2011), ITRF2020, 2022 beta frames, height references) only changes
how positions are *expressed*. Stored rows keep WGS84 lat/lon/ellipsoidal height (`crsEpsg` 4979/4326); the GNSS feed
normalises NAD83(2011) receiver output to WGS84 before anyone sees it. AR anchors, mock location, map markers, live
tracking and stakeout read those WGS84 values directly. `Wgs84PositionPathsGuardTest` pins that these sources never
call the display conversion (the only use is the AR point sheet's text). Phase 4 WP X item 10.10 on the tablet
checklist covers the same on the device.

## Limits

- ARCore Geospatial is metre-level: Earth-anchor horizontal accuracy is typically 1–5 m without VPS, better with VPS;
  heading a few degrees. Centimetre-level RTK positions do not make AR placement centimetre-level.
- With a tilted pole and **non-compensated** output, the APC is horizontally `pole × sin(tilt)` away from the mark
  (≈ 17 cm at 2 m, 5°); captures assume a plumb pole (Settings → Receiver says so; `antennaMayBeTilted` warns).
- With **compensated** output the live lat/lon is the pole tip, not the antenna: AR mock location then places the
  device at the pole tip horizontally (≤ pole × sin(tilt) off, well inside ARCore's metre-level error) and gets no
  altitude. The app has no calibration from pole tip back to the camera and deliberately adds none.
- Stakeout with non-compensated output compares the mark with the APC: keep the pole plumb.
- What the RS4 Pro's TCP output really contains with compensation on (and during alignment) still needs a receiver
  test on a known mark — see the tablet checklist section 11.

## CSV "Position reference" column — export option (decided 2026-11-25)

Settings → Data → "Include a Position reference column" (key `export_csv_position_reference`, default OFF).
OFF: the original six-column CSV for every export (backward compatible). ON: the seventh column is written in every
export, whatever points the file holds, blank where a point has no receiver position reference. The column count
never depends on the points. The unconfirmed state is also shown in the point list ("… · reference unconfirmed").

## Acknowledgment only for genuine uncertainty (decided 2026-11-25)

Saving asks "Position reference not confirmed" only when an averaged epoch's reference was genuinely uncertain:
- RS4 / RS4 Pro with "Compensated NMEA output" Unknown and no ETC proof that the tilt engine was off
  (ETC "tilt off" → antenna, no question);
- a receiver profile that could not be read **and** an Emlid ETC sentence in the stream (an IMU receiver).
A conventional stream without ETC (RS2+, generic) is never asked merely because the profile or HTTP receiver
identification is unavailable: it keeps the usual capture behaviour (antenna reduction, usual capture method).
