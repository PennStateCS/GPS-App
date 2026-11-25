# Phases 1, 2, 3 + 4 (WP U, WP X, WP C) — consolidated tablet acceptance checklist

Device: Samsung SM-X940 (debug build, `app.surrealar`). Build: `survey/phase2` debug APK for sections 1–7;
the Phase 3 build (`survey/phase3`, incl. WP W) for section 8 (sections 1–7 must still pass on it). Do the installation
exactly as in [install-and-rollback.md](install-and-rollback.md) (backup first, in-place install, never uninstall).
Each check lists the **expected result**; tick only what you saw on the device.

**Test data needed:** the existing 5 coordinates and 2 models (do not edit or delete them); one throwaway test
point captured during the test (delete only that one); a position in Hazleton, PA (for zone checks); the RS2+
receiver on TCP; optionally an old JSON backup made before the update (for import compatibility).

## 1. Data preservation and migration
| # | Check | Expected result |
|---|---|---|
| 1.1 | Open the app after the in-place install | Opens normally, no crash, map/lists load (Room v13 → v14 ran) |
| 1.2 | Coordinates list | Same 5 points, same names; Models list: same 2 models with thumbnails |
| 1.3 | Details of one existing point vs the pre-install backup | Lat/lon/h/H/N identical to the backup values |
| 1.4 | Details → Provenance of an existing point | Source frame, epoch, receiver frame, height source: "unknown" (nothing guessed) |
| 1.5 | Pull a post-install copy (see install doc §5) | `user_version` 14; the 5 rows' original columns unchanged; 5 new columns NULL |
| 1.6 | Settings → Coordinates / Map / Receiver | Same choices as before the update (nothing reset, no forced selection) |

## 2. Coordinate systems
| # | Check | Expected result |
|---|---|---|
| 2.1 | Zone field: type "Penn", then "3701", then "North" | List filters to matching SPCS83 zones; picking one shows its full label; reopening keeps it |
| 2.2 | In Hazleton, saved zone PA South, switch to State Plane | Proposes Pennsylvania North (3701) with the county-limits hint |
| 2.3 | Same with PA North saved | Kept; no mismatch hint (or "near boundary" only near a zone line) |
| 2.4 | Choose PA South manually in Hazleton | "Your position appears to be in Pennsylvania North (3701), not this zone."; hint mentions "SPCS2022: not yet available." |
| 2.5 | Datum WGS84 (ITRF2014) vs NAD83(2011), Geographic | NAD83 differs by ≈ 1.3 m horizontally / 1.2 m vertically around Hazleton (as before) |
| 2.6 | Datum ITRF2020, Geographic or UTM | Preview/Details show "ITRF2020 epoch 20xx.xxx"; values differ from WGS84 (ITRF2014) by only a few mm |
| 2.7 | State Plane with any datum | Datum field disabled; output on NAD83(2011) (as before) |
| 2.8 | Look for NATRF2022 / SPCS2022 anywhere | Not offered as options |

## 3. Geoid models and heights
| # | Check | Expected result |
|---|---|---|
| 3.1 | Height = Orthometric H (NAVD88, GEOID18), Hazleton | Same value as before the update for the same point (≈ h + 32.7 m there) |
| 3.2 | Height = H (EGM2008) | Value shown with label "H EGM2008"; differs from GEOID18 by a few dm (different datum — expected); never labelled NAVD88 |
| 3.3 | Height = H (EGM96, fallback) | Label "H EGM96 … fallback" in settings; values a few dm from EGM2008 |
| 3.4 | Airplane mode, choose H (EGM2008), open preview/list | Works offline; UI never freezes; at most a brief "EGM2008 data is loading" then values |
| 3.5 | Height = H (receiver geoid, model unknown) | Receiver's own value, unchanged by the model choice |
| 3.6 | Height field in light and dark theme | Every option fully readable (≤ 2 lines), nothing cut off |
| 3.7 | (Optional, replay data outside US regions) EGM2008 | "outside EGM2008 coverage bundled in this app (US regions)" — never an EGM96 value |

