# Graphite & Cobalt theme: colour specification (design stage)

Status: **approved 2026-11-26 with adjustments (§6); implemented on `feature/graphite-cobalt-theme` (not merged).**
The adjustments: cyan info role, ⋮ overflow icon fix, receiver-card FLOAT → warning / DGPS → info, AR success and the
model-viewer spinner follow the approved palette; system status-bar insets deferred.
Scope: colour values and colour-attribute references only. No layout, typography, spacing, navigation, toolbar content,
GNSS / coordinate / AR / map / receiver / capture behaviour changes.

Inputs: the user brief (Graphite & Cobalt), `docs/design-system.md` (current palette, roles, exceptions), the current
resources on `main` @ `fe471f9b` (`values/colors.xml`, `values-night/colors.xml`, `values*/themes.xml`, `attrs.xml`,
`styles.xml`) and tablet screenshots of the current app (SM-X940, light + dark).

Previews and contrast numbers come from two throwaway test classes (`GraphiteCobaltPreviewTest`: real layouts,
Robolectric native graphics, tablet `sw600dp` qualifiers; `GraphiteCobaltContrastReportTest`: the existing `Wcag` helper
on the resolved theme), run on `main` ("before") and on `feature/graphite-cobalt-theme` ("after"). They are not
committed to the feature branch; the first proposal round lives on `design/graphite-cobalt-preview` (never merged).

## 0. Summary of the direction

- **Graphite** neutrals for every background and surface (cool grey, low chroma), **cobalt** only where something is
  interactive or selected: primary / tonal buttons, text buttons and links, the selected navigation item, focus,
  switches and other active controls.
- **Neutral text** for section headings, field labels, descriptions and ordinary values (today many headings and two
  counts are `colorPrimary`: implementation items in §2.3).
- Dark mode is **layered graphite**, not near-black: page `#1B202B` < secondary `#252C38` < sidebar `#292F3D` <
  card `#303746` < elevated `#394253`.
- App bar is dark in both themes (`#23354B` / `#171F2D`); the GNSS strip is `#2A4158` in both themes (brief value kept;
  all strip foregrounds re-validated, one decorative separator adjusted).
- Semantic status hues are unchanged in meaning; success / warning / error dark values are the brief's, light values
  hue-matched counterparts; info is cyan (`#00658A` / `#7CCBEF`) so it cannot be mistaken for interactive cobalt. Map tiles, camera imagery and their overlays keep their fixed conventions.

## 1. Final palettes

All values are opaque sRGB. "Brief" = the user's starting value. Every adjustment is listed in §1.6 with the reason.

### 1.1 Brand chrome (app bar, GNSS strip)

| Role | Light | Dark | Notes |
|---|---|---|---|
| App bar `app_top_app_bar` (+ status-bar colour) | `#23354B` (brief) | `#171F2D` (brief) | was `#071A2D` / `#07121E` |
| On app bar `app_on_top_app_bar` (title, nav icon, actions) | `#FFFFFF` | `#FFFFFF` | 12.48 / 16.53 |
| GNSS strip `app_status_strip` | `#2A4158` (brief) | `#2A4158` (brief) | was `#0F3554`; same in both themes by design |
| Strip separator `app_status_separator` | `#7D93A8` | same | **adjusted** from `#6F8598` (2.75 on the new strip, decorative but kept ≥ 3) |
| Strip values `toolbar_on_primary` | `#F2F6FA` | same | unchanged, 9.69 |
| Strip labels / chevron / level NONE `toolbar_on_secondary` | `#B8C7D6` | same | unchanged, 6.10 |
| Strip RTK Fix / good `toolbar_success` | `#7DD6A7` | same | **adjusted** from `#81C995` (5.37 → 6.04; matches dark success) |
| Strip Float / aging `toolbar_warning` | `#F7C66A` | same | unchanged, 6.64 |
| Strip no fix / error `toolbar_error` | `#FFAAA5` | same | unchanged, 5.79 (brief's `#F29A9A` would be 4.94: kept the lighter toolbar value) |
| Strip DGPS / info `toolbar_info` | `#7CCBEF` | same | **adjusted** from `#9CC8FF` (6.07 → 5.84) to the approved cyan info |
| Critical pill fill / text / outline | `#C62828` / `#FFFFFF` / `toolbar_error` | same | unchanged; fill alone 1.87 on the strip, the 1 dp outline carries the edge (5.79) |
| Caution pill fill / text | `#FFB300` / `#1A1A1A` | same | unchanged; fill 5.86 on the strip, text 9.70 |

The app bar and strip are related (same blue-slate hue) but distinct: in light the strip is one step lighter than the
bar (1.18:1 luminance step, plus the hairline change of texture between title row and data row); in dark the bar is
much darker than the strip (1.57:1). Both are dark in both themes, so the strip's foreground roles stay theme-independent.

### 1.2 Light palette (Material 3 roles)

| Role | Value | Brief | Was |
|---|---|---|---|
| background (`android:colorBackground`) / onBackground | `#F3F5F8` / `#202937` | page bg / primary text | `#F4F7FA` / `#1A1C1E` |
| surface (cards) / onSurface | `#FFFFFF` / `#202937` | card / primary text | `#FFFFFF` / `#1A1C1E` |
| surfaceVariant (settings sidebar, filled cards) / onSurfaceVariant | `#E8EAF0` / `#515D6C` | sidebar / secondary text | `#EAF0F6` / `#41484D` |
| surfaceContainerLowest | `#FFFFFF` | | `#FFFFFF` |
| surfaceContainerLow (secondary surface; bottom sheets) | `#EEF2F6` | secondary surface | `#F5F7FB` |
| surfaceContainer (navigation drawer, list pane) | `#E8EAF0` | sidebar | `#EAF0F6` |
| surfaceContainerHigh (menus, dialogs, stat tiles) | `#E2E6EC` | derived | `#DFE7F0` |
| surfaceContainerHighest (pressed chips, placeholders) | `#DADFE7` | derived | `#D4DCE8` |
| surfaceDim / surfaceBright (new; were M3 defaults) | `#D9DDE4` / `#FFFFFF` | derived | unset |
| outline (text-field / control borders) | `#6B7685` | derived | `#71787E` |
| outlineVariant (card borders, dividers) | `#CDD4DE` | border/divider | `#C5CED8` |
| primary / onPrimary | `#245BC2` / `#FFFFFF` | cobalt | `#155DA8` / `#FFFFFF` |
| primaryContainer / onPrimaryContainer (tonal chips, selected list row) | `#E2EBFC` / `#102F66` | selected nav bg | `#D7E8FF` / `#001B44` |
| inversePrimary | `#A8C6FF` | = dark cobalt | `#ADC8FF` |
| inverseSurface / inverseOnSurface (snackbars) | `#2F3644` / `#EEF1F6` | derived | `#2F3134` / `#F3F5F8` |
| secondary / onSecondary | `#4A5A70` / `#FFFFFF` | slate (decision D1) | teal `#00796B` / `#FFFFFF` |
| secondaryContainer / onSecondaryContainer | `#DCE3ED` / `#1C2533` | | `#BFEDE6` / `#002019` |
| tertiary / on / container / onContainer | `#7C5800` / `#FFFFFF` / `#FFDEA8` / `#271900` | unchanged (D2) | same |
| error / onError | `#B3261E` / `#FFFFFF` | = status error | `#BA1A1A` / `#FFFFFF` |
| errorContainer / onErrorContainer | `#FCDCDA` / `#5C0B0B` | = status error | `#FFDAD6` / `#410002` |
| Selected navigation bg (`nav_selected_bg`, `dev_category_selected_bg`) | `#E2EBFC` | brief | `#D6E4FF` |
| Selected navigation fg (drawer + settings sidebar) | `#245BC2` (colorPrimary) | cobalt | drawer `#001B44`, settings `#155DA8` |
| Settings selected accent bar (`dev_category_selected_accent`) | `#245BC2` (colorPrimary) | | `#1A56A4` |

### 1.3 Dark palette (Material 3 roles)

| Role | Value | Brief | Was |
|---|---|---|---|
| background / onBackground | `#1B202B` / `#F0F2F7` | page bg / primary text | `#0B1117` / `#E2E4E7` |
| surface (cards) / onSurface | `#303746` / `#F0F2F7` | card / primary text | `#101820` / `#E2E4E7` |
| surfaceVariant (settings sidebar) / onSurfaceVariant | `#292F3D` / `#C8CED8` | sidebar / secondary text | `#1D2A36` / `#C1C8CE` |
| surfaceContainerLowest | `#161A23` | derived | `#0B1017` |
| surfaceContainerLow (secondary surface; bottom sheets) | `#252C38` | secondary surface | `#111920` |
| surfaceContainer (navigation drawer, list pane) | `#292F3D` | sidebar | `#16212B` |
| surfaceContainerHigh (menus, dialogs, stat tiles) | `#394253` | elevated surface | `#1D2A36` |
| surfaceContainerHighest | `#3C4555` | derived (§1.6) | `#243042` |
| surfaceDim / surfaceBright (new) | `#1B202B` / `#424B5D` | derived | unset |
| outline | `#8D97A8` | derived | `#8B9198` |
| outlineVariant (card borders, dividers) | `#505B6F` | border/divider | `#334454` |
| primary / onPrimary | `#A8C6FF` / `#0F2A55` | cobalt | `#8FC5FF` / `#0F3A63` |
| primaryContainer / onPrimaryContainer | `#293F61` / `#D9E5FF` | selected nav bg | `#0F3A63` / `#D7E8FF` |
| inversePrimary | `#245BC2` | = light cobalt | `#1A56A4` |
| inverseSurface / inverseOnSurface | `#E4E8EF` / `#2A303C` | derived | `#E2E4E7` / `#2F3134` |
| secondary / onSecondary | `#B9C5D6` / `#1C2533` | slate (D1) | teal `#79D7C9` / `#003731` |
| secondaryContainer / onSecondaryContainer | `#3A4658` / `#DCE3ED` | | `#00534A` / `#BCEDE4` |
| tertiary / on / container / onContainer | `#FBBE4D` / `#422C00` / `#5D4100` / `#FFDEA8` | unchanged (D2) | same |
| error / onError | `#F29A9A` / `#5C0B0B` | = status error | `#FFB4AB` / `#690005` |
| errorContainer / onErrorContainer | `#5E2A2D` / `#FCDADA` | = status error | `#93000A` / `#FFDAD6` |
| Selected navigation bg | `#293F61` | brief | `#004688` |
| Selected navigation fg | `#A8C6FF` (colorPrimary) | cobalt | drawer `#D7E8FF`, settings `#8FC5FF` |

Layering (dark, luminance contrast against the page): secondary 1.16 · sidebar 1.22 · card 1.37 · elevated 1.61. Before:
surface `#101820` on `#0B1117` was 1.05, i.e. cards were barely separable without their border.

### 1.4 Semantic status roles

Three colours per role, as today (`status_<role>` foreground for text / icons / dots on any surface,
`status_<role>_container` fill, `status_on_<role>_container` text on that fill). Exposed through the existing
`?attr/colorStatus*` attributes and `StatusTone`.

| Role | Light fg | Light container / on | Dark fg | Dark container / on |
|---|---|---|---|---|
| success | `#17693F` (was `#1E6B2A`) | `#D3EFDF` / `#0B3B22` | `#7DD6A7` (brief; was `#81C995`) | `#1F4A36` / `#C9F2DC` |
| warning | `#8C5000` (was `#9A4700`) | `#FBE3B6` / `#3A2400` | `#F2C26B` (brief; was `#F7C66A`) | `#54401A` / `#FCE4B4` |
| error | `#B3261E` (was `#BA1A1A`) | `#FCDCDA` / `#5C0B0B` | `#F29A9A` (brief; was `#FFB4AB`) | `#5E2A2D` / `#FCDADA` |
| info (cyan) | `#00658A` (was `#155DA8`) | `#CDEBF6` / `#002C3D` | `#7CCBEF` (was `#9CC8FF`) | `#1D4A5C` / `#C9ECFA` |
| neutral | `#515D6C` (= onSurfaceVariant) | `#E3E7EE` / `#202937` | `#C8CED8` (= onSurfaceVariant) | `#3A4252` / `#E4E8EF` |

Light counterparts are hue-matched to the brief's dark values (success mint-green ~150°, warning amber ~35°, error red
~3°, info blue ~215°) and darkened until they pass 4.5:1 on the darkest light surface they can sit on
(`surfaceContainerHighest #DADFE7`). Info is **cyan** (approved adjustment, D3): `#00658A` / `#7CCBEF` were the
requested starting values and pass unchanged on every applicable surface (light ≥ 4.86, dark ≥ 5.36, strip 5.84; table
below), so they are final. Containers are derived at the same hue: light `#CDEBF6` with `#002C3D` (11.77), dark
`#1D4A5C` with `#C9ECFA` (7.71).

