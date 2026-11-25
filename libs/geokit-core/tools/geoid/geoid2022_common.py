"""
Shared helpers for the GEOID2022 (NAPGD2022 beta) generators: reading the official NGS .bin grids and the
bundled region boxes. See libs/geokit-core/tools/geoid/README.md and docs/geodesy/nsrs2022-sources-geoid2022.md.

Official grids (https://beta.ngs.noaa.gov/NAPGD2022/download.html, version beta_v0a, downloaded 2026-11-24;
NGS publishes no checksums, the SHA-256 below were computed on download):
  SGEOID2022.NA.N.beta_v0a.bin     233 344 848 B  7b1d43bc6c65b05a07335272dc11de8231c7b31f1975060d1eae4f078bf72386
  SGEOID2022.AS.N.beta_v0a.bin       6 663 168 B  89c5c6878451ef396281718e24ff05d37ae86065a8c4fbe985f10e34fbacb66a
  SGEOID2022.GC.N.beta_v0a.bin       9 805 728 B  6b336c3ef76f325dd593b953b498779549ae624fa105819e7c16fdb950743599
  DGEOID2022.GL.Ndot.beta_v0a.bin    4 155 888 B  735c2c29b07cacd9308a1c9790a3410d28d837d7ae3ca43c79218dd651f33afb

.bin layout (NGS GEOID18-style, little-endian): float64 south latitude, float64 west longitude (0..360),
float64 dlat, float64 dlon (degrees), int32 nlat, int32 nlon, int32 ikind (=1: float32 values), then
nlat rows south->north of nlon float32 values west->east. Node-registered: node (i, j) is at
(south + i*dlat, west + j*dlon). SGEOID2022 is N in metres at epoch 2020.0; DGEOID2022 is dN/dt in m/yr.
"""
import hashlib
import struct

import numpy as np

KNOWN_SHA256 = {
    "SGEOID2022.NA.N.beta_v0a.bin": "7b1d43bc6c65b05a07335272dc11de8231c7b31f1975060d1eae4f078bf72386",
    "SGEOID2022.AS.N.beta_v0a.bin": "89c5c6878451ef396281718e24ff05d37ae86065a8c4fbe985f10e34fbacb66a",
    "SGEOID2022.GC.N.beta_v0a.bin": "6b336c3ef76f325dd593b953b498779549ae624fa105819e7c16fdb950743599",
    "DGEOID2022.GL.Ndot.beta_v0a.bin": "735c2c29b07cacd9308a1c9790a3410d28d837d7ae3ca43c79218dd651f33afb",
}

# Official grid extents (download page): region -> (south, west 0..360, nodes/degree, nlat, nlon).
GRIDS = {
    "NA": (0, 170, 60, 5401, 10801),
    "AS": (-22, 172, 60, 1321, 1261),
    "GC": (5, 130, 60, 1021, 2401),
    "GL": (-90, 0, 4, 721, 1441),
}

# Bundled SGEOID2022 region packs: name -> (grid region, [(south, north, west, east)] whole degrees, west > east
# crosses the antimeridian). 1 deg tiles; every tile whose SW corner lies in [south, north) x [west, east).
# Same boxes as the bundled EGM2008 packs, plus American Samoa (AS grid).
SGEOID_REGIONS = {
    "conus": ("NA", [(24, 50, -125, -66)]),
    "alaska": ("NA", [(51, 72, 172, -129)]),
    "hawaii": ("NA", [(18, 23, -161, -154)]),
    "prvi": ("NA", [(17, 19, -68, -64)]),
    "guam_cnmi": ("GC", [(13, 21, 144, 146)]),
    "american_samoa": ("AS", [(-15, -11, -172, -168)]),
}

# Bundled DGEOID2022 tiles (5 deg tiles of the 15' global grid) covering every SGEOID box above with a
# margin of at least 1 deg, so the 4x4 bicubic window (+-0.5 deg) of any SGEOID point is bundled.
DGEOID_BOXES = [(20, 55, -130, -60), (45, 75, 165, -120), (15, 25, -165, -150), (15, 20, -70, -60),
                (10, 25, 140, 150), (-20, -10, -175, -165)]

# Test-only tiles: official grid edges/corners (linear-extrapolation padding) and region boundaries.
SGEOID_TEST_TILES = {
    "AS": [(-22, -21, 172, 174), (-1, 0, -180, -179)],      # AS SW corner; AS north edge (0 deg) at 180 deg
    "NA": [(0, 1, -100, -99), (10, 11, 170, 171)],          # NA south edge (equator); NA west edge (170 E)
    "GC": [(10, 11, 169, 170)],                             # GC east edge (170 E), shared with NA
}


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def load_bin(path, region):
    """(values float64 [i south->north, j west->east], sha256, file name); checks header and digest."""
    name = path.replace("\\", "/").rsplit("/", 1)[-1]
    digest = sha256_of(path)
    if KNOWN_SHA256.get(name) != digest:
        raise SystemExit(f"{name}: sha256 {digest} is not the recorded beta_v0a digest")
    with open(path, "rb") as f:
        head = f.read(44)
    slat, wlon, dlat, dlon, nlat, nlon, ikind = struct.unpack("<4d3i", head)
    s, w, npd, ni, nj = GRIDS[region]
    assert (slat, wlon, nlat, nlon, ikind) == (s, w, ni, nj, 1), (name, slat, wlon, nlat, nlon, ikind)
    assert abs(dlat * npd - 1) < 1e-12 and abs(dlon * npd - 1) < 1e-12, (dlat, dlon)
    a = np.fromfile(path, dtype="<f4", offset=44)
    assert a.size == ni * nj, a.size
    a = a.reshape(ni, nj).astype(np.float64)
    assert np.isfinite(a).all(), "no-data / NaN nodes are not supported"
    assert np.abs(a).max() < 200, "implausible value"
    return a, digest, name


def boxes_to_tiles(boxes, tile_deg):
    """(tileRow, tileCol) set; row 0 = south edge -90, col 0 = west edge -180 (global SRGP indexing)."""
    out = set()
    for s, n, w, e in boxes:
        lons = list(range(w, e)) if w < e else list(range(w, 180)) + list(range(-180, e))
        for lat in range(s, n):
            for lon in lons:
                if lat % tile_deg == 0 and lon % tile_deg == 0:
                    out.add(((lat + 90) // tile_deg, (lon + 180) // tile_deg))
    return out
