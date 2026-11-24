"""
Shared helpers for the global geoid generators (EGM2008 / EGM96 from the PROJ-data GeoTIFFs).

Grids (both from https://cdn.proj.org/, NGA, public domain per PROJ-data's us_nga_README.txt):
  us_nga_egm96_15.tif  sha256 db493027562c9b004d7220fa881f5603adada4e1c5029b933fa7de4547b0e78d
  us_nga_egm08_25.tif  sha256 4191d471eefebf24091b56dbc604353cb3b8cf8cc70e448bb9ae56a272bef17a
(the same values as the "sha256sum" entries of https://cdn.proj.org/files.geojson, checked 2026-11-24).

Both are float32, PixelIsPoint (node-registered), tie point (-180, +90), rows north->south from +90 to
-90 inclusive, columns west->east from -180 to 180-step (the 180 column is not repeated: longitude
wraps). Values are geoid undulations N in metres relative to the WGS84 ellipsoid.
"""
import hashlib

import numpy as np
import tifffile

KNOWN_SHA256 = {
    "us_nga_egm96_15.tif": "db493027562c9b004d7220fa881f5603adada4e1c5029b933fa7de4547b0e78d",
    "us_nga_egm08_25.tif": "4191d471eefebf24091b56dbc604353cb3b8cf8cc70e448bb9ae56a272bef17a",
}

# Bundled EGM2008 region packs: name -> list of (south, north, west, east) in whole degrees. A box
# with west > east crosses the antimeridian. Tiles are 1 deg x 1 deg; every tile whose SW corner lies
# in [south, north) x [west, east) is packed.
EGM2008_REGIONS = {
    "conus": [(24, 50, -125, -66)],
    # Alaska incl. the Aleutians (west to Attu, 172.9 E) across the antimeridian.
    "alaska": [(51, 72, 172, -129)],
    # Main Hawaiian islands (Niihau to the Big Island); the NW islands are not bundled.
    "hawaii": [(18, 23, -161, -154)],
    "prvi": [(17, 19, -68, -64)],
    "guam_cnmi": [(13, 21, 144, 146)],
}

# Tiles packed only into the test-resource pack (exercise poles, the antimeridian away from Alaska,
# Greenwich/equator and the file-backed tile source). Same box convention.
EGM2008_TEST_TILES = [
    (89, 90, 179, -179),     # north pole, both sides of the antimeridian
    (89, 90, 0, 1),
    (-90, -89, -1, 1),       # south pole, both sides of Greenwich
    (-1, 1, -1, 1),          # equator x Greenwich
    (-45, -44, 179, -179),   # antimeridian south of New Zealand
]


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def load_grid(path):
    """Returns (N float32 array [rows north->south, cols west->east], nodes_per_degree, sha256)."""
    name = path.replace("\\", "/").rsplit("/", 1)[-1]
    digest = sha256_of(path)
    if name in KNOWN_SHA256 and KNOWN_SHA256[name] != digest:
        raise SystemExit(f"{name}: sha256 {digest} does not match the PROJ-data manifest")
    with tifffile.TiffFile(path) as t:
        page = t.pages[0]
        geo = page.geotiff_tags
        assert int(geo["GTRasterTypeGeoKey"]) == 2, "expected PixelIsPoint (node-registered) grid"
        scale = page.tags["ModelPixelScaleTag"].value
        tie = page.tags["ModelTiepointTag"].value
        assert abs(scale[0] - scale[1]) < 1e-15, scale
        n = round(1.0 / scale[0])
        assert abs(n * scale[0] - 1.0) < 1e-12, scale
        assert tuple(tie[:2]) == (0.0, 0.0) and tie[3] == -180.0 and tie[4] == 90.0, tie
        a = page.asarray()
    assert a.dtype == np.float32 and a.shape == (180 * n + 1, 360 * n), a.shape
    assert np.isfinite(a).all() and a.min() > -200 and a.max() < 200
    assert np.all(a[0] == a[0, 0]) and np.all(a[-1] == a[-1, 0]), "pole rows must be constant"
    return a, n, digest


def node_mm(grid, n, k_south, m_west):
    """Integer-mm values at node lat index k (0 = -90, from south) x lon index m (0 = -180, wraps)."""
    rows = 180 * n - np.asarray(k_south)
    cols = np.asarray(m_west) % (360 * n)
    return np.rint(grid[np.ix_(rows, cols)].astype(np.float64) * 1000.0).astype(np.int64)


def boxes_to_tiles(boxes, tile_deg=1):
    """(tileRow, tileCol) set; row 0 = south edge -90, col 0 = west edge -180."""
    out = set()
    for s, nlat, w, e in boxes:
        lons = list(range(w, e)) if w < e else list(range(w, 180)) + list(range(-180, e))
        for lat in range(s, nlat, tile_deg):
            for lon in lons:
                if lon % tile_deg == 0:
                    out.add(((lat + 90) // tile_deg, (lon + 180) // tile_deg))
    return out