| Info foreground on | Light `#00658A` | Dark `#7CCBEF` |
|---|---|---|
| page (background) | 5.96 | 9.04 |
| card (surface) / surfaceContainerLowest | 6.50 / 6.50 | 6.61 / 9.66 |
| secondary (surfaceContainerLow) | 5.78 | 7.78 |
| sidebar / drawer (surfaceVariant, surfaceContainer) | 5.41 | 7.43 |
| elevated (surfaceContainerHigh) | 5.19 | 5.60 |
| surfaceContainerHighest | 4.86 | 5.36 |
| selected row (primaryContainer) | 5.43 | 5.89 |
| GNSS strip `#2A4158` (`toolbar_info`) | — | 5.84 |
| AR chip scrim over white (`ar_overlay_info`, ≈ `#333333`) | — | 7.01 |

Legacy aliases keep pointing at the roles: `app_success|warning|error|info` → `status_*`; `status_fix|float|dgps|no_fix`
→ success / warning / info / error; `status_single` → `status_neutral` (was a separate `#546E7A` / `#B0BEC5`).

### 1.5 Status → meaning (one mapping everywhere)

| Condition | Role | Home field status | GNSS Status report | Coordinates (list badge / detail) | Map | AR | Toolbar strip | Diagnostics |
|---|---|---|---|---|---|---|---|---|
| RTK FIX (and good accuracy) | success | headline + dot `status_success` | row text success | `FIX` badge success container | selected-point RTK text success | `ar_overlay_success` | `toolbar_success` | pass = success |
| RTK FLOAT | warning | `status_warning` | warning | `FLOAT` badge warning container | warning | `ar_overlay_warning` | `toolbar_warning` | caution = warning |
| DGPS / SBAS (degraded but valid) | info | `status_info` | info | `DGPS` badge info container | info | `ar_overlay_info` | `toolbar_info` | info |
| Degraded accuracy (hAcc above threshold, sub-metre etc.) | warning | warning | warning | accuracy badge warning container | warning | warning | `toolbar_warning` | warning |
| Single / autonomous | neutral | neutral | neutral | neutral badge | neutral | `ar_overlay_text` | `toolbar_on_secondary` | neutral |
| No fix / lost | error | `status_error` | error | error badge | error | `ar_overlay_error` | `toolbar_error` (+ critical pill when it is a warning) | fail = error |
| Corrections aging | warning | warning (in words) | warning row, ⚠ prefix | — | — | warning | caution pill (amber) | warning |
| Corrections stale / unavailable | error | error (in words) | error row, ⚠ prefix | — | — | error | critical pill (red + outline) | error |
| Receiver error / link failed | error | error | error | — | — | error | critical pill | error |
| Connecting / waiting | neutral | neutral (`colorStatusNeutral`, I-9) | neutral | — | — | neutral | `toolbar_on_secondary` | neutral |

This is the mapping the code already implemented (design-system phase 2); the spec changes values, not meanings. The
two deviations found in the proposal are fixed on the feature branch: the receiver card fix chip now uses success /
warning / info containers for Fix / Float / DGPS (I-10; it used cobalt for Fix and amber for both Float and DGPS), and
Home / the report draw the neutral "waiting" headline in `colorStatusNeutral` (I-9). Status is never colour alone
(words / icon / ⚠ prefix stay).

### 1.6 Adjustments to the brief's values (original → adjusted, why)

| Value | Brief / current | Adjusted | Why |
|---|---|---|---|
| Strip separator | `#6F8598` | `#7D93A8` | 2.75 on the new strip `#2A4158` (was 3.31 on `#0F3554`); decorative, but kept at the documented ≥ 3 → 3.31 |
| `toolbar_success` | `#81C995` | `#7DD6A7` | lowest-margin strip role after the strip got lighter (6.47 → 5.37); the brief's success value restores 6.04 and unifies the hue |
| `toolbar_error` | brief error `#F29A9A` would apply | keep `#FFAAA5` | `#F29A9A` is only 4.94 on the strip; kept the toolbar-specific value (5.79) |
| Dark surfaceContainerHighest | (none; derived) | `#3C4555` (not `#424B5D`) | `status_error` `#F29A9A` must stay ≥ 4.5 on every surface; on `#424B5D` it is 4.12, on `#3C4555` 4.54 |
| Light status foregrounds | current values | hue-matched (§1.4) | the brief asks for light counterparts of the new dark hues; all ≥ 4.8 on `#DADFE7` |
| Info (approved adjustment) | `#1F5AA8` / `#8CB8FF` (proposal) | `#00658A` / `#7CCBEF` | proposal blue was too close to cobalt; the requested cyan starting values pass everywhere, so no further adjustment |
| `toolbar_info` | `#9CC8FF` | `#7CCBEF` | follows the cyan info on the strip (5.84); `ar_overlay_info` follows through its alias |
| Light onPrimaryContainer | first pick `#12336E` | `#102F66` | the coordinate list draws the selected row's coordinates in onPrimaryContainer at 70 % alpha; `#12336E` composited was 4.48 on `#E2EBFC` (caught by the existing `PaletteContrastLightTest`), `#102F66` gives 4.69 |
| Card / sidebar / page fills | brief | unchanged | — |
| Cobalt, selected nav, text colours | brief | unchanged | all pass (§4) |

No brief value failed WCAG on its intended pairs; the adjustments are only to derived roles and the strip foregrounds.

## 2. Mapping: existing resources and theme attributes → proposed values

Generated from `main` @ `fe471f9b` vs `feature/graphite-cobalt-theme` (the implementation of this mapping). Values are resolved per
theme (aliases followed; night overrides applied). **Bold** = changed. "unused" = no reference outside `colors.xml`
(left as is, D11). Fixed overlay resources (`ar_overlay_*`, `map_*`, `model_viewer_*`, constellation strokes) are listed
for completeness and are unchanged except where stated in §5.

### 2.1 Colour resources (`values/colors.xml`, `values-night/colors.xml`)

