# Phases 1, 2, 3 + 4 (WP U) — consolidated tablet acceptance checklist

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

## Verified only on the device (not by unit tests)
Real screen layout/themes; the real migration of this device's database; RS2+ TCP behaviour; AR placement;
offline first-load timing of the geoid packs; Android save/open (SAF) dialogs; capture interruption UX;
Phase 3: the preview row and status list layout, GEOID2022 offline first-load time, plausibility of NAPGD2022
heights against local knowledge.
