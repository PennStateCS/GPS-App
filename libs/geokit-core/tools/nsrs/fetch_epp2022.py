"""Fetch and pin NGS's beta EPP2022 (Euler pole parameter) definitions.

Downloads, from the NGS beta site, the NATRF2022/PATRF2022/CATRF2022/MATRF2022 page, its EPP CSV and the two
equation images, checks them strictly, and copies them into the geokit-core test resources together with a
provenance file. Nothing is computed here: the outputs are NGS's own files, byte for byte.

Usage (from the repo root, isolated mode so nothing is imported from the download directory):

    python -I libs/geokit-core/tools/nsrs/fetch_epp2022.py <empty-download-dir> libs/geokit-core/src/test/resources/nsrs

Downloads are untrusted data: each file is size-limited, the CSV is parsed with a fixed header and a strict
number pattern (no eval, no csv dialect sniffing), the HTML is only hashed and searched for fixed strings, and
the PNGs are only checked for the PNG signature. Exactly four HTTP GETs are made; re-running is cheap for NGS.

Exit status is non-zero, and nothing is written to the output directory, if any check fails.
"""

import hashlib
import os
import re
import sys
import urllib.request
from datetime import datetime, timezone

BASE = "https://beta.ngs.noaa.gov/NATRF2022/"
FILES = {
    # published name: (URL, max bytes)
    "natrf2022-index.html": (BASE + "index.html", 200_000),
    "epp2022-beta-values.csv": (BASE + "epp2022-beta-values.csv", 10_000),
    "equation1a.png": (BASE + "images/equation1a.png", 200_000),
    "equation2a.png": (BASE + "images/equation2a.png", 200_000),
}
HEADER = "Plate,Omega X (mas/yr),Omega Y (mas/yr),Omega Z (mas/yr)"
PLATES = ["NATRF2022", "CATRF2022", "PATRF2022", "MATRF2022"]
NUMBER = re.compile(r"^-?\d{1,3}\.\d{1,6}$")
# Fixed statements the implementation relies on; if NGS rewords the page, a human must re-read it.
REQUIRED_TEXT = [
    "identical to the International Terrestrial Reference Frame of 2020 (ITRF2020) at epoch 2020.0",
    "All values are in milliarcseconds/year",
    "Rotation is positive counterclockwise following the right-hand rule",
    "648 x 10<sup>6</sup>",
    "t</em> - 2020.0",
]


def fetch(url: str, limit: int) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "geokit-tools/1.0"})
    with urllib.request.urlopen(req, timeout=60) as r:  # noqa: S310 (fixed https URLs)
        data = r.read(limit + 1)
    if len(data) > limit:
        raise ValueError(f"{url}: larger than {limit} bytes")
    return data


def parse_csv(data: bytes) -> list:
    text = data.decode("ascii")  # strict: any non-ASCII byte is an error
    lines = [l for l in text.replace("\r\n", "\n").split("\n") if l != ""]
    if lines[0] != HEADER:
        raise ValueError(f"unexpected CSV header {lines[0]!r}")
    rows = []
    for line in lines[1:]:
        f = line.split(",")
        if len(f) != 4 or f[0] not in PLATES or not all(NUMBER.match(x) for x in f[1:]):
            raise ValueError(f"unexpected CSV row {line!r}")
        rows.append((f[0], f[1], f[2], f[3]))
    if sorted(r[0] for r in rows) != sorted(PLATES):
        raise ValueError(f"expected one row per plate, got {[r[0] for r in rows]}")
    return rows


def main(download_dir: str, out_dir: str) -> int:
    if os.listdir(download_dir):
        print(f"{download_dir} must be empty", file=sys.stderr)
        return 2
    got = {}
    for name, (url, limit) in FILES.items():
        data = fetch(url, limit)
        with open(os.path.join(download_dir, name), "wb") as fh:
            fh.write(data)
        got[name] = data
    rows = parse_csv(got["epp2022-beta-values.csv"])
    html = got["natrf2022-index.html"].decode("utf-8", errors="strict")
    missing = [t for t in REQUIRED_TEXT if t not in html]
    if missing:
        print(f"page wording changed, re-read it: missing {missing}", file=sys.stderr)
        return 3
    for png in ("equation1a.png", "equation2a.png"):
        if not got[png].startswith(b"\x89PNG\r\n\x1a\n"):
            print(f"{png} is not a PNG", file=sys.stderr)
            return 3

    raw_dir = os.path.join(out_dir, "raw")
    os.makedirs(raw_dir, exist_ok=True)
    for name, data in got.items():
        with open(os.path.join(raw_dir, name), "wb") as fh:
            fh.write(data)
    now = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    prov = [
        "# NGS beta EPP2022 sources, fetched by libs/geokit-core/tools/nsrs/fetch_epp2022.py",
        f"# retrieved {now}",
        "# file,url,bytes,sha256",
    ]
    for name, (url, _) in FILES.items():
        prov.append(f"{name},{url},{len(got[name])},{hashlib.sha256(got[name]).hexdigest()}")
    prov.append("# parsed EPP rows (plate,omega_x_mas_per_yr,omega_y_mas_per_yr,omega_z_mas_per_yr)")
    prov += ["#  " + ",".join(r) for r in rows]
    with open(os.path.join(raw_dir, "PROVENANCE.csv"), "w", encoding="utf-8", newline="\n") as fh:
        fh.write("\n".join(prov) + "\n")
    print("\n".join(prov))
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print(__doc__, file=sys.stderr)
        sys.exit(2)
    sys.exit(main(sys.argv[1], sys.argv[2]))
