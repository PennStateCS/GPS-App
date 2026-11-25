"""
Generate the SPCS2022 catalog of geokit-core from the official NGS machine-readable zone tables.

usage: python -I gen_spcs2022.py <download dir> <libs/geokit-core dir>

<download dir> must be a NEW, empty directory (or one this script filled before: files whose pinned
SHA-256 matches are reused, so a re-run makes no requests). Downloaded data is untrusted: it is only
parsed as JSON and validated, never executed. Run with `python -I` from outside the download dir.

Sources (NGS, public domain US Government works; see docs/geodesy/nsrs2022-sources-spcs2022.md):
  * https://beta.ngs.noaa.gov/SPCS/json_data/zoneDefinitions.json   - defining parameters (953 zones)
  * https://beta.ngs.noaa.gov/SPCS/json_data/coordinates.json       - NGS example point per zone
                                                                      (lat/lon -> N/E, k, convergence)
  * https://beta.ngs.noaa.gov/SPCS/json_data/zoneBounds.json        - lat/lon envelope per zone
    These are the data files behind the NGS tables zone-definitions.shtml, coordinates.shtml and
    zone-bounds.shtml (finalized beta zone designs released 2026-05-14, tables last updated 2026-06-01).
  * NGS ArcGIS Online feature layers (owner NGS.GIS_noaa) with the zone polygons, one per zone type,
    queried in EPSG:4326 with server-side generalisation (maxAllowableOffset 0.0005 deg), paged.

Outputs (paths relative to <libs/geokit-core dir>):
  * src/main/resources/crs/spcs2022_catalog.csv      CrsCatalogCsv format (v2 header with status, aliases)
  * src/main/resources/spcs/spcs2022_zones.bin       zone polygons, SPZB v1 format (SpcsZoneBoundaries.kt,
                                                     same writer as tools/coords/gen_spcs_boundaries.py),
                                                     keyed by the integer zone code
  * src/test/resources/crs/spcs2022/ngs_example_coordinates.csv   NGS vectors (parsed from coordinates.json)
  * src/test/resources/crs/spcs2022/proj_vectors.csv              PROJ forward at 5 points per zone
  * src/test/resources/crs/spcs2022/raw/*.json.gz                 the three NGS JSON files, byte-exact (gzip, mtime 0)

Checks before anything is written (the script stops on failure):
  * pinned SHA-256 of every download; exactly one record per zone code in all three tables;
  * the east and west longitude forms of every origin agree; parameters parse exactly (DMS, comma numbers);
  * PROJ (pyproj 3.8.0 / PROJ 9.8.1), evaluating each zone's parameters as an independent implementation,
    reproduces NGS's published example northing/easting to within the 1 mm rounding (0.6 mm). A zone that
    fails this keeps an empty verified_by (non-operational);
  * every polygon lies inside its NGS envelope (+ 0.011 deg); a zone whose polygon fails, or has none,
    is located by its envelope only (listed in the output).

Tooling: Python 3.12, pyproj 3.8.0 (PROJ 9.8.1), shapely 2.2.0. Output is deterministic.
"""
import gzip
import hashlib
import io
import json
import os
import re
import struct
import sys
import urllib.parse
import urllib.request
from fractions import Fraction

import pyproj
import shapely
from shapely.geometry import box, shape
from shapely.ops import unary_union

