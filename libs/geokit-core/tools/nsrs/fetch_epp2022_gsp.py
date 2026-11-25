"""Fetch NGS-computed ITRF2020 -> NATRF/PATRF/CATRF/MATRF2022 coordinate pairs and pin them as test vectors.

Source: the NGS beta Geodetic Station Pages (https://beta.ngs.noaa.gov/DDS/geodetic-station-pages/), whose
"Coordinates and Velocities" tool calls two NGS back-end services (found in the page's JavaScript bundle
index-DbaPV8or.js, 2026-11-25; they are not separately documented by NGS):

  1. https://beta.ngs.noaa.gov/api_m/cors/ccf?stationid=<9-char id>&epoch=<decimal year>
     -> the CORS coordinate function (MYCS3) evaluated at the epoch: ITRF2020 X, Y, Z (m), velocities (mm/yr),
        covariances.
  2. https://beta.ngs.noaa.gov/api_m/gsp/coords?station=..&x=..&y=..&z=..&epoch=..&<covariances>&vx=..
     -> NGS's conversion of that ITRF2020 position to ITRF2020, NATRF2022, PATRF2022, CATRF2022 and MATRF2022
        at the SAME epoch ("Datum", "epoch", x/y/z, lat/lon/eht, velocities).

This script makes exactly the requests the page makes (velocities divided by 1000 as the page does), one station
and epoch at a time with a 1 s pause, and caches every response in the download directory (re-running with the
same directory makes no request). Nothing is computed from GeoKit: the CSV holds NGS's own numbers verbatim.

Usage (repo root, isolated mode, download dir outside the source tree):

    python -I libs/geokit-core/tools/nsrs/fetch_epp2022_gsp.py <download-dir> libs/geokit-core/src/test/resources/nsrs/ngs-gsp

Downloads are untrusted: each response is size-limited, parsed as JSON (never executed), and every number used
must match a strict decimal pattern. Exit status is non-zero, and nothing is written, if any check fails.
"""

import datetime
import hashlib
import json
import os
import re
import sys
import time
import urllib.request

STATIONS = [
    "TMGO00USA", "AOML00USA", "FAIR00USA", "HNPT00USA",  # North American plate (CO, FL, AK, MD)
    "MKEA00USA", "HNLC00USA", "ASPA00ASM",  # Pacific plate (Hawaii, American Samoa)
    "PRMI00PRI", "CRO100VIR", "STVI00VIR",  # Caribbean plate (Puerto Rico, USVI)
    "GUUG00GUM", "CNMR00MNP", "GUAM00GUM",  # Mariana plate (Guam, CNMI)
]
EPOCHS = [2020.0, 2014.5, 2026.9]
FRAMES = ["ITRF2020", "NATRF2022", "PATRF2022", "CATRF2022", "MATRF2022"]
BASE = "https://beta.ngs.noaa.gov/api_m"
UA = {"User-Agent": "geokit-verification/0.1"}
MAX_BYTES = 100_000
NUM = re.compile(r"^-?\d{1,8}(\.\d{1,20})?([eE]-?\d{1,3})?$")


def fail(msg):
    raise SystemExit(f"error: {msg}")


def num(v, what):
    s = str(v)
    if not NUM.match(s):
        fail(f"{what}: not a plain number: {s!r}")
    return s


def get(dl, url, name, log):
    path = os.path.join(dl, name)
    if not os.path.exists(path):
        time.sleep(1.0)
        with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=90) as r:
            raw = r.read(MAX_BYTES + 1)
        if len(raw) > MAX_BYTES:
            fail(f"{url}: response too large")
        with open(path, "wb") as f:
            f.write(raw)
        stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        with open(os.path.join(dl, "requests.log"), "a", encoding="utf-8") as f:
            f.write(f"{stamp},{name},{len(raw)},{hashlib.sha256(raw).hexdigest()},{url}\n")
    with open(path, "rb") as f:
        raw = f.read()
    log.append((name, raw, url))
    return raw


