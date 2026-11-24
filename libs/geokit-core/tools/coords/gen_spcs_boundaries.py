"""
Generate the SPCS83 zone-boundary resource used by SpcsZoneLocator (geokit-core).

SPCS83 zones are defined in state law mostly by county lines. The EPSG database (shipped with PROJ)
lists those counties in each zone's area-of-use description, e.g. "United States (USA) -
Pennsylvania - counties of Bradford; Cameron; ...". This script maps every county of the Census
cartographic boundary file to its zone from those descriptions, dissolves the county polygons per
zone and writes them as a compact binary resource.

usage: gen_spcs_boundaries.py <cb_2023_us_county_500k.zip> <out spcs83_zones.bin> <out county csv>

Inputs (both public, pinned):
  * PROJ 9.8.1 EPSG database via pyproj 3.8.0 (same as gen_spcs.py).
  * US Census Bureau cartographic boundary file, counties, 1:500,000, 2023 (public domain):
    https://www2.census.gov/geo/tiger/GENZ2023/shp/cb_2023_us_county_500k.zip
    (sha256 99d6597b1fc7767deef62e01d28d8b5dcbd578e151855f7dc0d173cbf5bf0868).
    Each dissolved zone is simplified with a 0.0005 deg (~50 m) tolerance (SIMPLIFY_DEG), so a zone line
    is within ~50 m of the 1:500k line. The 1:20M file (sha256 479956f6...e931e4) is still accepted
    unsimplified for comparison, but it departs from the 1:500k lines by up to several km along coasts
    and rivers (measured 2026-11-24: per-zone 95th percentile up to 3.4 km), so it is not used.
Tooling only: shapely 2.2.0 (polygon dissolve / clip). The shapefile and dBase readers are below.

Zones that are NOT written (the library falls back to the EPSG area-of-use box for them):
  * Alaska zones 1-10: defined by meridians/parallels and the Aleutians, not by boroughs.
States without an NAD83(2011) SPCS zone in EPSG (Hawaii) are not written either. The District of Columbia
is not named in any EPSG description; by NGS convention (NOAA Manual NOS NGS 5) it uses the Maryland zone,
so it is added to Maryland (DC_ZONE).

Special cases (asserted below):
  * Single-zone states, Kentucky Single Zone and Puerto Rico: every county-equivalent of the state.
    (Connecticut's 2023 Census file has planning regions instead of counties; it is single-zone.)
  * Kentucky: every county is in Kentucky Single Zone AND in Kentucky North or South (both are legal).
  * Washington: Grant County is split at 47 deg 30' N between North and South ("approximately" in EPSG).
  * Independent cities: EPSG lists counties and only some Virginia cities. Each unlisted independent city
    (Virginia, St. Louis MO, Carson City NV) takes the zone of the listed county it shares the longest
    boundary with; Virginia's result is checked against the Code of Virginia's North-zone city list.
  * Broomfield County, Colorado (created 2001) is not in the EPSG list nor the statute text; it is
    assigned to Colorado North as NGS and GIS practice do (ADDITIONS table).
  * Spelling differences between EPSG and Census are resolved by the ALIASES table (asserted complete:
    every EPSG county name must match exactly one Census county and every Census county must be assigned).

Output format (big-endian), read by SpcsZoneBoundaries.kt:
  magic "SPZB", u8 version (1), i32 scale (units per degree), u16 zone count, then per zone:
  i32 zone EPSG code, i32 ring count, and per ring: i32 point count followed by zigzag-varint
  deltas (lon, lat) in 1/scale degree (the first point relative to 0, 0). Rings of a zone are
  combined with the even-odd rule (exteriors and holes alike).
Output is deterministic: zones sorted by EPSG, rings in shapely's order for a fixed input, no timestamps.
"""
import hashlib
import io
import re
import struct
import sys
import unicodedata
import zipfile

from pyproj import CRS
from pyproj.database import query_crs_info
import shapely
from shapely.geometry import MultiPolygon, Polygon, box
from shapely.ops import unary_union

