"""
Pack the official NGS GEOID2022 (NAPGD2022 beta_v0a) .bin grids into SurReal geoid tile packs, SRGP v2,
read by io.github.pennstatecs.geokit.geoid.GeoidTilePack and used by Geoid2022Model.

usage (repository root, grids in a new empty directory <dir>):
  python -I libs/geokit-core/tools/geoid/pack_geoid2022.py <dir> libs/geokit-core/src/main/resources/geoid \
      libs/geokit-core/src/test/resources/geoid

Outputs:
  main: geoid2022_<region>.srgp for each SGEOID_REGIONS entry (1 deg tiles, N in integer mm) and
        geoid2022_dgeoid.srgp (DGEOID2022 5 deg tiles of the 15' grid, dN/dt in integer micrometres/year).
  test: geoid2022_testtiles_<NA|AS|GC>.srgp (official grid edges/corners, see SGEOID_TEST_TILES).

SRGP v2 = SRGP v1 (pack_geoid_tiles.py) with three extra header fields after sourceSha256:
  'SRGP' | int32 version=2 | int32 nodesPerDegree | int32 tileDegrees
  | utf modelId | utf sourceFile | utf sourceSha256 | utf gridRegion | utf quantity | int32 unitsPerValue
  | int32 tileCount | tileCount x (int16 tileRow, int16 tileCol, int32 offset, int32 length) | blobs
gridRegion is the official grid the tiles were cut from ("NA", "AS", "GC", "GL"); quantity is the GGXF
parameter name ("geoidHeight" in metres, "geoidVelocity" in metres/year); a stored integer is
value * unitsPerValue rounded half-to-even (1000 -> mm; 1000000 -> micrometres/yr). Tiles, blobs and the
planar predictor are exactly as in v1: tile (row, col) covers -90 + row*D .. +D, -180 + col*D .. +D, stores
both edges, row-major south->north, west->east; blob = zlib(int32 base, S*S x int16 residual).

Tiles are cut from the official region grid at its own nodes (all GEOID2022 grids are aligned on whole
degrees, so global and grid node indices differ by an integer offset). A tile must lie entirely inside its
region grid. Values are rounded only once (float32 -> float64 -> integer units).

Deterministic: same grids -> same bytes (zlib level 9; Python 3.12.0, zlib 1.2.13, numpy 2.5.5).
"""
import hashlib
import os
import struct
import sys
import zlib

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))  # python -I does not add the script dir
import geoid2022_common as gc  # noqa: E402

FILES = {
    "NA": "SGEOID2022.NA.N.beta_v0a.bin",
    "AS": "SGEOID2022.AS.N.beta_v0a.bin",
    "GC": "SGEOID2022.GC.N.beta_v0a.bin",
    "GL": "DGEOID2022.GL.Ndot.beta_v0a.bin",
}


def tile_values(grid, region, d, row, col, units):
    s, w, npd, ni, nj = gc.GRIDS[region]
    lat0 = -90 + row * d
    lon0 = -180 + col * d
    i0 = (lat0 - s) * npd
    j0 = ((lon0 - w) % 360) * npd
    n = d * npd + 1
    assert 0 <= i0 and i0 + n <= ni and 0 <= j0 and j0 + n <= nj, (region, lat0, lon0)
    v = grid[i0:i0 + n, j0:j0 + n]
    return np.rint(v * units).astype(np.int64)


def tile_blob(v):
    res = np.zeros_like(v)
    res[0, 1:] = v[0, 1:] - v[0, :-1]
    res[1:, 0] = v[1:, 0] - v[:-1, 0]
    res[1:, 1:] = v[1:, 1:] - v[1:, :-1] - v[:-1, 1:] + v[:-1, :-1]
    assert res.min() >= -32768 and res.max() <= 32767
    return zlib.compress(struct.pack(">i", int(v[0, 0])) + res.astype(">i2").tobytes(), 9)


def utf(s):
    b = s.encode("utf-8")
    return struct.pack(">H", len(b)) + b


def write_pack(path, grid, region, d, quantity, units, source_file, source_sha, tiles):
    npd = gc.GRIDS[region][2]
    tiles = sorted(tiles)
    head = b"SRGP" + struct.pack(">iii", 2, npd, d) + utf("GEOID2022") + utf(source_file) + utf(source_sha)
    head += utf(region) + utf(quantity) + struct.pack(">i", units)
    head += struct.pack(">i", len(tiles))
    off = len(head) + 12 * len(tiles)
    index, blobs = b"", []
    for row, col in tiles:
        b = tile_blob(tile_values(grid, region, d, row, col, units))
        index += struct.pack(">hhii", row, col, off, len(b))
        blobs.append(b)
        off += len(b)
    data = head + index + b"".join(blobs)
    with open(path, "wb") as f:
        f.write(data)
    print(f"{path}: {len(tiles)} tiles, {len(data)} bytes, sha256 {hashlib.sha256(data).hexdigest()}")


def main():
    src, main_dir, test_dir = sys.argv[1], sys.argv[2], sys.argv[3]
    grids = {r: gc.load_bin(f"{src}/{f}", r) for r, f in FILES.items()}
    for name, (region, boxes) in gc.SGEOID_REGIONS.items():
        g, sha, fname = grids[region]
        write_pack(f"{main_dir}/geoid2022_{name}.srgp", g, region, 1, "geoidHeight", 1000, fname, sha,
                   gc.boxes_to_tiles(boxes, 1))
    g, sha, fname = grids["GL"]
    write_pack(f"{main_dir}/geoid2022_dgeoid.srgp", g, "GL", 5, "geoidVelocity", 1000000, fname, sha,
               gc.boxes_to_tiles(gc.DGEOID_BOXES, 5))
    for region, boxes in gc.SGEOID_TEST_TILES.items():
        g, sha, fname = grids[region]
        write_pack(f"{test_dir}/geoid2022_testtiles_{region}.srgp", g, region, 1, "geoidHeight", 1000, fname, sha,
                   gc.boxes_to_tiles(boxes, 1))


if __name__ == "__main__":
    main()
