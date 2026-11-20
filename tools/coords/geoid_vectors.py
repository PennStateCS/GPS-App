"""Reference N at seeded random points, bilinear from the RAW NGS float grids (not the packed asset).
usage: geoid_vectors.py <out.csv> <grid.bin>:<count> ..."""
import random, struct, sys
from array import array

random.seed(18)
out = ["lat,lon,n"]
for spec in sys.argv[2:]:
    path, count = spec.rsplit(":", 1)
    raw = open(path, "rb").read()
    south, westE, dlat, dlon = struct.unpack(">4d", raw[:32]); rows, cols, _ = struct.unpack(">3i", raw[32:44])
    a = array("f"); a.frombytes(raw[44:]); a.byteswap()
    for _ in range(int(count)):
        fr = random.uniform(0, rows - 1); fc = random.uniform(0, cols - 1)
        r = min(int(fr), rows - 2); c = min(int(fc), cols - 2); t = fr - r; u = fc - c
        v = lambda i, j: float(a[i * cols + j])
        n = (v(r, c) * (1 - u) + v(r, c + 1) * u) * (1 - t) + (v(r + 1, c) * (1 - u) + v(r + 1, c + 1) * u) * t
        lat = south + fr * dlat; lonE = westE + fc * dlon
        out.append(f"{lat!r},{lonE - 360!r},{n!r}")
open(sys.argv[1], "w", newline="\n").write("\n".join(out) + "\n")
print(len(out) - 1, "points")
