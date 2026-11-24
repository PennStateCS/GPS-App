# Surveyor review: response and Phase 1 status

Response to the technical-method review of the SurReal AR survey calculations by Dr. Dimitrios Bolkas
(questions Q01–Q10 and sections 01–33). Documentation only. Frame/epoch work is planned in
[reference-frames-roadmap.md](reference-frames-roadmap.md).

**Status key**

| Status | Meaning |
|---|---|
| Correct | Reviewer approved or did not comment; no change planned |
| Phase 1 | Planned in Phase 1 (terminology, accuracy metric, SPCS suggestion, receiver guidance), being implemented in parallel |
| Deferred | Needs new frames/models (ITRF2020, NATRF2022, SPCS2022, GEOID2022, EGM2008); see the roadmap |
| Question | We need Dr. Bolkas's answer first (see the last section) |

## Review questions

| # | Topic | Reviewer's answer (short) | Status | Response |
|---|---|---|---|---|
| Q01 | Mixed geoid models for "MSL" | GEOID18 fine for US; move to GEOID2022; EGM2008 (EGM96 fallback) for UTM; say "H (model)", never MSL | Phase 1 + Deferred | Phase 1: drop "MSL" wording, label H with its model. Deferred: GEOID2022, EGM2008/EGM96 |
| Q02 | WGS84 = ITRF2014 | Use ITRF2020 | Deferred | ITRF2020 hub frame, roadmap Phase 2a |
| Q03 | Grid/combined scale factor | Not needed now (no distance work) | Correct | No change |
| Q04 | Accuracy definition | Radial and vertical error; RMSE fine | Phase 1 + Question | Accuracy metric planned in Phase 1; see question (a) |
| Q05 | Accuracy stored with a point | Use the spread, not s/√n | Correct | Spread is already the reported statistic |
| Q06 | Spread targets / occupation times | Suggests 1 / 5 / 30 / 180 s; user should control it | Question | Custom preset already sets time; see question (f) |
| Q07 | Antenna reduction | Equations correct; no tilt; tell RS4 Pro users to turn IMU tilt off | Phase 1 | Receiver guidance planned in Phase 1 |
| Q08 | Averaging frame | Keep averaging in WGS84, then convert | Correct | Unchanged |
| Q09 | Model georeferencing datum | Models likely in SPCS/NAD83; 2022 frames close to WGS84 | Deferred + Question | SPCS/NAD83 model input after frame metadata exists; see question (e) |
| Q10 | SPCS zone suggestion | Use zone limits; new zones coming | Phase 1 + Deferred | Phase 1: boundary-based suggestion for SPCS83. Deferred: SPCS2022 zones |

## Sections 01–33

| § | Calculation | Reviewer comment | Status | Response |
|---|---|---|---|---|
| 01 | Ellipsoid constants | — | Correct | |
| 02 | Geodetic → ECEF | — | Correct | |
| 03 | ECEF → geodetic | — | Correct | |
| 04 | Radii, m per degree | — | Correct | |
| 05 | Local ENU | Results are ΔE, ΔN, ΔU | Phase 1 | Terminology: label as ΔE/ΔN/ΔU |
| 06 | Distance, azimuth | α₁ is an azimuth, not a bearing (bearing = 0–90° + quadrant) | Phase 1 | Terminology: "azimuth" for 0–360° values |
| 07 | 14-parameter Helmert | Correct; NATRF2022 coming (EPSG 10966/10968), networks switch ~after May 2027 | Correct + Deferred | Keep; NATRF2022 in roadmap Phase 2b |
| 08 | Decimal year | — | Correct | |
| 09 | Receiver datum normalisation | Receiver outputs WGS84 directly except RTN corrections (AlphaRTK) in NAD83(2011) → NATRF2022 | Correct + Phase 1 + Deferred + Question | Matches current setting split. Phase 1 receiver guidance explains when to pick NAD83. Deferred: NATRF2022 option. See question (c) |
| 10 | Transverse Mercator | Asks for the point coordinates | Correct | Example point is P = 40.970000° N, 76.010000° W, h = 150.000 m (WGS84), UTM 18N result E 415 018.3244, N 4 535 917.9194 (PROJ EPSG:32618 agrees) |
| 11 | UTM | Correct | Correct | Heights for UTM: Deferred (EGM2008/EGM96) |
| 12 | LCC | Correct; SPCS2022 zones coming | Correct + Deferred | |
| 13 | Hotine Oblique Mercator | Is it implemented? | Correct | Yes, **forward only** (lat/lon → grid) for Alaska zone 1 (`HomA` in `libs/geokit-core/.../projection/SpcsZones.kt`), checked against PROJ vectors. No inverse (grid → lat/lon) yet |
| 14 | SPCS83 zone table / suggestion | Changes with SPCS2022 | Phase 1 + Deferred | See Q10 |
| 15 | Ellipsoidal height from GGA | Unit gives h directly; use H = h − N only for display, name the model; don't assume a model without metadata | Question | GGA carries H and N, not h; see question (b) |
| 16 | GEOID18 | Correct; use GEOID2022 (covers territories); EGM2008/EGM96 outside US | Correct + Deferred | |
| 17 | Display height chain | Correct; with 2022 frames apply NAPGD2022 to WGS84 directly | Correct + Deferred | |
| 18 | Antenna geometry | Why cap ARP→APC at 0.5 m? | Correct | The cap is an input typo guard on the editable phase-centre offset in Receiver settings (`AntennaGeometry.MAX_PHASE_CENTER_OFFSET_M`, `ui/settings/AntennaHeightInput.kt`): entries outside 0–0.5 m are rejected and the previous value kept. Built-in Reach offsets are 0.095–0.134 m (`EmlidReachModel`). Willing to remove or raise it if preferred |
| 19 | Mark height at save | — | Correct | See question (d) for live display |
| 20 | Camera height for AR | — | Correct | |
| 21 | NMEA lat/lon | — | Correct | |
| 22 | 68 % horizontal radius | Report radial error √(σlat² + σlon²) as 1σ; no ellipse | Phase 1 + Question | Accuracy metric planned in Phase 1; see question (a) |
| 23 | DOP × UERE fallback | Questions the 1.515/√2 factor | Phase 1 + Question | Follows the decision on question (a) |
| 24 | Running mean / variance | — | Correct | |
| 25 | Occupation averaging | Fine | Correct | |
| 26 | Acceptance rules / presets | Offer 1-second shots | Question | See question (f) |
| 27 | Stakeout | — | Correct | Azimuth wording per §06 |
| 28 | Magnetic → true heading | — | Correct | |
| 29 | Compass point | — | Correct | |
| 30 | AR camera vs GNSS check | How is ARCore's camera position estimated? | Question | See question (g) |
| 31 | Expected AR placement error | — | Correct | |
| 32 | Georeferenced 3D model | Margin comment: "I don't expect Metashape to export the height in the Y component" (see also Q09) | Question | See question (e) |
| 33 | Model frame conventions | — | Correct | |

