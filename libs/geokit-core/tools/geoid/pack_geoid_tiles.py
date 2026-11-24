"""
Pack a global node-registered geoid GeoTIFF (PROJ-data us_nga_egm08_25.tif / us_nga_egm96_15.tif)
into SurReal geoid tile packs ("SRGP" v1), read by io.github.pennstatecs.geokit.geoid.GeoidTilePack.

usage:
  python -I pack_geoid_tiles.py egm2008 <us_nga_egm08_25.tif> <main-resources/geoid> <test-resources/geoid>
  python -I pack_geoid_tiles.py egm96   <us_nga_egm96_15.tif> <main-resources/geoid>

Outputs:
  egm2008:  egm2008_<region>.srgp for each region in geoid_common.EGM2008_REGIONS (1 deg tiles) into the
            main dir, and egm2008_testtiles.srgp (geoid_common.EGM2008_TEST_TILES) into the test dir.
  egm96:    egm96_global.srgp (every 5 deg tile of the globe) into the main dir.

Layout (all big-endian):
  'SRGP' | int32 version=1 | int32 nodesPerDegree | int32 tileDegrees
  | utf modelId | utf sourceFile | utf sourceSha256        (utf = uint16 length + UTF-8 bytes)
  | int32 tileCount | tileCount x (int16 tileRow, int16 tileCol, int32 offset, int32 length)
  | blobs
Tiles are sorted by (row, col). Tile (row, col) covers latitudes -90 + row*D .. +D and longitudes
-180 + col*D .. +D (D = tileDegrees). A blob is zlib( int32 baseMm, S*S x int16 residual ) with
S = D*nodesPerDegree + 1 nodes, row-major south->north, west->east. Edge nodes are stored in both
neighbouring tiles (identical values), so a bilinear cell never needs a second tile; the east edge
of the tiles at +180 holds the -180 column (longitude wraps). Values are N in integer millimetres
(rounding <= 0.5 mm); residual = value - (left + below - below-left), as in the GEOID18 SRGT pack.

Deterministic: the same input grid gives the same values; the bytes depend only on zlib level 9
(Python 3.12.0, zlib 1.2.13 / numpy 2.5.5 / tifffile 2026.11.16 were used).
"""
import os
import hashlib
import struct
import sys
import zlib

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))  # python -I does not add the script dir
import geoid_common as gc  # noqa: E402


def tile_blob(grid, n, d, row, col):
    s = d * n + 1
    k = np.arange(s) + row * d * n
    m = np.arange(s) + col * d * n
    v = gc.node_mm(grid, n, k, m)                     # [south->north, west->east]
    res = np.zeros_like(v)
    res[0, 1:] = v[0, 1:] - v[0, :-1]
    res[1:, 0] = v[1:, 0] - v[:-1, 0]
    res[1:, 1:] = v[1:, 1:] - v[1:, :-1] - v[:-1, 1:] + v[:-1, :-1]
    assert res.min() >= -32768 and res.max() <= 32767
    base = int(v[0, 0])
    return zlib.compress(struct.pack(">i", base) + res.astype(">i2").tobytes(), 9)


def utf(s):
    b = s.encode("utf-8")
    return struct.pack(">H", len(b)) + b


def write_pack(path, grid, n, d, model_id, source_file, source_sha, tiles):
    tiles = sorted(tiles)
    head = b"SRGP" + struct.pack(">iii", 1, n, d) + utf(model_id) + utf(source_file) + utf(source_sha)
    head += struct.pack(">i", len(tiles))
    index_len = 12 * len(tiles)
    off = len(head) + index_len
    index, blobs = b"", []
    for row, col in tiles:
        b = tile_blob(grid, n, d, row, col)
        index += struct.pack(">hhii", row, col, off, len(b))
        blobs.append(b)
        off += len(b)
    data = head + index + b"".join(blobs)
    with open(path, "wb") as f:
        f.write(data)
    print(f"{path}: {len(tiles)} tiles, {len(data)} bytes, sha256 {hashlib.sha256(data).hexdigest()}")


def main():
    model, tif = sys.argv[1], sys.argv[2]
    grid, n, sha = gc.load_grid(tif)
    name = tif.replace("\\", "/").rsplit("/", 1)[-1]
    if model == "egm2008":
        assert n == 24 and name == "us_nga_egm08_25.tif"
        main_dir, test_dir = sys.argv[3], sys.argv[4]
        for region, boxes in gc.EGM2008_REGIONS.items():
            write_pack(f"{main_dir}/egm2008_{region}.srgp", grid, n, 1, "EGM2008", name, sha,
                       gc.boxes_to_tiles(boxes))
        write_pack(f"{test_dir}/egm2008_testtiles.srgp", grid, n, 1, "EGM2008", name, sha,
                   gc.boxes_to_tiles(gc.EGM2008_TEST_TILES))
    elif model == "egm96":
        assert n == 4 and name == "us_nga_egm96_15.tif"
        d = 5
        tiles = {(r, c) for r in range(180 // d) for c in range(360 // d)}
        write_pack(f"{sys.argv[3]}/egm96_global.srgp", grid, n, d, "EGM96", name, sha, tiles)
    else:
        raise SystemExit("model must be egm2008 or egm96")


if __name__ == "__main__":
    main()
