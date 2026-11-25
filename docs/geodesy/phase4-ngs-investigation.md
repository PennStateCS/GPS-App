# Phase 4 WP N: NGS capability check for ITRF2020 ⇄ NATRF/PATRF/CATRF/MATRF2022

Branch `survey/phase4-n` from e000733. Checked 2026-11-25 (UTC, all retrievals 03:11–03:25 UTC).

**Result.** NGS now computes ITRF2020 → 2022-frame coordinates in a public beta service: the
"Coordinates and Velocities" tool of the beta Geodetic Station Pages. GeoKit's `EulerPoleRotation`
reproduces 156 NGS-computed pairs (four frames, 13 CORS, three epochs) to 1e-9 m. The sign is the one
NGS's official definition (NOAA TR NOS NGS 62, 2021 revision, eqs. 58–60) prescribes. ITRF2020 ⇄ NATRF2022
is registered as `ProductStatus.VERIFIED_BETA` in the new opt-in `FrameTransformationRegistry.NSRS2022_BETA`.
`DEFAULT` is unchanged (see "Activation").

## A. What NGS offers now

### Sources (retrieved 2026-11-25)

| Source | URL | Version / date | SHA-256 (bytes) |
|---|---|---|---|
| Beta frames page | https://beta.ngs.noaa.gov/NATRF2022/index.html | no revision date | `e941ada7…691320` (20,424), **unchanged** |
| EPP CSV | https://beta.ngs.noaa.gov/NATRF2022/epp2022-beta-values.csv | Last-Modified 2025-06-16 15:19:24 GMT, ETag `b5-637b1ebbc7700` | `63d80d64…143d52` (181), **unchanged** |
| Equation images | …/images/equation1a.png, equation2a.png | Last-Modified 2025-06-10 | `33d03ae2…f279`, `832a3109…1f011`, **unchanged** |
| NOAA TR NOS NGS 62 (Blueprint Part 1) | https://www.ngs.noaa.gov/library/pdfs/NOAA_TR_NOS_NGS_0062.pdf | versions page: 2017-04-28, 2017-09-21, **2021-04-20**; Last-Modified 2021-04-21 | `b0d25a26d827daf6ff01c8ba8d96ee66b12ca200be335f72732f10794d2ae72a` (4,505,404) |
| Policy page | https://www.ngs.noaa.gov/datums/newdatums/policy.shtml | links the Blueprints only | `d9776608…1006b5` |
| Beta portal | https://beta.ngs.noaa.gov/ | "available for public testing and evaluation until at least March 2027" | `03616618…7bafd0` |
| Track Our Progress | https://www.ngs.noaa.gov/datums/newdatums/TrackOurProgress.shtml | frames "BETA PUBLIC FEEDBACK PERIOD COMPLETE"; NCAT Beta 2 / OPUS "PENDING FOR BETA RELEASE" | `4fd99c1a…a49a796` |
| Beta NCAT | https://beta.ngs.noaa.gov/NCAT/ | change log: "Version 3.0 September 2026", "Modernized NCAT beta release 20260915" | page `4c919c17…8debbe`; change log `0b6d6aa2…f543a`; FAQ PDF `0fad40ea…335752` |
| Beta OPUS about | https://beta.ngs.noaa.gov/OPUS/about.jsp | "BETA OPUS-S 5.2 (M-PAGES 1.1.1)", note dated 10/29/2026 | `6e63733e…11471` |
| Beta Geodetic Station Pages | https://beta.ngs.noaa.gov/DDS/geodetic-station-pages/ (bundle `assets/index-DbaPV8or.js`, 7,770,405 B) | beta | responses pinned, see B |

The 2022-frame page, EPP CSV and equation images are byte-identical to the Phase 3 pins in
`libs/geokit-core/src/test/resources/nsrs/raw/` (PROVENANCE.csv). **The EPP dataset has not changed.**

### Sign convention: settled by NGS's definition

TR NOS NGS 62 (2021-04-20). The versions table says: "The official definitional relationship between
ITRF2020 and the *TRF2022 frames has been changed from the complicated M⁻¹(θ0, λ0, ω̇0, t, t0) matrix to a much
simpler R(ω̇X, ω̇Y, ω̇Z, t, t0) matrix". Appendix A, p. 50–51:

