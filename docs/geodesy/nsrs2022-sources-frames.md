# NSRS 2022 plate-fixed frames: sources and status (WP F)

Checked 2026-11-24/25 (UTC). Covers ITRF2020 ⇄ NATRF2022 / PATRF2022 / CATRF2022 / MATRF2022.

**Result: all four frames stay `ProductStatus.UNAVAILABLE`.** NGS has published the definition (beta
EPP2022 Euler pole parameters plus equations), but there are no NGS reference coordinates to verify an
implementation against, and the published beta equations are sign-ambiguous. GeoKit implements the
rotation (`datum/EulerPoleRotation`, `datum/PlateFrame2022`) for analysis and tests only. The default
`FrameTransformationRegistry` has no edge to any 2022 frame.

## Sources

| Source | URL | Version / date | Used for |
|---|---|---|---|
| NGS beta page "NATRF2022, PATRF2022, CATRF2022, and MATRF2022" | https://beta.ngs.noaa.gov/NATRF2022/ (index.html) | labelled Beta, no revision date; SHA-256 `e941ada7…691320` (20,424 B) | definition, epoch, units, equations |
| EPP CSV | https://beta.ngs.noaa.gov/NATRF2022/epp2022-beta-values.csv | HTTP Last-Modified 2025-06-16 15:19:24 GMT, ETag `b5-637b1ebbc7700`; SHA-256 `63d80d64…143d52` (181 B) | parameters |
| Equation images | …/NATRF2022/images/equation1a.png, equation2a.png | SHA-256 `33d03ae2…f279`, `832a3109…1f011` | sign convention |
| NOAA TR NOS NGS 62, *Blueprint for 2022, Part 1: Geometric Coordinates* | https://geodesy.noaa.gov/PUBS_LIB/NOAA_TR_NOS_NGS_0062.pdf | 2017-09-18 (versions page dated 2017-09-21) | frame definition, role of intra-frame velocities |
| NOAA TR NOS NGS 67, *Blueprint Part 3: Working in the Modernized NSRS* | https://geodesy.noaa.gov/PUBS_LIB/NOAA_TR_NOS_NGS_0067.pdf | April 2019, revised February 2021 | EPP2022 naming, IFVM/IFDM role, NADCON for NAD 83 → 2022 |
| Beta NCAT | https://beta.ngs.noaa.gov/NCAT/ | "Modernized NCAT beta release 20260915" (change log: Version 3.0, September 2026) | checked for reference values |
| NCAT web API on the beta host | https://beta.ngs.noaa.gov/api/ncat/llh | legacy API (NADCON 5.0) | checked for reference values |
| Track Our Progress | https://www.ngs.noaa.gov/datums/newdatums/TrackOurProgress.shtml | frames "BETA PUBLIC FEEDBACK PERIOD COMPLETE"; NCAT Beta 1 released; NCAT Beta 2 and OPUS "PENDING FOR BETA RELEASE" | status |
| PROJ 9.8.1 / pyproj 3.8.0 (EPSG v12.029) | local venv | — | EPSG coverage |

The raw page, CSV and equation images are pinned byte for byte in
`libs/geokit-core/src/test/resources/nsrs/raw/` with `PROVENANCE.csv` (URLs, sizes, SHA-256, retrieval
time 2026-11-25T01:00:40Z). They were fetched by `libs/geokit-core/tools/nsrs/fetch_epp2022.py`, which
makes four GETs and parses the files strictly. `PlateFrame2022Test` checks the hashes and the constants
against the CSV.

## Definition (NGS beta)

- All four frames are "identical to … ITRF2020 at epoch 2020.0 (January 1, 2020)". After 2020.0 each
  frame rotates with the stable part of its plate.
- The transformation from ITRF2020 uses Euler pole parameters (EPP). NGS says the beta EPPs "were
  derived using velocities relative to ITRF2020". Units are mas/yr, "positive counterclockwise
  following the right-hand rule".
- Equations, for point *i* at epoch *t*, global frame *g* = ITRF2020, plate frame *f*:
  `X_f(t) = X_g(t) − ΔX_{g−f}(t)` with
  `ΔX_{g−f}(t) = K·Δt·[[0, Ωz, −Ωy], [−Ωz, 0, Ωx], [Ωy, −Ωx, 0]]·X_g(t)`,
  `K = π/(648×10⁶)` (mas → rad), `Δt = t − 2020.0`. This is a pure first-order rotation: no translation
  and no scale.
- Beta EPP2022 (CSV, mas/yr):

| Frame | Plate | Ωx | Ωy | Ωz | EPSG |
|---|---|---|---|---|---|
| NATRF2022 | North American | 0.046 | −0.704 | −0.047 | 10966/10967/10968 |
| PATRF2022 | Pacific | −0.409 | 1.063 | −2.188 | none |
| CATRF2022 | Caribbean | −0.056 | −0.957 | 0.589 | none |
| MATRF2022 | Mariana | −8.089 | 5.937 | 2.159 | none |

