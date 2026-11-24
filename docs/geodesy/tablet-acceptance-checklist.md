# Phases 1 + 2 — consolidated tablet acceptance checklist

Device: Samsung SM-X940 (debug build, `app.surrealar`). Build: `survey/phase2` debug APK. Do the installation
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
| 4.2 | Start a capture, then change Settings → Receiver datum before it finishes | Capture stops with a clear message; nothing is saved |
| 4.3 | Start a capture, then switch receiver source | Capture stops (or behaves as documented); nothing mixed is saved |
| 4.4 | Delete the throwaway point | Only that point removed; the original 5 remain |

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

## Verified only on the device (not by unit tests)
Real screen layout/themes; the real migration of this device's database; RS2+ TCP behaviour; AR placement;
offline first-load timing of the geoid packs; Android save/open (SAF) dialogs; capture interruption UX.
