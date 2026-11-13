# SPDX-FileCopyrightText: 2026 RepoShelf contributors
# SPDX-License-Identifier: GPL-3.0-or-later
"""A THIRD_PARTY_NOTICES.md generált komponenslistája (C. 38.; docs: D-136).

Forrás: az APK-ba kerülő függőségek feloldott listája (ugyanaz, mint az OSV-ellenőrzésé) és a helyi Gradle-gyorsítótár
POM-fájljai (licenc, projekt-URL; ha a POM-ban nincs licenc, a szülő-POM-é). Hálózatot nem használ.
Csak az engedélyezett licencek fogadhatók el (`ALLOWED`); ismeretlen vagy hiányzó licencnél a kilépési kód 1, mert az új
függőség licencét embernek kell átnéznie (Q-001: csak GPL-3.0-or-later-rel kompatibilis licenc).

Futtatás (az `app/` mappából, egy build után, hogy a gyorsítótár teljes legyen):
    ./gradlew -q :app:dependencies --configuration githubReleaseRuntimeClasspath > build/runtime-deps.txt
    python3 -I tools/third_party_notices.py --tree build/runtime-deps.txt --file THIRD_PARTY_NOTICES.md
    (--check: nem ír, csak jelzi, ha a fájl elavult)
"""

from __future__ import annotations

import argparse
import os
import pathlib
import sys
import xml.etree.ElementTree as ET

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

from osv_check import parse_dependency_tree  # noqa: E402

BEGIN = "<!-- BEGIN GENERATED COMPONENT LIST (tools/third_party_notices.py) -->"
END = "<!-- END GENERATED COMPONENT LIST -->"

# A POM-okban előforduló licencnevek SPDX-azonosítója. Csak ezek fogadhatók el.
ALLOWED = {
    "the apache software license, version 2.0": "Apache-2.0",
    "the apache license, version 2.0": "Apache-2.0",
    "apache license, version 2.0": "Apache-2.0",
    "apache-2.0": "Apache-2.0",
    "bsd-2-clause": "BSD-2-Clause",
    "bsd-3-clause": "BSD-3-Clause",
    "mit": "MIT",
    "the mit license": "MIT",
}

POM_NS = "{http://maven.apache.org/POM/4.0.0}"


class NoticeError(Exception):
    pass


def spdx(license_name: str) -> str | None:
    return ALLOWED.get(license_name.strip().lower())


def _child(element, tag):
    found = element.find(POM_NS + tag)
    return found if found is not None else element.find(tag)


def _text(element, tag) -> str | None:
    found = _child(element, tag)
    return found.text.strip() if found is not None and found.text and found.text.strip() else None


def _licenses(root) -> list[str]:
    container = _child(root, "licenses")
    if container is None:
        return []
    names = []
    for lic in list(container):
        name = _text(lic, "name")
        if name:
            names.append(name)
    return names


def find_pom(cache: pathlib.Path, group: str, artifact: str, version: str) -> pathlib.Path | None:
    base = cache / group / artifact / version
    if not base.is_dir():
        return None
    for hashed in sorted(base.iterdir()):
        for file in sorted(hashed.glob("*.pom")):
            return file
    return None


def pom_info(cache: pathlib.Path, group: str, artifact: str, version: str, depth: int = 0) -> tuple[list[str], str | None]:
    """(licencnevek, projekt-URL) a POM-ból, licenc híján a szülő-POM-ból (legfeljebb 5 szint)."""
    pom = find_pom(cache, group, artifact, version)
    if pom is None:
        raise NoticeError(f"Nincs POM a Gradle-gyorsítótárban: {group}:{artifact}:{version} (futtass előbb buildet)")
    root = ET.parse(pom).getroot()
    names, url = _licenses(root), _text(root, "url")
    parent = _child(root, "parent")
    if (not names or not url) and parent is not None and depth < 5:
        p_names, p_url = pom_info(cache, _text(parent, "groupId"), _text(parent, "artifactId"), _text(parent, "version"), depth + 1)
        names = names or p_names
        url = url or p_url
    return names, url


def collect(coords: set[str], cache: pathlib.Path) -> list[dict]:
    rows, problems = [], []
    for coord in sorted(coords):
        group, artifact, version = coord.split(":")
        names, url = pom_info(cache, group, artifact, version)
        ids = sorted({spdx(n) or f"UNKNOWN({n})" for n in names}) or ["MISSING"]
        if any(i.startswith(("UNKNOWN", "MISSING")) for i in ids):
            problems.append(f"{coord}: {', '.join(ids)}")
        rows.append({"group": group, "artifact": artifact, "version": version, "license": " OR ".join(ids), "url": url})
    if problems:
        raise NoticeError("Át nem nézett licenc:\n  " + "\n  ".join(problems))
    return rows


def render(rows: list[dict]) -> str:
    counts: dict[str, int] = {}
    for row in rows:
        counts[row["license"]] = counts.get(row["license"], 0) + 1
    summary = ", ".join(f"{lic}: {n}" for lic, n in sorted(counts.items(), key=lambda kv: (-kv[1], kv[0])))
    lines = [BEGIN, "", f"{len(rows)} components ({summary}).", "",
             "| Component (Maven coordinates) | Version | License | Project |", "|---|---|---|---|"]
    for row in rows:
        url = row["url"] or ""
        project = f"<{url}>" if url.startswith("https://") else (url or "–")
        lines.append(f"| `{row['group']}:{row['artifact']}` | {row['version']} | {row['license']} | {project} |")
    lines += ["", END]
    return "\n".join(lines)


def replace_block(document: str, block: str) -> str:
    start, end = document.find(BEGIN), document.find(END)
    if start < 0 or end < start:
        raise NoticeError("A generált blokk jelölői hiányoznak a fájlból")
    return document[:start] + block + document[end + len(END):]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--tree", required=True, help="a :app:dependencies kimenete (githubReleaseRuntimeClasspath)")
    parser.add_argument("--file", required=True, help="a frissítendő THIRD_PARTY_NOTICES.md")
    parser.add_argument("--cache", default=os.path.expanduser("~/.gradle/caches/modules-2/files-2.1"))
    parser.add_argument("--check", action="store_true", help="csak ellenőrzés: 1-es kilépési kód, ha a fájl elavult")
    args = parser.parse_args(argv)
    try:
        coords = parse_dependency_tree(pathlib.Path(args.tree).read_text(encoding="utf-8"))
        if not coords:
            raise NoticeError("A függőségfa üres vagy nem értelmezhető")
        rows = collect(coords, pathlib.Path(args.cache))
        path = pathlib.Path(args.file)
        current = path.read_text(encoding="utf-8")
        updated = replace_block(current, render(rows))
    except (NoticeError, OSError, ET.ParseError) as error:
        print(f"HIBA: {error}", file=sys.stderr)
        return 1
    if args.check:
        if updated != current:
            print("A THIRD_PARTY_NOTICES.md elavult: futtasd újra az eszközt --check nélkül.", file=sys.stderr)
            return 1
        print(f"Rendben: {len(rows)} komponens.")
        return 0
    path.write_text(updated, encoding="utf-8", newline="\n")
    print(f"Frissítve: {len(rows)} komponens.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