NGS = "https://beta.ngs.noaa.gov/SPCS/json_data/"
TABLES = {
    # file: (url, sha256) - update only on purpose, after reading the NGS change log.
    "zoneDefinitions.json": (NGS + "zoneDefinitions.json", "f222dac669503c8e25eb41d477bbb129b813b894b43e7d012effb9dc00bbc06a"),
    "coordinates.json": (NGS + "coordinates.json", "90ed9bff3d2395f146e6533d7805e234e736193d09906372852df40abaf457a3"),
    "zoneBounds.json": (NGS + "zoneBounds.json", "040f9d5a6e4af2587cb8306d05829a0efefd17a482b37f55678e4ea861f48b66"),
}
ARCGIS = "https://services2.arcgis.com/C8EMgrsFcRFL6LrL/arcgis/rest/services/"
LAYERS = [
    "Alpha_SPCS2022_Statewide_Zones_Feature_Layer_View",
    "Alpha_SPCS2022_Multizone_Complete_Zones_Feature_Layer_View",
    "Alpha_SPCS2022_Multizone_Partial_Zones_Feature_Layer_View",
    "Alpha_SPCS2022_Special_Use_Zones_Feature_Layer_View",
    # The web map also references Beta_SPCS2022_Special_Use_Gulf_Zones_Feature_Layer_View, but that URL
    # answers "Invalid URL" (checked 2026-11-24); Gulf zones without a polygon use their envelope.
]
# sha256 over the concatenated page responses of each layer (pages of PAGE features, ordered by ZoneCode).
LAYER_SHA256 = {
    # Fetched 2026-11-24; identical bytes on repeated requests.
    "Alpha_SPCS2022_Statewide_Zones_Feature_Layer_View": "a9fa39a60c30818cd824152429879fae8a88c59c60131e5d29faef089b3d5a24",
    "Alpha_SPCS2022_Multizone_Complete_Zones_Feature_Layer_View": "4eccc2f44428300066b6022b705cdfcd389dc6b141dee3aad467ba0e8a6303d5",
    "Alpha_SPCS2022_Multizone_Partial_Zones_Feature_Layer_View": "5506f9cd6b36c47e54b5beb8732eb5b5bbbf724eca63046bfa00ee3cd4323ee1",
    "Alpha_SPCS2022_Special_Use_Zones_Feature_Layer_View": "57e4ac4d018ec4ae08694db0bb0085485f44e0406f1fe34b8e5985637da529ce",
}
PAGE = 50
SIMPLIFY_DEG = 0.001   # ~110 m (coarser than SPCS83: 953 overlapping zones)
MIN_PART_DEG2 = 2e-5   # ~0.2 km2
SCALE = 10000           # 1e-4 degree
DATASET = "NGS SPCS2022 finalized beta zone designs 2026-05-14 (zone tables 2026-06-01)"
VECTORS = "crs/spcs2022/ngs_example_coordinates.csv"
PROJ_TOL_M = 0.0006
FRAMES = {"NATRF2022": "natrf2022", "PATRF2022": "patrf2022", "CATRF2022": "catrf2022", "MATRF2022": "matrf2022"}
USER_AGENT = "geokit-core-spcs2022-generator/1.0"


def fetch(url, path, sha=None):
    if os.path.exists(path):
        data = open(path, "rb").read()
        if sha is None or hashlib.sha256(data).hexdigest() == sha:
            return data
        raise SystemExit(f"{path}: cached file does not match the pinned sha256; use a new empty download dir")
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=180) as r:
        data = r.read()
    digest = hashlib.sha256(data).hexdigest()
    if sha is not None and digest != sha:
        raise SystemExit(f"{url}: sha256 {digest} != pinned {sha} (NGS updated the table? check the change log)")
    open(path, "wb").write(data)
    return data


DMS = re.compile(r"^(\d{1,3})°(\d{2})'(?:(\d{2}(?:\.\d+)?)\")?([NSEW])$")


def dms(text):
    """Exact Fraction of degrees, signed (S, W negative)."""
    m = DMS.match(text.strip())
    if not m:
        raise SystemExit(f"bad DMS {text!r}")
    d, mi, s, h = m.groups()
    v = Fraction(int(d)) + Fraction(int(mi), 60) + (Fraction(s) / 3600 if s else 0)
    if int(mi) >= 60 or (s and Fraction(s) >= 60):
        raise SystemExit(f"bad DMS {text!r}")
    return -v if h in "SW" else v


def num(text):
    t = text.strip().replace(",", "")
    if not re.fullmatch(r"[+-]?\d+(\.\d+)?", t):
        raise SystemExit(f"bad number {text!r}")
    return Fraction(t)


def plain(text):
    """NGS number text without thousands separators (validated, digits kept exactly as published)."""
    t = text.strip().replace(",", "")
    if not re.fullmatch(r"[+-]?\d+(\.\d+)?", t):
        raise SystemExit(f"bad number {text!r}")
    return t