> R̃ZYX = I + (t − t0) [[0, ω̇Z, −ω̇Y], [−ω̇Z, 0, ω̇X], [ω̇Y, −ω̇X, 0]]   (58)
>
> "Equation 58 will be the official definitional relationship between ITRF2020 and each of the four
> plate-fixed reference frames of the modernized NSRS."
>
> [X(t) Y(t) Z(t)]_N = R(t, t0)_{N,I} [X(t) Y(t) Z(t)]_I   (59), expanded in (60), "Where "N" means NATRF2022
> and "I" means ITRF2020."
>
> ω̇X = ω̇0 cosλ0 sinθ0, ω̇Y = ω̇0 sinλ0 sinθ0, ω̇Z = ω̇0 cosθ0   (64–66)

Here ω̇ is the plate's own rotation vector in the ITRF (Euler pole co-latitude θ0, longitude λ0, rate ω̇0;
Appendix A rotates the point counter-clockwise, eq. 8). The derivation follows Condition 1 (p. 16): "The
coordinate of any point in a plate-fixed frame should remain constant through time, if that point's only
motion is a rotation about the Euler pole of that plate." Since the matrix in (58) times X is −(ω̇ × X),
eq. 60 is **X_N = X_I − (t − t0)(ω̇ × X_I)**. That is exactly `EulerPoleRotation.forward`.

The beta page gives the same matrix but writes the first equation as `X_f = X_g − ΔX_{g−f}` with
`ΔX = KΔt·matrix·X_g` (equation1a/2a.png). That is the opposite sign. It contradicts TR 62 eq. 60 and the
page's own words ("each reference frame rotates with the stable part of their tectonic plate, thereby
minimizing changes in coordinates due to plate motion"). It also contradicts NGS's computed numbers (B):
the literal reading misses them by 8 cm (CATRF2022 at TMGO, Colorado, Δt = −5.5 yr) up to 4.3 m (MATRF2022 at TMGO, Δt = 6.9 yr). Phase 3 cited the 2017
TR 62 (geodesy.noaa.gov copy). That version predates eq. 58–60. The 2021 revision above is the current one.

Corroboration from an NGS output sample: the beta OPUS about page shows a sample report (LSU 1, Baton Rouge)
with ITRF2020 at epoch 2025.8808 (−113403.067, −5504359.561, 3209403.152) and NATRF2022 at epoch 2020.0000
(−113402.995, −5504359.565, 3209403.166). EPP2022 with the TR 62 sign brings the ITRF2020 position to NATRF2022(2025.8808).
That point differs from the OPUS NATRF2022(2020.0) by E +0.4 / N −0.2 / U +9.2 mm, which is the
residual (IFDM2022) epoch step. The literal sign gives E +143.8 / N +21.8 mm. The sample mixes
epochs and is illustrative only, so it was not used as a reference vector.

### Beta NCAT (https://beta.ngs.noaa.gov/NCAT/)

- Datum options in every form, single point and multipoint (`indatum`, `outdatum`, `indatum2`, `outdatum2`):
  `NATRF2022 epoch 2020.00`, `NAD83(2011) epoch 2010.00`, NAD83(NSRS2007/FBN/HARN/1986), NAD27, USSD. The
  forms have **no ITRF2020, no PATRF/CATRF/MATRF2022 and no explicit coordinate epoch**. XYZ input
  (`cotype=xyz`) and multipoint upload (≤ 500 KB) exist, but only for those datums.
- Page text: "Future NCAT updates: time-dependent operations, global dynamic reference frame
  transformations, and API functions will be added after internal testing is complete."
  FAQ (NCAT-FAQs.pdf), Q2: "Will time-dependent operations, dynamic global reference frame
  transformations, and web services be available for NCAT 3.0? Yes, those enhancements will be added in
  the future." Q4: "All versions of WGS 84 will soon be added to NCAT, along with several other global
  dynamic reference frames (e.g., all versions of ITRF)". Q5: "No, there is no batch mode for web service."
