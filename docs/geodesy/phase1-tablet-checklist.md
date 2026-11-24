# Phase 1 — manual tablet checklist

For the SM-X940 test tablet before merging `survey/phase1`. Automated checks are done (see the Phase 1
report); these need eyes on the device. Back up app data first (the debug build allows
`adb exec-out run-as app.surrealar tar cf - databases files shared_prefs > backup.tar`) and install in
place (`adb install -r`, never uninstall).

## 1. Settings → Coordinates layout and labels
- [ ] Height reference field: each option shows in full (two lines for the orthometric ones), nothing cut
      off, in light and dark theme; open dropdown lists all three options readably.
- [ ] Help text under the height field reads correctly (h, H, N, H = h − N; receiver geoid model unknown;
      live = antenna phase centre, saved points = mark).
- [ ] Current-position preview line shows the new short labels (h / H GEOID18 / H rcvr).

## 2. Long orthometric-height descriptions elsewhere
- [ ] Toolbar strip: height token with "h", "H GEOID18" and "H rcvr" fits and drops in the right order on
      a narrow width (portrait).
- [ ] Point details: "Height at mark", "H at mark (receiver geoid, model unknown)", "N (receiver geoid,
      model unknown)", "Ellipsoidal height h (WGS84)" wrap cleanly; no Radial/Vertical RMSE rows.
- [ ] Developer → GNSS: "Orthometric Height H …", "Horizontal Accuracy (68% radius)".

## 3. State Plane zone suggestion and manual selection
- [ ] In Hazleton with a saved Pennsylvania South zone, switching to State Plane proposes Pennsylvania
      North (3701) with the county-limits hint.
- [ ] With Pennsylvania North saved, switching keeps it (no hint or "near boundary" only if near a line).
- [ ] Manually choosing Pennsylvania South shows "Your position appears to be in Pennsylvania North
      (3701), not this zone."; choosing North clears it.
- [ ] Typing in the zone field still filters the 118 zones; the chosen zone persists after leaving
      Settings and in the converted preview.

## 4. Existing data preserved
- [ ] All 5 coordinates and both models still present after install; values unchanged (spot-check one
      point's lat/lon/h on the details screen against the pre-install backup).

## 5. Map, Details, AR and stakeout
- [ ] Map opens with markers; select a point: card shows "Azimuth: …° (N …° … E)".
- [ ] Stakeout card: "Azimuth" caption with degrees, ΔN/ΔE caption visible, "Move …" text unchanged.
- [ ] Details and AR round trips still work (RenderMap behaviour unchanged by Phase 1).

## 6. CSV export
- [ ] Export points CSV in State Plane ftUS + GEOID18 and in lat/lon + receiver height: header reads
      "Elevation (ftUS H GEOID18)" / "Elevation (m H rcvr)"; six columns; values match the details screen.
- [ ] If any external tool imports these CSVs, check it accepts the new header text.

## 7. Receiver settings
- [ ] Antenna section shows the "Level the pole…" note with the conditional RS4 Pro tilt warning, wrapping
      cleanly.
- [ ] (Optional, RS4 Pro owners) Field test from docs/geodesy/rs4-pro-tilt.md: GGA with tilt compensation
      on/off on a known mark.
