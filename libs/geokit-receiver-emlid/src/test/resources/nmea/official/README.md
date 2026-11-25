# Official Emlid NMEA example sentences (test fixtures)

These files hold Emlid's **published** example sentences, copied character for character (including
checksums). They are not captures and not synthetic. Tests: `EmlidOfficialFixturesTest`.

| File | Source | Retrieved |
|---|---|---|
| `emlid_rs4_nmea_format_etc_examples.txt` | The six ETC examples on https://docs.emlid.com/reachrs4/specifications/nmea-format/ (in page order: tilt off / no GNSS solution; tilt off / GNSS solution; setup; alignment; compensating; compensating with fast-motion warning) | 2026-11-25 (also 2026-11-24, same text) |
| `emlid_rs4_nmea_format_ebp_example.txt` | The EBP example on the same page | 2026-11-25 |
| `emlid_etc_rev3_pdf_examples.txt` | The same six examples as printed in https://files.emlid.com/docs/ETC_rev.3_RS4.pdf ("Message rev. 1, Document rev. 3"), as transcribed in Phase 4 WP T (docs/geodesy/rs4-pro-tilt.md). The PDF prints different checksums; all but `*5A` are wrong and `*2H` is not hexadecimal. Kept to pin that the registry rejects them. | 2026-11-24 |

The web page shows no last-updated date. If Emlid changes its examples, add new files with the new
retrieval date rather than editing these.

Other fixture kinds in this repo:

- **Captured** (real receiver output): `libs/geokit-gnss/src/test/resources/nmea/reach_nmea_*.txt`.
- **Synthetic** (generated, file names contain `SYNTHETIC`): `../synthetic/` next to this folder.