def main():
    if len(sys.argv) != 3:
        fail("usage: fetch_epp2022_gsp.py <download-dir> <output-dir>")
    dl, out = sys.argv[1], sys.argv[2]
    os.makedirs(dl, exist_ok=True)
    log, rows = [], []
    f16 = lambda v: f"{v:.16f}"
    for st in STATIONS:
        for ep in EPOCHS:
            tag = f"{st}_{ep:.4f}"
            c = json.loads(get(dl, f"{BASE}/cors/ccf?stationid={st}&epoch={ep:.4f}", f"ccf_{tag}.json", log))
            for k in ("x", "y", "z", "vx", "vy", "vz", "vx_sigma", "vy_sigma", "vz_sigma", "x_sigma", "y_sigma",
                      "z_sigma", "xy_cov", "xz_cov", "yz_cov", "vxvy_cov", "vxvz_cov", "vyvz_cov"):
                num(c[k], f"{tag} ccf {k}")
            for k in ("vx", "vy", "vz", "vx_sigma", "vy_sigma", "vz_sigma"):
                c[k] = c[k] / 1e3
            q = (f"station={st}&x={c['x']}&y={c['y']}&z={c['z']}&epoch={ep}&xx={f16(c['x_sigma']**2)}"
                 f"&yy={f16(c['y_sigma']**2)}&zz={f16(c['z_sigma']**2)}&xy={f16(c['xy_cov'])}&xz={f16(c['xz_cov'])}"
                 f"&yz={f16(c['yz_cov'])}&vx={c['vx']}&vy={c['vy']}&vz={c['vz']}&vxx={f16(c['vx_sigma']**2)}"
                 f"&vyy={f16(c['vy_sigma']**2)}&vzz={f16(c['vz_sigma']**2)}&vxy={f16(c['vxvy_cov'])}"
                 f"&vxz={f16(c['vxvz_cov'])}&vyz={f16(c['vyvz_cov'])}")
            resp = json.loads(get(dl, f"{BASE}/gsp/coords?{q}", f"coords_{tag}.json", log))
            by = {r["Datum"]: r for r in resp}
            if sorted(by) != sorted(FRAMES) or len(resp) != len(FRAMES):
                fail(f"{tag}: unexpected frames {sorted(by)}")
            g = by["ITRF2020"]
            for r in resp:
                if r["station"] != st or float(num(r["epoch"], "epoch")) != ep:
                    fail(f"{tag}: station/epoch mismatch in {r['Datum']}")
            # The ITRF2020 row must echo the CCF position we sent.
            for k in "xyz":
                if float(g[k]) != float(c[k]):
                    fail(f"{tag}: ITRF2020 echo {k} differs")
            for fr in FRAMES[1:]:
                r = by[fr]
                rows.append([st, num(g["epoch"], "epoch"), fr]
                            + [num(g[k], f"{tag} itrf {k}") for k in ("x", "y", "z", "lat", "lon", "eht")]
                            + [num(r[k], f"{tag} {fr} {k}") for k in ("x", "y", "z", "lat", "lon", "eht")]
                            + [f"coords_{tag}.json"])
    os.makedirs(out, exist_ok=True)
    for name, raw, _ in log:
        with open(os.path.join(out, name), "wb") as f:
            f.write(raw)
    with open(os.path.join(dl, "requests.log"), encoding="utf-8") as f:
        times = {line.split(",")[1]: line.split(",")[0] for line in f if line.strip()}
    with open(os.path.join(out, "PROVENANCE.csv"), "w", encoding="utf-8", newline="\n") as f:
        f.write("# NGS beta Geodetic Station Pages back end (api_m), fetched by libs/geokit-core/tools/nsrs/fetch_epp2022_gsp.py\n")
        f.write("# file,retrieved_utc,bytes,sha256,url\n")
        for name, raw, url in log:
            f.write(f"{name},{times.get(name, '')},{len(raw)},{hashlib.sha256(raw).hexdigest()},{url}\n")
    with open(os.path.join(out, "epp2022_gsp_vectors.csv"), "w", encoding="utf-8", newline="\n") as f:
        f.write("# NGS-computed ITRF2020 -> 2022-frame pairs at one coordinate epoch, copied verbatim from the coords_*.json\n")
        f.write("# responses (lat/lon degrees, eht metres on GRS80; x/y/z metres). Source: beta.ngs.noaa.gov/api_m/gsp/coords.\n")
        f.write("station,epoch,frame,itrf_x,itrf_y,itrf_z,itrf_lat,itrf_lon,itrf_h,frame_x,frame_y,frame_z,frame_lat,frame_lon,frame_h,source\n")
        for r in rows:
            f.write(",".join(r) + "\n")
    with open(os.path.join(out, ".gitattributes"), "w", encoding="utf-8", newline="\n") as f:
        f.write("# Raw NGS responses: keep the exact bytes (SHA-256 pinned in PROVENANCE.csv).\n* -text\n")
    print(f"{len(rows)} vector rows, {len(log)} files -> {out}")


if __name__ == "__main__":
    main()