## 4. Capture, provenance and receiver frame
| # | Check | Expected result |
|---|---|---|
| 4.1 | Capture a throwaway point with the RS2+ (receiver datum ITRF2014) | Saved; Details → Provenance: source frame WGS84 (ITRF2014), a coordinate epoch, receiver frame ITRF2014, height source "External receiver (NMEA GGA)", model unknown |
| 4.2 | Start an External capture in Add Coordinate, then leave the dialog (e.g. to change Settings → Coordinates → External receiver datum) | The capture is cancelled with the dialog; nothing is saved. (The in-dialog "receiver datum changed" stop is covered by unit tests only: the dialog cannot stay open while Settings is changed.) |
| 4.3 | Open Add Coordinate in External mode while the RS2+ is still connecting | The capture keeps running when the receiver connects (no "source changed" message) and completes normally |
| 4.4 | During an External capture with samples accepted, disconnect the receiver / switch the source | Capture stops with "Capture stopped: the GNSS source changed during the occupation…"; nothing is saved |
| 4.5 | Delete the throwaway point | Only that point removed; the original 5 remain |

## 5. Map, Details, AR, stakeout
| # | Check | Expected result |
|---|---|---|
| 5.1 | Map | Markers where they were before; selected card "Azimuth: …° (N …° … E)" |
| 5.2 | Stakeout a point; open Details and come back; switch tabs and come back | Stakeout restored (card, marker, guidance); "Azimuth" caption; ΔN/ΔE caption |
| 5.3 | Details screen | Height rows (h, H at mark, N) and the Provenance block wrap cleanly |
| 5.4 | AR | Pins/models where they were before the update |

## 6. GNSS and exports
| # | Check | Expected result |
|---|---|---|
| 6.1 | External (RS2+ TCP) and Internal GNSS | Both work; switching works; toolbar height token label fits |
| 6.2 | CSV export: State Plane ftUS + GEOID18; lat/lon + EGM2008; ITRF2020 | 6 columns; header e.g. "Elevation (m H EGM2008)"; System column names frame/epoch; values match Details |
| 6.3 | JSON backup export, then import that backup | Imports; provenance of new points preserved |
| 6.4 | Import an OLD backup (made before the update) | Imports; provenance shows "unknown" |

## 7. Receiver settings
| # | Check | Expected result |
|---|---|---|
| 7.1 | Receiver → antenna section | "Level the pole…" and the conditional RS4 Pro tilt warning, wrapping cleanly |
| 7.2 | (RS4 Pro, optional) field test in [rs4-pro-tilt.md](rs4-pro-tilt.md) | Records whether GGA is antenna or pole tip with tilt on/off |

## 8. Phase 3 — Modernized NSRS 2022 (beta preview)

Beta = NGS pre-release data (GEOID2022 `beta_v0a`): never a default, always labelled, not for authoritative
results. **A** = also covered by automated tests (named), **D** = device only.