| Resource | Light (old → new) | Dark (old → new) | State | Note |
|---|---|---|---|---|
| `md_primary_10` | `#001B44` | `#001B44` | unchanged | unused (D11) |
| `md_primary_20` | `#002F65` | `#002F65` | unchanged | unused (D11) |
| `md_primary_30` | `#004688` | `#004688` | unchanged | unused (D11) |
| `md_primary_40` | `#1A56A4` | `#1A56A4` | unchanged |  |
| `md_primary_80` | `#ADC8FF` | `#ADC8FF` | unchanged |  |
| `md_primary_90` | `#D6E4FF` | `#D6E4FF` | unchanged | unused (D11) |
| `md_primary_100` | `#FFFFFF` | `#FFFFFF` | unchanged | unused (D11) |
| `md_secondary_10` | `#002019` | `#002019` | unchanged | unused (D11) |
| `md_secondary_20` | `#003731` | `#003731` | unchanged | unused (D11) |
| `md_secondary_30` | `#005049` | `#005049` | unchanged | unused (D11) |
| `md_secondary_40` | `#006A5B` | `#006A5B` | unchanged | unused (D11) |
| `md_secondary_80` | `#79D8C8` | `#79D8C8` | unchanged | unused (D11) |
| `md_secondary_90` | `#BCEDE4` | `#BCEDE4` | unchanged | unused (D11) |
| `md_secondary_100` | `#FFFFFF` | `#FFFFFF` | unchanged | unused (D11) |
| `md_tertiary_10` | `#271900` | `#271900` | unchanged |  |
| `md_tertiary_20` | `#422C00` | `#422C00` | unchanged |  |
| `md_tertiary_30` | `#5D4100` | `#5D4100` | unchanged |  |
| `md_tertiary_40` | `#7C5800` | `#7C5800` | unchanged |  |
| `md_tertiary_80` | `#FBBE4D` | `#FBBE4D` | unchanged |  |
| `md_tertiary_90` | `#FFDEA8` | `#FFDEA8` | unchanged |  |
| `md_tertiary_100` | `#FFFFFF` | `#FFFFFF` | unchanged |  |
| `md_error_10` | `#410002` | `#410002` | unchanged |  |
| `md_error_20` | `#690005` | `#690005` | unchanged |  |
| `md_error_30` | `#93000A` | `#93000A` | unchanged |  |
| `md_error_40` | `#BA1A1A` | `#BA1A1A` | unchanged |  |
| `md_error_80` | `#FFB4AB` | `#FFB4AB` | unchanged |  |
| `md_error_90` | `#FFDAD6` | `#FFDAD6` | unchanged |  |
| `md_error_100` | `#FFFFFF` | `#FFFFFF` | unchanged |  |
| `md_neutral_4` | `#0F1114` | `#0F1114` | unchanged | unused (D11) |
| `md_neutral_6` | `#121416` | `#121416` | unchanged | unused (D11) |
| `md_neutral_10` | `#1A1C1E` | `#1A1C1E` | unchanged | unused (D11) |
| `md_neutral_20` | `#2F3134` | `#2F3134` | unchanged |  |
| `md_neutral_90` | `#E2E4E7` | `#E2E4E7` | unchanged |  |
| `md_neutral_95` | `#F3F5F8` | `#F3F5F8` | unchanged |  |
| `md_neutral_99` | `#FAFBFC` | `#FAFBFC` | unchanged | unused (D11) |
| `md_neutral_var_20` | `#3D4349` | `#3D4349` | unchanged | unused (D11) |
| `md_neutral_var_30` | `#41484D` | `#41484D` | unchanged | unused (D11) |
| `md_neutral_var_50` | `#71787E` | `#71787E` | unchanged | unused (D11) |
| `md_neutral_var_60` | `#8B9198` | `#8B9198` | unchanged | unused (D11) |
| `md_neutral_var_80` | `#C1C8CE` | `#C1C8CE` | unchanged | unused (D11) |
| `md_neutral_var_90` | `#DDE3EA` | `#DDE3EA` | unchanged | unused (D11) |
| `md_surface_container_lowest` | `#FFFFFF` | `#0B1017` | unchanged |  |
| `md_surface_container_low` | `#F5F7FB` | `#111920` | unchanged |  |
| `md_surface_container` | `#EFF2F6` | `#162029` | unchanged | unused (D11) |
| `md_surface_container_high` | `#E9EDF2` | `#1D2836` | unchanged | unused (D11) |
| `md_surface_container_highest` | `#E3E7ED` | `#243042` | unchanged | unused (D11) |
| `app_top_app_bar` | `#071A2D` → **`#23354B`** | `#07121E` → **`#171F2D`** | changed |  |
| `app_on_top_app_bar` | `#FFFFFF` | `#FFFFFF` | unchanged |  |
| `app_status_strip` | `#0F3554` → **`#2A4158`** | `#0F3554` → **`#2A4158`** | changed |  |
| `app_on_status_strip` | `#F2F6FA` | `#F2F6FA` | unchanged | alias `toolbar_on_primary`; unused (D11) |
| `app_on_status_strip_variant` | `#B8C7D6` | `#B8C7D6` | unchanged | alias `toolbar_on_secondary` |
| `app_status_separator` | `#6F8598` → **`#7D93A8`** | `#6F8598` → **`#7D93A8`** | changed |  |
| `status_success` | `#1E6B2A` → **`#17693F`** | `#81C995` → **`#7DD6A7`** | changed |  |
| `status_success_container` | `#CDEBD2` → **`#D3EFDF`** | `#1E4D2B` → **`#1F4A36`** | changed |  |
| `status_on_success_container` | `#0B3D14` → **`#0B3B22`** | `#C8F0D0` → **`#C9F2DC`** | changed |  |
| `status_warning` | `#9A4700` → **`#8C5000`** | `#F7C66A` → **`#F2C26B`** | changed |  |
| `status_warning_container` | `#FFDEA8` → **`#FBE3B6`** | `#5D4100` → **`#54401A`** | changed |  |
| `status_on_warning_container` | `#2E1A00` → **`#3A2400`** | `#FFDEA8` → **`#FCE4B4`** | changed |  |
| `status_error` | `#BA1A1A` → **`#B3261E`** | `#FFB4AB` → **`#F29A9A`** | changed |  |
| `status_error_container` | `#FFDAD6` → **`#FCDCDA`** | `#93000A` → **`#5E2A2D`** | changed |  |
| `status_on_error_container` | `#410002` → **`#5C0B0B`** | `#FFDAD6` → **`#FCDADA`** | changed |  |
| `status_info` | `#155DA8` → **`#00658A`** | `#9CC8FF` → **`#7CCBEF`** | changed |  |
| `status_info_container` | `#D7E8FF` → **`#CDEBF6`** | `#0F3A63` → **`#1D4A5C`** | changed |  |
| `status_on_info_container` | `#001B44` → **`#002C3D`** | `#D7E8FF` → **`#C9ECFA`** | changed |  |
| `status_neutral` | `#41484D` → **`#515D6C`** | `#C1C8CE` → **`#C8CED8`** | changed |  |
| `status_neutral_container` | `#E3E7ED` → **`#E3E7EE`** | `#2A3644` → **`#3A4252`** | changed |  |
| `status_on_neutral_container` | `#1A1C1E` → **`#202937`** | `#E2E4E7` → **`#E4E8EF`** | changed |  |
| `app_success` | `#1E6B2A` → **`#17693F`** | `#81C995` → **`#7DD6A7`** | changed | alias `status_success` |
| `app_warning` | `#9A4700` → **`#8C5000`** | `#F7C66A` → **`#F2C26B`** | changed | alias `status_warning` |
| `app_error` | `#BA1A1A` → **`#B3261E`** | `#FFB4AB` → **`#F29A9A`** | changed | alias `status_error` |
| `app_info` | `#155DA8` → **`#00658A`** | `#9CC8FF` → **`#7CCBEF`** | changed | alias `status_info` |
| `toolbar_on_primary` | `#F2F6FA` | `#F2F6FA` | unchanged |  |
| `toolbar_on_secondary` | `#B8C7D6` | `#B8C7D6` | unchanged |  |
| `toolbar_success` | `#81C995` → **`#7DD6A7`** | `#81C995` → **`#7DD6A7`** | changed |  |
| `toolbar_warning` | `#F7C66A` | `#F7C66A` | unchanged |  |
| `toolbar_error` | `#FFAAA5` | `#FFAAA5` | unchanged |  |
| `toolbar_info` | `#9CC8FF` → **`#7CCBEF`** | `#9CC8FF` → **`#7CCBEF`** | changed |  |
| `ar_overlay_success` | `#81C995` → **`#7DD6A7`** | `#81C995` → **`#7DD6A7`** | changed | alias `toolbar_success` |
| `ar_overlay_warning` | `#F7C66A` | `#F7C66A` | unchanged | alias `toolbar_warning` |
| `ar_overlay_error` | `#FFAAA5` | `#FFAAA5` | unchanged | alias `toolbar_error` |
| `ar_overlay_info` | `#9CC8FF` → **`#7CCBEF`** | `#9CC8FF` → **`#7CCBEF`** | changed | alias `toolbar_info` |
| `app_primary` | `#155DA8` → **`#245BC2`** | `#8FC5FF` → **`#A8C6FF`** | changed |  |
| `app_on_primary` | `#FFFFFF` | `#0F3A63` → **`#0F2A55`** | changed |  |
| `app_primary_container` | `#D7E8FF` → **`#E2EBFC`** | `#0F3A63` → **`#293F61`** | changed |  |
| `app_on_primary_container` | `#001B44` → **`#102F66`** | `#D7E8FF` → **`#D9E5FF`** | changed |  |
| `app_secondary` | `#00796B` → **`#4A5A70`** | `#79D7C9` → **`#B9C5D6`** | changed |  |
| `app_on_secondary` | `#FFFFFF` | `#003731` → **`#1C2533`** | changed |  |
| `app_secondary_container` | `#BFEDE6` → **`#DCE3ED`** | `#00534A` → **`#3A4658`** | changed |  |
| `app_on_secondary_container` | `#002019` → **`#1C2533`** | `#BCEDE4` → **`#DCE3ED`** | changed |  |
| `app_background` | `#F4F7FA` → **`#F3F5F8`** | `#0B1117` → **`#1B202B`** | changed |  |
| `app_on_background` | `#1A1C1E` → **`#202937`** | `#E2E4E7` → **`#F0F2F7`** | changed |  |
| `app_surface` | `#FFFFFF` | `#101820` → **`#303746`** | changed |  |
| `app_on_surface` | `#1A1C1E` → **`#202937`** | `#E2E4E7` → **`#F0F2F7`** | changed |  |
| `app_surface_variant` | `#EAF0F6` → **`#E8EAF0`** | `#1D2A36` → **`#292F3D`** | changed |  |
| `app_on_surface_variant` | `#41484D` → **`#515D6C`** | `#C1C8CE` → **`#C8CED8`** | changed |  |
| `app_surface_container` | `#EAF0F6` → **`#E8EAF0`** | `#16212B` → **`#292F3D`** | changed |  |
| `app_surface_container_high` | `#DFE7F0` → **`#E2E6EC`** | `#1D2A36` → **`#394253`** | changed |  |
| `app_surface_container_highest` | `#D4DCE8` → **`#DADFE7`** | `#243042` → **`#3C4555`** | changed |  |
| `app_outline` | `#71787E` → **`#6B7685`** | `#8B9198` → **`#8D97A8`** | changed |  |
| `app_outline_variant` | `#C5CED8` → **`#CDD4DE`** | `#334454` → **`#505B6F`** | changed |  |
| `ic_launcher_background` | `#020B1A` | `#020B1A` | unchanged | unused (D11) |
| `black` | `#000000` | `#000000` | unchanged |  |
| `white` | `#FFFFFF` | `#FFFFFF` | unchanged |  |
| `coordinate_row_even` | `#FFFFFF` | `#1E2024` | unchanged | unused (D11) |
| `coordinate_row_odd` | `#F5F5F5` | `#252A2E` | unchanged | unused (D11) |
| `coordinate_card_stroke_normal` | `#33000000` | `#33000000` | unchanged | unused (D11) |
| `coordinate_card_stroke_selected` | `#1A56A4` → **`#245BC2`** | `#1A56A4` → **`#A8C6FF`** | changed | now alias `app_primary`; unused (D11) |
| `coordinate_list_bg` | `#F4F4F6` → **`#F3F5F8`** | `#1A1C1E` → **`#1B202B`** | changed | now alias `app_background` |
| `coordinate_divider` | `#33000000` → **`#CDD4DE`** | `#33000000` → **`#505B6F`** | changed | now alias `app_outline_variant` |
| `dev_panel_bg` | `#F5F5F5` | `#1E2124` | unchanged | unused (D11) |
| `dev_category_selected_bg` | `#D6E4FF` → **`#E2EBFC`** | `#004688` → **`#293F61`** | changed | now alias `nav_selected_bg` |
| `dev_category_selected_accent` | `#1A56A4` → **`#245BC2`** | `#1A56A4` → **`#A8C6FF`** | changed | now alias `app_primary` |
| `dev_category_ripple_mask` | `#33000000` | `#33000000` | unchanged |  |
| `dev_info_label` | `#212121` | `#CACFD4` | unchanged | unused (D11) |
| `dev_info_value` | `#000000` | `#E2E4E7` | unchanged | unused (D11) |
| `dev_info_section_bg` | `#FFFFFF` | `#1E2124` | unchanged | unused (D11) |
| `dev_info_row_alt` | `#F7F7F7` | `#232729` | unchanged | unused (D11) |
| `dev_perm_header_bg` | `#263238` | `#263238` | unchanged | unused (D11) |
| `dev_perm_header_text` | `#FFFFFF` | `#FFFFFF` | unchanged | unused (D11) |
| `dev_perm_row_even` | `#FFFFFF` | `#1E2124` | unchanged | unused (D11) |
| `dev_perm_row_odd` | `#EEF3F8` | `#252B30` | unchanged | unused (D11) |
| `dev_perm_row_divider` | `#1F000000` | `#1F000000` | unchanged | unused (D11) |
| `dev_perm_status_granted` | `#1B5E20` | `#1B5E20` | unchanged | unused (D11) |
| `dev_perm_status_denied` | `#B71C1C` | `#B71C1C` | unchanged | unused (D11) |
| `status_fix` | `#1E6B2A` → **`#17693F`** | `#81C995` → **`#7DD6A7`** | changed | alias `status_success` |
| `status_float` | `#9A4700` → **`#8C5000`** | `#F7C66A` → **`#F2C26B`** | changed | alias `status_warning` |
| `status_dgps` | `#155DA8` → **`#00658A`** | `#9CC8FF` → **`#7CCBEF`** | changed | alias `status_info` |
| `status_single` | `#546E7A` → **`#515D6C`** | `#B0BEC5` → **`#C8CED8`** | changed | now alias `status_neutral` |
| `status_no_fix` | `#BA1A1A` → **`#B3261E`** | `#FFB4AB` → **`#F29A9A`** | changed | alias `status_error` |
| `home_right_column_bg` | `#DDE3EA` | `#252B30` | unchanged | alias `md_neutral_var_90`; unused (D11) |
| `card_stroke_light` | `#C1C8CE` | `#C1C8CE` | unchanged | alias `md_neutral_var_80`; unused (D11) |
| `nav_selected_bg` | `#D6E4FF` → **`#E2EBFC`** | `#004688` → **`#293F61`** | changed |  |
| `stat_card_bg` | `#FFFFFF` | `#1D2836` → **`#303746`** | changed | now alias `app_surface` |
| `stat_card_stroke` | `#D0D0D0` → **`#CDD4DE`** | `#3D4349` → **`#505B6F`** | changed | now alias `app_outline_variant` |
| `chip_map_bg` | `#1A56A4` | `#1A56A4` | unchanged | alias `md_primary_40`; unused (D11) |
| `chip_ar_bg` | `#006A5B` | `#006A5B` | unchanged | alias `md_secondary_40`; unused (D11) |
| `chip_settings_bg` | `#1A56A4` | `#1A56A4` | unchanged | alias `md_primary_40`; unused (D11) |
| `chip_ripple_overlay` | `#40FFFFFF` | `#40FFFFFF` | unchanged | unused (D11) |
| `ar_overlay_bg_dark` | `#CC000000` | `#CC000000` | unchanged |  |
| `ar_overlay_bg_medium` | `#66000000` | `#66000000` | unchanged |  |
| `ar_overlay_bg_heavy` | `#BB000000` | `#BB000000` | unchanged |  |
| `ar_overlay_divider` | `#33FFFFFF` | `#33FFFFFF` | unchanged |  |
| `ar_overlay_ripple` | `#40FFFFFF` | `#40FFFFFF` | unchanged |  |
| `ar_overlay_label_bg` | `#CC111111` | `#CC111111` | unchanged |  |
| `ar_overlay_text_secondary` | `#CCCCCC` | `#CCCCCC` | unchanged |  |
| `ar_overlay_model_accent` | `#00E5FF` | `#00E5FF` | unchanged |  |
| `ar_overlay_text` | `#FFFFFF` | `#FFFFFF` | unchanged |  |
| `ar_overlay_alert` | `#FFC107` | `#FFC107` | unchanged | unused (D11) |
| `map_overlay_bg` | `#AAFFFFFF` | `#AAFFFFFF` | unchanged |  |
| `map_label_bg` | `#B3000000` | `#B3000000` | unchanged |  |
| `map_label_text` | `#FFFFFF` | `#FFFFFF` | unchanged |  |
| `map_grid_line` | `#80616161` | `#80BDBDBD` | unchanged |  |
| `chart_axis` | `#71787E` → **`#6B7685`** | `#8B9198` → **`#8D97A8`** | changed |  |
| `chart_grid` | `#C5CED8` → **`#CDD4DE`** | `#41484D` → **`#505B6F`** | changed |  |
| `chart_text` | `#424242` → **`#515D6C`** | `#E2E4E7` → **`#F0F2F7`** | changed |  |
| `chart_title` | `#1A1C1E` → **`#202937`** | `#FFFFFF` | changed |  |
| `chart_bar_strong` | `#1E6B2A` → **`#17693F`** | `#81C995` → **`#7DD6A7`** | changed | alias `status_fix` |
| `chart_bar_medium` | `#9A4700` → **`#8C5000`** | `#F7C66A` → **`#F2C26B`** | changed | alias `status_float` |
| `chart_bar_weak` | `#BA1A1A` → **`#B3261E`** | `#FFB4AB` → **`#F29A9A`** | changed | alias `status_no_fix` |
| `chart_bar_unused` | `#757575` | `#8B9198` | unchanged |  |
| `chart_used_halo` | `#1A1C1E` → **`#202937`** | `#FFFFFF` | changed |  |
| `chart_constellation_gps` | `#1565C0` | `#90CAF9` | unchanged |  |
| `chart_constellation_glonass` | `#8E24AA` | `#CE93D8` | unchanged |  |
| `chart_constellation_galileo` | `#2E7D32` | `#A5D6A7` | unchanged |  |
| `chart_constellation_beidou` | `#8D6E00` | `#FFE082` | unchanged |  |
| `chart_constellation_qzss` | `#5E35B1` | `#B39DDB` | unchanged |  |
| `chart_constellation_sbas` | `#546E7A` | `#B0BEC5` | unchanged |  |
| `chart_constellation_irnss` | `#C43E00` | `#FFAB91` | unchanged |  |
| `chart_constellation_other` | `#757575` | `#C0C0C0` | unchanged |  |
| `gnss_toolbar_warning_critical_bg` | `#C62828` | `#C62828` | unchanged |  |
| `gnss_toolbar_warning_critical_stroke` | `#FFAAA5` | `#FFAAA5` | unchanged | alias `toolbar_error` |
| `gnss_toolbar_warning_critical_fg` | `#FFFFFF` | `#FFFFFF` | unchanged |  |
| `gnss_toolbar_warning_caution_bg` | `#FFB300` | `#FFB300` | unchanged |  |
| `gnss_toolbar_warning_caution_fg` | `#1A1A1A` | `#1A1A1A` | unchanged |  |
| `model_viewer_canvas` | `#EEEEEE` | `#EEEEEE` | unchanged |  |
| `model_viewer_on_canvas` | `#1A1C1E` | `#1A1C1E` | unchanged |  |
| `model_viewer_on_canvas_error` | `#BA1A1A` | `#BA1A1A` | unchanged |  |
| `model_viewer_on_canvas_accent` | `#1A56A4` → **`#245BC2`** | `#1A56A4` → **`#245BC2`** | changed |  |
| `app_surface_container_lowest` | `—` → **`#FFFFFF`** | `—` → **`#161A23`** | changed | **new**  |
| `app_surface_container_low` | `—` → **`#EEF2F6`** | `—` → **`#252C38`** | changed | **new**  |
| `app_surface_dim` | `—` → **`#D9DDE4`** | `—` → **`#1B202B`** | changed | **new**  |
| `app_surface_bright` | `—` → **`#FFFFFF`** | `—` → **`#424B5D`** | changed | **new**  |
| `app_inverse_surface` | `—` → **`#2F3644`** | `—` → **`#E4E8EF`** | changed | **new**  |
| `app_inverse_on_surface` | `—` → **`#EEF1F6`** | `—` → **`#2A303C`** | changed | **new**  |
| `app_inverse_primary` | `—` → **`#A8C6FF`** | `—` → **`#245BC2`** | changed | **new**  |
| `app_on_error` | `—` → **`#FFFFFF`** | `—` → **`#5C0B0B`** | changed | **new**  |
| `app_error_container` | `—` → **`#FCDCDA`** | `—` → **`#5E2A2D`** | changed | **new**  |
| `app_on_error_container` | `—` → **`#5C0B0B`** | `—` → **`#FCDADA`** | changed | **new**  |
| `app_control_highlight` | `—` → **`#1A245BC2`** | `—` → **`#1AA8C6FF`** | changed | **new**  |