- Form tests: two scripted submissions with session cookie and ViewState, 2026-11-25T03:14:50Z.
  1. NAD83(2011) epoch 2010.00 → NATRF2022 epoch 2020.00 at 40 N, 80 W returned 40.0000085393, −80.0000076631
     (0.948 ± 0.020 m N, −0.654 ± 0.015 m E). Input epoch 2010.00 and output epoch 2020.00 use NADCON 6.0.0
     grids plus IFDM2022, not EPP. Response SHA-256 `e3f97625…eb49ce`.
  2. NATRF2022 → `ITRF2020` was rejected by the server: "Output datum: Validation Error: Value is not valid".
     SHA-256 `e84b3c85…9434b6`.
- Legacy API `https://beta.ngs.noaa.gov/api/ncat/llh` returned `{"error": "Invalid inputDatum"}` for
  `inDatum=NATRF2022 epoch 2020.00`, and `{"error": "Invalid outputDatum"}` for `outDatum=NATRF2022`.
- No "2026-11-12" update is visible. The change log still ends at Version 3.0 / release 20260915.
- **Conclusion:** NCAT cannot isolate the EPP rotation and offers no ITRF2020 or plate frames.

### Beta OPUS (https://beta.ngs.noaa.gov/OPUS/about.jsp)

- The page claims OPUS-S 5.2 gives ITRF2020 at the survey epoch, the plate frame at 2020.0, NAPGD2022 and
  SPCS2022. The same page also says "NGS will no longer support this Beta version of OPUS-S. Be on the
  lookout for an upcoming multi-GNSS version", and a 10/29/2026 note reports "A software issue … in our CORS
  selection process that impacts the quality of your OPUS solution".