| # | Check | Expected result | A/D |
|---|---|---|---|
| 8.1 | After installing the Phase 3 build, open Settings → Coordinates | New row "Modernized NSRS 2022 (beta preview)" with the warning "Preliminary NGS beta products, not for authoritative survey results."; switch **off**; all other choices as before | A (`CoordinateSystemTokensTest`, `SettingsLocalDataSourceTest`) + D (look) |
| 8.2 | Preview off: open the Height list; check Details, preview line, toolbar, CSV of an existing point | No GEOID2022 option; every value and label identical to before the update | A (`Geoid2022HeightTest` preview OFF, `CoordinateConverterGoldenTest`, `PointsCsvExporterPhase2Test`) + D |
| 8.3 | Turn the preview on | The status list appears: "GEOID2022 heights (NAPGD2022): beta"; NATRF2022, PATRF2022, CATRF2022, MATRF2022 and "SPCS2022 (953 NGS beta zones)": "unavailable — awaiting NGS verification", followed by "Why: …" reasons | A (`Nsrs2022CapabilitiesTest`) + D (layout, light/dark) |
| 8.4 | Preview on: Datum list, State Plane zone search ("Penn", "420001", "PA_NE") | Datum list unchanged (no NATRF2022/2022 frames); zone search lists only the SPCS83 zones, as before | A (`CoordinateSystemTokensTest`, `SpcsZoneSearchTest`) + D |
| 8.5 | Preview on: Height list | Extra option "Orthometric height H (NAPGD2022, GEOID2022 beta)", fully readable (≤ 2 lines, light and dark theme) | A (`CoordinateHeightDropdownFitTest`) + D |
| 8.6 | Choose it (Hazleton or any lower-48 position) | Preview: "… m NAPGD2022 beta" and "… · NAPGD2022 beta · GEOID2022 (beta beta_v0a) epoch 20xx.xxx"; red note under Height "Beta: GEOID2022 (beta beta_v0a) … not for authoritative survey results."; value differs from the GEOID18 (NAVD88) height (a different vertical datum: NGS expects NAVD88 → NAPGD2022 changes of decimetres to about a metre in the lower 48), never identical to it | A (`Geoid2022HeightTest`: equals h(ITRF2020) − N(GEOID2022, epoch) from geokit) + D (plausibility) |
| 8.7 | Point Details with GEOID2022 | Coordinate system row ends with "GEOID2022 (beta beta_v0a) epoch …" (the point's coordinate epoch); Height row label "Height at mark · Orthometric height H (NAPGD2022, GEOID2022 beta)"; row "Height model (beta)" with NAPGD2022, ITRF2020 and the caution | A (`CoordinateDetailFormatterTest`) + D |
| 8.8 | Airplane mode, cold start with GEOID2022 chosen; open the list / preview | Works offline; UI never freezes; at most a brief "GEOID2022 data is loading", then values | A (`Geoid2022HeightTest` main-thread test) + D (timing) |
| 8.9 | (Replay or a position outside the bundled packs, e.g. Mexico City; optional) | Height "outside GEOID2022 (beta) data bundled in this app (…)" — never an EGM/GEOID18 value | A |
| 8.10 | Turn the preview **off** with GEOID2022 chosen | Height field shows "Orthometric height H (receiver geoid, model unknown)" with the red note "… is saved, but the NSRS 2022 beta preview is off: showing the default height …"; values are the default receiver heights; turning the preview on again restores GEOID2022 (the saved choice was not changed) | A (`CoordinateSystemTokensTest`, `Geoid2022HeightTest`, `SettingsLocalDataSourceTest`) + D |
| 8.11 | Force-stop and reopen the app (preview on, GEOID2022) | Preview switch and height choice persisted | A (`SettingsLocalDataSourceTest`) + D |
| 8.12 | CSV export with GEOID2022 | 6 columns, same order; header "Elevation (m H GEOID2022 beta)"; System column "… · NAPGD2022 beta · GEOID2022 (beta beta_v0a) epoch …"; values match Details | A (`PointsCsvExporterPhase2Test`) + D (SAF save) |
| 8.13 | JSON backup export + import with the preview on | Unchanged format (raw measurements only; GEOID2022 heights are computed for display, never stored); imports as before | D (no change in code; regression check) |
| 8.14 | Existing points, Map, AR pins/models, stakeout with the preview on and off | Unchanged (map/AR/stakeout use the stored WGS84 positions; GEOID2022 affects only displayed/exported heights) | D |
| 8.15 | Capture a throwaway point with the preview on; then change the External receiver datum during an External capture (as in 4.2–4.4) | Capture saves the receiver data exactly as in 4.1 (receiver H keeps "model unknown"; nothing GEOID2022 stored); a datum or source change during the occupation still stops the capture | A (capture tests, unchanged) + D |

## 9. Phase 4 WP U — NSRS 2022 app integration polish

Same rules as section 8 (beta opt-in, labelled, never default). Preview OFF: every existing value and label is
unchanged; the only additions are the Elevation rows on Details, a third preview line (h / N / H parts) and a
loading state where a height model is still loading.

| # | Check | Expected result | A/D |
|---|---|---|---|
| 9.1 | Preview on: Settings → Coordinates under the switch | Briefly "Loading NSRS 2022 product status…" (catalog read in the background, no freeze), then three groups: "Verified systems" (Systems / Datums / Heights), "Verified NGS beta" (GEOID2022 heights, β BETA badge), "Unsupported / not yet verified" (NATRF2022 … MATRF2022, SPCS2022) each with a one-line "Why: …" | A (`CoordinatesSettingsOffMainTest`, `CoordinatesSettingsControllerTest`, `CoordinateSystemGroupsTest`) + D (look, light/dark) |
| 9.2 | Tap an unsupported row (e.g. SPCS2022) | Dialog with the full registry reason; nothing is saved or selected | A (`CoordinatesSettingsControllerTest`) + D |
| 9.3 | State Plane zone search with the preview on | Still only the 118 SPCS83 zones (no SPCS2022 zone is operational in GeoKit); hint ends "SPCS2022: not yet available." | A (`ZoneSelectorOptionsTest`) + D |
| 9.4 | Point Details, each Height choice | New "Elevation" block: h (with its frame), H (with its reference), N (GEOID18 / EGM / GEOID2022), geoid model + version, vertical reference, epoch (GEOID2022 / ITRF2020), status Verified or Beta. GEOID2022 reads NAPGD2022 (beta), never NAVD88 | A (`ElevationDetailTest`) + D |
| 9.5 | Settings preview line with a live fix | Third line "h … (frame) · N … (model version) · H … reference"; H matches the first line | A (`ElevationDetailTest`) + D |
| 9.6 | Toolbar with GEOID2022 | Height label "NAPGD2022β" (all tokens still fit on the SM-X940 in both orientations); long-press help explains it | A (`ToolbarHeightLabelTest`, `ToolbarFitTest`) + D |
| 9.7 | Cold start (airplane mode) with GEOID18, EGM2008 or GEOID2022 chosen; open Details / preview at once | "… data is loading" (toolbar "loading"), never "outside coverage" or another model's value; it switches to the value by itself when the data has loaded (no need to leave the screen or wait for a new fix) | A (`ElevationDetailTest`, `CoordinatesSettingsControllerTest`, `NonBlockingGeoidModelTest`, `CoordinateSystemServiceGeoidLoadTest`) + D (timing) |
| 9.8 | Open Settings → Coordinates and immediately pick a Datum (slow device) with preview on and GEOID2022 saved | Only the datum changes; preview stays on and GEOID2022 stays chosen | A (`CoordinatesSettingsControllerTest` save-race tests) + D |

## 10. Phase 4 WP X — NATRF2022 / PATRF2022 / CATRF2022 / MATRF2022 and SPCS2022 output (beta)

Build: `survey/phase4-x`. Same rules as sections 8–9: beta opt-in, always labelled beta with the dataset version
("EPP2022 beta 2025-06-16"; SPCS2022 also "NGS beta zones 2026-05-14") and the caveat ("Verified against NGS beta
Geodetic Station Page coordinates; the NGS endpoints are undocumented; not authoritative"), never a default.
**Preview OFF: everything in sections 1–9 must be exactly as before** (no new option, no new note; the zone hint still
ends "SPCS2022: not yet available."). With the preview ON, 9.1 and 9.3 change as in 10.1 and 10.5.

| # | Check | Expected result | A/D |
|---|---|---|---|
| 10.1 | Preview on: Settings → Coordinates groups | "Verified NGS beta" lists GEOID2022 heights, NATRF2022, PATRF2022, CATRF2022, MATRF2022 and "SPCS2022 (953 NGS beta zones)", each with a β BETA badge; no "Unsupported" group; no "awaiting NGS verification" anywhere. Tap a frame row: dialog with dataset, area and caveat; nothing saved | A (`CoordinatesSettingsControllerTest`, `Nsrs2022CapabilitiesTest`) + D (look) |
| 10.2 | Preview on, System = Latitude / Longitude: Datum dropdown | Adds "NATRF2022 (beta)", "PATRF2022 (beta)", "CATRF2022 (beta)", "MATRF2022 (beta)" after ITRF2020 (labels fit on one line). Choose NATRF2022: note under Datum "Beta: NATRF2022 at each point's coordinate epoch (EPP2022 beta 2025-06-16). Area: North American plate … caveat" | A (`CoordinateSystemTokensTest`, `CoordinateHeightDropdownFitTest`) + D |
| 10.3 | Hazleton point (or any PA point), NATRF2022 chosen: Details and Settings preview | System "NATRF2022 (beta) epoch 2026.xxx · m · <height> · EPP2022 beta 2025-06-16"; the epoch is the point's coordinate epoch (capture time for older points); lat/lon differ from ITRF2020 at the same epoch by about 10–15 cm (plate motion since 2020.0) and from NAD83(2011) by about 1–1.5 m | A (`Nsrs2022OutputTest`) + D (plausibility) |
| 10.4 | Preview on, System = UTM with NATRF2022 saved | Datum dropdown offers no 2022 frame; field shows WGS84 (ITRF2014) with the note "… no UTM on a 2022 frame …"; UTM values = WGS84 UTM exactly | A (`Nsrs2022OutputTest`) + D |
| 10.5 | Preview on: System dropdown → "State Plane (SPCS2022, beta)" in Hazleton / State College | Zone row appears with all 953 SPCS2022 beta zones (search "Penn", "421001", "PA_NE"); hint names the zones possible here (statewide 420001 and the multizone zone(s)), "Choose the zone your project uses" — never picked for you. Each entry shows zone, frame (NATRF2022), epoch 2020.0, projection, unit m, β beta | A (`ZoneSelectorOptionsTest`) + D |
| 10.6 | Pick "Pennsylvania" (420001), then "Pennsylvania North East" (421001) / your project zone | Datum field (disabled) "NATRF2022 (zone frame, beta)"; note "Beta: SPCS2022 … on NATRF2022 … caveat"; Details / preview / CSV System "SPCS2022 420001 (beta) · NATRF2022 epoch 2026.xxx · m · … · EPP2022 beta 2025-06-16 · NGS beta zones 2026-05-14"; Northing/Easting change with the zone; heights unchanged by the zone (vertical choice separate) | A (`Nsrs2022OutputTest`) + D |
| 10.7 | Units with SPCS2022: m, then International feet, then US survey feet | m and ft (international) as chosen; US survey feet show as "ft" (international feet, note says NGS does not support the US survey foot) | A (`Nsrs2022OutputTest`) + D |
| 10.8 | Consistency at one point: Latitude / Longitude on NATRF2022 vs SPCS2022 PA zone vs UTM (WGS84) | SPCS2022 is the zone projection of the NATRF2022 lat/lon at the same epoch (independent check: NGS beta NCAT/SPCS page for the NATRF2022 lat/lon, ≤ 1 mm); UTM stays WGS84 (ITRF2014) and differs from NATRF2022 by the frame difference only | A (`Nsrs2022OutputTest` vs NGS example points) + D |
| 10.9 | Turn the preview off with SPCS2022 or a 2022 datum saved | System shows Latitude / Longitude (or Datum WGS84 (ITRF2014)) with a note "… is saved, but the NSRS 2022 beta preview is off …"; toolbar, Details, CSV show the legacy default; turning it on again restores the saved choice and zone | A (`CoordinatesSettingsControllerTest`, `Nsrs2022OutputTest`) + D |
| 10.10 | Map, AR, stakeout, capture with SPCS2022 / NATRF2022 chosen | Unchanged (they use the stored WGS84 positions; nothing 2022 is stored); a captured throwaway point stores exactly what 4.1 stores | A (unchanged tests) + D |
| 10.11 | Hawaii / Puerto Rico / Guam (only if a test point exists there, else skip) | PATRF2022 / CATRF2022 / MATRF2022 (beta) outputs and zones 150001 / 720001 / 660001 work with their own frame; off-plate use is allowed but the area note says where each frame is meant for | A (`Nsrs2022OutputTest`) |

## 11. Phase 4 WP C — RS4 / RS4 Pro tilt awareness, capture rules, survey context

Build: `survey/phase4-c`. Background: [rs4-pro-tilt.md](rs4-pro-tilt.md), [phase4-position-references.md](phase4-position-references.md).
Column **Needs**: **H** = real Reach RS4 Pro (firmware ≥ 34.7) on TCP, **K** = a known control mark (published or
project coordinates), **E** = expert (surveyor / geodesist) interpretation, **—** = tablet + RS2+ only. A = covered by
unit tests (named), D = device check.

**RS2+ capture regression** (must be exactly as in section 4)

| # | Check | Expected result | Needs | A/D |
|---|---|---|---|---|
| 11.1 | RS2+ profile: capture a throwaway point with the usual preset | Same rules, timing and Save as before; Details: captureMethod "External GNSS", antenna height = pole + 0.134 m, altitude reduced as before; no "Position:" line in the dialog | — | A (`CaptureTiltRulesJvmTest` rs2Plus…, `CaptureViewModelJvmTest`, `CaptureReceiverDatumJvmTest`) + D |
| 11.2 | Settings → Receiver with RS2+ (and Generic NMEA) | No "Compensated NMEA output" field and no tilt status; Developer page "Receiver Tilt": "not applicable" | — | D |
| 11.3 | RS2+ capture, then change the pole height during the occupation | Capture continues; saved point uses the pole height from the start | — | A (`antennaSettingChange_midCapture…`) + D |

**RS4 Pro NMEA / ETC stream**

| # | Check | Expected result | Needs | A/D |
|---|---|---|---|---|
| 11.4 | RS4 Pro profile, TCP connected, tilt off in Emlid Flow | Settings → Receiver: "Tilt: off", "Position: receiver antenna"; Developer: ETC rate ≈ GGA rate (5 Hz) | H | A (`ReceiverTiltMonitorTest`) + D |
| 11.5 | Firmware shown | Settings tilt status / Developer "Firmware" = the receiver's firmware (from device info); with output Enabled and firmware < 34.7 or unknown, a note says compensated output needs 34.7 | H | D |
| 11.6 | Diagnostic export with the RS4 Pro streaming | survey-context.txt: ETC count / rate, tilt state, freshness "live", position reference + reason | H | A (`SurveyContextCollectorTest`) + D |

**Tilt on / off transitions**

| # | Check | Expected result | Needs | A/D |
|---|---|---|---|---|
| 11.7 | Turn tilt on in Emlid Flow; watch Settings → Receiver | initializing → aligning (move the receiver) → active with tilt angle and heading; fast motion shows "Tilt warning: fast motion" | H | A (`TiltStatusText` via `ReceiverTiltMonitorTest`) + D |
| 11.8 | Output setting Unknown, tilt active, start a capture | No epochs accepted; after ≈ 10 s "Receiver may be sending tilt-compensated positions; set 'Compensated NMEA output' in Settings → Receiver."; Restart capture button shown; nothing saved | H | A (`unknownSetting_withTiltOn…`) + D |
| 11.9 | Output Enabled, tilt active: capture; then break RTK FIX (cover the antenna) during a second capture | First: completes, "Position: pole tip (receiver tilt-compensated)"; second: "Capture stopped: tilt compensation was lost …" | H | A (`tiltLoss_duringPoleTipOccupation_stops`) + D |
| 11.10 | Output Enabled, tilt active capture running; turn tilt off in Emlid Flow | "Capture stopped: the receiver's position reference changed …"; Restart capture starts a clean antenna occupation | H | A (`referenceChange…`, `restart_afterAnInterruption…`) + D |
| 11.11 | Mock location / AR with tilt active and output Enabled | AR still localises; log line "Mock location altitude withheld …"; AR-vs-GNSS panel shows no vertical value | H | A (`status_followsObservedFixes_andFlagsPoleTipHeightsForAr`) + D |

**Pole-height interpretation** (the open question of rs4-pro-tilt.md)

| # | Check | Expected result | Needs | A/D |
|---|---|---|---|---|
| 11.12 | On a known mark, output Enabled, tilt active, pole plumb then tilted ≈ 15°: capture both | Both saved points within a few cm of the mark horizontally **and** vertically; Details "External GNSS (receiver tilt-compensated pole tip; app pole height not applied)", no antenna height; values independent of the app's pole height setting | H, K, E | D |
| 11.13 | Same mark, output Disabled (non-compensated), pole plumb | Point within a few cm of the mark with the app's pole height + PCO; with the pole tilted the horizontal error ≈ pole × sin(tilt) | H, K, E | D |
| 11.14 | Compare 11.12 against 11.13 heights | Differ by < 3 cm (no double correction ≈ pole height); if not, record GGA + ETC (NMEA log) for the geodesist | H, K, E | D |
| 11.15 | Output Enabled during alignment / after a FIX loss: what does GGA hold? | The app refuses those epochs; record whether GGA was antenna or pole tip for the rs4-pro-tilt doc | H, K, E | D |

**Datum / profile changes during capture**

| # | Check | Expected result | Needs | A/D |
|---|---|---|---|---|
| 11.16 | During an external occupation change the receiver profile | "Capture stopped: the receiver profile changed …"; nothing saved; Restart capture works | — | A (`profileChange_stops…`) + D |
| 11.17 | During an RS4 occupation change "Compensated NMEA output" | "Capture stopped: 'Compensated NMEA output' changed …" | — (RS4 profile, any receiver) | A + D |
| 11.18 | During an occupation connect to another receiver (host) | "Capture stopped: the receiver connection changed …" | — | A (`receiverHostChange_stops`) + D |
| 11.19 | Correction base change during an occupation (switch NTRIP mount / base) | "Capture stopped: the correction base station changed …" (only when the GGA carries station ids) | — (RTK base/NTRIP) | A (`correctionBaseChange…`) + D |
| 11.20 | Change Settings → External receiver datum right before / during an occupation | As 4.x: buffered old-datum fixes never averaged; mid-occupation change stops | — | A (`CaptureFeedDatumIntegrationTest`, `CaptureReceiverDatumJvmTest`) + D |
| 11.21 | Wi-Fi off / on during an RS2+ occupation (reconnect) | Occupation continues after the reconnect (status only) | — | D |

**Known-control observations**

| # | Check | Expected result | Needs | A/D |
|---|---|---|---|---|
| 11.22 | Capture on 2–3 known marks with RS2+ and (if available) RS4 Pro, output Disabled and Enabled | Residuals vs published values within the receiver's RTK spec + datum handling (record the datum, epoch and geoid used) | K, E (H for RS4) | D |

**Diagnostic export**

| # | Check | Expected result | Needs | A/D |
|---|---|---|---|---|
| 11.23 | Export a report after 11.8–11.10 | survey-context.txt lists receiver model / connection type, source and profile, receiver frame, compensated output, firmware, coordinate epoch, fix type, correction age, h/v accuracy, sats/DOP, sentence counts incl. ETC rate, tilt state + freshness, pole height / PCO, position reference, interruption counts + last reason, horizontal / vertical systems + beta flag, geoid model + version + load state, transformation paths, grid failures | — | A (`SurveyContextCollectorTest`, `ReportAssemblerTest`) + D |
| 11.24 | Search the exported ZIP for the receiver IP, NTRIP user/password, lat/lon of the captured points | None present in survey-context.txt (fix shown by quality only) | — | A (`SurveyContextCollectorTest` redaction) + D |

**Review follow-ups** (see rs4-pro-tilt.md "Phase 4 WP C review follow-ups")

| # | Check | Expected result | Needs | A/D |
|---|---|---|---|---|
| 11.25 | RS4 Pro, output Enabled, tilt active: run a 60 s capture on weak Wi-Fi (walk to the edge of range) | Occasional excluded epochs only; the capture keeps accepting epochs at the stream rate after the link recovers (no growing lag, no "tilt compensation was lost") | H | A (`droppedEtcs_doNotCascade…`, `alreadyLateFix_neverWaits`) + D |
| 11.26 | Same, while the receiver's web service misses a device-info poll (e.g. 30–60 s of weak Wi-Fi) | Firmware stays shown; the pole-tip capture is not stopped; if it ever is, the message is "the receiver firmware could not be confirmed …", never a tilt-loss message | H | A (`FirmwareMemoryTest`, `firmwareUnknownMidOccupation…`) + D |
| 11.27 | RS2+ capture with NTRIP where the GGA station id briefly reads empty / 0000 or flickers to another id for 1–2 epochs | Capture continues (flicker epochs not averaged, logged as transient); switching the mount to another base still stops it after ≈ 3 epochs / 1 s | — (RTK base/NTRIP) | A (`rs2Plus_…StationId…`, `rs2Plus_realBaseChange_stillStops`) + D |
| 11.28 | RS4 / RS4 Pro profile, "Compensated NMEA output" Unknown: start a capture | Dialog shows "⚠ RS4: set 'Compensated NMEA output' in Settings → Receiver — if Emlid Flow streams compensated positions, heights would be reduced twice." for the whole occupation (no dismiss); saving still allowed (antenna reduction); captures.log has "Capture saved with unconfirmed position reference id=…". Not shown for RS2+ / Generic or with Enabled / Disabled | — (RS4 profile, any receiver) | A (`outputUnknownWarning_onlyForRs4AndRs4Pro…`) + D |
| 11.29 | Mock location / AR with tilt active and output Enabled | Injected location has neither altitude nor vertical accuracy | H | A (`omitted altitude also omits the vertical accuracy`) + D |

**Unconfirmed position reference: acknowledgment on Save** (Phase 4 stabilization WP A, build `survey/phase4-sa`)

| # | Check | Expected result | Needs | A/D |
|---|---|---|---|---|
| 11.30 | RS4 / RS4 Pro profile, output Unknown, tilt off: complete a capture, tap Save | Dialog "Position reference not confirmed" explains the antenna reduction and the possible double reduction; buttons Save as unconfirmed / Open receiver settings / Cancel; nothing saved until a choice | — (RS4 profile, any receiver) | A (`unknownOutput_saveIsRefusedUntilAcknowledged…`) + D |
| 11.31 | In 11.30 tap Cancel, then Save again; then Save as unconfirmed | Cancel keeps the dialog and the completed result; Save asks again; Save as unconfirmed saves once and closes; captures.log "Capture saved with unconfirmed position reference … acknowledged=true" | — | A + D |
| 11.32 | Open the point from 11.31 | Capture method "External GNSS (position reference unconfirmed; antenna reduction applied)"; Provenance starts with "Position reference: Unconfirmed — may be reduced twice …"; antenna height = pole + PCO; coordinates / height display otherwise unchanged | — | A (`positionReference_distinguishes…`) + D |
| 11.33 | 11.30 again, tap Open receiver settings | Capture dialog closes without saving; Settings → Receiver opens; set Disabled (or Enabled), capture again: Save does not ask | — | A (`verifiedSettings_andOtherReceivers_neverAsk`) + D |
| 11.34 | Save as unconfirmed, then capture the next point (or Restart) with the setting still Unknown | Asked again (acknowledgment is per occupation) | — | A (`acknowledgment_isRememberedForTheOccupationOnly`) + D |
| 11.35 | RS2+ / Generic, and RS4 with Enabled (pole tip) / Disabled | Save never asks; Enabled pole tip: Position reference "Receiver pole tip (compensated, no pole height applied) …", no antenna height; RS2+: "Antenna (pole height applied)" | — (H for the pole tip) | A + D |
| 11.36 | Export CSV with an unconfirmed, a pole-tip and an antenna point (plus an internal one) | Last column "Position reference": "UNCONFIRMED: antenna reduction applied; …", "receiver pole tip …", "antenna …", blank; all other columns as before; a CSV of internal / manual points only has no extra column | — | A (`external captures append a Position reference column…`) + D |
| 11.37 | Backup (JSON) export → restore on a clean install | Unconfirmed point restored with the same capture method and Details row; backup JSON carries `"positionReference": "unconfirmed"` | — | A (`positionReference_captureMethodsRoundTrip…`) + D |
| 11.38 | During an RS4 occupation change in turn: receiver datum, profile, host, "Compensated NMEA output"; and switch the NTRIP base | Each stops with its own "Capture stopped: …" message and Restart capture; Restart completes and saves; a change after the capture completed never discards the result | — (RTK base for the base change) | A (`eachSetupChange_midOccupation…`, `completedResult_isNeverDiscarded…`, `correctionBaseChange…`, `firmwareLost…`) + D |

## Verified only on the device (not by unit tests)
Real screen layout/themes; the real migration of this device's database; RS2+ TCP behaviour; AR placement;
offline first-load timing of the geoid packs; Android save/open (SAF) dialogs; capture interruption UX;
Phase 3: the preview row and status list layout, GEOID2022 offline first-load time, plausibility of NAPGD2022
heights against local knowledge. Phase 4 WP X: the new notes' layout, the SPCS2022 catalog first-load time on the
tablet (toolbar briefly "SPCS2022 zone data is loading" in the system summary), plausibility of SPCS2022 values
against a project control point or NGS beta tools. Phase 4 WP C: the RS4 Pro stream and every tilt
transition (section 11, H), pole-tip interpretation on a known mark (11.12–11.15, H + K + E), the capture dialog's
Restart button and messages on the tablet.
