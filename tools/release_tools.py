# SPDX-FileCopyrightText: 2026 RepoShelf contributors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Kiadási segédeszköz a release-workflow-hoz (C. 37.; docs: D-130). Csak a Python standard könyvtárát használja.

Parancsok (a repó gyökeréből):
    python3 -I tools/release_tools.py notes --tag v0.1.0 --out build/release-notes.md
        Ellenőrzi, hogy a tag a gradle.properties verziójával egyezik, és a CHANGELOG.md-ben van nem üres szakasz
        ehhez a verzióhoz; a szakasz szövegét kiírja (ez lesz a GitHub-kiadás leírása).
    python3 -I tools/release_tools.py sums dist/*.apk --out dist/SHA256SUMS
        SHA-256 ellenőrzőösszegek a `sha256sum -c` formátumában.
    python3 -I tools/release_tools.py version
        A gradle.properties verzióneve és verziókódja.
"""

from __future__ import annotations

import argparse
import hashlib
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
VERSION_NAME = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+([-.][A-Za-z0-9.]+)?$")
SECTION = re.compile(r"^## \[(?P<version>[^\]]+)\](?P<rest>.*)$")


class ReleaseError(Exception):
    pass


def read_version(properties_text: str) -> tuple[str, int]:
    values: dict[str, str] = {}
    for line in properties_text.splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
    name = values.get("reposhelf.versionName", "")
    code = values.get("reposhelf.versionCode", "")
    if not VERSION_NAME.fullmatch(name):
        raise ReleaseError(f"érvénytelen reposhelf.versionName: {name!r}")
    if not code.isdigit() or not 0 < int(code) <= 2_100_000_000:
        raise ReleaseError(f"érvénytelen reposhelf.versionCode: {code!r}")
    return name, int(code)


def check_tag(tag: str, version_name: str) -> None:
    if tag != f"v{version_name}":
        raise ReleaseError(f"a tag ({tag}) nem egyezik a gradle.properties verziójával (v{version_name})")


def changelog_section(changelog: str, version: str) -> str:
    """A `## [version]` szakasz szövege a következő `## ` címig (Keep a Changelog formátum)."""
    lines = changelog.splitlines()
    start = None
    for index, line in enumerate(lines):
        match = SECTION.match(line)
        if match and match.group("version") == version:
            if start is not None:
                raise ReleaseError(f"a CHANGELOG.md-ben kétszer szerepel a(z) {version} szakasz")
            start = index + 1
    if start is None:
        raise ReleaseError(f"a CHANGELOG.md-ben nincs „## [{version}]” szakasz")
    body: list[str] = []
    for line in lines[start:]:
        if line.startswith("## "):
            break
        body.append(line)
    text = "\n".join(body).strip()
    if not text:
        raise ReleaseError(f"a CHANGELOG.md {version} szakasza üres")
    return text + "\n"


def sha256sums(paths: list[pathlib.Path]) -> str:
    if not paths:
        raise ReleaseError("nincs állomány")
    lines = []
    for path in sorted(paths, key=lambda p: p.name):
        digest = hashlib.sha256()
        with path.open("rb") as handle:
            for chunk in iter(lambda: handle.read(1 << 20), b""):
                digest.update(chunk)
        lines.append(f"{digest.hexdigest()}  {path.name}")
    return "\n".join(lines) + "\n"


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    notes = sub.add_parser("notes")
    notes.add_argument("--tag", required=True)
    notes.add_argument("--changelog", default=str(ROOT / "CHANGELOG.md"))
    notes.add_argument("--properties", default=str(ROOT / "gradle.properties"))
    notes.add_argument("--out")
    sums = sub.add_parser("sums")
    sums.add_argument("files", nargs="+")
    sums.add_argument("--out")
    version = sub.add_parser("version")
    version.add_argument("--properties", default=str(ROOT / "gradle.properties"))
    args = parser.parse_args(argv)
    try:
        if args.command == "notes":
            name, _ = read_version(pathlib.Path(args.properties).read_text(encoding="utf-8"))
            check_tag(args.tag, name)
            output = changelog_section(pathlib.Path(args.changelog).read_text(encoding="utf-8"), name)
        elif args.command == "sums":
            output = sha256sums([pathlib.Path(f) for f in args.files])
        else:
            name, code = read_version(pathlib.Path(args.properties).read_text(encoding="utf-8"))
            output = f"{name} {code}\n"
    except (ReleaseError, OSError) as error:
        print(f"HIBA: {error}", file=sys.stderr)
        return 1
    if getattr(args, "out", None):
        pathlib.Path(args.out).write_text(output, encoding="utf-8", newline="\n")
    else:
        sys.stdout.write(output)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
