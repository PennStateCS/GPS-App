# Phase 3 plan: modernized NSRS (2022 frames, SPCS2022, GEOID2022)

Branch `survey/phase3` from main 56662f5 (Phases 1 + 2, incl. receiver-frame provenance).

Shared rule: every new capability carries `io.github.pennstatecs.geokit.status.ProductStatus`
(VERIFIED_LEGACY / VERIFIED_BETA / UNAVAILABLE) plus its NGS dataset version. Beta is opt-in, labelled,
never default, never authoritative; nothing unverified is operational; no fake or identity conversions.
Reference values come from NGS tools (beta NCAT / NGS services) or independent calculations — never from
the implementation under test.

| WP | Scope | Module |
|---|---|---|
| F | NATRF2022 / PATRF2022 / CATRF2022 / MATRF2022 ⇄ ITRF2020 via NGS Euler-pole parameters (+ IFDM if required by NGS for the frame definition), status + versions, NGS reference vectors | geokit-core datum/ |
| S | SPCS2022 catalog from official NGS machine-readable definitions (generator), projection methods required, areas of use/boundaries, NGS reference vectors | geokit-core crs/, projection/, tools |
| G | GEOID2022 (NAPGD2022) model: official grids → regional packs, NGS interpolation, coverage, NGS reference values | geokit-core geoid/, tools |
| W | App: beta capability model + opt-in preview setting, NATRF/SPCS2022/GEOID2022 options when verified, provenance in display/CSV, checklist | app |

Sources and dataset versions are recorded in docs/geodesy/nsrs2022-sources.md (each WP adds its section).

## WP W (app wiring) notes

- **Opt-in:** Settings → Coordinates → "Modernized NSRS 2022 (beta preview)", DataStore key
  `coord_nsrs2022_beta_preview` (boolean, absent = OFF; in the diagnostics allowlist). OFF = exactly the
  previous behaviour (golden/identity tests). Existing keys, tokens and defaults unchanged.
- **Capability model** (`coords/Nsrs2022Capabilities`): `ProductStatus` → UI state legacy / beta /
  unavailable ("unavailable — awaiting NGS verification"). States come from geokit: `Geoid2022Model.INFO.status`,
  `FrameTransformationRegistry.DEFAULT.find(WGS84 (ITRF2014) → natrf2022/patrf2022/catrf2022/matrf2022)` and the
  SPCS2022 catalog of `CrsRegistry.DEFAULT`. Unavailable products are listed with the registries' reasons and are
  never selectable (enforced in the model). The SPCS83 zone search is unchanged (SPCS2022 zones are not listed
  in it; the preview status list says why).
- **GEOID2022 height** (`VerticalReference.GEOID2022`, token `geoid2022_beta`, label
  "Orthometric height H (NAPGD2022, GEOID2022 beta)", short "H GEOID2022 beta"; preview ON only):
  1. source lat/lon/h (stored WGS84 (ITRF2014), NAD83(2011) or ITRF2020) → ITRF2020 at the coordinate epoch through
     the verified registry (no-op for ITRF2020 sources);
  2. N = NGS time-dependent GEOID2022, SGEOID2022 + DGEOID2022·(mjd − 58849)/365.25, at that ITRF2020 position on
     the UTC day of the same epoch (`Geoid2022Model.undulationAt`). Rationale: NGS defines GEOID2022 heights in
     ITRF2020 with this formula; using the coordinate epoch for both the position and N keeps H consistent with
     h at that epoch. Epoch: stored `coordinateEpoch` (valid 1980..2100), else the capture time; live fixes: fix time;
  3. H = h(ITRF2020) − N (one subtraction; the engine is asked only for an ellipsoidal placeholder).
  Missing data → explicit reason (`TILE_NOT_AVAILABLE` / `OUTSIDE_COVERAGE` / loading), never EGM or GEOID18.
  The main thread never loads the packs (`NonBlockingGeoidModel.guarded`). Labels: vertical label "NAPGD2022 beta",
  provenance note "GEOID2022 (beta beta_v0a) epoch 2026.886" in the system summary (Details, preview, CSV System
  column) and a "Height model (beta)" Details row. Heights are computed for display/export only: nothing is
  stored, Room schema and backups unchanged, receiver heights keep `orthometricModel` NULL.
- **Preview turned OFF while GEOID2022 is saved:** the stored value is kept (never rewritten); conversions use
  the existing default (receiver H) via `CoordinateSystemSettings.effectiveVertical`, and the Height field shows a
  note. Turning the preview back on restores GEOID2022. Chosen as the safest option: no silent settings change and
  no beta output while the user has opted out. An older app version reading `geoid2022_beta` falls back to the
  default as for any unknown token.
- **geokit-core:** `CrsRegistry` entries now report the weaker of their declared status and their frame path's
  status (`CrsRegistryStatusTest`); the shipped registry is unchanged (0 operational SPCS2022 zones).

## Known limitations after review (Phase 3)

- One coordinate epoch per conversion: with GEOID2022 (or ITRF2020 output) selected, a stored point's
  recorded `coordinateEpoch` is used for the whole conversion, including the horizontal NAD83/SPCS step;
  otherwise the capture time is used, as before. For every point the app captures the two are the same
  number; they can differ only for a backup-imported point whose epoch differs from its capture time.
- ~~The preview flag is written with every Coordinates save; a pick made before the screen's first bind
  could write the preview OFF.~~ Fixed in Phase 4 WP U: each save applies only the changed field to the latest
  stored settings (serialised), for the Coordinate System card and the display switches.
- ~~The SPCS2022 status line parses the 953-row catalog once on the main thread.~~ Fixed in Phase 4 WP U: parsed
  on Dispatchers.Default when the screen opens; "Loading NSRS 2022 product status…" until ready.
- ~~Screens that showed "GEOID2022 data is loading" refresh on the next update.~~ Fixed in Phase 4 WP U:
  `CoordinateSystemService.heightDataVersion` (GEOID18 grid + `AppGeoidModels.loadEvents`) refreshes the Settings
  preview and Details; the toolbar re-converts on its 1 s tick. A GEOID18 height before the grid loaded now reads
  "GEOID18 data is loading" instead of "outside GEOID18 coverage".
- 2022 frames and SPCS2022 stay unavailable until NGS publishes ITRF2020 ⇄ 2022-frame reference values
  (and confirms the Euler-pole sign: the beta page's literal equation would double plate motion; the code
  follows the blueprint and matches ITRF2020-PMM plate velocities).

## Phase 4 WP U (app integration polish)

- Settings → Coordinates (preview ON): products in three groups (`coords/CoordinateSystemGroups`): verified
  systems, verified NGS beta, unsupported / not yet verified with a concise reason
  (`Nsrs2022Capabilities.shortReason`, ≤ 60 characters) and the full registry text in an info dialog.
- SPCS2022 in the zone selector (`ui/settings/categories/ZoneSelectorOptions`): driven by `CrsRegistry` operational
  status, preview ON only, 2022 frames only; label "SPCS2022 <zone> (<code>) · <frame> epoch <ref> · <projection> ·
  <unit> · β beta". Shipped registry: 0 operational zones → nothing listed. Not selectable while
  `SPCS2022_OUTPUT_SUPPORTED = false` (needs a stored value form and a converter output path; no schema or key
  change was made).
- Elevation detail (`coords/ElevationDetail`, `CoordinateConverter.elevation`): h / H / N / model + version /
  vertical reference / epoch / beta for Details and the Settings preview; `convert()` output unchanged.
- Toolbar height label for GEOID2022: "NAPGD2022β" (≤ 10 characters like "EGM2008"); "loading" while a model loads.