def fstr(f):
    """Shortest text that parses back to the nearest double of the exact value."""
    return repr(float(f))


def by_code(rows, what):
    out = {}
    for r in rows:
        c = r["Zone code"]
        if not re.fullmatch(r"\d{6}", c) or c in out:
            raise SystemExit(f"{what}: bad or duplicate zone code {c!r}")
        out[c] = r
    return out


def lon180(f):
    f = Fraction(f)
    while f > 180:
        f -= 360
    while f <= -180:
        f += 360
    return f


def zone_params(z):
    lat = dms(z["Origin latitude"])
    lon_e = dms(z["Origin longitude east"])
    lon_w = dms(z["Origin longitude west"])
    if lon180(lon_e) != lon180(lon_w):
        raise SystemExit(f"{z['Zone code']}: east/west origin longitudes disagree")
    lon = lon180(lon_e)
    k = num(z["Projection origin scale"])
    fn = num(z["False northing (m)"])
    fe = num(z["False easting (m)"])
    # Cross-check the ift values: metres / 0.3048 (NC 370001 is rounded to 0.001 ift by NGS).
    for m_key, f_key in (("False northing (m)", "False northing (ift)"), ("False easting (m)", "False easting (ift)")):
        if abs(num(z[m_key]) / Fraction("0.3048") - num(z[f_key])) > Fraction("0.0011"):
            raise SystemExit(f"{z['Zone code']}: {m_key} and {f_key} disagree")
    t = z["Proj type"]
    skew = z["Skew azimuth (deg)"].strip()
    if t == "TM":
        if skew:
            raise SystemExit(f"{z['Zone code']}: TM with skew")
        return "tm", [("lat_0", lat), ("lon_0", lon), ("k_0", k), ("x_0", fe), ("y_0", fn)]
    if t == "LC1":
        if skew:
            raise SystemExit(f"{z['Zone code']}: LC1 with skew")
        return "lcc1sp", [("lat_0", lat), ("lon_0", lon), ("k_0", k), ("x_0", fe), ("y_0", fn)]
    if t == "OMC":
        a = num(skew)
        # SPCS2022 Procedures 6.a.iii / 6.f.vi: centred, rectified skew orthomorphic (gamma = alpha),
        # false origin at the centre: EPSG 9815 Hotine Oblique Mercator (variant B).
        return "omerc_b", [("lat_0", lat), ("lonc", lon), ("alpha", a), ("gamma", a), ("k_0", k), ("x_0", fe), ("y_0", fn)]
    raise SystemExit(f"{z['Zone code']}: unknown projection type {t!r}")


def proj_for(method, p):
    v = {k: fstr(x) for k, x in p}
    if method == "tm":
        s = f"+proj=tmerc +lat_0={v['lat_0']} +lon_0={v['lon_0']} +k_0={v['k_0']} +x_0={v['x_0']} +y_0={v['y_0']}"
    elif method == "lcc1sp":
        s = f"+proj=lcc +lat_1={v['lat_0']} +lat_0={v['lat_0']} +lon_0={v['lon_0']} +k_0={v['k_0']} +x_0={v['x_0']} +y_0={v['y_0']}"
    else:
        s = (f"+proj=omerc +lat_0={v['lat_0']} +lonc={v['lonc']} +alpha={v['alpha']} +gamma={v['gamma']} "
             f"+k_0={v['k_0']} +x_0={v['x_0']} +y_0={v['y_0']}")
    return pyproj.Proj(s + " +ellps=GRS80 +units=m +no_defs")


def envelope(b):
    s = float(num(b["Min lat (deg)"]))
    n = float(num(b["Max lat (deg)"]))
    w = float(lon180(num(b["Min lon east (deg)"])))
    e = float(lon180(num(b["Max lon east (deg)"])))
    if not (-90 <= s < n <= 90):
        raise SystemExit(f"{b['Zone code']}: bad envelope")
    return w, s, e, n


def csv_field(s):
    return '"' + s.replace('"', '""') + '"' if any(c in s for c in ',"') else s


