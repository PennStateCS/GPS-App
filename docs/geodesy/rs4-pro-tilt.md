# Reach RS4 Pro tilt compensation and the app's antenna-height reduction

Status: investigation notes (Phase 1, 2026-11-24). Nothing here changes the app's maths.

## What the app reads from the receiver

| Interface | Code | Used for |
|---|---|---|
| NMEA 0183 over TCP (GGA, RMC, GSA, GSV, GST, ZDA, plus Emlid `EBP`/`ETC`) | `geokit-gnss` `link/TcpNmeaSource`, `bus/adapters/NmeaFuser`; Emlid parsers in `geokit-receiver-emlid` `nmea/EmlidNmea` | Every position, height, accuracy and time |
| Reach HTTP / socket.io services | `geokit-receiver-emlid` `EmlidReachDevice` | Battery, device info and correction status only — never positions |

The position the app captures is the GGA latitude/longitude and H + N (h = H + N, confirmed by the reviewer).
`$..ETC` (Emlid Tilt Compensation) sentences are only **detected and counted** for diagnostics
(`EmlidNmeaDiagnostics`, shown by `NmeaStreamDiagnosticsFormatter` with the note that ETC is not mapped to
heading/tilt). No tilt value is used anywhere.

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

**Not established:** whether the RS4 Pro's TCP NMEA output (the interface this app uses) carries the
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