# sha256 -> (member stem in the zip, simplification tolerance in degrees or None)
CENSUS_FILES = {
    "99d6597b1fc7767deef62e01d28d8b5dcbd578e151855f7dc0d173cbf5bf0868": ("cb_2023_us_county_500k", 0.0005),
    "479956f6e3cb1573705c1ceb35d17ca7cd1b5179f3947ee6adbaa274b7e931e4": ("cb_2023_us_county_20m", None),
}
SCALE = 10000  # 1e-4 degree (~11 m): below the ~50 m simplification of the 1:500k county lines.

ALIASES = {  # (state FIPS, normalised EPSG spelling) -> normalised Census name
    ("12", "dade"): "miamidade",
    ("38", "mountrial"): "mountrail",
    ("46", "shannon"): "oglalalakota",      # renamed 2015
}
# Counties created after the EPSG/statute county lists; zone as published by NGS and used in GIS practice.
ADDITIONS = {("08", "Broomfield"): "Colorado North"}   # City and County of Broomfield (2001)
VA_NORTH_CITIES = {"Alexandria", "Fairfax", "Falls Church", "Fredericksburg", "Harrisonburg",
                   "Manassas", "Manassas Park", "Staunton", "Waynesboro", "Winchester"}
WA_GRANT_SPLIT_LAT = 47.5
DC_GEOID, DC_ZONE = "11001", "Maryland"   # NGS: DC uses the Maryland SPCS83 zone


def norm(name):
    s = unicodedata.normalize("NFKD", name).encode("ascii", "ignore").decode().lower()
    s = re.sub(r"^(saint|st\.?)\s+", "st ", s)
    return re.sub(r"[^a-z0-9]", "", s)


def read_dbf(data):
    n, hlen, rlen = struct.unpack("<IHH", data[4:12])
    fields, pos = [], 32
    while data[pos] != 0x0D:
        name = data[pos:pos + 11].split(b"\0")[0].decode("ascii")
        fields.append((name, data[pos + 16]))
        pos += 32
    out = []
    for i in range(n):
        rec = data[hlen + i * rlen: hlen + (i + 1) * rlen]
        off, row = 1, {}
        for name, length in fields:
            row[name] = rec[off:off + length].decode("utf-8").strip()
            off += length
        out.append(row)
    return out


def read_shp_polygons(data):
    pos, shapes = 100, []
    while pos < len(data):
        _, clen = struct.unpack(">ii", data[pos:pos + 8])
        content = data[pos + 8: pos + 8 + clen * 2]
        pos += 8 + clen * 2
        stype = struct.unpack("<i", content[:4])[0]
        if stype != 5:
            raise SystemExit(f"unexpected shape type {stype}")
        nparts, npts = struct.unpack("<ii", content[36:44])
        parts = list(struct.unpack(f"<{nparts}i", content[44:44 + 4 * nparts])) + [npts]
        base = 44 + 4 * nparts
        pts = struct.unpack(f"<{2 * npts}d", content[base:base + 16 * npts])
        rings = [[(pts[2 * j], pts[2 * j + 1]) for j in range(parts[k], parts[k + 1])] for k in range(nparts)]
        shapes.append(rings_to_geometry(rings))
    return shapes


def rings_to_geometry(rings):
    """Shapefile rings: exteriors clockwise, holes counter-clockwise."""
    outers, holes = [], []
    for r in rings:
        p = Polygon(r)
        (holes if shapely.is_ccw(p.exterior) else outers).append(r)
    polys = []
    for o in outers:
        po = Polygon(o)
        inner = [h for h in holes if po.contains(Polygon(h).representative_point())]
        polys.append(Polygon(o, inner))
    g = MultiPolygon(polys) if len(polys) > 1 else polys[0]
    return g if g.is_valid else shapely.make_valid(g)


