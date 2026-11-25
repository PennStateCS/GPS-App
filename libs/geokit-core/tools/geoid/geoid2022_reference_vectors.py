"""
Independent GEOID2022 reference values for :geokit-core tests (separate code path from the Kotlin model:
reads the official NGS .bin grids directly, never the SRGP packs).

usage (repository root):
  python -I libs/geokit-core/tools/geoid/geoid2022_reference_vectors.py <dir with the .bin grids and
      NAPGD2022TestCases.beta_v0a.csv> libs/geokit-core/src/test/resources/geoid/geoid2022_vectors.csv

Interpolation, as NGS specifies for NAPGD2022 grids (developer guide, checked 2026-11-24): "local 4x4
bicubic interpolation as described in Numerical Recipes (Press, et al., 2007). Interpolation within 1 pixel
of the edge should also use bicubic interpolation with linear extrapolation for padding the missing parts of
the 4x4 window." Implemented here as Numerical Recipes' bicubic patch (bcucof/bcuint) whose corner
derivatives are the centred finite differences of the 4x4 neighbourhood; that patch equals the tensor
product of 1-D Catmull-Rom cubics, which is how it is evaluated here (Lagrange weights are NOT used).
Before writing anything the script checks this choice against NGS's own published test values
(NAPGD2022TestCases.beta_v0a.csv, SGEOID2022 to 0.1 mm, DGEOID2022 to 0.01 mm/yr) and aborts if any
point differs by more than the published rounding.

Columns: kind, region, lat, lon, n_raw_m (from the float32 grid), n_mm_m (same interpolation over nodes
rounded to integer mm, i.e. what the SRGP packs store), ndot_raw_mpy, ndot_um_mpy (nodes rounded to
integer micrometres/yr; empty where no DGEOID tile is bundled). Points are chosen so that the whole 4x4
window lies in bundled tiles (or beyond an official grid edge, which is extrapolated).
"""
import csv
import os
import random
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import geoid2022_common as gc  # noqa: E402

# Region chosen for a point inside several official grids (only their shared edges: NA/GC at 170 E,
# 5-22 N; NA/AS at the equator, 172-193 E). Purely geometric, as in Geoid2022Model.
ORDER = ("NA", "GC", "AS")


def catmull_rom(t):
    return (((-t + 2.0) * t - 1.0) * t / 2.0,
            ((3.0 * t - 5.0) * t * t + 2.0) / 2.0,
            ((-3.0 * t + 4.0) * t + 1.0) * t / 2.0,
            (t - 1.0) * t * t / 2.0)


class Grid:
    def __init__(self, values, region):
        self.v = values
        self.s, self.w, self.npd, self.ni, self.nj = gc.GRIDS[region]

    def covers(self, lat, lon):
        x = ((lon - self.w) % 360.0) * self.npd
        y = (lat - self.s) * self.npd
        return 0 <= y <= self.ni - 1 and 0 <= x <= self.nj - 1

    def node(self, i, j, q):
        if j < 0:
            return 2 * self.node(i, 0, q) - self.node(i, 1, q)
        if j >= self.nj:
            return 2 * self.node(i, self.nj - 1, q) - self.node(i, self.nj - 2, q)
        if i < 0:
            return 2 * self.node(0, j, q) - self.node(1, j, q)
        if i >= self.ni:
            return 2 * self.node(self.ni - 1, j, q) - self.node(self.ni - 2, j, q)
        x = self.v[i, j]
        return x if q is None else np.rint(x * q) / q

    def interp(self, lat, lon, q=None):
        y = (lat - self.s) * self.npd
        x = ((lon - self.w) % 360.0) * self.npd
        i = min(int(np.floor(y)), self.ni - 2)
        j = min(int(np.floor(x)), self.nj - 2)
        wy = catmull_rom(y - i)
        wx = catmull_rom(x - j)
        total = 0.0
        for a in range(4):
            row = 0.0
            for b in range(4):
                row += wx[b] * self.node(i - 1 + a, j - 1 + b, q)
            total += wy[a] * row
        return total


def pick(grids, lat, lon):
    for r in ORDER:
        if grids[r].covers(lat, lon):
            return r
    return None


def check_against_ngs(grids, dgeoid, csv_path):
    worst_n = worst_d = 0.0
    count = 0
    with open(csv_path, encoding="latin-1") as f:
        rows = [r for r in csv.reader(line for line in f if not line.startswith("#"))]
    for r in rows[1:]:
        lat, lon, n_ref, d_ref = float(r[2]), float(r[3]), float(r[5]), float(r[6])
        reg = pick(grids, lat, lon)
        worst_n = max(worst_n, abs(grids[reg].interp(lat, lon) - n_ref))
        worst_d = max(worst_d, abs(dgeoid.interp(lat, lon) * 1000.0 - d_ref))
        count += 1
    print(f"NGS test cases: {count} rows, max |dN| {worst_n * 1000:.4f} mm, max |dNdot| {worst_d:.4f} mm/yr")
    assert worst_n <= 0.00005 + 1e-9 and worst_d <= 0.005 + 1e-9, "interpolation does not reproduce NGS"


