"""
Pack NGS GEOID18 big-endian .bin grids into one tiled, compressed asset ("SRGT" v1).

usage: pack_geoid.py <out.bin> <grid1.bin> [<grid2.bin> ...]

Layout (all big-endian):
  'SRGT' | int32 version=1 | int32 regionCount
  per region:
    f64 south, f64 westE (0-360), f64 dLat, f64 dLon, int32 rows, int32 cols,
    int32 tileCells (T), int32 tileRows, int32 tileCols,
    tileRows*tileCols x (int32 offset, int32 length)      -- offset from file start
  blobs: zlib( int32 baseMm, (T+1)^2 x int16 residual )  -- row-major, south→north, west→east
Each tile holds (T+1)x(T+1) points (one-cell overlap, so a bilinear cell never spans tiles); points
past the grid edge repeat the edge value (the reader bounds-checks against rows/cols first).
Values are N in integer millimetres (max rounding error 0.5 mm). Residual = value − planar
prediction (left + below − below-left), so the smooth geoid compresses well.
"""
import struct, sys, zlib
from array import array

T = 60
out_path, grids = sys.argv[1], sys.argv[2:]

regions = []
for g in grids:
    raw = open(g, "rb").read()
    south, westE, dlat, dlon = struct.unpack(">4d", raw[:32])
    rows, cols, kind = struct.unpack(">3i", raw[32:44])
    assert kind == 1 and len(raw) == 44 + 4 * rows * cols, g
    a = array("f"); a.frombytes(raw[44:]); a.byteswap()
    lo, hi = min(a), max(a)
    assert -200 < lo and hi < 200, (g, lo, hi)          # no no-data markers in GEOID18 grids
    mm = array("i", (int(round(v * 1000.0)) for v in a))
    regions.append(dict(south=south, westE=westE, dlat=dlat, dlon=dlon, rows=rows, cols=cols, mm=mm,
                        src=g, lo=lo, hi=hi))

def tile_blob(r, tr, tc):
    rows, cols, mm = r["rows"], r["cols"], r["mm"]
    pts = []
    for i in range(T + 1):
        ri = min(tr * T + i, rows - 1)
        base_idx = ri * cols
        pts.append([mm[base_idx + min(tc * T + j, cols - 1)] for j in range(T + 1)])
    base = pts[0][0]
    res = array("h")
    for i in range(T + 1):
        for j in range(T + 1):
            if i == 0 and j == 0: pred = base
            elif i == 0: pred = pts[0][j - 1]
            elif j == 0: pred = pts[i - 1][0]
            else: pred = pts[i][j - 1] + pts[i - 1][j] - pts[i - 1][j - 1]
            d = pts[i][j] - pred
            assert -32768 <= d <= 32767
            res.append(d)
    res.byteswap()
    return zlib.compress(struct.pack(">i", base) + res.tobytes(), 9)

header_len = 12
for r in regions:
    r["tr"] = (r["rows"] - 2) // T + 1      # tiles needed so every cell (r, r+1) fits
    r["tc"] = (r["cols"] - 2) // T + 1
    header_len += 4 * 8 + 5 * 4 + r["tr"] * r["tc"] * 8

blobs, index, off = [], [], header_len
for r in regions:
    idx = []
    for tr in range(r["tr"]):
        for tc in range(r["tc"]):
            b = tile_blob(r, tr, tc)
            idx.append((off, len(b))); blobs.append(b); off += len(b)
    index.append(idx)

with open(out_path, "wb") as f:
    f.write(b"SRGT" + struct.pack(">ii", 1, len(regions)))
    for r, idx in zip(regions, index):
        f.write(struct.pack(">4d", r["south"], r["westE"], r["dlat"], r["dlon"]))
        f.write(struct.pack(">5i", r["rows"], r["cols"], T, r["tr"], r["tc"]))
        for o, l in idx:
            f.write(struct.pack(">ii", o, l))
    assert f.tell() == header_len
    for b in blobs:
        f.write(b)
    total = f.tell()

for r in regions:
    print(f'{r["src"]}: {r["rows"]}x{r["cols"]} N {r["lo"]:.3f}..{r["hi"]:.3f} m, tiles {r["tr"]}x{r["tc"]}')
print("wrote", out_path, total, "bytes")