- History: an earlier *alpha* EPP table (on alpha.ngs.noaa.gov/EPP, which no longer resolves) was
  derived in IGb14. Its CATRF2022 values were corrected on 2025-01-17 because they had been given in
  nanoradians per year (reported in search-engine snapshots; the page could not be retrieved). Only the
  beta values above are used.

### Sign convention: unresolved

Ω × X for these parameters gives the familiar plate velocities in ITRF. For example, at 40° N 80° W
NATRF gives E −15.0 / N +2.4 mm/yr. At Hawaii PATRF gives E −63 / N +35 mm/yr. So Ω is the plate's
rotation in ITRF2020. For plate-fixed coordinates to stay constant on the rigid plate (TR NOS NGS 62
eqs. 17–19), the transformation must be `X_f = X_g − K·Δt·(Ω × X_g)`.

The skew matrix on the beta page gives `matrix·X = −(Ω × X)`. Combined with the page's
`X_f = X_g − ΔX`, a literal reading becomes `X_f = X_g + K·Δt·(Ω × X_g)`. That doubles the plate motion
instead of removing it, about 0.3 m in CONUS for |Δt| = 10 yr. Probably the page has a sign slip,
either in the matrix or in the minus of the first equation.

GeoKit implements the physically consistent form, and `PlateFrame2022Test` proves the rigid-plate
property. NGS reference coordinates must still confirm it before any edge is registered.

## IFDM2022 is not part of the frame transformation

- TR NOS NGS 62, §6: the intra-frame velocities "will not be removed when NGS provides coordinates in the
  new reference frames … they will be provided as a separate service". The frames "will only be
  related to the ideal frame through the rotation of the plate". Eq. 19 relates ITRF and the plate
  frame at the same survey epoch *t*.
- TR NOS NGS 67, §2.7.1/2.8: EPP2022 takes ITRF2020 to the four TRFs. IFVM (now IFDM2022) propagates
  coordinates between epochs (for example 2020.00 ⇄ 2025.00 reference-epoch coordinates). NAD 83(2011/PA11/MA11)
  epoch 2010.00 connects to the 2022 frames at epoch 2020.00 through NADCON.
- Beta NCAT: "NCAT allows changing the epoch of coordinates through the IFDM2022 model" (grids are
  downloadable). This is epoch propagation, a separate step.

So ITRF2020(t) → *TRF2022(t) is EPP only. Moving a 2022-frame coordinate to epoch 2020.00 would be a
separate IFDM2022 step, which GeoKit does not implement. GeoKit transformations never change the
epoch (see `FrameTransformation`).

## Why nothing can be verified yet

1. **Beta NCAT** offers only `NATRF2022 epoch 2020.00`, `NAD83(2011) epoch 2010.00`, NAD83(NSRS2007/FBN/
   HARN/1986), NAD27 and USSD, in both the single-point and the multi-point forms. There is no ITRF2020
   and no PATRF/CATRF/MATRF. Its NAD 83(2011) → NATRF2022 path is NADCON plus IFDM (TR 67), so it cannot
   isolate the EPP rotation, not even as a sign check.
   Two scripted single-point submissions to the JSF form returned NCAT's "expired or an error occurred"
   page and were not pursued.
2. **The NCAT web API** (`https://beta.ngs.noaa.gov/api/ncat/llh`, and the same on geodesy.noaa.gov) is
   the legacy API. It answers `{"error": "Invalid outputDatum"}` for `NATRF2022`, `NATRF2022 epoch 2020.00`,
   `PATRF2022` and `ITRF2020`. It works for `NAD83(NSRS2007)`.
3. **NGS test data**: the beta page says "A test dataset for software developers will be released
   publically on GitHub after completing internal review". As of 2026-11-25 it is not released, and no
   repository was found.
4. **Beta OPUS** (`https://beta.ngs.noaa.gov/OPUS/`, OPUS-S 5.2) says NGS "will no longer support this
   Beta version" in the modernized NSRS. A multi-GNSS modernized version is upcoming.
5. **EPSG / PROJ 9.8.1**: NATRF2022 is in EPSG as 10966 (geocentric), 10967 (3D) and 10968 (2D), plus
   offshore "NATRF2022 / Gulf" projected CRSs 10979–10987. There are no PATRF/CATRF/MATRF CRSs. The only
   operations are EPSG 10969 "NATRF2022 to WGS 84 (1)" and PROJ's "Ballpark geocentric translation from
   ITRF2020 to NATRF2022" (`proj=noop`). No EPP operation "ITRF2020 to NATRF2022" is listed.

The only independent checks possible are algebraic (identity at 2020.0, exact inverse, linearity in Δt)
and physical (rigid-plate constancy). Both are tested. They do not replace NGS vectors, so no frame is
`VERIFIED_BETA`.

## GeoKit API (datum/)

