# Phases 1 + 2 — consolidated tablet acceptance checklist

For the SM-X940 test tablet before merging `survey/phase2` (which contains Phase 1). Everything below needs
the physical device; automated results are in the Phase 1 and Phase 2 reports. Do not uninstall or clear data.

## 0. Before installing
- [ ] Back up app data: `adb exec-out run-as app.surrealar tar cf - databases files shared_prefs no_backup > backup.tar`
      and check it (5 coordinates, 2 models, schema 13).
- [ ] Signing/version match the installed build (shared debug keystore, same `app.surrealar`, versionCode ≥).
- [ ] Install in place: `adb install -r` (never uninstall).

## 1. Data preservation and the v13 → v14 database migration
- [ ] App opens normally after the update (migration ran; no crash, no "loading" stuck).
- [ ] All 5 coordinates and both models are present; spot-check one point's lat/lon/h on Details against the backup.
- [ ] Pull a fresh copy: DB `user_version` = 14, the 5 rows unchanged, the five new columns NULL for old rows.
- [ ] Old points' Details → Provenance block shows "unknown" (never a guessed frame/model).

## 2. Coordinate and height settings (Settings → Coordinates)
- [ ] Existing choices are kept after the update (no forced re-selection; same datum/height/zone as before).
- [ ] Height field lists: Ellipsoidal height (h); Orthometric H (NAVD88, GEOID18); H (receiver geoid, model
      unknown); H (EGM2008); H (EGM96, fallback) — each shown in full (≤ 2 lines), light and dark theme.
- [ ] Datum list includes ITRF2020; its description mentions the coordinate epoch; selecting it changes the preview
      to "ITRF2020 epoch …".
- [ ] Help text explains h, H, N, H = h − N, receiver model unknown, EGM2008 bundled for US regions, EGM96 coarse
      fallback, EGM heights are not NAVD88.

## 3. Geoid models and labels
- [ ] Hazleton: H (EGM2008) and H (EGM96) give plausible values (EGM2008 vs GEOID18 typically differ by a few dm
      — they are different datums); labels/preview/toolbar say "H EGM2008" / "H EGM96", never NAVD88.
- [ ] Offline (airplane mode): EGM2008 and EGM96 still work in the US (bundled); first selection does not freeze the UI.
- [ ] Outside the bundled EGM2008 regions (if you can simulate, e.g. replay data abroad): EGM2008 shows the
      "outside EGM2008 coverage bundled in this app" reason, never an EGM96 value.
- [ ] Receiver H option still shows the receiver value unchanged.

## 4. State Plane zone selection
- [ ] Zone field search: typing "Penn", "3701", "North" filters correctly; reopening shows the chosen zone's label.
- [ ] Hazleton with saved PA South → switching to State Plane proposes PA North (3701); with PA North saved it is kept.
- [ ] Choosing PA South shows "appears to be in Pennsylvania North (3701)"; hint ends with "SPCS2022: not yet available."

## 5. Reference-frame conversions
- [ ] Geographic / UTM in ITRF2020: values differ from WGS84 (ITRF2014) by only a few mm (expected at this epoch).
- [ ] State Plane remains NAD83(2011) regardless of datum choice (datum field disabled as before).
- [ ] NATRF2022 / SPCS2022 are not offered anywhere.

## 6. Capture and provenance (new points)
- [ ] With the RS2+ (receiver datum ITRF2014) capture a throwaway test point: Details → Provenance shows
      source frame WGS84 (ITRF2014), a coordinate epoch, receiver frame ITRF2014, orthometric source "External
      receiver (NMEA GGA)", model unknown. Delete the test point afterwards (only the test point).
- [ ] (If available) internal-GNSS capture: orthometric source "Device GNSS", receiver frame unknown.

## 7. Map, Details, AR and stakeout
- [ ] Map markers in the same places as before; selected-point card "Azimuth: …° (N …° … E)".
- [ ] Stakeout on a point: readout, "Azimuth" caption, ΔN/ΔE caption; Details/AR round trips; tab switch restores stakeout.
- [ ] AR places models/pins where they were before the update (WGS84 path unchanged).

## 8. GNSS receiver operation
- [ ] RS2+ connects over TCP as before; toolbar SRC/SOL/SATS/accuracy/height update; height token label fits.
- [ ] Switching Internal ⇄ External still works.

## 9. Exports and backup
- [ ] CSV export (State Plane ftUS + GEOID18; lat/lon + EGM2008; ITRF2020): six columns, header names H and the
      model (e.g. "Elevation (m H EGM2008)"), System column names frame/epoch; values match Details.
- [ ] JSON backup export → import into the app: provenance survives; importing an OLD backup (from before the
      update) works and leaves provenance unknown.

## 10. Receiver settings
- [ ] Antenna section shows "Level the pole…" and the conditional RS4 Pro tilt warning, wrapping cleanly.
- [ ] (RS4 Pro owners, optional) field test in docs/geodesy/rs4-pro-tilt.md.

## Needs the tablet (cannot be proven by unit tests)
Visual layout and text fit on the real screen; real migration of the device database; real receiver/TCP
behaviour; AR placement; offline first-load timing of the geoid packs; SAF export/import dialogs.
