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
