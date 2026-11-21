"""Generate UTM test vectors with PROJ (via pyproj) for geo-core's Utm tests.

Usage (repo root):
    python geo-core/tools/utm/gen_utm_vectors.py geo-core/src/test/resources/utm/utm_vectors.csv

Output columns: zone,hemisphere,lat,lon,easting,northing
  - WGS84 geographic (EPSG:4326) -> WGS 84 / UTM zone N (EPSG:326zz) or S (EPSG:327zz).
  - Points cover several zones, both hemispheres, the equator, high latitudes and the zone edges
    (central meridian +/- 3 degrees), so both the forward and inverse series are exercised.
"""
import sys

import pyproj
from pyproj import Transformer

ZONES = [1, 10, 17, 18, 31, 32, 33, 56, 60]
LATS = [-80.0, -60.0, -45.5, -33.8688, -10.0, -0.5, 0.0, 0.5, 10.0, 18.4655, 41.3477, 60.0, 72.0, 84.0]
OFFSETS = [-3.0, -2.999, -1.5, -0.25, 0.0, 0.75, 2.0, 2.999, 3.0]


def main(out_path: str) -> None:
    rows = []
    for zone in ZONES:
        cm = (zone - 1) * 6 - 180 + 3
        for lat in LATS:
            hemi = "N" if lat >= 0 else "S"
            epsg = (32600 if hemi == "N" else 32700) + zone
            tr = Transformer.from_crs("EPSG:4326", f"EPSG:{epsg}", always_xy=True)
            for off in OFFSETS:
                lon = cm + off
                if lon > 180.0:
                    lon -= 360.0
                if lon < -180.0:
                    lon += 360.0
                e, n = tr.transform(lon, lat)
                rows.append(f"{zone},{hemi},{lat!r},{lon!r},{e!r},{n!r}")
    with open(out_path, "w", newline="\n") as f:
        f.write(f"# PROJ {pyproj.proj_version_str} / pyproj {pyproj.__version__}: EPSG:4326 -> EPSG:326zz/327zz\n")
        f.write("zone,hemisphere,lat,lon,easting,northing\n")
        f.write("\n".join(rows) + "\n")
    print(f"wrote {len(rows)} vectors to {out_path}")


if __name__ == "__main__":
    main(sys.argv[1])