def spcs83_zones():
    zones = []
    for i in query_crs_info(auth_name="EPSG", pj_types=["PROJECTED_CRS"]):
        if not i.name.startswith("NAD83(2011) / ") or i.deprecated:
            continue
        crs = CRS.from_epsg(int(i.code))
        if crs.axis_info[0].unit_name != "metre" or not crs.coordinate_operation.name.startswith("SPCS83"):
            continue
        zones.append((int(i.code), i.name[len("NAD83(2011) / "):], crs.area_of_use.name))
    return sorted(zones)


def county_list(desc):
    m = re.search(r"counties(?: of)?:?\s*(.*)$", desc)
    if not m:
        return None
    items = [x.strip().rstrip(".").strip() for x in m.group(1).split(";")]
    return [x for x in items if x]


def main():
    census_zip, out_bin, out_csv = sys.argv[1:4]
    digest = hashlib.sha256(open(census_zip, "rb").read()).hexdigest()
    if digest not in CENSUS_FILES:
        raise SystemExit(f"unexpected county file (sha256 {digest}); update CENSUS_FILES only on purpose")
    stem, simplify_deg = CENSUS_FILES[digest]
    z = zipfile.ZipFile(census_zip)
    rows = read_dbf(z.read(f"{stem}.dbf"))
    geoms = read_shp_polygons(z.read(f"{stem}.shp"))
    assert len(rows) == len(geoms)
    counties = [dict(r, geom=g) for r, g in zip(rows, geoms)]
    state_names = {r["STATE_NAME"]: r["STATEFP"] for r in rows}

    zones = spcs83_zones()
    assert len(zones) == 118
    by_state = {}
    for epsg, name, desc in zones:
        if name.startswith("Alaska"):
            continue
        if name.startswith("Puerto Rico"):
            st = "72"
        else:
            st = state_names[max((s for s in state_names if name.startswith(s)), key=len)]
        by_state.setdefault(st, []).append((epsg, name, desc))

    assign = {}   # GEOID -> list of (epsg, geometry part or None)
    notes = {}    # GEOID -> note
    for st, zs in sorted(by_state.items()):
        sc = [c for c in counties if c["STATEFP"] == st]
        assert sc, f"no counties for state {st}"
        index = {}
        for c in sc:
            index.setdefault(norm(c["NAME"]), []).append(c)
        for epsg, name, desc in zs:
            names = county_list(desc)
            if names is None or len(zs) == 1:
                for c in sc:
                    assign.setdefault(c["GEOID"], []).append((epsg, None))
                    notes.setdefault(c["GEOID"], "whole state")
                continue
            for raw in names:
                split = None
                m = re.match(r"(.*?) (north|south) of approximately", raw)
                if m:
                    raw, split = m.group(1), m.group(2)
                key = ALIASES.get((st, norm(raw)), norm(raw))
                hits = index.get(key, [])
                if len(hits) > 1:   # "Richmond" VA, "St Louis" MO: the county, not the independent city
                    hits = [c for c in hits if c["LSAD"] != "25"]
                assert len(hits) == 1, f"{name}: '{raw}' matched {len(hits)} counties"
                c = hits[0]
                part = None
                if split:
                    assert (st, c["NAME"]) == ("53", "Grant"), raw
                    minx, miny, maxx, maxy = c["geom"].bounds
                    cut = (box(minx - 1, WA_GRANT_SPLIT_LAT, maxx + 1, maxy + 1) if split == "north"
                           else box(minx - 1, miny - 1, maxx + 1, WA_GRANT_SPLIT_LAT))
                    part = c["geom"].intersection(cut)
                    notes[c["GEOID"]] = f"split at {WA_GRANT_SPLIT_LAT} N"
                assign.setdefault(c["GEOID"], []).append((epsg, part))
                notes.setdefault(c["GEOID"], "listed")
        # Independent cities that EPSG does not list (Virginia, St. Louis, Carson City): longest shared boundary.
        for c in sc:
            if c["GEOID"] in assign:
                continue
            extra = ADDITIONS.get((st, c["NAME"]))
            if extra:
                assign[c["GEOID"]] = [(next(e for e, n, _ in zs if n == extra), None)]
                notes[c["GEOID"]] = "not in EPSG list: county created later, NGS zone"
                continue
            assert c["LSAD"] == "25" or c["NAMELSAD"] == "Carson City", f"unassigned county {c['NAMELSAD']}, {c['STATE_NAME']}"
            best, best_len = None, 0.0
            for o in sc:
                if o["GEOID"] in assign and notes[o["GEOID"]] == "listed":
                    shared = c["geom"].boundary.intersection(o["geom"].boundary).length
                    if shared > best_len:
                        best, best_len = o, shared
            assert best is not None, c["NAMELSAD"]
            assign[c["GEOID"]] = [(assign[best["GEOID"]][0][0], None)]
            notes[c["GEOID"]] = f"independent city: zone of {best['NAMELSAD']}"
        if st == "51":
            north = next(e for e, n, _ in zs if n.endswith("North"))
            for c in sc:
                if c["LSAD"] == "25":
                    is_north = assign[c["GEOID"]][0][0] == north
                    assert is_north == (c["NAME"] in VA_NORTH_CITIES), f"VA city zone mismatch: {c['NAME']}"
        for c in sc:
            zl = assign[c["GEOID"]]
            ok = len(zl) == 1 or (st == "21" and len(zl) == 2) or notes[c["GEOID"]].startswith("split")
            assert ok, f"{c['NAMELSAD']}, {c['STATE_NAME']}: zones {zl}"

    # District of Columbia: no EPSG description names it; NGS assigns it to the Maryland zone.
    dc = next((c for c in counties if c["GEOID"] == DC_GEOID), None)
    assert dc is not None and DC_GEOID not in assign
    assign[DC_GEOID] = [(next(e for e, n, _ in zones if n == DC_ZONE), None)]
    notes[DC_GEOID] = "not in EPSG list: NGS uses the Maryland zone"

    # Dissolve per zone.
    parts = {}
    csv = ["geoid,state,county,zone_epsg,basis"]
    for c in sorted(counties, key=lambda c: c["GEOID"]):
        for epsg, part in assign.get(c["GEOID"], []):
            parts.setdefault(epsg, []).append(part if part is not None else c["geom"])
            csv.append(f'{c["GEOID"]},{c["STUSPS"]},"{c["NAMELSAD"]}",{epsg},"{notes[c["GEOID"]]}"')

    out = io.BytesIO()
    out.write(b"SPZB")
    out.write(struct.pack(">BiH", 1, SCALE, len(parts)))
    total_pts = 0
    for epsg in sorted(parts):
        g = unary_union(parts[epsg])
        if simplify_deg:
            g = g.simplify(simplify_deg, preserve_topology=True)
            if not g.is_valid:
                g = shapely.make_valid(g)
        polys = list(g.geoms) if hasattr(g, "geoms") else [g]
        rings = []
        for p in polys:
            if p.geom_type != "Polygon":
                continue
            for r in [p.exterior, *p.interiors]:
                q = []
                for x, y in r.coords:
                    pt = (round(x * SCALE), round(y * SCALE))
                    if not q or q[-1] != pt:
                        q.append(pt)
                if len(q) > 1 and q[0] == q[-1]:
                    q.pop()
                if len(q) >= 3:
                    rings.append(q)
        out.write(struct.pack(">ii", epsg, len(rings)))
        for q in rings:
            out.write(struct.pack(">i", len(q)))
            px = py = 0
            for x, y in q:
                for d in (x - px, y - py):
                    v = (d << 1) ^ (d >> 63)
                    while True:
                        b = v & 0x7F
                        v >>= 7
                        out.write(bytes([b | 0x80]) if v else bytes([b]))
                        if not v:
                            break
                px, py = x, y
            total_pts += len(q)
    open(out_bin, "wb").write(out.getvalue())
    open(out_csv, "w", encoding="utf-8", newline="\n").write("\n".join(csv) + "\n")
    print(f"{len(parts)} county-defined zones, {total_pts} points, {len(out.getvalue())} bytes")
    missing = [n for e, n, _ in zones if e not in parts]
    print("area-of-use box only:", ", ".join(missing))


if __name__ == "__main__":
    main()