def fetch_layer(dl, layer):
    feats, blob, offset = [], hashlib.sha256(), 0
    while True:
        q = urllib.parse.urlencode({
            "where": "1=1", "outFields": "ZoneCode", "returnGeometry": "true", "outSR": "4326",
            "maxAllowableOffset": "0.0005", "geometryPrecision": "5", "orderByFields": "ZoneCode",
            "resultOffset": str(offset), "resultRecordCount": str(PAGE), "f": "geojson",
        })
        data = fetch(f"{ARCGIS}{layer}/FeatureServer/0/query?{q}", os.path.join(dl, f"{layer}.{offset:04d}.geojson"))
        blob.update(data)
        page = json.loads(data)
        got = page.get("features", [])
        feats += got
        if len(got) < PAGE:
            break
        offset += PAGE
    digest = blob.hexdigest()
    pinned = LAYER_SHA256.get(layer)
    if pinned is not None and pinned != digest:
        raise SystemExit(f"{layer}: polygon pages sha256 {digest} != pinned {pinned}")
    return feats, digest


def zone_geometry(feats, env):
    parts = [shape(f["geometry"]) for f in feats if f.get("geometry")]
    g = unary_union([p if p.is_valid else shapely.make_valid(p) for p in parts])
    if not g.is_valid:
        g = shapely.make_valid(g)
    w, s, e, n = env
    if e < w:   # envelope crosses the antimeridian: work in 0..360 and split at 180
        g = shapely.transform(g, lambda c: c + [[360.0, 0.0] if x < 0 else [0.0, 0.0] for x in c[:, 0]])
        east = g.intersection(box(0, -90, 180, 90))
        west = shapely.transform(g.intersection(box(180, -90, 360, 90)), lambda c: c - [360.0, 0.0])
        g = unary_union([east, west])
    g = g.simplify(SIMPLIFY_DEG, preserve_topology=True)
    if not g.is_valid:
        g = shapely.make_valid(g)
    # Drop islets and holes smaller than MIN_PART_DEG2: a point there finds no polygon and the library
    # falls back to the envelopes (never a false "outside").
    polys = []
    for p in (g.geoms if hasattr(g, "geoms") else [g]):
        for q in (p.geoms if hasattr(p, "geoms") else [p]):
            if q.geom_type == "Polygon" and q.area >= MIN_PART_DEG2:
                holes = [r for r in q.interiors if shapely.Polygon(r).area >= MIN_PART_DEG2]
                polys.append(shapely.Polygon(q.exterior, holes))
    return shapely.MultiPolygon(polys) if polys else shapely.MultiPolygon()


def inside_envelope(g, env, tol=0.011):
    w, s, e, n = env
    minx, miny, maxx, maxy = g.bounds
    if miny < s - tol or maxy > n + tol:
        return False
    if e >= w:
        return minx >= w - tol and maxx <= e + tol
    for p in (g.geoms if hasattr(g, "geoms") else [g]):
        x0, _, x1, _ = p.bounds
        if not ((x0 >= w - tol) or (x1 <= e + tol)):
            return False
    return True


def write_rings(out, key, g):
    polys = []
    for p in (g.geoms if hasattr(g, "geoms") else [g]):
        if p.geom_type == "Polygon":
            polys.append(p)
        elif hasattr(p, "geoms"):
            polys += [q for q in p.geoms if q.geom_type == "Polygon"]
    rings = []
    for p in polys:
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
    out.write(struct.pack(">ii", key, len(rings)))
    pts = 0
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
        pts += len(q)
    return pts