## Questions for Dr. Bolkas

**(a) Horizontal accuracy metric.** Today the single horizontal figure is a 68 % radius,
1.515 · √((σlat² + σlon²)/2), chosen to match Android `Location.accuracy` (also passed to ARCore and
mock-location apps). That equals ≈ 1.07 × the radial RMSE √(σlat² + σlon²). Should the user-facing value
be the radial RMSE (1σ) you describe, with the 68 % radius kept only internally for Android/ARCore —
or should we show both?

**(b) Ellipsoidal height from GGA.** NMEA 0183 GGA carries orthometric height ("altitude above mean
sea level", field 9) and geoid separation (field 11), not ellipsoidal height. On a TCP/NMEA link the
app therefore obtains h = H + N using the receiver's own N, then applies its chosen geoid
(H = h − N_model) for display. Is this acceptable, given your section 15 comment? (The Reach
may also stream solutions with ellipsoidal height in a non-NMEA format such as LLH; we could use
that if preferred, subject to confirming it on the TCP link.)

**(c) NAD83 RTK network streams.** For NAD83(2011) network output we transform h to ITRF, keep the
receiver's H unchanged and recompute N = h_itrf − H so h = H + N still holds. Acceptable, or should H
be recomputed (e.g. h_itrf − N from our own geoid model)?

**(d) Antenna vs mark heights.** Live toolbar heights are antenna phase-centre (APC) heights; saved
points are reduced to the ground mark (pole + phase-centre offset). Which should the live display
show — the mark height (assuming the pole is on the mark), the APC height, or both?

**(e) Model export convention.** The georeferenced-model import assumes a Metashape-style Y-up glTF with
X = longitude (°), Y = ellipsoidal height (m), Z = −latitude (°), WGS84 (section 32). Your margin comment on
section 32 doubts that Metashape puts height in Y. Given that, and your Q09
answer that models will likely be in SPCS/NAD83: what export should we expect — projected
easting/northing in a stated SPCS zone, which height (ellipsoidal or H with which geoid), and which
file format/axis convention?

**(f) 1-second preset.** You suggest 1 / 5 / 30 / 180 s. The Custom preset already lets the user set
minimum/maximum time and fix count (so 1 s or 3 s is possible). Do you want a dedicated "1 s quick
shot" preset as well, and with what minimum fix type and spread rule?

**(g) ARCore camera position (section 30).** ARCore's camera pose comes from ARCore Geospatial, which
combines Google's Visual Positioning System (camera imagery vs Street View) with the tablet's own GNSS
and IMU — not the RTK receiver. Its horizontal accuracy is typically decimetres to metres, so the
section 30 check measures how far ARCore is from the RTK position. Should we show this caveat to users
on the AR screen (e.g. "AR position from ARCore/VPS; survey position from RTK receiver")?
