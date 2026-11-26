# SurReal AR design system (colour, contrast, typography)

How colour and text are chosen in the app, what is enforced by tests, and how to add new UI. Phase 1
(`feature/visual-p1`) built the system and fixed the core theme-bypassing components; phase 2 applies it to the
remaining screens (`feature/visual-p2`): badges / status text / charts / AR and map overlays on semantic or named
roles, the remaining raw text sizes on the type scale, layout fixes for 1.3x / 1.5x font scale, and a Kotlin colour
guard. The [Audit](#audit) records what was done and the [intentional exceptions](#intentional-exceptions).

Scope is visual only: no GNSS, capture, coordinate, database, AR-rendering, receiver or navigation logic changes.

## Principles

- **Purpose, not hex.** Views use theme attributes (`?attr/colorOnSurface`, `?attr/colorStatusWarning`) or
  purpose-named colour resources (`@color/toolbar_warning`, `@color/ar_overlay_text`). Raw hex in layouts or Kotlin is
  for genuinely fixed colours only, and those still go through a named resource.
- **WCAG AA minimums, stronger where it matters.** Normal text ≥ 4.5:1, large text and meaningful graphics /
  component boundaries / indicators ≥ 3:1. Translucent colours are measured after compositing. Live surveying values
  are all ≥ 6:1 in practice.
- **Never colour alone.** Status always also has words and/or an icon (the toolbar pill has an icon + text, report rows
  get a ⚠ prefix, the Home card states the status in words).
- **Keep the hierarchy.** App bar (darkest navy) → GNSS strip (navy) → page background → cards (surface) → dialogs /
  menus (surface containers) → overlays. Not everything is pure white or black.

## Palette

Material 3 roles stay as they were (they already pass, see the contrast table); the app layer adds semantic status
roles and toolbar roles.

### Brand / Material 3 roles (unchanged in phase 1)

| Role | Light | Dark |
|---|---|---|
| App bar (`app_top_app_bar`) | `#071A2D` | `#07121E` |
| GNSS strip (`app_status_strip`) | `#0F3554` | `#0F3554` |
| colorPrimary / onPrimary | `#155DA8` / `#FFFFFF` | `#8FC5FF` / `#0F3A63` |
| colorSecondary | `#00796B` | `#79D7C9` |
| colorTertiary (amber) | `#7C5800` | `#FBBE4D` |
| colorError | `#BA1A1A` | `#FFB4AB` |
| background | `#F4F7FA` | `#0B1117` |
| surface | `#FFFFFF` | `#101820` |
| surfaceVariant / surfaceContainer / High / Highest | `#EAF0F6` / `#EAF0F6` / `#DFE7F0` / `#D4DCE8` | `#1D2A36` / `#16212B` / `#1D2A36` / `#243042` |
| onSurface / onSurfaceVariant | `#1A1C1E` / `#41484D` | `#E2E4E7` / `#C1C8CE` |
| outline / outlineVariant | `#71787E` / `#C5CED8` | `#8B9198` / `#334454` |

No Material role needed a contrast adjustment (before = after).

### Semantic status roles

Defined in `values/colors.xml` + `values-night/colors.xml`, exposed as theme attributes (`values/attrs.xml`, set in
`Theme.SurveyingApp` in both theme files). Each role has three colours:

- **foreground** `status_<role>` / `?attr/colorStatus<Role>`: text, icons and indicators on surface, background and any
  surface container (≥ 4.5:1 on all of them).
- **container** `status_<role>_container` / `?attr/colorStatus<Role>Container`: fill for badges, banners, chips. Never
  text.
- **on-container** `status_on_<role>_container` / `?attr/colorOnStatus<Role>Container`: text / icon on that fill
  (≥ 4.5:1).

| Role (purpose) | Foreground light | Container / on light | Foreground dark | Container / on dark |
|---|---|---|---|---|
| success (RTK Fix, good, pass) | `#1E6B2A` | `#CDEBD2` / `#0B3D14` | `#81C995` | `#1E4D2B` / `#C8F0D0` |
| warning (Float, aging, caution) | `#9A4700` | `#FFDEA8` / `#2E1A00` | `#F7C66A` | `#5D4100` / `#FFDEA8` |
| error (no fix, fail, critical) | `#BA1A1A` | `#FFDAD6` / `#410002` | `#FFB4AB` | `#93000A` / `#FFDAD6` |
| info (DGPS, informational) | `#155DA8` | `#D7E8FF` / `#001B44` | `#9CC8FF` | `#0F3A63` / `#D7E8FF` |
| neutral (Single, unknown) | `#41484D` | `#E3E7ED` / `#1A1C1E` | `#C1C8CE` | `#2A3644` / `#E2E4E7` |

Legacy names alias the roles: `app_success`, `app_warning`, `app_error`, `app_info` → `status_*`;
`status_fix` / `status_float` / `status_dgps` / `status_no_fix` → success / warning / info / error (so they now have
night values); `status_single` gained a night value (`#B0BEC5`).

### GNSS toolbar roles

The strip is `#0F3554` in **both** themes, so these have no night override. They are only for text/icons drawn on the
strip (not global semantics). Mapping lives in `ui/toolbar/GnssToolbarColors` (used by `MainActivity.levelColor`).

| Resource | Hex | Use | on `#0F3554` |
|---|---|---|---|
| `toolbar_on_primary` (= `app_on_status_strip`) | `#F2F6FA` | values | 11.67 |
| `toolbar_on_secondary` (= `app_on_status_strip_variant`) | `#B8C7D6` | labels, chevron, level NONE | 7.35 |
| `toolbar_success` | `#81C995` | RTK Fix, good accuracy, battery fill OK | 6.47 |
| `toolbar_warning` | `#F7C66A` | Float, aging, battery ≤ 30 % | 7.99 |
| `toolbar_error` | `#FFAAA5` | no fix / error, battery ≤ 15 % | 6.97 |
| `toolbar_info` | `#9CC8FF` | DGPS / info | 7.31 |
| `app_status_separator` | `#6F8598` | decorative `|` dividers | 3.31 |

Warning pills stay **filled** (distinct from the coloured status text):

| Pill | Fill | Text / icon | Text on fill | Edge on strip |
|---|---|---|---|---|
| critical | `#C62828` | `#FFFFFF` | 5.62 | 1 dp outline `toolbar_error` 6.97 (fill alone 2.25) |
| caution | `#FFB300` | `#1A1A1A` | 9.70 | fill 7.06 |

### Overlay roles

- **AR camera overlays** (`ar_overlay_*`): fixed light-on-dark over `ar_overlay_bg_*` scrims. Status text uses
  `ar_overlay_success/warning/error/info` (same values as the toolbar roles); primary text `ar_overlay_text` white.
  Checked against the heaviest scrim (`#BB000000`) composited over a white camera frame.
- **Model viewer canvas**: the Filament skybox/clear colour is a fixed light grey in both themes, so the loading
  overlay is fixed `model_viewer_canvas` `#EEEEEE` and text over it uses `model_viewer_on_canvas` `#1A1C1E`,
  `model_viewer_on_canvas_error` `#BA1A1A` and the spinner `model_viewer_on_canvas_accent` `#1A56A4`.
- **Map overlays** (`map_overlay_bg`, `TextAppearance.App.Map.*`): near-white panel with dark text. Map point labels
  use `map_label_text` on `map_label_bg` (see [Overlay conventions](#overlay-conventions-phase-2)).

Overlay rules: fixed colours are allowed over a camera or map, but only via a named `ar_overlay_*` / `map_*` resource
or style; they must pass 4.5:1 for text against the scrim composited over the worst-case (white) frame; do not use
theme surface colours over the camera.

## Type scale

Named text appearances in `values/styles.xml`, each parented on the matching Material 3 style. Use them instead of
`android:textSize`.

| Style | Size | Parent / notes |
|---|---|---|
| `TextAppearance.App.PageTitle` | 24 sp | M3 HeadlineSmall, onBackground |
| `TextAppearance.App.SectionHeading` | 18 sp | M3 TitleMedium (medium weight) |
| `TextAppearance.App.Body` (= `BodyPrimary`) | 16 sp | M3 BodyLarge, primary content / interactive |
| `TextAppearance.App.Secondary` | 14 sp | M3 BodyMedium, onSurfaceVariant |
| `TextAppearance.App.Reading` | 14 sp | BodyMedium, **tabular figures**, letter spacing 0 |
| `TextAppearance.App.ReadingCompact` | 13 sp | Reading, dense numeric rows |
| `TextAppearance.App.Supporting` | 12 sp | M3 BodySmall, onSurfaceVariant |
| `TextAppearance.App.LabelSmall` | 12 sp | M3 LabelSmall raised from 11 sp; the theme's `textAppearanceLabelSmall` |
| `TextAppearance.App.StatusLabel` | 12 sp | was 11 sp |
| `TextAppearance.App.Mono` | 13 sp | monospace — raw NMEA / debug only |
| `TextAppearance.App.AR.Overlay` | 12 sp | white, camera overlays |
| `TextAppearance.App.AR.OverlaySmall` | 12 sp | was 9 sp; AR / map tool-card labels |
| `TextAppearance.App.Toolbar.Label` / `.Value` | 12 / 14 sp bold tnum | GNSS strip (no parent: same metrics as before) |

Existing `PageSubtitle`, `SectionTitle`, `CardSectionLabel`, `BodySecondary`, `DataValue`, `DataLabel`, `Metadata`
stay. Nothing named is below 12 sp (`TypeScaleTest`). Prefer regular/medium weight; bold only for live values and
headlines.

### Numeric conventions

- Coordinates, elevations, accuracies, distances, correction ages and any continuously changing measurement use
  tabular figures: `TextAppearance.App.Reading` / `ReadingCompact` **and** `android:fontFeatureSettings="tnum"` on the
  view (text-appearance font features are honoured from API 28; minSdk is 24). In Kotlin: `fontFeatureSettings = "tnum"`.
- Keep precision, units and formatting exactly as produced by the formatters.
- Monospace (`TextAppearance.App.Mono`) only for raw NMEA, debug output, fixed-width technical text.
- Applied in phase 1: GNSS toolbar values, Home field-status detail and receiver lines, Home coordinate-settings
  values, Home counts, GNSS Status report / receiver detail values (`item_receiver_detail_row`), FixBadgeView.

## How to add new UI

1. Text colour: `?attr/colorOnSurface` (primary) or `?attr/colorOnSurfaceVariant` (secondary). Avoid `android:alpha`
   on text; if used, the composite must still pass 4.5:1.
2. Status: `?attr/colorStatus<Role>` for text/icons/dots on any surface; for a filled badge use
   `colorStatus<Role>Container` + `colorOnStatus<Role>Container`. In Kotlin:
   `MaterialColors.getColor(view, R.attr.colorStatusWarning)`, or from a pure mapper return a `StatusTone`
   (`ui/theme/StatusTone`) and resolve it in the view with `tone.foreground(view)` / `tone.container(view)` /
   `tone.onContainer(view)`. Always pair with words or an icon.
3. Backgrounds: `?attr/colorSurface`, `?android:attr/colorBackground`, `?attr/colorSurfaceContainer*`. Never
   `@android:color/background_light`, `@color/white` or raw hex.
4. On the GNSS strip: only `toolbar_*` roles. Over the camera: only `ar_overlay_*` roles. Charts: only `chart_*`.
5. Text size: a `TextAppearance.App.*` style; nothing meaningful below 12 sp; no fixed heights that clip at 1.3×/1.5×
   font scale.
6. Add a contrast check to `PaletteContrastChecks` for any new colour pair and a row to the allowlist only for a
   documented fixed colour.

## Tests

- `ui/theme/WcagTest` — pure WCAG helper (`Wcag`: relative luminance, contrast, alpha compositing).
- `ui/theme/PaletteContrastLightTest` / `PaletteContrastNightTest` — resolve the real resources through
  `Theme.SurveyingApp` (night via `@Config(qualifiers = "night")`) and assert: status foregrounds on every surface
  ≥ 4.5, on-container ≥ 4.5, RTK chip colours, Material text roles (incl. onSurface at alpha 0.7), outline / primary
  indicators ≥ 3, toolbar roles and every toolbar level on `#0F3554` ≥ 4.5, warning pill text ≥ 4.5 and pill edges
  ≥ 3, AR overlay status text on the heaviest scrim, model-viewer canvas text. Each check prints a
  `contrast[theme] …` line.
- `ui/theme/TypeScaleLightTest` / `TypeScaleNightTest` — named styles resolve with the documented sizes and a text
  colour, ≥ 12 sp, tabular figures where required, theme label-small mapped.
- `ui/theme/HardcodedColorGuardTest` — no raw hex / `@android:color/*` / `@color/white|black` in any layout; Kotlin
  scanned for `Color.parseColor/rgb/argb`, `toColorInt`, 8-digit `0x…` literals and `android.R.color.*` with an exact
  allowlist of the documented fixed exceptions.
- Phase 2 contrast additions (both themes): `StatusTone` resolves to the roles and badge text on its container ≥ 4.5;
  chart text ≥ 4.5 and axis / series / constellation / halo ≥ 3 on surface and surfaceContainer; AR label pill and
  AR chip text ≥ 4.5 over a white frame; map label on its halo ≥ 4.5; coordinate-list selection text ≥ 4.5;
  disabled text (onSurface 38 %, WCAG-exempt) ≥ 2.0 floor.
- `ui/theme/FontScalePreviewTest` — font scale 1.0 / 1.3 / 1.5 renders + clipping / word-split / touch-target audit
  (see [Font scaling](#font-scaling)).
- Previews (`@GraphicsMode(NATIVE)`, PNGs in `app/build/previews/`): `GnssToolbarPreviewTest` (toolbar states,
  GNSS Status report), `ReceiverUiPreviewTest` (Home field status, receiver card / settings), `DesignSystemPreviewTest`
  (palette swatches, type scale, FixBadgeView, legacy Settings layout, model viewer chrome), light and dark.

## Before / after contrast (key pairs)

| Pair | Before | After |
|---|---|---|
| Warning text on white (light) | `#E65100` 3.79 | `#9A4700` 6.41 |
| Warning text on surfaceContainerHigh (light) | 3.04 | 5.14 |
| Success text on surfaceContainerHigh (light) | `#2E7D32` 4.11 | `#1E6B2A` 5.27 |
| Error text on dark surface `#101820` | `#D32F2F` 3.59 | `#FFB4AB` 10.54 |
| Info text on dark surface | `#1976D2` 3.89 | `#9CC8FF` 10.33 |
| Error text on dark surfaceContainerHigh | 2.94 | 8.61 |
| Info text on dark surfaceContainerHigh | 3.18 | 8.44 |
| RTK Fix chip on dark surface (`status_fix`, no night value) | `#2E7D32` 3.49 | `#81C995` 9.14 |
| No-fix chip on dark surface | `#C62828` 3.18 | `#FFB4AB` 10.54 |
| Single chip on dark surface | `#546E7A` 3.31 | `#B0BEC5` 9.39 |
| Toolbar RTK Fix (light theme) | `#2E7D32` 2.47 | `#81C995` 6.47 |
| Toolbar Fix (dark theme) | `#43A047` 3.84 | 6.47 |
| Toolbar Float / warning (light / dark) | 3.34 / 4.11 | `#F7C66A` 7.99 |
| Toolbar error (light / dark) | 2.25 / 2.54 | `#FFAAA5` 6.97 |
| Toolbar info / DGPS (light / dark) | 2.20 / 2.75 | `#9CC8FF` 7.31 |
| Toolbar level NONE | `#AAAAAA` 5.45 | `#B8C7D6` 7.35 |
| Toolbar battery fill red | `#F44336` 3.44 | 6.97 |
| Critical pill edge on strip | 2.25 | 6.97 (outline) |
| AR GNSS chip status, light theme (on 73 % black over white) | `#2E7D32` ≈ 1.9 | ≥ 6.4 |
| Model viewer loading text, dark theme (on `#EEEEEE`) | `#E2E4E7` 1.10 | `#1A1C1E` 14.73 |
| Model viewer spinner, dark theme | `#8FC5FF` 1.56 | `#1A56A4` 6.22 |
| FixBadgeView "orange" (`holo_orange_dark` on white) | 2.39 | 6.41 |
| FixBadgeView grey supporting text (`darker_gray`) | 2.23 | ≥ 4.5 (onSurfaceVariant) |
| **Phase 2** | | |
| Coordinate badge "sub-metre" (white on `#F9A825`) | 1.97 | on-container on warning container 12.87 (dark 7.32) |
| Coordinate badge FLOAT / degraded (white on `#E65100` / `#EF6C00`) | 3.79 / 3.08 | ≥ 7.3 (on-container) |
| Model readiness chip, dark (white on `#81C995`) | 1.96 | 7.82 (success on-container) |
| Self-test pass / warning (`holo_green_dark` / `holo_orange_dark` on white) | 3.43 / 2.39 | 6.4+ (status roles) |
| Developer Maps "warn" (`#F9A825` on white) | 1.97 | `#9A4700` 6.41 |
| Developer DENIED / RTK no-fix on dark card (`#C62828`) | 3.18 | `#FFB4AB` 10.54 |
| Model card edit / delete icons on dark card (`#0D47A1` / `#B71C1C`) | 2.07 / 2.72 | colorPrimary / colorError ≥ 8 |
| Stakeout distance in tolerance / near (`#4CAF50` / `#FF9800` on white) | 2.78 / 2.16 | 6.4+ (status roles) |
| Pin sheet FLOAT (`#2196F3` on white, and blue meant "float") | 3.12 | warning `#9A4700` 6.41 |
| Chart axis (light) `#BDBDBD` | 1.88 | `#71787E` 4.48 |
| Chart unused bar (light) `#9E9E9E` | 2.68 | `#757575` 4.61 |
| Skyplot / SNR-bar amber (light) `#FFC107` | 1.63 | warning `#9A4700` 6.41 |
| Skyplot constellation strokes (light, e.g. BeiDou `#FFE082`) | 1.29–1.75 | 4.8–8.0 |
| Coordinate-list placeholder (white on `#BDBDBD`) | 1.88 | onSurfaceVariant on surfaceContainerHighest ≥ 6 |
| AR label distance line (`#CCCCCC` on pill over white) | 6.36 (9 dp) | 6.36 at 12 sp |

## Audit

Inventory of theme-bypassing colour and type in `app/src/main` as of `main` 9947ea73, worked in two phases.
✅ = fixed (phase 1 or 2), **F** = intentional fixed colour (kept, named and validated, see
[Intentional exceptions](#intentional-exceptions)), **T** = theme-aware. Nothing in the queue is open any more except the
items under [Remaining / device-only](#remaining--device-only).

### Layouts: hex / framework colours

| File | Hit | Class | Status |
|---|---|---|---|
| `layout/fragment_settings.xml` | `#10111111`, `#08000000`, `@android:color/background_light` | T | ✅ p1 surface containers |
| `layout/activity_model_viewer.xml` | `#EEEEEE` loading overlay; white FAB icons | F / T | ✅ p1 `model_viewer_*`, `?attr/colorOnPrimary` |
| `drawable/bg_rounded_tab_floating.xml` | `@color/white` behind onSurface text | T | ✅ p1 `?attr/colorSurface` |
| `layout/item_token.xml` | `@android:color/white` icon tint | F (navy strip) | ✅ p1 `toolbar_on_primary` |
| `layout/fragment_coordinate_detail.xml` | 4 × white badge text | T | ✅ p2 on-container from `StatusTone` (set in code), `StatusLabel`, no bold |
| `layout/item_model.xml` | white readiness-badge text on `bg_model_badge` | T | ✅ p2 on-container from the verdict tone |
| `layout/item_point.xml` | `@android:color/white` row background | T | ✅ p2 `?attr/colorSurface` (layout currently unused) |

`HardcodedColorGuardTest` now allows **no** layout hits.

### Drawables / colour selectors

| File | Hit | Class | Status |
|---|---|---|---|
| `drawable/bg_ar_chip.xml`, `ripple_ar_toolbar_btn.xml` | `#CC000000`, `#40FFFFFF` | F (camera / map) | ✅ p2 `ar_overlay_bg_dark`, `ar_overlay_ripple` |
| `color/nav_ripple_color.xml` | light-only `#1E155DA8` / `#1F41484D` | T | ✅ p2 `?attr/colorPrimary` / `colorOnSurfaceVariant` at 12 % (light unchanged) |
| `drawable/bg_model_badge.xml` | `#FFFFFFFF` | — | shape only; always tinted (`backgroundTint`) |
| `drawable/battery_level.xml`, `ic_bolt.xml` | fixed fills, tinted in code | F | ✅ p1 |
| `drawable/bg_token.xml` | `#33FFFFFF` / `#14FFFFFF` | F (navy strip) | kept |
| `drawable/side_nav_bar.xml`, `badge_bg.xml`, `bg_rounded_tab.xml`, `thumbnail_border.xml`, `thumbnail_placeholder_bg.xml`, `bg_device_box.xml`, `circle_background.xml`, `panel_handle_grip.xml`, `panel_edge_shadow.xml` | assorted fixed colours | — | **unreferenced** by any layout or Kotlin (checked in p2); left untouched, no visual effect |
| `ic_ar_altitude`, `ic_ar_debug`, `ic_ar_menu` (untinted, white fill) | `#FFFFFF` | F | only used on the dark AR tool card — verified |
| `ic_camera_orange` | orange | — | only referenced from commented-out XML |
| `ic_stat_location` | grey fills | — | tinted `?attr/colorOnSurface` at use; notification icon is system-tinted |
| `ic_launcher_*`, `rs2_client_*` | brand artwork | F | kept |

### Kotlin: programmatic colours

| File | Hits | Status |
|---|---|---|
| `MainActivity.kt` `levelColor`, `GnssToolbarRenderer.kt`, `GnssStatusSheet.kt`, `FixBadgeView.kt` | | ✅ p1 |
| `ui/home/HomeFragment.kt`, `ui/models/readiness/ReadinessUi.kt` | `app_*` aliases | ✅ p1 aliases; p2 adds `ReadinessStyle.verdictTone` |
| `ui/theme/StatusTone.kt` (new, p2) | — | enum of the 5 roles: `foreground / container / onContainer(view or context)` |
| `ui/viewpoints/CoordinateDetailUiMapper.kt` | 16 fixed fills + white text | ✅ p2 `BadgeUi.tone`; chip = container + on-container |
| `ui/models/ModelsAdapter.kt` | `#0D47A1` / `#B71C1C` icon tints (2.1 / 2.7:1 on dark card) | ✅ p2 colorPrimary / colorError; readiness chip tone |
| `ui/models/ModelsFragment.kt`, `ModelPickerActivity.kt` | holo refresh colours | ✅ p2 colorPrimary / colorSecondary on surfaceContainerHigh |
| `ui/models/ModelDiagnosticsDialogFragment.kt` | white verdict text | ✅ p2 verdict tone container + on-container |
| `ui/viewpoints/SimpleCoordinatesAdapter.kt` | avatar `#BDBDBD` + white (1.9:1); legacy accent `md_primary_40` | ✅ p2 surfaceContainerHighest + onSurfaceVariant; colorPrimary |
| `ui/development/DevelopmentFragment.kt` | 13 raw sizes, 7 fixed status colours | ✅ p2 type-scale styles, `StatusTone` |
| `ui/settings/SelfTestDisplay.kt` | holo colours (2.4–3.4:1) | ✅ p2 returns `StatusTone` (pure, tested) |
| `ui/openinar/PinInspectBottomSheet.kt` | FLOAT blue / DGPS orange (inconsistent) | ✅ p2 same roles as everywhere (FLOAT warning, DGPS info) |
| `ui/openinar/OpenInARFragment.kt` | `android.R.color.white` toggles / Single | ✅ p2 `ar_overlay_text`; test-cube marker colour is stored data (F) |
| `ui/openinar/CoordinateLabelOverlay.kt`, `OffScreenPinIndicatorOverlay.kt` | `0xCC111111`, cyan, white, `#CCCCCC`; 11/9/8 dp | ✅ p2 named `ar_overlay_*`; 12 sp (scales with font) |
| `ui/common/SkyplotView.kt`, `SnrBarView.kt`, `SatelliteSignalChartView.kt` | fixed series / axes; 10–11 sp | ✅ p2 `chart_*` roles, 12 sp (see [Charts](#charts)) |
| `ui/rendermap/StakeoutController.kt` | distance colours `#4CAF50/#FF9800/#F44336` on the card | ✅ p2 `StatusTone`; stakeout line stays F |
| `ui/rendermap/MapOverlayController.kt` | label `0xB3000000` + white; boundary lines | ✅ p2 `map_label_*`; lines F |
| `ui/rendermap/LiveTrackingController.kt`, `CoordinateMarkerLayer.kt`, `CoordinateDetailFragment.kt` marker bitmap | map polylines / marker border | F (kept, guard allowlist) |
| `ui/rendermap/CoordinateToggleAdapter.kt` | `Color.WHITE` behind model thumbnails | F: thumbnails are rendered with a transparent background; a fixed light backdrop keeps dark geometry visible in both themes |
| `ui/settings/categories/CoordinatesSettingsController.kt` | fallback `0xFF7D5260` | ✅ p2 no fallback literal |
| `data/export/CoordinateBackup.kt`, `ImportCoordinateBackupUseCase.kt`, `AddCoordinateDialogFragment.kt`, `CoordinateInfoBottomSheet.kt` | default user marker colour | F (stored data) |
| `service/Notif.kt` | `Color.GREEN` LED | F |

`HardcodedColorGuardTest` now also scans Kotlin (`Color.parseColor/rgb/argb`, `toColorInt`, 8-digit `0x…` literals,
`android.R.color.*` except transparent) with an exact per-file allowlist of the F items above.

### Text sizes and fonts

- Raw `android:textSize` in layouts: ✅ p2 all replaced by `TextAppearance.App.*` / Material 3 styles except
  `item_token.xml` (toolbar, phase 1, pinned by `ToolbarFitTest`) and the decorative `item_sep.xml` "|" (14 sp on the
  strip). `item_map_point` 10 → 12 sp; `fragment_open_in_ar` chips 11 → 12 sp; dialogs / sheets on the scale.
- Kotlin sizes: ✅ p2 DevelopmentFragment (styles), charts (12 sp), AR canvas labels (12 sp, sp-based).
  `MapOverlayController` labels stay 12 dp (bitmap drawn into a map marker; size matched to the map, not the font
  scale). `SnrBarView` title 14 sp (unused view).
- Monospace: only raw NMEA (`dev_page_raw_nmea`, NMEA history), the GSV per-constellation table and the AR debug
  overlay. Readings that were monospace (pin sheet lat/lon, map selected coordinates, stakeout ΔN/ΔE, move-panel
  offset) now use `Reading` + `tnum`.
- Bold: removed from chips / badges (`StatusLabel` medium), section headers (`CardSectionLabel`), dialog titles,
  sheet titles and developer row labels. Kept for live values (toolbar, stakeout distance / azimuth, AR labels).
- Tabular numerals added in p2: coordinate list + detail rows + stat tiles, pin sheet, map selected card, stakeout,
  AR chips / labels / move panel, developer live readings, model-readiness details, Home counts, nav badge.

### Alpha text

onSurface at 0.7 passes on every surface (tested). p2 removed the 0.85 alpha on the coordinate list lat/lon line;
the remaining settings-subtitle alphas are unchanged (they pass; converting them is cosmetic churn).

## Charts

Charts are drawn on the card surface (`colorSurface`, sometimes `surfaceContainer`) and use only `chart_*`
resources (light in `values/`, dark in `values-night/`):

| Role | Light | Dark | Rule |
|---|---|---|---|
| `chart_text` / `chart_title` (axis labels, PRNs, legend, titles) | `#424242` / `#1A1C1E` | `#E2E4E7` / `#FFFFFF` | ≥ 4.5:1, 12 sp |
| `chart_axis` | `#71787E` | `#8B9198` | ≥ 3:1 |
| `chart_grid` | `#C5CED8` | `#41484D` | decorative |
| SNR series `chart_bar_strong / medium / weak` | status success / warning / error | same (night values) | ≥ 3:1, legend names them |
| `chart_bar_unused` | `#757575` | `#8B9198` | ≥ 3:1 |
| `chart_used_halo` (skyplot "used") | `#1A1C1E` | `#FFFFFF` | ≥ 3:1 |
| constellation strokes GPS / GLO / GAL / BDS / QZSS / SBAS / IRNSS | `#1565C0 #8E24AA #2E7D32 #8D6E00 #5E35B1 #546E7A #C43E00` | the original pastels `#90CAF9 #CE93D8 #A5D6A7 #FFE082 #B39DDB #B0BEC5 #FFAB91` | ≥ 3:1, hues kept distinct |

Skyplot dots keep their SNR-dependent alpha (decorative fill); the constellation stroke and halo carry the
boundary. Constellations are also named by the PRN prefix (G/R/E/B/Q/S/I).

## Overlay conventions (phase 2)

- **AR canvas labels**: pill `ar_overlay_label_bg` (80 % `#111111`), text `ar_overlay_text`, distance line
  `ar_overlay_text_secondary`, model-linked accent `ar_overlay_model_accent` (cyan). Measured on the pill composited
  over a white frame: white 10.2, `#CCCCCC` 6.4, cyan 6.6. Text 12 sp, follows the font scale, tabular distance.
- **AR chips** (`bg_ar_chip` = `ar_overlay_bg_dark`): `TextAppearance.App.AR.Overlay` (12 sp) + `tnum`; status text
  `ar_overlay_*` ≥ 6.4 over white. The GNSS and AR-quality chips are stacked in a column so they can't overlap.
- **Tool cards** (AR + map): width wrap_content, min 72 dp, labels ≤ 96 dp so long labels wrap to two lines.
- **Map point labels**: `map_label_text` on `map_label_bg` (70 % black halo), 8.6:1 over a white tile; satellite
  tiles are darker, so contrast only rises. Map lines and marker borders are fixed hues (F) chosen for both light
  and satellite tiles; not renderable in tests.

## Font scaling

`FontScalePreviewTest` (light + dark) renders at font scale 1.0, 1.3 and 1.5: Home, GNSS toolbar Fix / Float /
warning (411 and 924 dp), GNSS Status report, Settings → Receiver (Advanced expanded), coordinate list rows and
detail (badges, stat tiles, rows), model cards, model viewer chrome, developer raw-NMEA page and GNSS charts
(skyplot + signal charts), capture dialog, pin-inspect bottom sheet, visible-models sheet, AR control layout and map
control layout (camera / map imagery not renderable). Each tree is audited for vertically / horizontally clipped
text (not ellipsized), text cut off by a fixed-size ancestor, and 48 dp touch targets on the listed controls; the test
fails on any finding. PNGs: `app/build/previews/fontscale/<screen>_<10|13|15>x_<theme>.png`, findings in
`findings_<theme>.txt`.

Results (Robolectric, 411 dp phone frame unless noted; the SM-X940 tablet is wider, so these are worst cases).
"Before" = the same test run against the phase-1 sources (`feature/visual-consistency`): 9 sub-48 dp coordinate-list
overflow buttons (40 dp), the receiver-details and stakeout-title word splits and the capture-dialog clip below; the
stat-tile split was found with the plain row.

| Found | Fix |
|---|---|
| AR / map tool cards: at 1.5x "Location" filled the fixed 72 dp card edge to edge (before render); any longer one-word label (e.g. a map-type name like "Satellite") would split | card wrap_content, items wrap_content with 72 dp minimum, labels ≤ 96 dp (two lines) — 1.0x unchanged |
| AR GNSS + AR-quality chips at fixed 68 / 98 dp offsets would overlap once the first wraps | stacked in a vertical column |
| Coordinate detail stat tiles ("±0.012 m", "41.24538710°") split mid-number at 1.5x (lat/lon tiles already tight on a phone at 1.0x) | `AdaptiveTileRow`: stacks the tiles when a value can't fit its column; unchanged when it fits |
| Receiver card: "Receive / r details" at 1.5x | action row → ConstraintLayout `Flow` (wraps whole buttons) |
| Capture dialog model button fixed 48 dp height clipped text at 1.5x | wrap_content, minHeight 48 dp |
| Visible-models sheet: three weighted buttons overflowed (minWidth 88 dp) and the two-line one was baseline-shifted out of the row | minWidth 0, `baselineAligned=false` |
| Stakeout panel title split mid-word at 1.5x (panel is 72 dp inset each side) | single line, ellipsized (the icon + panel content identify it) |
| Nav drawer badge fixed 22 dp height | wrap_content, 22 dp minimum |
| Coordinate list overflow button 40 × 40 dp; map selected-point close 40 dp | 48 × 48 dp (icon unchanged, padding 12 dp) |
| Signal-chart legend (3×2 overview mode only) ran off the right edge with 12 sp labels | stops before an item that doesn't fit (overview mode is not used by the app's developer page, which shows per-constellation charts) |

After the fixes the audit reports **no** clipped / word-split text and no sub-48 dp checked controls at 1.0 / 1.3 /
1.5 in either theme. Observed but accepted: the GNSS toolbar at 411 dp and 1.5x ellipsizes the warning pill text
("Corr. st…") — the filled red pill + ⚠ icon still signal the warning and the chevron opens the full report (the
toolbar's priority / compaction rules are phase-1 logic and unchanged); long settings descriptions and dialog text
wrap to more lines and the pages scroll. On a 891 dp-tall phone at 1.5x the expanded map tool card reaches the
stakeout panel (both optional overlays; not an issue on the tablet).

## Intentional exceptions

Fixed colours that stay fixed (all named, all validated where text is involved):

1. GNSS strip `#0F3554` and its `toolbar_*` roles — same in both themes by design.
2. AR camera overlays (`ar_overlay_*`) — light-on-dark over scrims; theme surfaces would vanish over a bright frame.
3. Model-viewer canvas (`model_viewer_*`) — matches the fixed light Filament clear colour.
4. Map graphics (polylines, accuracy circle, stakeout line, marker borders, label halo) — drawn over map tiles,
   not themed UI.
5. User marker colours (stored data) and the test-cube marker.
6. Model thumbnail backdrop (white) in the coordinate toggle list — transparent thumbnails need a light backdrop.
7. Chart constellation hues — data series; per-theme variants, not theme roles.
8. Notification LED, launcher / brand artwork.

## Remaining / device-only

- Outdoor legibility on the SM-X940 in sunlight: toolbar roles, AR chips / labels over real camera imagery, map
  labels over satellite tiles. Not verifiable in Robolectric.
- AR GNSS chip is tappable (opens the GNSS Status report) but ~28 dp tall; it sits in the AR chip stack, so enlarging
  its hit area is left for a device review (the toolbar's own report entry is hidden in AR).
- Unreferenced drawables listed above could be deleted in a cleanup change (not a visual change).