def points():
    rnd = random.Random(20221124)
    out = []

    def add(kind, lat, lon):
        out.append((kind, round(lat, 9), round(((lon + 180.0) % 360.0) - 180.0, 9)))

    m = 1.5 / 60.0                   # stay 1.5' inside a pack so the 4x4 window is bundled
    counts = {"conus": 45, "alaska": 35, "hawaii": 15, "prvi": 10, "guam_cnmi": 10, "american_samoa": 10}
    for name, (region, boxes) in gc.SGEOID_REGIONS.items():
        s, n, w, e = boxes[0]
        width = (e - w) % 360
        for _ in range(counts[name]):
            add(f"random:{name}", rnd.uniform(s + m, n - m), w + rnd.uniform(m, width - m))
        for _ in range(4):           # exact grid nodes
            add(f"node:{name}", s + (2 + rnd.randrange((n - s) * 60 - 4)) / 60.0,
                w + (2 + rnd.randrange(width * 60 - 4)) / 60.0)
        for _ in range(3):           # whole-degree tile corners (node shared by four tiles)
            add(f"corner:{name}", s + 1 + rnd.randrange(n - s - 1), w + 1 + rnd.randrange(width - 1))
        for _ in range(3):           # on a tile edge (latitude, then longitude)
            add(f"edge:{name}", s + 1 + rnd.randrange(n - s - 1), w + rnd.uniform(m, width - m))
            add(f"edge:{name}", rnd.uniform(s + m, n - m), w + 1 + rnd.randrange(width - 1))
        for _ in range(4):           # within 1' of a tile corner: the 4x4 window spans four tiles
            la = s + 1 + rnd.randrange(n - s - 1) + rnd.choice((-1, 1)) * rnd.uniform(0.05, 0.95) / 60.0
            lo = w + 1 + rnd.randrange(width - 1) + rnd.choice((-1, 1)) * rnd.uniform(0.05, 0.95) / 60.0
            add(f"cross:{name}", la, lo)
    # Outer boundary nodes of the packs (exactly on the edge: only the edge row/column has weight).
    add("boundary:conus", 50.0, -100.25)
    add("boundary:conus", 24.0, -80.5)
    add("boundary:conus", 37.5, -125.0)
    add("boundary:conus", 50.0, -66.0)
    add("boundary:alaska", 61.5, 172.0)
    add("boundary:alaska", 72.0, -150.0)
    # Antimeridian (Aleutians, inside the alaska pack).
    add("antimeridian:alaska", 52.2, 180.0)
    add("antimeridian:alaska", 52.2, 179.99)
    add("antimeridian:alaska", 52.2, -179.995)
    add("antimeridian:alaska", 53.0, -180.0)
    # Test-only packs: official grid edges and corners (linear-extrapolation padding).
    for la, lo in ((-22.0, 172.0), (-21.995, 172.004), (-21.99, 172.5), (-21.5, 172.008), (-21.0, 173.0),
                   (-21.98, 173.97), (-0.004, -179.5), (-0.5, -179.5), (-0.01, 180.0)):
        add("gridedge:AS", la, lo)
    for la, lo in ((0.0, -99.5), (0.004, -99.5), (0.5, -99.97), (10.5, 170.004), (10.5, 170.5),
                   (10.98, 170.01), (10.5, 170.0)):
        add("gridedge:NA", la, lo)
    for la, lo in ((10.5, 169.997), (10.02, 169.5), (10.97, 169.99)):
        add("gridedge:GC", la, lo)
    return out


def main():
    src, out_path = sys.argv[1], sys.argv[2]
    grids = {r: Grid(gc.load_bin(f"{src}/SGEOID2022.{r}.N.beta_v0a.bin", r)[0], r) for r in ("NA", "AS", "GC")}
    dgeoid = Grid(gc.load_bin(f"{src}/DGEOID2022.GL.Ndot.beta_v0a.bin", "GL")[0], "GL")
    check_against_ngs(grids, dgeoid, f"{src}/NAPGD2022TestCases.beta_v0a.csv")
    dtiles = gc.boxes_to_tiles(gc.DGEOID_BOXES, 5)
    with open(out_path, "w", newline="\n", encoding="utf-8") as f:
        f.write("# GEOID2022 beta_v0a independent reference (tools/geoid/geoid2022_reference_vectors.py)\n")
        f.write("kind,region,lat,lon,n_raw_m,n_mm_m,ndot_raw_mpy,ndot_um_mpy\n")
        n = 0
        for kind, lat, lon in points():
            reg = pick(grids, lat, lon)
            g = grids[reg]
            key = (int((lat + 90) // 5), int((lon + 180) // 5))
            if key in dtiles and not kind.startswith("gridedge:"):
                d_raw = f"{dgeoid.interp(lat, lon):.12f}"
                d_q = f"{dgeoid.interp(lat, lon, 1000000):.12f}"
            else:
                d_raw = d_q = ""
            f.write(f"{kind},{reg},{lat!r},{lon!r},{g.interp(lat, lon):.9f},{g.interp(lat, lon, 1000):.9f},{d_raw},{d_q}\n")
            n += 1
    print(f"{out_path}: {n} points")


if __name__ == "__main__":
    main()