- The shared-solution viewer `https://beta.ngs.noaa.gov/OPUS/view.jsp` is HTTP 404 ("JSP file [/view.jsp]
  not found"). No public shared beta solutions were found.
- The only NGS examples are the two sample reports on the about page. The ITRF2020 coordinates are at the
  survey epoch and the 2022-frame coordinates at 2020.0, so the pair includes IFDM2022. The second sample is
  internally inconsistent: it has a 2022 observation with an "EPOCH: 2025.1507" header, a 0.9–1.4 m
  NAD83-like offset, and H = 2115 m at sea level. **Not usable as reference vectors.**

### NGS developer test dataset

The frames page still says "A test dataset for software developers will be released publically on
GitHub after completing internal review". The `noaa-ngs` GitHub organisation (`gh repo list noaa-ngs`) has
no such repository. Its newest repository is `xintg`, 2026-08-03. A GitHub search for "NATRF2022" and
"EPP2022" finds only third-party projects: `jjimenezshaw/NSRS-2022-PROJ` (PROJ auxiliary DB, NADCON grids and
geoids, no NGS EPP vectors) and `Perez-Stesy/epp2022` (unrelated SQL). Neither is an NGS source.

### Beta Geodetic Station Pages service: the NGS source used

The beta DDS station pages (https://beta.ngs.noaa.gov/DDS/geodetic-station-pages/) have a "Coordinates and
Velocities" tool. Its frame selector lists ITRF2020 and the four 2022 frames, and it takes a date. The page
JavaScript (`index-DbaPV8or.js`) calls two NGS services, base `https://beta.ngs.noaa.gov/api_m`:

1. `GET /cors/ccf?stationid=<9-char id>&epoch=<t>` returns the station's CORS coordinate function (MYCS3)
   at t: ITRF2020 x, y, z, velocities and covariances.
2. `GET /gsp/coords?station=…&x=…&y=…&z=…&epoch=<t>&<covariances>&vx=…` returns one record per frame:
   `"Datum": "ITRF2020" | "NATRF2022" | "PATRF2022" | "CATRF2022" | "MATRF2022"`, the same `"epoch"`,
   x/y/z, lat/lon/eht and velocities.

These services are NGS's own beta computation, but NGS does not document them as an API. The x/y/z sent
in request 2 is what NGS converts. A test with a position 1 km off the station gave the same agreement. The
ITRF2020 record echoes the input and every record carries the same epoch, so each pair is unambiguous:
same epoch, labelled frames, and no IFDM (the epoch never changes).

## B. Reference vectors

`libs/geokit-core/tools/nsrs/fetch_epp2022_gsp.py` makes the page's own two requests per station and
epoch, with a 1 s pause, 78 requests in total, 2026-11-25T03:23:56Z–03:25:45Z. It validates the responses
strictly and writes the following to `libs/geokit-core/src/test/resources/nsrs/ngs-gsp/`:

- the raw `ccf_*.json` and `coords_*.json`, byte for byte (`* -text`);
- `PROVENANCE.csv`: file, retrieval time, bytes, SHA-256 and full URL for each;
- `epp2022_gsp_vectors.csv`: 156 rows (station, epoch, frame, ITRF2020 xyz + lat/lon/h, frame xyz + lat/lon/h),
  numbers copied verbatim.

Coverage: North American plate TMGO (Colorado, 1673 m), AOML (Florida), FAIR (Alaska), HNPT (Maryland);
Pacific MKEA (Mauna Kea, ≈ 3,750 m), HNLC (Honolulu), ASPA (American Samoa); Caribbean PRMI (Puerto Rico),
CRO1, STVI (USVI); Mariana GUUG, GUAM (Guam), CNMR (Saipan). Epochs 2014.5, 2020.0 and 2026.9; heights
−30 to 3,750 m. Each station is given in all four frames, so every frame is also tested off its own plate.

Comparison (`PlateFrame2022NgsVectorsTest`, plus an independent pure-Python evaluation of eq. 60 in the
investigation):

| Quantity | Max difference |
|---|---|
| X/Y/Z, forward (156 pairs) | 9.3e-10 m |
| GRS80 lat/lon, forward and inverse | < 1e-11° (test tolerance), Python 4e-14° |
| ellipsoidal h | < 1e-6 m (test tolerance), Python 2.6e-9 m |
| inverse: NGS frame output → NGS ITRF2020 input | < 1e-6 m (test tolerance) |
| velocities v_f = v_g − Ω×X (Python) | 0 |
| 2020.0 rows | identical to ITRF2020, as defined |
| literal beta-page sign | 8 cm … 4.3 m off |

Tolerance: NGS returns full double precision and eq. 60 is exact by definition, so the test requires 1 µm.
That is three orders of magnitude below the 1 mm surveying tolerance. The inverse is GeoKit's exact
closed-form inverse, checked against NGS's input. NGS offers no inverse service.

## C. Activation

| Frame | NGS-verified? | GeoKit status |
|---|---|---|
| NATRF2022 | yes (156-pair set, 39 NATRF rows) | `FrameTransformationRegistry.ITRF2020_TO_NATRF2022`, **VERIFIED_BETA**, dataset `PlateFrame2022.DATASET_VERSION`, source `PlateFrame2022.VERIFICATION`, in the opt-in `NSRS2022_BETA` registry |
| PATRF2022 | yes (39 rows) | rotation verified; **no edge** because PATRF2022 is not a `ReferenceFrame` entry |
| CATRF2022 | yes (39 rows) | same as PATRF2022 |
| MATRF2022 | yes (39 rows) | same as PATRF2022 |

- With `CrsRegistry(frameRegistry = FrameTransformationRegistry.NSRS2022_BETA)`, the existing registry
  logic makes all 949 NATRF2022 SPCS2022 zones operational as `VERIFIED_BETA`, and so is
  `geographic:natrf2022`. Hawaii, PRVI, Guam/CNMI and American Samoa zones stay unsupported because their
  frames are not GeoKit frames. The test is `PlateFrame2022NgsVectorsTest`.
- `NSRS2022_BETA` gives exactly the `DEFAULT` result (status, steps, numbers) for every pair of
  `ReferenceFrame`s that does not involve NATRF2022 (tested).
- **`DEFAULT` is deliberately unchanged.** Registering the edge there changes app behaviour.
  `app/.../Nsrs2022CapabilitiesTest` ("the four 2022 frames are unavailable") and the Phase 3 library tests
  (`PlateFrame2022Test`, `FrameTransformationRegistryTest`, `CrsSpcs2022Test`, `CrsRegistryStatusTest`) pin
  `DEFAULT` as having no 2022 edge. This WP may not touch the app or run app tasks. All 296 geokit-core tests
  pass unchanged (Phase 1–3 plus 9 new).
- Follow-up for an app WP: switch the app's registry to `NSRS2022_BETA` while the beta preview is on, or
  make it `DEFAULT`. Update the capability model and its tests and the label "unavailable — awaiting NGS
  verification". For PATRF/CATRF/MATRF, add `ReferenceFrame` entries. That means extending
  `CrsRegistry.geographicArea`, the frame list in `FrameTransformationRegistryTest` and the app's frame
  handling, then registering their edges the same way.
- Caveats, recorded in `VERIFICATION`: the service is beta, NGS does not document it as an API, and the
  beta page's equation still shows the other sign. If NGS revises the EPPs, the Phase 3 hash test fails.
  Re-running the fetch tool against an empty directory then shows whether NGS's computed numbers changed.
- IFDM2022: not implemented. EPP2022 changes the frame and not the epoch (TR 62 §9: "EPP2022 changes a
  coordinate's frame / IFVM2022 changes a coordinate's epoch"). No IFDM validation data were collected.

**Update (Phase 4 WP X):** the follow-up below is done; see section E.

What NGS should still publish: the promised developer test dataset (GitHub), documentation of the
`api_m` services or ITRF2020 support in NCAT, and a corrected beta frames page that matches TR 62 eq. 60.

## D. SPCS2022 and GEOID2022 versions

- **SPCS2022:** the change log (https://beta.ngs.noaa.gov/SPCS/change-log.html, SHA-256 `724dc4a9…a945ede1`)
  still ends at 6/1/2026, the "Nebraska McCook" rename. `zoneDefinitions.json`, `coordinates.json` and
  `zoneBounds.json` are byte-identical to the pins in `tools/nsrs/gen_spcs2022.py`, Last-Modified
  2026-06-01. The tables still say "Last Updated 6/1/2026". **No newer version; nothing to regenerate.**
- **GEOID2022:** the download page (https://beta.ngs.noaa.gov/NAPGD2022/download.html, `6a724dc4…dd48410f`)
  lists only `beta_v0a` files. The change log has 4/27/2026 `beta_v0a` and 6/16/2025 `beta_v0`. The pinned
  files keep their Phase 3 sizes and dates: `SGEOID2022.NA.N.beta_v0a.bin` 233,344,848 B and
  `DGEOID2022.GL.Ndot.beta_v0a.bin` 4,155,888 B, Last-Modified 2026-05-11. The test cases file is 10,393 B.
  A `…v1a.bin` URL returns 404. The FAQ (https://beta.ngs.noaa.gov/new-datums/faq.html, `1eb708a4…84d619`)
  announces no new geoid version. It gives the current-NSRS submission deadline 2027-01-13 and says the
  modernized NSRS becomes official "sometime around March 2027". OPUS sample reports name "GEOID2022v1.0.0",
  and a third-party PROJ repository has a file named `sgeoid2022_na_v1a`. No such NGS download exists, so
  **`beta_v0a` remains current and nothing changes.**

## Review note (2026-11-25): derived paths through the NATRF2022 edge

Only the single ITRF2020 ⇄ NATRF2022 edge was verified against NGS coordinates. A request such as
NAD83(2011) → NATRF2022 resolves through NAD83(2011) → ITRF2014 → ITRF2020 → NATRF2022 and inherits the beta
status, but NGS never verified that chain end to end: NGS's own NAD83(2011) → NATRF2022 path uses NADCON 6 grids
plus IFDM2022 with output at epoch 2020.00, while the chain keeps the input epoch. An independent check at
40° N 80° W (epoch 2010) agreed with the NCAT value to ~5 mm, but regional NADCON distortion and the epoch
difference are not covered. Treat such derived results as beta-derived, not NGS-equivalent.

## E. Activation in the app (Phase 4 WP X)

Branch `survey/phase4-x` from `survey/phase4` (e2395bd). No new NGS data was fetched; the activation rests on the
156 NGS station-page vectors of section B.

### geokit-core

- `ReferenceFrame` gains `PATRF2022`, `CATRF2022`, `MATRF2022` (ids `patrf2022` / `catrf2022` / `matrf2022`, appended
  after `NATRF2022`; plate-fixed on GRS80; reference epoch 2020.0; `plate` Pacific / Caribbean / Mariana). EPSG v12.029
  has no CRS for them, so all EPSG codes are null. `PlateFrame2022.referenceFrame` now points at them.
- `FrameTransformationRegistry.ITRF2020_TO_PATRF2022` / `_CATRF2022` / `_MATRF2022`: the EPP2022 rotations as
  `VERIFIED_BETA` edges with `PlateFrame2022.DATASET_VERSION` and `PlateFrame2022.VERIFICATION`, like the NATRF2022 edge.
  `NSRS2022_BETA` = `DEFAULT` edges + `NSRS2022_BETA_EDGES` (all four), no 2022 frame blocked.
- **`DEFAULT` is unchanged**: the same two legacy edges. The three new frames are in `DEFAULT_UNAVAILABLE` with the
  reason `find(id)` already gave for their reserved ids (`NOT_VERIFIED`).
- `CrsRegistry`: areas of use for the three new geographic CRSs (GeoKit boxes, EPSG has none); `frameRegistry` is now
  readable. With `NSRS2022_BETA` all 953 SPCS2022 zones (incl. Hawaii, American Samoa, PRVI, Guam/CNMI) are operational
  `VERIFIED_BETA`; `CrsRegistry.DEFAULT` still has none.
- Tests: the Phase 2 frame-list test is extended (append-only order pinned); a new test checks PATRF/CATRF/MATRF
  against the 39 NGS rows each through the registry (forward and inverse, 1e-11° / 1 µm), the chains from the app's
  stored frame, 2022 → 2022 via ITRF2020, and `DEFAULT` refusing them. Assertions that pinned the three frames as "not
  a GeoKit frame yet" now pin the new state.

### App (preview ON only; preview OFF is byte-identical)

- Registries: `coords/Nsrs2022Registries` — preview OFF: `FrameTransformationRegistry.DEFAULT` / `CrsRegistry.DEFAULT`
  exactly as before; preview ON: `NSRS2022_BETA` and a `CrsRegistry` built on it (conversions, capability status, zone
  picker). `CoordinateConverter` picks per conversion from `CoordinateSystemSettings.nsrs2022Preview`.
- Capabilities: the four frames and SPCS2022 are "Verified NGS beta" (selectable) with dataset version, area note
  and the caveat ("Verified against NGS beta Geodetic Station Page coordinates; the NGS endpoints are undocumented;
  not authoritative"). The stale "unavailable — awaiting NGS verification" wording is replaced by "unavailable"
  (only seen with geokit's default registry).
- Output datums (appended, persisted values): `natrf2022_beta`, `patrf2022_beta`, `catrf2022_beta`,
  `matrf2022_beta`, labels "NATRF2022 (beta)" … Offered only with the preview ON and System = Latitude / Longitude.
  The position is the stored source transformed through `NSRS2022_BETA` at the coordinate epoch (stored
  `coordinateEpoch`, else the capture time; live fix time), shown as "NATRF2022 (beta) epoch 2026.886"; the
  ellipsoidal height is the 2022 frame's; orthometric heights are those of the vertical choice (unchanged).
  All four are offered everywhere with an area note (each frame is meant for its plate).
- **No UTM on a 2022 frame**: no authority defines one (no EPSG / NGS CRS), so with System = UTM a saved 2022 datum is
  not used (WGS84 (ITRF2014) UTM, with a note).
- SPCS2022: `HorizontalSystem.SPCS2022` (persisted `spcs2022_beta`, label "State Plane (SPCS2022, beta)") and a
  new DataStore key `coord_spcs2022_zone` holding the registry id `spcs2022:<zone code>` (default absent/null). The zone
  picker lists the SPCS83 zones and, with the preview ON, all 953 SPCS2022 zones (zone, frame, frame epoch, projection,
  unit, β beta); picking an SPCS2022 entry selects SPCS2022 with that zone, picking an SPCS83 entry returns to SPCS83.
  Conversion: source → the zone's own frame (NATRF/PATRF/CATRF/MATRF2022, never NAD83) at the coordinate epoch via
  `NSRS2022_BETA` → `CrsDefinition.forward` (metres, the NGS defining unit). Display: metres or international feet;
  US survey feet are shown as international feet (NGS does not support the US survey foot for SPCS2022). Summary / CSV
  System: "SPCS2022 421001 (beta) · NATRF2022 epoch 2026.886 · m · H rcvr · EPP2022 beta 2025-06-16 · NGS beta zones
  2026-05-14". Zone suggestion: `CrsRegistry.locateSpcs2022` candidates with near-boundary / envelope wording; the app
  never picks a zone. The catalog is parsed off the main thread (`CoordinateSystemService` warm-up; a main-thread
  conversion before that shows the labelled default with "SPCS2022 zone data is loading").
- Fallbacks (stored values kept, never rewritten): preview OFF with a 2022 datum → WGS84 (ITRF2014); with SPCS2022 →
  Latitude / Longitude; SPCS2022 without a zone → Latitude / Longitude with "Choose an SPCS2022 zone"; an unknown zone
  id → the default with "SPCS2022 zone … is not available". Unknown persisted values fall back to the existing defaults.
- Untouched: GNSS feed, capture, receiver provenance, database schema, AR / Maps / stakeout (WGS84 path), CSV column
  layout, the JSON backup format.

### Risks and limits

- Everything 2022 is NGS beta: the EPPs, the SPCS2022 zone designs and the station-page service. NGS may revise
  them before the modernized NSRS becomes official (around March 2027); the hash pins fail if the EPP table changes.
- No IFDM2022: coordinates are given at the coordinate epoch, not propagated to 2020.0 (NGS's published NATRF2022
  coordinates are at 2020.0, so they differ from the app's by intra-frame motion, a few mm/yr in stable areas).
- The verification is against NGS's computed coordinates for 13 CORS; NGS has not published an API or developer
  dataset, and beta NCAT still cannot isolate the EPP rotation.
- SPCS2022 zone choice is the user's: layered zones overlap legally; polygons are generalised (±~150 m).

## F. Validation scope and provenance (Phase 4 stabilization, 2026-11-25)

What each 2022 result rests on. All 2022 products stay **beta**, appear only with the opt-in
"Modernized NSRS 2022 (beta preview)" setting (default OFF), and are never production defaults.

| Product | Verified how | Scope of the claim |
|---|---|---|
| ITRF2020 ⇄ NATRF2022 / PATRF2022 / CATRF2022 / MATRF2022 (each edge on its own) | 156 NGS pairs (13 CORS × epochs 2014.5 / 2020.0 / 2026.9 × 4 frames) from the beta Geodetic Station Pages back end, reproduced to < 1 µm; sign from TR NOS NGS 62 (2021) eq. 58/60 | The single Euler-pole rotation at the **same** epoch. Beta: the NGS service is undocumented and the EPP2022 parameters are preliminary. |
| SPCS2022 projections (953 zones) | One NGS example point per zone (`crs/spcs2022/ngs_example_coordinates.csv`, from NGS `coordinates.json`, sha256 recorded) plus PROJ vectors (`proj_vectors.csv`) | The projection from the zone's own frame to grid. Not a frame transformation. |
| NAD83(2011) → NATRF2022 (and other chains through ITRF2014 → ITRF2020) | **Not independently validated end to end.** Built from three verified edges; one spot check at 40° N 80° W agreed with NCAT to ~5 mm | NGS's own path uses NADCON 6 + IFDM2022 and outputs epoch 2020.00; ours keeps the input epoch. Regional NADCON distortion is not reproduced. Labelled beta-derived. |
| Propagating a coordinate from one epoch to another | **Unsupported.** IFDM2022 (the NGS intra-frame deformation model) is not implemented | Every 2022 output is at the position's own coordinate epoch; no velocity / deformation is applied. |

**Reproducibility.** The tests never contact NGS. The 78 raw station-page responses are committed in
`libs/geokit-core/src/test/resources/nsrs/ngs-gsp/` with `PROVENANCE.csv` (file, retrieval time, bytes,
SHA-256, URL), and `PlateFrame2022NgsVectorsTest` fails if any file is missing or its bytes or hash differ.
`tools/nsrs/fetch_epp2022_gsp.py` only re-fetches for a deliberate refresh. If the undocumented endpoint
disappears, the pinned values still verify the implementation; the SPCS2022 examples and GEOID2022 test values
are pinned the same way (`nsrs2022-sources-spcs2022.md`, `nsrs2022-sources-geoid2022.md`). No reference value or
manifest changed in this phase.
