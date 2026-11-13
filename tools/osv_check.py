# SPDX-FileCopyrightText: 2026 RepoShelf contributors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Ismert sérülékenységek lekérdezése az OSV.dev adatbázisából (C. 37. 4.: függőségek ellenőrzése; docs: D-124, D-131).

Csak NYILVÁNOS csomagneveket és verziókat küld (Maven-koordináták), mást nem. Két forrás:
  --runtime FILE  a `./gradlew :app:dependencies --configuration githubReleaseRuntimeClasspath` kimenete: ezek kerülnek
                  az APK-ba; ha bármelyikre ismert sérülékenység van, a kilépési kód 1 (a CI elbukik);
  --all FILE      a `gradle/verification-metadata.xml`: a build minden komponense (pluginok, teszt-függőségek is);
                  ezekre csak figyelmeztetés jár, mert az APK-ba nem kerülnek.

Futtatás (a repó gyökeréből):
    ./gradlew -q :app:dependencies --configuration githubReleaseRuntimeClasspath > build/runtime-deps.txt
    python3 -I tools/osv_check.py --runtime build/runtime-deps.txt --all gradle/verification-metadata.xml
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import urllib.request
import xml.etree.ElementTree as ET

OSV_BATCH = "https://api.osv.dev/v1/querybatch"
CHUNK = 100
# `+--- group:artifact:1.0 -> 1.2 (*)` vagy `\--- group:artifact:{strictly 1.0} -> 1.0 (c)`
TREE_LINE = re.compile(r"^[\s|\\+-]*[+\\]--- (?P<group>[A-Za-z0-9_.-]+):(?P<artifact>[A-Za-z0-9_.-]+)(?::(?P<version>\{[^}]*\}|[^\s{]+))?(?: -> (?P<resolved>[^\s]+))?")


def parse_dependency_tree(text: str) -> set[str]:
    coords: set[str] = set()
    for line in text.splitlines():
        match = TREE_LINE.match(line)
        if not match:
            continue
        version = match.group("resolved") or match.group("version")
        if not version or version.startswith("{"):
            continue
        if line.rstrip().endswith("(n)"):
            continue
        coords.add(f"{match.group('group')}:{match.group('artifact')}:{version}")
    return coords


def parse_verification_metadata(text: str) -> set[str]:
    root = ET.fromstring(text)
    coords = set()
    for element in root.iter():
        if element.tag.endswith("component"):
            group, name, version = element.get("group"), element.get("name"), element.get("version")
            if group and name and version:
                coords.add(f"{group}:{name}:{version}")
    return coords


def query(coords: list[str]) -> dict[str, list[str]]:
    found: dict[str, list[str]] = {}
    for start in range(0, len(coords), CHUNK):
        chunk = coords[start:start + CHUNK]
        payload = {"queries": [
            {"package": {"name": c.rsplit(":", 1)[0], "ecosystem": "Maven"}, "version": c.rsplit(":", 1)[1]} for c in chunk
        ]}
        request = urllib.request.Request(
            OSV_BATCH, data=json.dumps(payload).encode("utf-8"),
            headers={"Content-Type": "application/json", "User-Agent": "dependency-audit"},
        )
        with urllib.request.urlopen(request, timeout=60) as response:
            results = json.load(response)["results"]
        for coord, result in zip(chunk, results):
            ids = [v["id"] for v in result.get("vulns") or []]
            if ids:
                found[coord] = ids
    return found


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--runtime", required=True)
    parser.add_argument("--all")
    args = parser.parse_args(argv)
    with open(args.runtime, encoding="utf-8") as handle:
        runtime = parse_dependency_tree(handle.read())
    if len(runtime) < 10:
        print(f"HIBA: a futásidejű függőségek listája gyanúsan rövid ({len(runtime)})", file=sys.stderr)
        return 2
    others: set[str] = set()
    if args.all:
        with open(args.all, encoding="utf-8") as handle:
            others = parse_verification_metadata(handle.read()) - runtime
    found = query(sorted(runtime | others))
    runtime_hits = {c: v for c, v in found.items() if c in runtime}
    for coord, ids in sorted(found.items()):
        kind = "APK" if coord in runtime else "build/teszt"
        print(f"[{kind}] {coord}: {', '.join(ids)}")
    print(f"Lekérdezve: {len(runtime)} futásidejű + {len(others)} build/teszt komponens; "
          f"ismert sérülékenység: {len(runtime_hits)} futásidejű, {len(found) - len(runtime_hits)} build/teszt.")
    return 1 if runtime_hits else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