New resources (added to both files; referenced only by the theme): `app_surface_container_lowest`,
`app_surface_container_low`, `app_surface_dim`, `app_surface_bright`, `app_inverse_surface`, `app_inverse_on_surface`,
`app_inverse_primary`, `app_on_error`, `app_error_container`, `app_on_error_container`, `app_control_highlight`.
`app_error` stays an alias of `status_error` and the theme's `colorError` now points at it (one error red everywhere).
The `md_*` tonal resources are left untouched (several are referenced only from the old theme items and become unused).

### 2.2 Theme attributes (`Theme.SurveyingApp`, light / night)

| Theme attribute (`Theme.SurveyingApp`) | Reference (old → new) | Light (old → new) | Dark (old → new) | State |
|---|---|---|---|---|
| `colorPrimary` | `app_primary` | `#155DA8` → **`#245BC2`** | `#8FC5FF` → **`#A8C6FF`** | changed |
| `colorOnPrimary` | `app_on_primary` | `#FFFFFF` | `#0F3A63` → **`#0F2A55`** | changed |
| `colorPrimaryContainer` | `app_primary_container` | `#D7E8FF` → **`#E2EBFC`** | `#0F3A63` → **`#293F61`** | changed |
| `colorOnPrimaryContainer` | `app_on_primary_container` | `#001B44` → **`#102F66`** | `#D7E8FF` → **`#D9E5FF`** | changed |
| `colorPrimaryInverse` | `md_primary_80` → `app_inverse_primary` | `#ADC8FF` → **`#A8C6FF`** | `#1A56A4` → **`#245BC2`** | changed |
| `colorSecondary` | `app_secondary` | `#00796B` → **`#4A5A70`** | `#79D7C9` → **`#B9C5D6`** | changed |
| `colorOnSecondary` | `app_on_secondary` | `#FFFFFF` | `#003731` → **`#1C2533`** | changed |
| `colorSecondaryContainer` | `app_secondary_container` | `#BFEDE6` → **`#DCE3ED`** | `#00534A` → **`#3A4658`** | changed |
| `colorOnSecondaryContainer` | `app_on_secondary_container` | `#002019` → **`#1C2533`** | `#BCEDE4` → **`#DCE3ED`** | changed |
| `colorTertiary` | `md_tertiary_40` | `#7C5800` | `#FBBE4D` | unchanged |
| `colorOnTertiary` | `md_tertiary_100` | `#FFFFFF` | `#422C00` | unchanged |
| `colorTertiaryContainer` | `md_tertiary_90` | `#FFDEA8` | `#5D4100` | unchanged |
| `colorOnTertiaryContainer` | `md_tertiary_10` | `#271900` | `#FFDEA8` | unchanged |
| `colorError` | `md_error_40` → `app_error` | `#BA1A1A` → **`#B3261E`** | `#FFB4AB` → **`#F29A9A`** | changed |
| `colorOnError` | `md_error_100` → `app_on_error` | `#FFFFFF` | `#690005` → **`#5C0B0B`** | changed |
| `colorErrorContainer` | `md_error_90` → `app_error_container` | `#FFDAD6` → **`#FCDCDA`** | `#93000A` → **`#5E2A2D`** | changed |
| `colorOnErrorContainer` | `md_error_10` → `app_on_error_container` | `#410002` → **`#5C0B0B`** | `#FFDAD6` → **`#FCDADA`** | changed |
| `colorSurface` | `app_surface` | `#FFFFFF` | `#101820` → **`#303746`** | changed |
| `colorOnSurface` | `app_on_surface` | `#1A1C1E` → **`#202937`** | `#E2E4E7` → **`#F0F2F7`** | changed |
| `colorSurfaceVariant` | `app_surface_variant` | `#EAF0F6` → **`#E8EAF0`** | `#1D2A36` → **`#292F3D`** | changed |
| `colorOnSurfaceVariant` | `app_on_surface_variant` | `#41484D` → **`#515D6C`** | `#C1C8CE` → **`#C8CED8`** | changed |
| `colorSurfaceInverse` | `md_neutral_20` → `app_inverse_surface` | `#2F3134` → **`#2F3644`** | `#E2E4E7` → **`#E4E8EF`** | changed |
| `colorOnSurfaceInverse` | `md_neutral_95` → `app_inverse_on_surface` | `#F3F5F8` → **`#EEF1F6`** | `#2F3134` → **`#2A303C`** | changed |
| `colorSurfaceContainerLowest` | `md_surface_container_lowest` → `app_surface_container_lowest` | `#FFFFFF` | `#0B1017` → **`#161A23`** | changed |
| `colorSurfaceContainerLow` | `md_surface_container_low` → `app_surface_container_low` | `#F5F7FB` → **`#EEF2F6`** | `#111920` → **`#252C38`** | changed |
| `colorSurfaceContainer` | `app_surface_container` | `#EAF0F6` → **`#E8EAF0`** | `#16212B` → **`#292F3D`** | changed |
| `colorSurfaceContainerHigh` | `app_surface_container_high` | `#DFE7F0` → **`#E2E6EC`** | `#1D2A36` → **`#394253`** | changed |
| `colorSurfaceContainerHighest` | `app_surface_container_highest` | `#D4DCE8` → **`#DADFE7`** | `#243042` → **`#3C4555`** | changed |
| `colorSurfaceDim` | `—` → `app_surface_dim` | `—` → **`#D9DDE4`** | `—` → **`#1B202B`** | changed |
| `colorSurfaceBright` | `—` → `app_surface_bright` | `—` → **`#FFFFFF`** | `—` → **`#424B5D`** | changed |
| `android:colorBackground` | `app_background` | `#F4F7FA` → **`#F3F5F8`** | `#0B1117` → **`#1B202B`** | changed |
| `colorOnBackground` | `app_on_background` | `#1A1C1E` → **`#202937`** | `#E2E4E7` → **`#F0F2F7`** | changed |
| `colorOutline` | `app_outline` | `#71787E` → **`#6B7685`** | `#8B9198` → **`#8D97A8`** | changed |
| `colorOutlineVariant` | `app_outline_variant` | `#C5CED8` → **`#CDD4DE`** | `#334454` → **`#505B6F`** | changed |
| `android:statusBarColor` | `app_top_app_bar` | `#071A2D` → **`#23354B`** | `#07121E` → **`#171F2D`** | changed |
| `android:navigationBarColor` | `app_background` | `#F4F7FA` → **`#F3F5F8`** | `#0B1117` → **`#1B202B`** | changed |
| `colorStatusSuccess` | `status_success` | `#1E6B2A` → **`#17693F`** | `#81C995` → **`#7DD6A7`** | changed |
| `colorStatusSuccessContainer` | `status_success_container` | `#CDEBD2` → **`#D3EFDF`** | `#1E4D2B` → **`#1F4A36`** | changed |
| `colorOnStatusSuccessContainer` | `status_on_success_container` | `#0B3D14` → **`#0B3B22`** | `#C8F0D0` → **`#C9F2DC`** | changed |
| `colorStatusWarning` | `status_warning` | `#9A4700` → **`#8C5000`** | `#F7C66A` → **`#F2C26B`** | changed |
| `colorStatusWarningContainer` | `status_warning_container` | `#FFDEA8` → **`#FBE3B6`** | `#5D4100` → **`#54401A`** | changed |
| `colorOnStatusWarningContainer` | `status_on_warning_container` | `#2E1A00` → **`#3A2400`** | `#FFDEA8` → **`#FCE4B4`** | changed |
| `colorStatusError` | `status_error` | `#BA1A1A` → **`#B3261E`** | `#FFB4AB` → **`#F29A9A`** | changed |
| `colorStatusErrorContainer` | `status_error_container` | `#FFDAD6` → **`#FCDCDA`** | `#93000A` → **`#5E2A2D`** | changed |
| `colorOnStatusErrorContainer` | `status_on_error_container` | `#410002` → **`#5C0B0B`** | `#FFDAD6` → **`#FCDADA`** | changed |
| `colorStatusInfo` | `status_info` | `#155DA8` → **`#00658A`** | `#9CC8FF` → **`#7CCBEF`** | changed |
| `colorStatusInfoContainer` | `status_info_container` | `#D7E8FF` → **`#CDEBF6`** | `#0F3A63` → **`#1D4A5C`** | changed |
| `colorOnStatusInfoContainer` | `status_on_info_container` | `#001B44` → **`#002C3D`** | `#D7E8FF` → **`#C9ECFA`** | changed |
| `colorStatusNeutral` | `status_neutral` | `#41484D` → **`#515D6C`** | `#C1C8CE` → **`#C8CED8`** | changed |
| `colorStatusNeutralContainer` | `status_neutral_container` | `#E3E7ED` → **`#E3E7EE`** | `#2A3644` → **`#3A4252`** | changed |
| `colorOnStatusNeutralContainer` | `status_on_neutral_container` | `#1A1C1E` → **`#202937`** | `#E2E4E7` → **`#E4E8EF`** | changed |

