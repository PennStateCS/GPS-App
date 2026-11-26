# SurReal AR design system (colour, contrast, typography)

How colour and text are chosen in the app, what is enforced by tests, and how to add new UI. Phase 1
(`feature/visual-p1`) built the system and fixed the core theme-bypassing components; phase 2 applies it to the
remaining screens using the [Audit](#audit) list as its work queue.

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
- **Map overlays** (`map_overlay_bg`, `TextAppearance.App.Map.*`): near-white panel with dark text; reviewed in
  phase 2.

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
   `MaterialColors.getColor(view, R.attr.colorStatusWarning)`. Always pair with words or an icon.
3. Backgrounds: `?attr/colorSurface`, `?android:attr/colorBackground`, `?attr/colorSurfaceContainer*`. Never
   `@android:color/background_light`, `@color/white` or raw hex.
4. On the GNSS strip: only `toolbar_*` roles. Over the camera: only `ar_overlay_*` roles.
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
- `ui/theme/HardcodedColorGuardTest` — ratchet: no new raw hex / `@android:color/*` / `@color/white|black` in
  layouts; the allowlist is the remaining phase-2 layout queue.
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

## Audit

Inventory of theme-bypassing colour and type in `app/src/main` as of `main` 9947ea73. **Phase 1 fixed** items are
marked ✅; everything else is the phase-2 queue. Classification: **F** = intentional fixed (camera / map / brand
graphic / data series — keep, but move to a named resource and validate), **T** = should be theme-aware.

### Layouts: hex / framework colours

| File | Hit | Class | Status |
|---|---|---|---|
| `layout/fragment_settings.xml` | `#10111111`, `#08000000`, `@android:color/background_light` (legacy layout, only a `tools:layout` preview) | T | ✅ surface containers |
| `layout/activity_model_viewer.xml` | `#EEEEEE` loading overlay; white FAB icons on colorPrimary; `?attr/colorOnSurface` / `colorError` text on the fixed grey | F (matches Filament skybox) / T | ✅ named `model_viewer_*`, icons `?attr/colorOnPrimary` |
| `drawable/bg_rounded_tab_floating.xml` | `@color/white` behind `?attr/colorOnSurface` text (white-on-white in dark) | T | ✅ `?attr/colorSurface` |
| `layout/item_token.xml` | `@android:color/white` icon tint | F (navy strip) | ✅ `toolbar_on_primary` |
| `layout/fragment_coordinate_detail.xml` | 4 × `@android:color/white` badge text (fills set in code) | T | phase 2 (on-container roles) |
| `layout/item_model.xml` | `@android:color/white` badge text on `bg_model_badge` (white fill!) | T | phase 2 |
| `layout/item_point.xml` | `@android:color/white` icon tile | T | phase 2 |

### Drawables / colour selectors

| File | Hit | Class |
|---|---|---|
| `drawable/badge_bg.xml` | `#607D8B` | T → neutral container |
| `drawable/bg_model_badge.xml` | `#FFFFFFFF` | T |
| `drawable/bg_rounded_tab.xml` | `@color/white` (unused?) | T / remove |
| `drawable/thumbnail_border.xml`, `thumbnail_placeholder_bg.xml` | `#BDBDBD`, `#FFFFFF`, `#F0F0F0` | T → outlineVariant / surfaceContainer |
| `drawable/bg_device_box.xml` | `@android:color/darker_gray` stroke (2.3:1) | T → colorOutline |
| `drawable/circle_background.xml` | `@android:color/holo_blue_light` | T → primary container |
| `drawable/side_nav_bar.xml` | teal gradient `#4DB6AC/#009688/#00695C` | F (brand header) — check text on it |
| `drawable/panel_handle_grip.xml`, `panel_edge_shadow.xml` | translucent black | T → onSurfaceVariant / outline (invisible in dark) |
| `drawable/bg_token.xml` | `#33FFFFFF` / `#14FFFFFF` | F (on navy strip) |
| `drawable/bg_ar_chip.xml`, `ripple_ar_toolbar_btn.xml` | `#CC000000`, `#40FFFFFF` | F (AR/map overlay) → `ar_overlay_bg_dark` |
| `drawable/bg_map_rounded.xml` | `#00FFFFFF` | F (transparent) |
| `color/nav_ripple_color.xml` | `#1E155DA8`, `#1F41484D` | T (light-tuned; check dark) |
| `drawable/battery_level.xml`, `ic_bolt.xml` | fixed fills, tinted in code | F (toolbar) ✅ fill now `toolbar_*` |
| ~60 vector icons (`ic_*`) | `@android:color/black|white` or hex `fillColor` | usually tinted at use (`app:tint`); phase 2: verify every untinted use (`ic_camera_orange`, `ic_delete_red`, `ic_edit_blue`, `ic_device_status`, `ic_stat_location`, `ic_ar_*`, `ic_viewer_*`) |
| `ic_launcher_*`, `rs2_client_*` | brand artwork | F |

### Kotlin: programmatic colours

| File | Hits | Class | Status |
|---|---|---|---|
| `MainActivity.kt` `levelColor` | `app_*` on navy (2.2–4.1:1), `darker_gray` | T (toolbar roles) | ✅ `GnssToolbarColors` |
| `ui/toolbar/GnssToolbarRenderer.kt` | battery `0xFF4CAF50/F44336/FFC107`; pill fills | F (strip) | ✅ toolbar roles + pill outline |
| `ui/toolbar/report/GnssStatusSheet.kt` | `app_warning` / `app_success` / colorError | T | ✅ `?attr/colorStatus*` |
| `ui/components/FixBadgeView.kt` | `background_light`, black, `darker_gray`, `holo_*`, `Color.GRAY`, 12/10 sp | T | ✅ theme attrs, 14/12 sp, tnum |
| `ui/openinar/OpenInARFragment.kt` | `app_*` status on AR chips (≈ 1.9:1 in light theme), `android.R.color.white`, `0xFFFF8C0D` | F (overlay) | ✅ status → `ar_overlay_*`; white / orange phase 2 |
| `ui/home/HomeFragment.kt` | `app_*` severity (aliases) | T | ✅ via aliases; coordinate values Reading/tnum |
| `ui/models/readiness/ReadinessUi.kt` | `app_*` (aliases) | T | ✅ via aliases |
| `ui/models/ModelsAdapter.kt` | `#0D47A1`, `#B71C1C` icon tints (fail in dark) | T | phase 2 → colorPrimary / colorError |
| `ui/models/ModelsFragment.kt`, `ModelPickerActivity.kt` | `holo_purple`, `holo_blue_dark` (refresh spinner) | T | phase 2 → colorPrimary/secondary |
| `ui/models/ModelDiagnosticsDialogFragment.kt` | `android.R.color.white` verdict text | T | phase 2 |
| `ui/viewpoints/CoordinateDetailUiMapper.kt` | 16 fixed badge fills (`0xFF1565C0`, `0xFF2E7D32`, `0xFFE65100`, `0xFFF9A825`, …) with white text | T | phase 2 → status containers / on-container (`F9A825` + white ≈ 1.9:1) |
| `ui/viewpoints/SimpleCoordinatesAdapter.kt` | fallbacks `Color.BLACK/GRAY`, avatar `#BDBDBD` + white | T (fallbacks ok) | phase 2: avatar |
| `ui/development/DevelopmentFragment.kt` | 13 raw `textSize` (11–14), `0xFF2E7D32/C62828/F9A825/1565C0/FF5722/9E9E9E/808080` | T | phase 2 → status roles + type styles |
| `ui/settings/SelfTestDisplay.kt` | `holo_green_dark` (3.4:1), `holo_orange_dark` (2.4:1), `holo_red_dark`, `darker_gray` | T | phase 2 → status roles (pure mapper; keep testable) |
| `ui/common/SkyplotView.kt` | constellation series colours, `Color.WHITE`, 11 sp labels | F (series) / T (labels) | phase 2: keep series, theme axes/labels, ≥ 12 sp |
| `ui/common/SnrBarView.kt` | bar colours, night-aware axis/text alpha | F (series) | phase 2: validate bars ≥ 3:1 in both themes |
| `ui/common/SatelliteSignalChartView.kt` | 10 sp axis labels | T | phase 2: ≥ 12 sp |
| `res/values/colors.xml` `chart_*` | light/dark chart roles | T | phase 2: verify chart_text / axes |
| `ui/openinar/CoordinateLabelOverlay.kt`, `OffScreenPinIndicatorOverlay.kt` | `0xCC111111`, cyan `0xFF00E5FF`, white, `0xFFCCCCCC`; 11/9/8 sp × dp | F (camera) | phase 2: sizes ≥ 12 sp-equivalent, centralise colours |
| `ui/openinar/PinInspectBottomSheet.kt` | FIX/FLOAT/DGPS colours (FLOAT blue, DGPS orange — inconsistent) | T | phase 2 → status roles |
| `ui/rendermap/StakeoutController.kt` | `0xFF4CAF50/FF9800/F44336`, `0xCCFF5722` | F (map lines) / T (text at 465) | phase 2 |
| `ui/rendermap/MapOverlayController.kt`, `LiveTrackingController.kt`, `CoordinateMarkerLayer.kt` | map polylines / labels | F (map) | phase 2: validate on satellite + light tiles |
| `ui/rendermap/CoordinateToggleAdapter.kt`, `CoordinateInfoBottomSheet.kt` | `Color.WHITE` icon bg, default `0xFF2196F3` | T / F (user colour) | phase 2 |
| `ui/settings/categories/CoordinatesSettingsController.kt` | fallback `0xFF7D5260` (purple M3 default) | T fallback only | phase 2 (low) |
| `data/export/CoordinateBackup.kt`, `ImportCoordinateBackupUseCase.kt`, `AddCoordinateDialogFragment.kt` | default user marker colour | F (stored data) | keep |
| `service/Notif.kt` | `Color.GREEN` LED | F | keep |

### Text sizes and fonts

- Raw `android:textSize` in layouts (41): `dialog_add_point` (4 × 12), `dialog_model_diagnostic` (22/15/16/14/13),
  `fragment_coordinate_detail` (2 × 12), `fragment_home` + sw600dp (counts 18), `fragment_open_in_ar` (16/12/**11**/13/**11**),
  `fragment_render_map` (18), `item_ar_model_row` (16/13), **`item_map_point` (10)**, `item_readiness_check` (16/15/14),
  `item_sep` (14), `item_token` (✅ now styles), `nav_drawer_badge` (12), `sheet_ar_models` (18/13/12/12/13/13/13/12),
  `styles_model_card.xml` (13). Phase 2: replace with `TextAppearance.App.*`; fix the 10–11 sp ones.
- `?attr/textAppearanceLabelSmall` (11 → 12 sp ✅ via theme) used in `dialog_add_point`, `fragment_coordinate_detail`,
  `fragment_render_map` (×6), `item_coordinate_toggle`, `item_gnss_stat_tile`, `item_receiver_detail_row`,
  `view_receiver_card`: phase 2 should screenshot these for wrapping.
- `TextAppearance.App.AR.OverlaySmall` 9 → 12 sp ✅ (AR tool card in `fragment_open_in_ar`, map tools in
  `fragment_render_map`, `view_ar_move_panel`): phase 2 screenshots; "My Location" may wrap to two lines in the 72 dp card.
- Kotlin `textSize`: `DevelopmentFragment` (13 hits, 11–14), `SkyplotView` (11), `SatelliteSignalChartView` (10/14),
  `SnrBarView` (14), `CoordinateLabelOverlay` / `OffScreenPinIndicatorOverlay` (11/9/8 × dp), `MapOverlayController` (12 × dp),
  `SimpleCoordinatesAdapter` avatar.
- `fontFamily="monospace"` in `view_ar_move_panel`, `fragment_render_map` (×2), `fragment_open_in_ar`,
  `dev_page_raw_nmea`, `bottom_sheet_pin_inspect`: keep for raw NMEA / debug; readings (render map stakeout values,
  move panel) should move to `Reading` + tnum.
- 43 × `textStyle="bold"`: review for unnecessary bold in phase 2.

### Alpha text

80 `android:alpha="0.x"` attributes in layouts (mostly 0.7 on settings subtitles / metric labels:
`content_settings_*`, `item_settings_row`, `dialog_capture_coordinate`, `fragment_render_map`, …) plus the
`Settings.Row.Subtitle` and `CaptureMetricLabel` styles. onSurface at 0.7 passes (light 6.3:1 on white; tested on all
surfaces in both themes), but phase 2 should prefer `?attr/colorOnSurfaceVariant` without alpha.

## Open items for phase 2

1. Work the audit queue above; lower the `HardcodedColorGuardTest` allowlist as layouts are fixed (and consider a
   Kotlin-side guard once the queue is short).
2. Coordinate detail / list badges → status container + on-container roles (`CoordinateDetailUiMapper`).
3. Charts / skyplot / SNR: keep series hues, theme the axes and labels, ≥ 12 sp.
4. AR / map overlays: label sizes (Canvas overlays 8–11 sp), `item_map_point` 10 sp, AR model sheets; validate on
   camera and satellite imagery.
5. Font-scale (1.3×, 1.5×) screenshots for Home, toolbar, report, settings, dialogs, AR controls.
6. Device-only checks: outdoor legibility of the toolbar roles on the SM-X940, AR overlay readability in sunlight.