def main():
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    dl, core = sys.argv[1], sys.argv[2]
    if pyproj.__version__ != "3.8.0" or pyproj.proj_version_str != "9.8.1":
        raise SystemExit(f"need pyproj 3.8.0 / PROJ 9.8.1, have {pyproj.__version__} / {pyproj.proj_version_str}")
    os.makedirs(dl, exist_ok=True)
    raw = {name: fetch(url, os.path.join(dl, name), sha) for name, (url, sha) in TABLES.items()}
    defs = by_code(json.loads(raw["zoneDefinitions.json"]), "zoneDefinitions")
    coords = by_code(json.loads(raw["coordinates.json"]), "coordinates")
    bounds = by_code(json.loads(raw["zoneBounds.json"]), "zoneBounds")
    if set(defs) != set(coords) or set(defs) != set(bounds):
        raise SystemExit("the three NGS tables do not list the same zones")
    codes = sorted(defs)

    # Polygons.
    feats_by_zone, layer_digests = {}, {}
    for layer in LAYERS:
        feats, digest = fetch_layer(dl, layer)
        layer_digests[layer] = digest
        for f in feats:
            c = (f.get("properties") or {}).get("ZoneCode")
            if c not in defs:
                raise SystemExit(f"{layer}: polygon for unknown zone {c!r}")
            feats_by_zone.setdefault(c, []).append(f)

    catalog, ngs_rows, proj_rows = [], [], []
    poly_out, poly_keys, box_only, total_pts = io.BytesIO(), [], [], 0
    poly_parts = []
    unverified = []
    for c in codes:
        z, pt, b = defs[c], coords[c], bounds[c]
        method, p = zone_params(z)
        env = envelope(b)
        frame = FRAMES.get(z["Reference frame"])
        if frame is None:
            raise SystemExit(f"{c}: unknown frame {z['Reference frame']!r}")

        # NGS example point and the PROJ cross-check.
        lat = num(pt["Latitude (deg)"])
        lon = lon180(num(pt["Longitude east (deg)"]))
        if dms(pt["Latitude"]) != lat or lon180(dms(pt["Longitude east"])) != lon:
            raise SystemExit(f"{c}: example point DMS and decimal degrees disagree")
        n_m, e_m = num(pt["Northing (m)"]), num(pt["Easting (m)"])
        pj = proj_for(method, p)
        x, y = pj(float(lon), float(lat))
        dx, dy = abs(x - float(e_m)), abs(y - float(n_m))
        verified = dx <= PROJ_TOL_M and dy <= PROJ_TOL_M
        if not verified:
            unverified.append(f"{c} {z['Zone abrv']}: PROJ vs NGS dE={dx:.4f} dN={dy:.4f} m")
        ngs_rows.append(",".join([
            c, csv_field(pt["Point name"]), fstr(lat), fstr(lon), plain(pt["Ellipsoid height (m)"]),
            plain(pt["Northing (m)"]), plain(pt["Easting (m)"]), plain(pt["Point scale factor"]),
            plain(pt["Combined factor"]), csv_field(pt["Convergence angle"]),
        ]))

        # PROJ vectors: the NGS point and four interior points of the envelope (inside the polygon if known).
        w, s, e, n = env
        span = (e - w) if e >= w else (e - w + 360)
        geom = None
        if c in feats_by_zone:
            geom = zone_geometry(feats_by_zone[c], env)
            if geom.is_empty or not inside_envelope(geom, env):
                box_only.append(f"{c} (polygon outside NGS envelope)")
                geom = None
        else:
            box_only.append(f"{c} (no polygon)")
        pts = [(float(lat), float(lon))]
        for fy, fx in ((0.3, 0.3), (0.3, 0.7), (0.7, 0.3), (0.7, 0.7), (0.5, 0.5), (0.15, 0.85), (0.85, 0.15)):
            qy, qx = s + fy * (n - s), w + fx * span
            qx = qx - 360 if qx > 180 else qx
            qy, qx = round(qy, 6), round(qx, 6)
            if geom is not None and not geom.contains(shapely.Point(qx, qy)):
                continue
            pts.append((qy, qx))
            if len(pts) == 5:
                break
        for qy, qx in pts:
            ex, ny = pj(qx, qy)
            proj_rows.append(f"{c},{qy!r},{qx!r},{ex:.6f},{ny:.6f}")
        if geom is not None:
            poly_parts.append((int(c), geom))

        terms = [z["Zone abrv"], z["Zone abrv"].replace("_", " "), z["Zone type"], f"designed by {z['Design by']}", z["Proj type"]]
        aliases = ";".join(dict.fromkeys(terms))
        catalog.append(",".join([
            c, "", csv_field(z["Zone name"]), frame, method, ";".join(f"{k}={fstr(v)}" for k, v in p), "metre",
            *(repr(v) for v in env), DATASET, VECTORS if verified else "", "verified_beta", csv_field(aliases),
        ]))

    poly_out.write(b"SPZB")
    poly_out.write(struct.pack(">BiH", 1, SCALE, len(poly_parts)))
    for key, g in poly_parts:
        total_pts += write_rings(poly_out, key, g)

    def w(rel, text_or_bytes):
        path = os.path.join(core, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        mode = "wb" if isinstance(text_or_bytes, bytes) else "w"
        with open(path, mode, **({} if mode == "wb" else {"encoding": "utf-8", "newline": "\n"})) as f:
            f.write(text_or_bytes)

    header = [
        "# SPCS2022 zone catalog for io.github.pennstatecs.geokit.crs.CrsRegistry (format: CrsCatalogCsv KDoc, v2 header).",
        "# GENERATED by libs/geokit-core/tools/nsrs/gen_spcs2022.py - do not edit by hand.",
        f"# Source: NGS {NGS}zoneDefinitions.json (sha256 {TABLES['zoneDefinitions.json'][1]}),",
        f"#         zoneBounds.json (sha256 {TABLES['zoneBounds.json'][1]}).",
        "# Status: NGS beta (finalized beta designs, 2026-05-14). Never authoritative; opt-in only (ProductStatus.VERIFIED_BETA).",
        f"# verified_by = {VECTORS}: PROJ reproduced NGS's example point when generated; CrsSpcs2022Test checks GeoKit's",
        "# own maths against the same NGS vectors. Area of use = NGS zone envelope (outward to 0.01 deg).",
        "# A zone is operational only when CrsRegistry reaches its frame through verified transformations.",
        "zone_code,epsg,name,frame,method,parameters,unit,west,south,east,north,dataset,verified_by,status,aliases",
    ]
    w("src/main/resources/crs/spcs2022_catalog.csv", "\n".join(header + catalog) + "\n")
    w("src/main/resources/spcs/spcs2022_zones.bin", poly_out.getvalue())
    w("src/test/resources/" + VECTORS, "\n".join([
        f"# NGS SPCS2022 example coordinates, parsed from {NGS}coordinates.json (sha256 {TABLES['coordinates.json'][1]}).",
        "# One NGS point per zone; lat/lon exact (0.0025 deg grid), northing/easting rounded to 1 mm by NGS. Frame = the zone's frame.",
        "# GENERATED by libs/geokit-core/tools/nsrs/gen_spcs2022.py.",
        "zone_code,point,lat,lon,h_m,northing_m,easting_m,point_scale,combined_factor,convergence",
    ] + ngs_rows) + "\n")
    w("src/test/resources/crs/spcs2022/proj_vectors.csv", "\n".join([
        f"# PROJ {pyproj.proj_version_str} (pyproj {pyproj.__version__}) forward projection of each SPCS2022 zone's",
        "# catalog parameters (+ellps=GRS80; tmerc default algorithm, lcc with lat_1=lat_0, omerc with gamma) at the NGS",
        "# example point and up to four interior points of the zone. Independent implementation, not NGS data.",
        "# GENERATED by libs/geokit-core/tools/nsrs/gen_spcs2022.py.",
        "zone_code,lat,lon,easting_m,northing_m",
    ] + proj_rows) + "\n")
    for name, data in raw.items():
        buf = io.BytesIO()
        with gzip.GzipFile(filename="", mode="wb", fileobj=buf, mtime=0, compresslevel=9) as gz:
            gz.write(data)
        w(f"src/test/resources/crs/spcs2022/raw/{name}.gz", buf.getvalue())

    print(f"{len(codes)} zones; {len(codes) - len(unverified)} verified (PROJ vs NGS example point)")
    for u in unverified:
        print("  NOT verified:", u)
    print(f"polygons: {len(poly_parts)} zones, {total_pts} points, {len(poly_out.getvalue())} bytes")
    for layer, d in layer_digests.items():
        print(f'    "{layer}": "{d}",')
    print("envelope only:", ", ".join(box_only) or "none")


if __name__ == "__main__":
    main()