`Theme.SurveyingApp.PopupOverlay` (both files) follows through its `app_*` references; its hard-coded
`colorControlHighlight` becomes `@color/app_control_highlight`. `textAppearanceLabelSmall`, menu styles and
`windowLightStatusBar=false` are unchanged.

### 2.3 Implementation items beyond value swaps (still colour-only)

Usage rules (cobalt only for interactive / selected; neutral for headings, labels, values) need colour-attribute
reference changes in a few places. No size, weight, spacing or structure changes. **All items are implemented on
`feature/graphite-cobalt-theme`.**

| # | Where | Change |
|---|---|---|
| I-1 | `layout/fragment_home.xml`, `layout-sw600dp/fragment_home.xml` | Card titles ("Current Location", "Field Status", "Coordinate Settings", "Quick Actions", "Survey Data") `textColor` `?attr/colorPrimary` → `?attr/colorOnSurface`; their header icons `app:tint` `colorPrimary` → `colorOnSurfaceVariant`; Coordinates count `colorPrimary` → `colorOnSurface`; Models row icon `colorSecondary` → `colorOnSurfaceVariant`, Models count `colorSecondary` → `colorOnSurface`, Models "View" chip `colorSecondary` / `colorOnSecondary` → `colorPrimary` / `colorOnPrimary` (same as the Coordinates "View"). Header actions ("Open Map", "Change") and Quick Action chips stay cobalt. |
| I-2 | `values/styles.xml` | `TextAppearance.Settings.SectionHeader` and `TextAppearance.App.CardSectionLabel` (and so `TextAppearance.App.Settings.CardHeader`) `android:textColor` `?attr/colorPrimary` → `?attr/colorOnSurfaceVariant`. |
| I-3 | Settings card section labels with an explicit `textColor="?attr/colorPrimary"` (LabelLarge): `content_settings_about` (2), `_appearance` (3), `_ar_display` (5), `_coordinate_display` (3), `_data` (3), `_developer` (1), `_gnss_capture` (4), `_location` (3), `dev_page_permissions` (1), `item_receiver_picker_header`, `view_receiver_card` (`text_receiver_source`) | → `?attr/colorOnSurfaceVariant`. |
| I-4 | `fragment_coordinate_detail.xml` section labels (Summary, Location, Projection / CRS, … 9 LabelLarge) | → `?attr/colorOnSurfaceVariant`. |
| I-5 | `dialog_capture_coordinate.xml` ("Current location", "External GNSS capture"), `fragment_render_map.xml` ("SELECTED POINT", "Stakeout Target") | → `?attr/colorOnSurfaceVariant`. |
| I-6 | `color/nav_item_color.xml` (drawer) | checked state `?attr/colorOnPrimaryContainer` → `?attr/colorPrimary` (cobalt selected text / icon, as the settings sidebar already does). |
| I-7 | `themes.xml` (both) | point the M3 error, inverse, surfaceContainerLowest/Low roles at new `app_*` resources (§2.2) and add `colorSurfaceDim` / `colorSurfaceBright` (today they fall back to Material's baseline purple-greys). PopupOverlay `colorControlHighlight` `#1A155DA8` / `#1A8FC5FF` → `@color/app_control_highlight` (`#1A245BC2` / `#1AA8C6FF`). |
| I-8 | `dev_category_selected_bg` → `@color/nav_selected_bg`; `dev_category_selected_accent` and `coordinate_card_stroke_selected` → `@color/app_primary` (they pointed at `md_primary_90/30/40`, i.e. not the theme's cobalt). |
| I-9 | `HomeFragment` NEUTRAL severity and `GnssStatusSheet` headline fallback use `colorPrimary` for a non-interactive status ("Connecting…") | → `colorStatusNeutral` (Kotlin attribute reference only). |
| I-10 | `ReceiverCardBinder` fix chip: Fix used the cobalt primary container, FLOAT and DGPS the tertiary (amber) container | FIX → `colorStatusSuccessContainer` / on, FLOAT → `colorStatusWarningContainer` / on, DGPS → `colorStatusInfoContainer` / on (matches §1.5). Colour mapping only; `CardFix` classification unchanged. The connection-state chip (Connected / Testing / Failed) is not a GNSS status and keeps its colours. |
| I-11 | Interactive cobalt kept as is: text buttons / links, `Widget.App.Button.Primary`, switches, sliders, focus, selected list row (`primaryContainer` + 4 dp `colorPrimary` accent), `dialog_add_point` "▸ Model placement" toggle, model card edit icon, file/model picker toolbars (`colorPrimary` background with `colorOnPrimary`). | no change |
| I-13 | `drawable/ic_more_vert_24.xml` (coordinate list ⋮, map points panel) | Root cause: white-filled vector that relied on the use site's `app:tint`; `app:tint` is only applied by the AppCompat inflater, so in any plain `LayoutInflater` context (the previews, and any non-AppCompat inflation) the dots were white on the white list row ("nearly invisible"). On the tablet the AppCompat inflater did apply it. Fix: root `android:tint="?attr/colorOnSurfaceVariant"` (6.70 light / 7.54 dark on the card, ≥ 5.0 on every surface incl. the selected row); use-site tints still override. Removed from the `HardcodedColorGuardTest` drawable allowlist (stricter). No geometry change. |
| I-12 | Tests updated with the implementation: `PaletteContrastTest` strip pin `#0F3554` → `#2A4158` (light and night classes, the "two assertions"); new checks for app bar text, cyan info on every surface, the ⋮ tint and selected navigation; surfaceContainerLow / Lowest added to the checked surfaces; `docs/design-system.md` tables; `GnssToolbarColors` KDoc. `WcagTest` keeps `#0F3554` as a pure-maths fixture (not a palette pin). No threshold lowered. | done |

## 3. Previews (before / after)

Rendered by the throwaway `GraphiteCobaltPreviewTest` with Robolectric native graphics at the SM-X940 size
(`sw924dp-w1480dp-h924dp-land-hdpi`, so the `sw600dp` / `sw600dp-w700dp` layouts are used), light and dark, on `main`
("before") and `feature/graphite-cobalt-theme` ("after"). Real layouts and the existing fixtures (`ReceiverUiFixtures`,
`GnssReportFixtures`, `ToolbarFitTest.buildStrip`, the real `SettingsCategoryAdapter`, `ReceiverCardPresenter` /
`ReceiverCardBinder`, `StatusTone`). Map tiles and model thumbnails are not renderable in Robolectric (blank areas).
Output: `app/build/previews/graphite2/`, copied to the session scratchpad `graphite2/before` and `graphite2/after`.

| Screen | File stem (`_light` / `_dark`; `_13x_` = font scale 1.3) |
|---|---|
| App bar + GNSS strip (tablet, phone) | `chrome_appbar_toolbar_1480dp`, `chrome_appbar_toolbar_411dp` |
| GNSS strip warning pills | `chrome_toolbar_stale_corrections` (caution), `chrome_toolbar_no_fix` (critical) |
| Home (tablet) | `home_tablet`, `home_tablet_13x` |
| Settings → Appearance / → Receiver (two-pane) | `settings_appearance`, `settings_receiver` |
| Receiver card, fix chip Fix / Float / DGPS | `receiver_card_fix_float_dgps` |
| GNSS Status report (900 dp) | `gnss_report_900dp` |
| Coordinates list with ⋮ (plain inflater = how the previews inflate) | `coordinates_list`, `coordinates_list_13x` |
| Coordinates list with ⋮ (AppCompat inflater = how the app inflates) | `coordinates_list_appcompat_inflater` |
| Coordinate detail / rows + tiles | `coordinates_detail`, `coordinates_detail_13x`, `coordinates_detail_rows` |
| Models list | `models_list` |

## 4. Contrast report (WCAG 2.x, existing `Wcag` helper)

Computed by the throwaway `GraphiteCobaltContrastReportTest` on the resolved theme (Robolectric, light and `night`):
before = `main` @ `fe471f9b`, after = `feature/graphite-cobalt-theme`. Min: 4.5 normal text, 3.0 large text / meaningful
graphics / control boundaries, "info" = reported for design judgement (fills, card borders, decorative).
**No pair is below its minimum after the change.** The committed `PaletteContrastLightTest` / `PaletteContrastNightTest`
enforce the same roles (plus the new info, ⋮, app-bar and selected-navigation checks) and pass on the feature branch.

Key pairs:
- Cobalt button: label 6.27 (light, white on `#245BC2`) / 8.22 (dark, `#0F2A55` on `#A8C6FF`); fill vs card 6.27 / 6.92.
- Links (colorPrimary text) ≥ 5.00 on every light surface up to `surfaceContainerHigh` and 4.68 on
  `surfaceContainerHighest`; dark ≥ 5.60.
- Info (cyan): light ≥ 4.86, dark ≥ 5.36 on every surface (§1.4); on-container 11.77 / 7.71.
- ⋮ overflow icon (`colorOnSurfaceVariant`): ≥ 5.01 light / ≥ 6.11 dark on every surface, 5.59 / 6.71 on the selected row.
- Selected navigation: fg on selected bg 5.23 (light) / 6.16 (dark). Selected bg vs sidebar is 1.00 / 1.26: the selection
  is identified by the cobalt text + icon and, in Settings, the 4 dp cobalt accent bar (5.21 / 7.77), not by the fill (D7).
- Card borders (`outlineVariant`) 1.49 / 1.74 on the card: non-interactive grouping, supplementary to the fill step
  (D10, kept). Control borders (`outline`) ≥ 3.83 (light) / 4.05 (dark).
- Strip: every text role ≥ 5.79 on `#2A4158` (info 5.84); separator 3.31; critical pill fill 1.87 (edge carried by the
  1 dp `toolbar_error` outline, 5.79); caution pill fill 5.86; pill text 5.62 / 9.70.

### 4.1 Light

| Pair | Before | After | Min |
|---|---|---|---|
| onSurface text on page | `#1A1C1E` on `#F4F7FA` 15.90 | `#202937` on `#F3F5F8` **13.41** | 4.5 |
| onSurface text on card | `#1A1C1E` on `#FFFFFF` 17.09 | `#202937` on `#FFFFFF` **14.65** | 4.5 |
| onSurface text on sidebar(surfaceVariant) | `#1A1C1E` on `#EAF0F6` 14.89 | `#202937` on `#E8EAF0` **12.18** | 4.5 |
| onSurface text on drawer(surfaceContainer) | `#1A1C1E` on `#EAF0F6` 14.89 | `#202937` on `#E8EAF0` **12.18** | 4.5 |
| onSurface text on secondary(surfaceContainerLow) | `#1A1C1E` on `#F5F7FB` 15.93 | `#202937` on `#EEF2F6` **13.02** | 4.5 |
| onSurface text on elevated(surfaceContainerHigh) | `#1A1C1E` on `#DFE7F0` 13.69 | `#202937` on `#E2E6EC` **11.69** | 4.5 |
| onSurface text on surfaceContainerHighest | `#1A1C1E` on `#D4DCE8` 12.37 | `#202937` on `#DADFE7` **10.94** | 4.5 |
| onSurfaceVariant text on page | `#41484D` on `#F4F7FA` 8.65 | `#515D6C` on `#F3F5F8` **6.14** | 4.5 |
| onSurfaceVariant text on card | `#41484D` on `#FFFFFF` 9.30 | `#515D6C` on `#FFFFFF` **6.70** | 4.5 |
| onSurfaceVariant text on sidebar(surfaceVariant) | `#41484D` on `#EAF0F6` 8.10 | `#515D6C` on `#E8EAF0` **5.57** | 4.5 |
| onSurfaceVariant text on drawer(surfaceContainer) | `#41484D` on `#EAF0F6` 8.10 | `#515D6C` on `#E8EAF0` **5.57** | 4.5 |
| onSurfaceVariant text on secondary(surfaceContainerLow) | `#41484D` on `#F5F7FB` 8.67 | `#515D6C` on `#EEF2F6` **5.96** | 4.5 |
| onSurfaceVariant text on elevated(surfaceContainerHigh) | `#41484D` on `#DFE7F0` 7.45 | `#515D6C` on `#E2E6EC` **5.35** | 4.5 |
| onSurfaceVariant text on surfaceContainerHighest | `#41484D` on `#D4DCE8` 6.73 | `#515D6C` on `#DADFE7` **5.01** | 4.5 |
| colorPrimary link on page | `#155DA8` on `#F4F7FA` 6.17 | `#245BC2` on `#F3F5F8` **5.74** | 4.5 |
| colorPrimary link on card | `#155DA8` on `#FFFFFF` 6.64 | `#245BC2` on `#FFFFFF` **6.27** | 4.5 |
| colorPrimary link on sidebar(surfaceVariant) | `#155DA8` on `#EAF0F6` 5.78 | `#245BC2` on `#E8EAF0` **5.21** | 4.5 |
| colorPrimary link on drawer(surfaceContainer) | `#155DA8` on `#EAF0F6` 5.78 | `#245BC2` on `#E8EAF0` **5.21** | 4.5 |
| colorPrimary link on secondary(surfaceContainerLow) | `#155DA8` on `#F5F7FB` 6.19 | `#245BC2` on `#EEF2F6` **5.57** | 4.5 |
| colorPrimary link on elevated(surfaceContainerHigh) | `#155DA8` on `#DFE7F0` 5.32 | `#245BC2` on `#E2E6EC` **5.00** | 4.5 |
| colorPrimary link on surfaceContainerHighest | `#155DA8` on `#D4DCE8` 4.81 | `#245BC2` on `#DADFE7` **4.68** | 4.5 |
| onPrimary label on colorPrimary button | `#FFFFFF` on `#155DA8` 6.64 | `#FFFFFF` on `#245BC2` **6.27** | 4.5 |
| colorPrimary button fill vs card | `#155DA8` on `#FFFFFF` 6.64 | `#245BC2` on `#FFFFFF` **6.27** | 3.0 |
| colorPrimary button fill vs page | `#155DA8` on `#F4F7FA` 6.17 | `#245BC2` on `#F3F5F8` **5.74** | 3.0 |
| onPrimaryContainer on primaryContainer (tonal chip, selected row) | `#001B44` on `#D7E8FF` 13.61 | `#102F66` on `#E2EBFC` **10.81** | 4.5 |
| onSecondary on colorSecondary | `#FFFFFF` on `#00796B` 5.32 | `#FFFFFF` on `#4A5A70` **7.03** | 4.5 |
| colorSecondary on card | `#00796B` on `#FFFFFF` 5.32 | `#4A5A70` on `#FFFFFF` **7.03** | 4.5 |
| onSecondaryContainer on secondaryContainer | `#002019` on `#BFEDE6` 13.49 | `#1C2533` on `#DCE3ED` **11.94** | 4.5 |
| onTertiaryContainer on tertiaryContainer | `#271900` on `#FFDEA8` 13.27 | `#271900` on `#FFDEA8` **13.27** | 4.5 |
| colorError on card | `#BA1A1A` on `#FFFFFF` 6.46 | `#B3261E` on `#FFFFFF` **6.54** | 4.5 |
| onError on colorError | `#FFFFFF` on `#BA1A1A` 6.46 | `#FFFFFF` on `#B3261E` **6.54** | 4.5 |
| onErrorContainer on errorContainer | `#410002` on `#FFDAD6` 13.26 | `#5C0B0B` on `#FCDCDA` **10.87** | 4.5 |
| drawer selected fg (nav_item_color checked) on nav_selected_bg | `#001B44` on `#D6E4FF` 13.22 | `#245BC2` on `#E2EBFC` **5.23** | 4.5 |
| nav_selected_bg vs drawer | `#D6E4FF` on `#EAF0F6` 1.12 | `#E2EBFC` on `#E8EAF0` **1.00** | info |
| settings selected fg (colorPrimary) on dev_category_selected_bg | `#155DA8` on `#D6E4FF` 5.19 | `#245BC2` on `#E2EBFC` **5.23** | 4.5 |
| dev_category_selected_bg vs sidebar | `#D6E4FF` on `#EAF0F6` 1.12 | `#E2EBFC` on `#E8EAF0` **1.00** | info |
| settings selected accent bar vs sidebar | `#1A56A4` on `#EAF0F6` 6.28 | `#245BC2` on `#E8EAF0` **5.21** | 3.0 |
| settings unselected fg (onSurfaceVariant) on sidebar | `#41484D` on `#EAF0F6` 8.10 | `#515D6C` on `#E8EAF0` **5.57** | 4.5 |
| colorOutline (field/control border) vs card | `#71787E` on `#FFFFFF` 4.48 | `#6B7685` on `#FFFFFF` **4.61** | 3.0 |
| colorOutline (field/control border) vs page | `#71787E` on `#F4F7FA` 4.16 | `#6B7685` on `#F3F5F8` **4.22** | 3.0 |
| colorOutline (field/control border) vs sidebar | `#71787E` on `#EAF0F6` 3.90 | `#6B7685` on `#E8EAF0` **3.83** | 3.0 |
| colorOutlineVariant (card border/divider) vs card | `#C5CED8` on `#FFFFFF` 1.59 | `#CDD4DE` on `#FFFFFF` **1.49** | info |
| colorOutlineVariant (card border/divider) vs page | `#C5CED8` on `#F4F7FA` 1.48 | `#CDD4DE` on `#F3F5F8` **1.37** | info |
| colorOutlineVariant (card border/divider) vs sidebar | `#C5CED8` on `#EAF0F6` 1.39 | `#CDD4DE` on `#E8EAF0` **1.24** | info |
| card fill vs page | `#FFFFFF` on `#F4F7FA` 1.08 | `#FFFFFF` on `#F3F5F8` **1.09** | info |
| sidebar fill vs page | `#EAF0F6` on `#F4F7FA` 1.07 | `#E8EAF0` on `#F3F5F8` **1.10** | info |
| sidebar fill vs card | `#EAF0F6` on `#FFFFFF` 1.15 | `#E8EAF0` on `#FFFFFF` **1.20** | info |
| elevated fill vs card | `#DFE7F0` on `#FFFFFF` 1.25 | `#E2E6EC` on `#FFFFFF` **1.25** | info |
| status_success on card | `#1E6B2A` on `#FFFFFF` 6.58 | `#17693F` on `#FFFFFF` **6.71** | 4.5 |
| status_success on page | `#1E6B2A` on `#F4F7FA` 6.12 | `#17693F` on `#F3F5F8` **6.15** | 4.5 |
| status_success on sidebar | `#1E6B2A` on `#EAF0F6` 5.73 | `#17693F` on `#E8EAF0` **5.58** | 4.5 |
| status_success on elevated | `#1E6B2A` on `#DFE7F0` 5.27 | `#17693F` on `#E2E6EC` **5.36** | 4.5 |
| status_success on surfaceContainerHighest | `#1E6B2A` on `#D4DCE8` 4.76 | `#17693F` on `#DADFE7` **5.01** | 4.5 |
| status_on_success_container on container | `#0B3D14` on `#CDEBD2` 9.70 | `#0B3B22` on `#D3EFDF` **10.33** | 4.5 |
| status_success_container fill vs card | `#CDEBD2` on `#FFFFFF` 1.28 | `#D3EFDF` on `#FFFFFF` **1.22** | info |
| status_warning on card | `#9A4700` on `#FFFFFF` 6.41 | `#8C5000` on `#FFFFFF` **6.44** | 4.5 |
| status_warning on page | `#9A4700` on `#F4F7FA` 5.96 | `#8C5000` on `#F3F5F8` **5.89** | 4.5 |
| status_warning on sidebar | `#9A4700` on `#EAF0F6` 5.59 | `#8C5000` on `#E8EAF0` **5.35** | 4.5 |
| status_warning on elevated | `#9A4700` on `#DFE7F0` 5.14 | `#8C5000` on `#E2E6EC` **5.14** | 4.5 |
| status_warning on surfaceContainerHighest | `#9A4700` on `#D4DCE8` 4.64 | `#8C5000` on `#DADFE7` **4.81** | 4.5 |
| status_on_warning_container on container | `#2E1A00` on `#FFDEA8` 12.87 | `#3A2400` on `#FBE3B6` **11.71** | 4.5 |
| status_warning_container fill vs card | `#FFDEA8` on `#FFFFFF` 1.29 | `#FBE3B6` on `#FFFFFF` **1.25** | info |
| status_error on card | `#BA1A1A` on `#FFFFFF` 6.46 | `#B3261E` on `#FFFFFF` **6.54** | 4.5 |
| status_error on page | `#BA1A1A` on `#F4F7FA` 6.01 | `#B3261E` on `#F3F5F8` **5.98** | 4.5 |
| status_error on sidebar | `#BA1A1A` on `#EAF0F6` 5.63 | `#B3261E` on `#E8EAF0` **5.43** | 4.5 |
| status_error on elevated | `#BA1A1A` on `#DFE7F0` 5.18 | `#B3261E` on `#E2E6EC` **5.22** | 4.5 |
| status_error on surfaceContainerHighest | `#BA1A1A` on `#D4DCE8` 4.68 | `#B3261E` on `#DADFE7` **4.88** | 4.5 |
| status_on_error_container on container | `#410002` on `#FFDAD6` 13.26 | `#5C0B0B` on `#FCDCDA` **10.87** | 4.5 |
| status_error_container fill vs card | `#FFDAD6` on `#FFFFFF` 1.29 | `#FCDCDA` on `#FFFFFF` **1.28** | info |
| status_info on card | `#155DA8` on `#FFFFFF` 6.64 | `#00658A` on `#FFFFFF` **6.50** | 4.5 |
| status_info on page | `#155DA8` on `#F4F7FA` 6.17 | `#00658A` on `#F3F5F8` **5.96** | 4.5 |
| status_info on sidebar | `#155DA8` on `#EAF0F6` 5.78 | `#00658A` on `#E8EAF0` **5.41** | 4.5 |
| status_info on elevated | `#155DA8` on `#DFE7F0` 5.32 | `#00658A` on `#E2E6EC` **5.19** | 4.5 |
| status_info on surfaceContainerHighest | `#155DA8` on `#D4DCE8` 4.81 | `#00658A` on `#DADFE7` **4.86** | 4.5 |
| status_on_info_container on container | `#001B44` on `#D7E8FF` 13.61 | `#002C3D` on `#CDEBF6` **11.77** | 4.5 |
| status_info_container fill vs card | `#D7E8FF` on `#FFFFFF` 1.24 | `#CDEBF6` on `#FFFFFF` **1.25** | info |
| status_neutral on card | `#41484D` on `#FFFFFF` 9.30 | `#515D6C` on `#FFFFFF` **6.70** | 4.5 |
| status_neutral on page | `#41484D` on `#F4F7FA` 8.65 | `#515D6C` on `#F3F5F8` **6.14** | 4.5 |
| status_neutral on sidebar | `#41484D` on `#EAF0F6` 8.10 | `#515D6C` on `#E8EAF0` **5.57** | 4.5 |
| status_neutral on elevated | `#41484D` on `#DFE7F0` 7.45 | `#515D6C` on `#E2E6EC` **5.35** | 4.5 |
| status_neutral on surfaceContainerHighest | `#41484D` on `#D4DCE8` 6.73 | `#515D6C` on `#DADFE7` **5.01** | 4.5 |
| status_on_neutral_container on container | `#1A1C1E` on `#E3E7ED` 13.77 | `#202937` on `#E3E7EE` **11.81** | 4.5 |
| status_neutral_container fill vs card | `#E3E7ED` on `#FFFFFF` 1.24 | `#E3E7EE` on `#FFFFFF` **1.24** | info |
| app_on_top_app_bar on app bar | `#FFFFFF` on `#071A2D` 17.58 | `#FFFFFF` on `#23354B` **12.48** | 4.5 |
| app bar vs GNSS strip | `#071A2D` on `#0F3554` 1.39 | `#23354B` on `#2A4158` **1.19** | info |
| GNSS strip vs page | `#0F3554` on `#F4F7FA` 11.78 | `#2A4158` on `#F3F5F8` **9.64** | info |
| toolbar_on_primary on strip | `#F2F6FA` on `#0F3554` 11.67 | `#F2F6FA` on `#2A4158` **9.69** | 4.5 |
| toolbar_on_secondary on strip | `#B8C7D6` on `#0F3554` 7.35 | `#B8C7D6` on `#2A4158` **6.10** | 4.5 |
| toolbar_success on strip | `#81C995` on `#0F3554` 6.47 | `#7DD6A7` on `#2A4158` **6.04** | 4.5 |
| toolbar_warning on strip | `#F7C66A` on `#0F3554` 7.99 | `#F7C66A` on `#2A4158` **6.64** | 4.5 |
| toolbar_error on strip | `#FFAAA5` on `#0F3554` 6.97 | `#FFAAA5` on `#2A4158` **5.79** | 4.5 |
| toolbar_info on strip | `#9CC8FF` on `#0F3554` 7.31 | `#7CCBEF` on `#2A4158` **5.84** | 4.5 |
| app_status_separator on strip | `#6F8598` on `#0F3554` 3.31 | `#7D93A8` on `#2A4158` **3.31** | 3.0 |
| critical pill text on pill | `#FFFFFF` on `#C62828` 5.62 | `#FFFFFF` on `#C62828` **5.62** | 4.5 |
| critical pill fill vs strip | `#C62828` on `#0F3554` 2.25 | `#C62828` on `#2A4158` **1.87** | info (outline carries edge) |
| critical pill outline vs strip | `#FFAAA5` on `#0F3554` 6.97 | `#FFAAA5` on `#2A4158` **5.79** | 3.0 |
| caution pill text on pill | `#1A1A1A` on `#FFB300` 9.70 | `#1A1A1A` on `#FFB300` **9.70** | 4.5 |
| caution pill fill vs strip | `#FFB300` on `#0F3554` 7.06 | `#FFB300` on `#2A4158` **5.86** | 3.0 |
| chart_text on card | `#424242` on `#FFFFFF` 10.05 | `#515D6C` on `#FFFFFF` **6.70** | 4.5 |
| chart_axis on card | `#71787E` on `#FFFFFF` 4.48 | `#6B7685` on `#FFFFFF` **4.61** | 3.0 |
| chart_grid on card (decorative) | `#C5CED8` on `#FFFFFF` 1.59 | `#CDD4DE` on `#FFFFFF` **1.49** | info |
| chart_text on drawer | `#424242` on `#EAF0F6` 8.75 | `#515D6C` on `#E8EAF0` **5.57** | 4.5 |
| chart_axis on drawer | `#71787E` on `#EAF0F6` 3.90 | `#6B7685` on `#E8EAF0` **3.83** | 3.0 |
| chart_grid on drawer (decorative) | `#C5CED8` on `#EAF0F6` 1.39 | `#CDD4DE` on `#E8EAF0` **1.24** | info |

### 4.2 Dark

| Pair | Before | After | Min |
|---|---|---|---|
| onSurface text on page | `#E2E4E7` on `#0B1117` 14.89 | `#F0F2F7` on `#1B202B` **14.56** | 4.5 |
| onSurface text on card | `#E2E4E7` on `#101820` 14.05 | `#F0F2F7` on `#303746` **10.65** | 4.5 |
| onSurface text on sidebar(surfaceVariant) | `#E2E4E7` on `#1D2A36` 11.47 | `#F0F2F7` on `#292F3D` **11.96** | 4.5 |
| onSurface text on drawer(surfaceContainer) | `#E2E4E7` on `#16212B` 12.81 | `#F0F2F7` on `#292F3D` **11.96** | 4.5 |
| onSurface text on secondary(surfaceContainerLow) | `#E2E4E7` on `#111920` 13.93 | `#F0F2F7` on `#252C38` **12.53** | 4.5 |
| onSurface text on elevated(surfaceContainerHigh) | `#E2E4E7` on `#1D2A36` 11.47 | `#F0F2F7` on `#394253` **9.02** | 4.5 |
| onSurface text on surfaceContainerHighest | `#E2E4E7` on `#243042` 10.46 | `#F0F2F7` on `#3C4555` **8.62** | 4.5 |
| onSurfaceVariant text on page | `#C1C8CE` on `#0B1117` 11.22 | `#C8CED8` on `#1B202B` **10.31** | 4.5 |
| onSurfaceVariant text on card | `#C1C8CE` on `#101820` 10.58 | `#C8CED8` on `#303746` **7.54** | 4.5 |
| onSurfaceVariant text on sidebar(surfaceVariant) | `#C1C8CE` on `#1D2A36` 8.65 | `#C8CED8` on `#292F3D` **8.47** | 4.5 |
| onSurfaceVariant text on drawer(surfaceContainer) | `#C1C8CE` on `#16212B` 9.65 | `#C8CED8` on `#292F3D` **8.47** | 4.5 |
| onSurfaceVariant text on secondary(surfaceContainerLow) | `#C1C8CE` on `#111920` 10.49 | `#C8CED8` on `#252C38` **8.87** | 4.5 |
| onSurfaceVariant text on elevated(surfaceContainerHigh) | `#C1C8CE` on `#1D2A36` 8.65 | `#C8CED8` on `#394253` **6.39** | 4.5 |
| onSurfaceVariant text on surfaceContainerHighest | `#C1C8CE` on `#243042` 7.88 | `#C8CED8` on `#3C4555` **6.11** | 4.5 |
| colorPrimary link on page | `#8FC5FF` on `#0B1117` 10.48 | `#A8C6FF` on `#1B202B` **9.46** | 4.5 |
| colorPrimary link on card | `#8FC5FF` on `#101820` 9.88 | `#A8C6FF` on `#303746` **6.92** | 4.5 |
| colorPrimary link on sidebar(surfaceVariant) | `#8FC5FF` on `#1D2A36` 8.07 | `#A8C6FF` on `#292F3D` **7.77** | 4.5 |
| colorPrimary link on drawer(surfaceContainer) | `#8FC5FF` on `#16212B` 9.02 | `#A8C6FF` on `#292F3D` **7.77** | 4.5 |
| colorPrimary link on secondary(surfaceContainerLow) | `#8FC5FF` on `#111920` 9.80 | `#A8C6FF` on `#252C38` **8.15** | 4.5 |
| colorPrimary link on elevated(surfaceContainerHigh) | `#8FC5FF` on `#1D2A36` 8.07 | `#A8C6FF` on `#394253` **5.86** | 4.5 |
| colorPrimary link on surfaceContainerHighest | `#8FC5FF` on `#243042` 7.36 | `#A8C6FF` on `#3C4555` **5.60** | 4.5 |
| onPrimary label on colorPrimary button | `#0F3A63` on `#8FC5FF` 6.42 | `#0F2A55` on `#A8C6FF` **8.22** | 4.5 |
| colorPrimary button fill vs card | `#8FC5FF` on `#101820` 9.88 | `#A8C6FF` on `#303746` **6.92** | 3.0 |
| colorPrimary button fill vs page | `#8FC5FF` on `#0B1117` 10.48 | `#A8C6FF` on `#1B202B` **9.46** | 3.0 |
| onPrimaryContainer on primaryContainer (tonal chip, selected row) | `#D7E8FF` on `#0F3A63` 9.35 | `#D9E5FF` on `#293F61` **8.39** | 4.5 |
| onSecondary on colorSecondary | `#003731` on `#79D7C9` 7.78 | `#1C2533` on `#B9C5D6` **8.83** | 4.5 |
| colorSecondary on card | `#79D7C9` on `#101820` 10.55 | `#B9C5D6` on `#303746` **6.83** | 4.5 |
| onSecondaryContainer on secondaryContainer | `#BCEDE4` on `#00534A` 7.01 | `#DCE3ED` on `#3A4658` **7.40** | 4.5 |
| onTertiaryContainer on tertiaryContainer | `#FFDEA8` on `#5D4100` 7.32 | `#FFDEA8` on `#5D4100` **7.32** | 4.5 |
| colorError on card | `#FFB4AB` on `#101820` 10.54 | `#F29A9A` on `#303746` **5.60** | 4.5 |
| onError on colorError | `#690005` on `#FFB4AB` 7.72 | `#5C0B0B` on `#F29A9A` **6.54** | 4.5 |
| onErrorContainer on errorContainer | `#FFDAD6` on `#93000A` 7.24 | `#FCDADA` on `#5E2A2D` **8.77** | 4.5 |
| drawer selected fg (nav_item_color checked) on nav_selected_bg | `#D7E8FF` on `#004688` 7.56 | `#A8C6FF` on `#293F61` **6.16** | 4.5 |
| nav_selected_bg vs drawer | `#004688` on `#16212B` 1.73 | `#293F61` on `#292F3D` **1.26** | info |
| settings selected fg (colorPrimary) on dev_category_selected_bg | `#8FC5FF` on `#004688` 5.20 | `#A8C6FF` on `#293F61` **6.16** | 4.5 |
| dev_category_selected_bg vs sidebar | `#004688` on `#1D2A36` 1.55 | `#293F61` on `#292F3D` **1.26** | info |
| settings selected accent bar vs sidebar | `#1A56A4` on `#1D2A36` 2.03 | `#A8C6FF` on `#292F3D` **7.77** | 3.0 |
| settings unselected fg (onSurfaceVariant) on sidebar | `#C1C8CE` on `#1D2A36` 8.65 | `#C8CED8` on `#292F3D` **8.47** | 4.5 |
| colorOutline (field/control border) vs card | `#8B9198` on `#101820` 5.63 | `#8D97A8` on `#303746` **4.05** | 3.0 |
| colorOutline (field/control border) vs page | `#8B9198` on `#0B1117` 5.96 | `#8D97A8` on `#1B202B` **5.53** | 3.0 |
| colorOutline (field/control border) vs sidebar | `#8B9198` on `#1D2A36` 4.59 | `#8D97A8` on `#292F3D` **4.54** | 3.0 |
| colorOutlineVariant (card border/divider) vs card | `#334454` on `#101820` 1.79 | `#505B6F` on `#303746` **1.74** | info |
| colorOutlineVariant (card border/divider) vs page | `#334454` on `#0B1117` 1.89 | `#505B6F` on `#1B202B` **2.38** | info |
| colorOutlineVariant (card border/divider) vs sidebar | `#334454` on `#1D2A36` 1.46 | `#505B6F` on `#292F3D` **1.96** | info |
| card fill vs page | `#101820` on `#0B1117` 1.06 | `#303746` on `#1B202B` **1.37** | info |
| sidebar fill vs page | `#1D2A36` on `#0B1117` 1.30 | `#292F3D` on `#1B202B` **1.22** | info |
| sidebar fill vs card | `#1D2A36` on `#101820` 1.22 | `#292F3D` on `#303746` **1.12** | info |
| elevated fill vs card | `#1D2A36` on `#101820` 1.22 | `#394253` on `#303746` **1.18** | info |
| status_success on card | `#81C995` on `#101820` 9.14 | `#7DD6A7` on `#303746` **6.84** | 4.5 |
| status_success on page | `#81C995` on `#0B1117` 9.69 | `#7DD6A7` on `#1B202B` **9.35** | 4.5 |
| status_success on sidebar | `#81C995` on `#1D2A36` 7.46 | `#7DD6A7` on `#292F3D` **7.68** | 4.5 |
| status_success on elevated | `#81C995` on `#1D2A36` 7.46 | `#7DD6A7` on `#394253` **5.80** | 4.5 |
| status_success on surfaceContainerHighest | `#81C995` on `#243042` 6.80 | `#7DD6A7` on `#3C4555` **5.54** | 4.5 |
| status_on_success_container on container | `#C8F0D0` on `#1E4D2B` 7.82 | `#C9F2DC` on `#1F4A36` **8.23** | 4.5 |
| status_success_container fill vs card | `#1E4D2B` on `#101820` 1.83 | `#1F4A36` on `#303746` **1.19** | info |
| status_warning on card | `#F7C66A` on `#101820` 11.28 | `#F2C26B` on `#303746` **7.22** | 4.5 |
| status_warning on page | `#F7C66A` on `#0B1117` 11.96 | `#F2C26B` on `#1B202B` **9.86** | 4.5 |
| status_warning on sidebar | `#F7C66A` on `#1D2A36` 9.22 | `#F2C26B` on `#292F3D` **8.10** | 4.5 |
| status_warning on elevated | `#F7C66A` on `#1D2A36` 9.22 | `#F2C26B` on `#394253` **6.11** | 4.5 |
| status_warning on surfaceContainerHighest | `#F7C66A` on `#243042` 8.40 | `#F2C26B` on `#3C4555` **5.84** | 4.5 |
| status_on_warning_container on container | `#FFDEA8` on `#5D4100` 7.32 | `#FCE4B4` on `#54401A` **7.95** | 4.5 |
| status_warning_container fill vs card | `#5D4100` on `#101820` 1.89 | `#54401A` on `#303746` **1.21** | info |
| status_error on card | `#FFB4AB` on `#101820` 10.54 | `#F29A9A` on `#303746` **5.60** | 4.5 |
| status_error on page | `#FFB4AB` on `#0B1117` 11.18 | `#F29A9A` on `#1B202B` **7.66** | 4.5 |
| status_error on sidebar | `#FFB4AB` on `#1D2A36` 8.61 | `#F29A9A` on `#292F3D` **6.29** | 4.5 |
| status_error on elevated | `#FFB4AB` on `#1D2A36` 8.61 | `#F29A9A` on `#394253` **4.75** | 4.5 |
| status_error on surfaceContainerHighest | `#FFB4AB` on `#243042` 7.85 | `#F29A9A` on `#3C4555` **4.54** | 4.5 |
| status_on_error_container on container | `#FFDAD6` on `#93000A` 7.24 | `#FCDADA` on `#5E2A2D` **8.77** | 4.5 |
| status_error_container fill vs card | `#93000A` on `#101820` 1.91 | `#5E2A2D` on `#303746` **1.05** | info |
| status_info on card | `#9CC8FF` on `#101820` 10.33 | `#7CCBEF` on `#303746` **6.61** | 4.5 |
| status_info on page | `#9CC8FF` on `#0B1117` 10.95 | `#7CCBEF` on `#1B202B` **9.04** | 4.5 |
| status_info on sidebar | `#9CC8FF` on `#1D2A36` 8.44 | `#7CCBEF` on `#292F3D` **7.43** | 4.5 |
| status_info on elevated | `#9CC8FF` on `#1D2A36` 8.44 | `#7CCBEF` on `#394253` **5.60** | 4.5 |
| status_info on surfaceContainerHighest | `#9CC8FF` on `#243042` 7.69 | `#7CCBEF` on `#3C4555` **5.36** | 4.5 |
| status_on_info_container on container | `#D7E8FF` on `#0F3A63` 9.35 | `#C9ECFA` on `#1D4A5C` **7.71** | 4.5 |
| status_info_container fill vs card | `#0F3A63` on `#101820` 1.54 | `#1D4A5C` on `#303746` **1.24** | info |
| status_neutral on card | `#C1C8CE` on `#101820` 10.58 | `#C8CED8` on `#303746` **7.54** | 4.5 |
| status_neutral on page | `#C1C8CE` on `#0B1117` 11.22 | `#C8CED8` on `#1B202B` **10.31** | 4.5 |
| status_neutral on sidebar | `#C1C8CE` on `#1D2A36` 8.65 | `#C8CED8` on `#292F3D` **8.47** | 4.5 |
| status_neutral on elevated | `#C1C8CE` on `#1D2A36` 8.65 | `#C8CED8` on `#394253` **6.39** | 4.5 |
| status_neutral on surfaceContainerHighest | `#C1C8CE` on `#243042` 7.88 | `#C8CED8` on `#3C4555` **6.11** | 4.5 |
| status_on_neutral_container on container | `#E2E4E7` on `#2A3644` 9.64 | `#E4E8EF` on `#3A4252` **8.21** | 4.5 |
| status_neutral_container fill vs card | `#2A3644` on `#101820` 1.46 | `#3A4252` on `#303746` **1.18** | info |
| app_on_top_app_bar on app bar | `#FFFFFF` on `#07121E` 18.85 | `#FFFFFF` on `#171F2D` **16.53** | 4.5 |
| app bar vs GNSS strip | `#07121E` on `#0F3554` 1.49 | `#171F2D` on `#2A4158` **1.57** | info |
| GNSS strip vs page | `#0F3554` on `#0B1117` 1.50 | `#2A4158` on `#1B202B` **1.55** | info |
| toolbar_on_primary on strip | `#F2F6FA` on `#0F3554` 11.67 | `#F2F6FA` on `#2A4158` **9.69** | 4.5 |
| toolbar_on_secondary on strip | `#B8C7D6` on `#0F3554` 7.35 | `#B8C7D6` on `#2A4158` **6.10** | 4.5 |
| toolbar_success on strip | `#81C995` on `#0F3554` 6.47 | `#7DD6A7` on `#2A4158` **6.04** | 4.5 |
| toolbar_warning on strip | `#F7C66A` on `#0F3554` 7.99 | `#F7C66A` on `#2A4158` **6.64** | 4.5 |
| toolbar_error on strip | `#FFAAA5` on `#0F3554` 6.97 | `#FFAAA5` on `#2A4158` **5.79** | 4.5 |
| toolbar_info on strip | `#9CC8FF` on `#0F3554` 7.31 | `#7CCBEF` on `#2A4158` **5.84** | 4.5 |
| app_status_separator on strip | `#6F8598` on `#0F3554` 3.31 | `#7D93A8` on `#2A4158` **3.31** | 3.0 |
| critical pill text on pill | `#FFFFFF` on `#C62828` 5.62 | `#FFFFFF` on `#C62828` **5.62** | 4.5 |
| critical pill fill vs strip | `#C62828` on `#0F3554` 2.25 | `#C62828` on `#2A4158` **1.87** | info (outline carries edge) |
| critical pill outline vs strip | `#FFAAA5` on `#0F3554` 6.97 | `#FFAAA5` on `#2A4158` **5.79** | 3.0 |
| caution pill text on pill | `#1A1A1A` on `#FFB300` 9.70 | `#1A1A1A` on `#FFB300` **9.70** | 4.5 |
| caution pill fill vs strip | `#FFB300` on `#0F3554` 7.06 | `#FFB300` on `#2A4158` **5.86** | 3.0 |
| chart_text on card | `#E2E4E7` on `#101820` 14.05 | `#F0F2F7` on `#303746` **10.65** | 4.5 |
| chart_axis on card | `#8B9198` on `#101820` 5.63 | `#8D97A8` on `#303746` **4.05** | 3.0 |
| chart_grid on card (decorative) | `#41484D` on `#101820` 1.92 | `#505B6F` on `#303746` **1.74** | info |
| chart_text on drawer | `#E2E4E7` on `#16212B` 12.81 | `#F0F2F7` on `#292F3D` **11.96** | 4.5 |
| chart_axis on drawer | `#8B9198` on `#16212B` 5.13 | `#8D97A8` on `#292F3D` **4.54** | 3.0 |
| chart_grid on drawer (decorative) | `#41484D` on `#16212B` 1.76 | `#505B6F` on `#292F3D` **1.96** | info |

## 5. Map and AR colours: intentionally unchanged

Ordinary theme colours are never applied to map tiles or camera imagery. These keep their current fixed values
(design-system "Intentional exceptions" 2–7):

| Resource / colour | Value | Why it stays |
|---|---|---|
| `ar_overlay_bg_dark` / `_medium` / `_heavy` (camera scrims) | `#CC000000` / `#66000000` / `#BB000000` | light-on-dark over any frame; graphite surfaces would vanish over bright frames |
| `ar_overlay_text`, `ar_overlay_text_secondary`, `ar_overlay_label_bg`, `ar_overlay_divider`, `ar_overlay_ripple` | `#FFFFFF`, `#CCCCCC`, `#CC111111`, `#33FFFFFF`, `#40FFFFFF` | validated on the scrim over a white frame (10.2 / 6.4) |
| `ar_overlay_model_accent` (cyan model-linked pins) | `#00E5FF` | distinct from every status hue and from cobalt; 6.6 on the pill |
| `ar_overlay_alert` (unused), `ar_overlay_warning` / `_error` | alias toolbar roles, unchanged values | |
| `ar_overlay_info` | alias of `toolbar_info` → `#7CCBEF` | cyan info on dark chrome; 7.0 on the chip scrim over white |
| `ar_overlay_success` | alias of `toolbar_success` → `#7DD6A7` | approved (D8): follows the success palette; 7.25 on the chip scrim over white |
| `map_overlay_bg`, `map_label_bg`, `map_label_text`, `map_grid_line` (light / night) | `#AAFFFFFF`, `#B3000000`, `#FFFFFF`, `#80616161` / `#80BDBDBD` | drawn over tiles; label 8.6:1 over a white tile |
| Map polylines, accuracy circle, stakeout line, marker borders (Kotlin, guard allowlist) | fixed hues | chosen for light + satellite tiles; not themed UI |
| User marker colours, test-cube colour | stored data | data, not theme |
| `model_viewer_canvas` / `_on_canvas` / `_on_canvas_error` | `#EEEEEE` / `#1A1C1E` / `#BA1A1A` | match the fixed Filament clear colour |
| `model_viewer_on_canvas_accent` (spinner) | `#1A56A4` → **`#245BC2`** | approved (D9): light cobalt, 5.40 on the fixed canvas in both themes |
| Chart constellation strokes (light / dark) | unchanged | data series, per-theme variants; all still ≥ 3.8 on the new card and drawer surfaces (D4) |
| Coordinate-toggle thumbnail backdrop | white | transparent thumbnails need a light backdrop |

Overlay changes: only the `ar_overlay_success` / `ar_overlay_info` aliases and the spinner (approved). Map/AR tool cards and chips already use
the dark scrims and keep them; the AR/Map screens' app bar uses `app_top_app_bar` and so picks up the new bar colour
(`fragment_render_map` top bar, `activity_model_viewer` toolbar) with white text (12.5 / 16.5).

## 6. Decisions (resolved 2026-11-26)

| # | Decision | Outcome |
|---|---|---|
| D1 | Secondary role | **Approved**: slate `#4A5A70` / `#B9C5D6`; Home Models row neutral + cobalt View chip. |
| D2 | Tertiary (amber) | Kept as proposed (unchanged values). The receiver fix chip no longer uses it (I-10); coordinate-settings accent still does. |
| D3 | Info vs cobalt | **Adjusted**: cyan info `#00658A` / `#7CCBEF` with containers `#CDEBF6`/`#002C3D`, `#1D4A5C`/`#C9ECFA`; `toolbar_info` / `ar_overlay_info` follow. |
| D4 | Chart constellation hues | Unchanged (user: do not change). |
| D5 | System status-bar colour on edge-to-edge Android | **Deferred** to a separate task; no window-inset change here. |
| D6 | Launcher artwork | Unchanged (user: do not change). |
| D7 | Selected rows / nav pill fill vs sidebar (1.00 / 1.26) | As proposed: identified by cobalt text + icon and the settings accent bar. |
| D8 | AR success | **Approved**: follows `toolbar_success` `#7DD6A7`. |
| D9 | Model-viewer spinner | **Approved**: `#245BC2` (5.40 on the canvas). |
| D10 | Card borders | **Kept** subtle graphite outlineVariant in both modes. |
| D11 | Unused colour resources | Left unchanged (separate cleanup). |
| D12 | Darker strip `#263B51` | **Rejected**: strip stays `#2A4158`. |
| — | ⋮ overflow icon nearly invisible (light preview) | **Fixed** (I-13). |