- `EulerPoleParameters(Ωx, Ωy, Ωz)` (mas/yr) and `EulerPoleRotation(epp, referenceEpoch)`: ECEF and
  GRS80-geodetic `forward` (global → plate) and an exact closed-form `inverse`. `MAS_TO_RAD = π/648e6`.
- `TectonicPlate` (`NORTH_AMERICA`, `PACIFIC`, `CARIBBEAN`, `MARIANA`, with an `areaNote` so callers can
  warn) and `PlateFrame2022`: reserved ids `natrf2022`, `patrf2022`, `catrf2022`, `matrf2022`, plus plate,
  EPP, `referenceEpoch` 2020.0, EPSG, `rotation`, `status = UNAVAILABLE`, `unavailableReason` and
  `DATASET_VERSION`. Only NATRF2022 is a `ReferenceFrame` entry. Adding the other three would need the
  `when` in `crs/CrsRegistry.geographicArea` and the frame list in `FrameTransformationRegistryTest`
  extended (both outside WP F). That is deferred until the frames can be verified.
- `ReferenceFrame.plate` (NAD83(2011) and NATRF2022 → North American, global frames → null).
- `FrameEdge` gains `status` (`VERIFIED_LEGACY` / `VERIFIED_BETA`; `UNAVAILABLE` is rejected) and
  `datasetVersion`. The Phase 2 constructor is kept and means `VERIFIED_LEGACY`.
- `FrameTransformation.status` gives the weakest edge status along the chain, and
  `datasetVersions` lists the dataset versions it uses. `FrameTransformationLookup.status` gives the
  transformation's status, or `UNAVAILABLE`.
- Path search uses legacy-only chains when they exist, and a beta edge only otherwise.
- `find(id, id)` maps the reserved PATRF/CATRF/MATRF ids to `NOT_VERIFIED`, not `UNKNOWN_FRAME`.
- Existing numbers are unchanged. ITRF2014 ⇄ NAD83(2011) and ITRF2014 ⇄ ITRF2020 report `VERIFIED_LEGACY`.

## To enable a frame later

1. Get NGS reference coordinates for ITRF2020(t) → frame(t) at several epochs, regions and heights:
   the NGS GitHub test dataset, a modernized NCAT/OPUS with ITRF2020, or an NGS worked example. Store
   the raw files with provenance next to `nsrs/raw/`.
2. Resolve the sign against those coordinates. Agreement should be ≤ 1 mm; the first-order form is
   exact by definition, so residuals should be at the µm level.
3. Register `FrameEdge(ITRF2020, frame, …, ProductStatus.VERIFIED_BETA, PlateFrame2022.DATASET_VERSION, …)`.
   Remove the frame from `DEFAULT_UNAVAILABLE`. Composite paths such as NAD83(2011) → ITRF2014 → ITRF2020
   → NATRF2022 then report beta automatically.
4. If NGS revises the EPPs, re-run `tools/nsrs/fetch_epp2022.py`. The hash test fails until the
   constants and this document are updated.

## Update 2026-11-25 (Phase 4 WP N)

Superseded in part; details and evidence in `phase4-ngs-investigation.md`.

- The EPP page, CSV and equation images are byte-identical to the pins above (EPP dataset unchanged).
- **Sign settled by NGS's definition:** NOAA TR NOS NGS 62, revision of 2021-04-20
  (https://www.ngs.noaa.gov/library/pdfs/NOAA_TR_NOS_NGS_0062.pdf, SHA-256 `b0d25a26…2ae72a`), eqs. 58–60:
  "Equation 58 will be the official definitional relationship between ITRF2020 and each of the four
  plate-fixed reference frames". X_N = {I + (t − t0)·[[0, ω̇Z, −ω̇Y], [−ω̇Z, 0, ω̇X], [ω̇Y, −ω̇X, 0]]}·X_I, which
  is X_N = X_I − Δt·(ω̇ × X_I), the GeoKit form. The beta page's `X_f = X_g − ΔX` is the opposite sign.
- **NGS reference coordinates now exist:** the beta Geodetic Station Pages service
  (`https://beta.ngs.noaa.gov/api_m/gsp/coords`) converts ITRF2020(t) to all four frames at t. GeoKit
  matches 156 pairs (13 CORS, epochs 2014.5/2020.0/2026.9) to 1e-9 m. They are pinned in
  `libs/geokit-core/src/test/resources/nsrs/ngs-gsp/`.
- ITRF2020 ⇄ NATRF2022 is `VERIFIED_BETA` in the opt-in `FrameTransformationRegistry.NSRS2022_BETA`.
  `DEFAULT` is unchanged and still has no 2022-frame edge. PATRF/CATRF/MATRF are verified numerically but are
  not yet `ReferenceFrame` entries.
- Beta NCAT (release 20260915) still has no ITRF2020 or plate frames ("Validation Error: Value is not
  valid" for ITRF2020). The NGS developer test dataset is still unreleased. The beta OPUS shared-solution
  viewer returns 404.
