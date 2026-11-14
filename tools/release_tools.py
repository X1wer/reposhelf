# SPDX-FileCopyrightText: 2026 RepoShelf contributors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Kiadási segédeszköz a release-workflow-hoz (C. 37.; docs: D-130). Csak a Python standard könyvtárát használja.

Parancsok (a repó gyökeréből):
    python3 -I tools/release_tools.py notes --tag v0.1.0 [--repo owner/name] --out build/release-notes.md
        Ellenőrzi, hogy a tag a gradle.properties verziójával egyezik, és a CHANGELOG.md-ben van nem üres szakasz
        ehhez a verzióhoz; a szakasz szövegét kiírja (ez lesz a GitHub-kiadás leírása). A CHANGELOG kézi tördelését
        összefűzi (a GitHub a kiadási leírásban minden sortörést megjelenít), `--repo` megadásakor a relatív linkeket
        a tag szerinti abszolút GitHub-címre cseréli.
    python3 -I tools/release_tools.py sums dist/*.apk --out dist/SHA256SUMS
        SHA-256 ellenőrzőösszegek a `sha256sum -c` formátumában.
    python3 -I tools/release_tools.py verification --sums dist/SHA256SUMS
        A kiadási leírás „Verification” szakasza: a release-tanúsítvány ujjlenyomata(i) a gradle.properties-ből és az
        ellenőrzőösszegek kódblokkban.
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


BLOCK_START = re.compile(r"^\s*([-*+]\s|\d+[.)]\s|#|>|\||```|~~~)")
RELATIVE_LINK = re.compile(r"\]\((?!https?:|mailto:|#)([^)\s]+)\)")
REPO = re.compile(r"^[A-Za-z0-9-]{1,39}/[A-Za-z0-9._-]{1,100}$")


def unwrap(markdown: str) -> str:
    """A kézi tördelés összefűzése: a bekezdés vagy listaelem folytatósora az előző sorhoz kerül, szóközzel.

    Címsor, új listaelem, idézet, táblázat és kódblokk nem folytatás; a kódblokk tartalma változatlan marad.
    """
    out: list[str] = []
    in_code = False
    for line in markdown.split("\n"):
        stripped = line.strip()
        if stripped.startswith(("```", "~~~")):
            in_code = not in_code
            out.append(line)
            continue
        joinable = (
            not in_code
            and stripped
            and out
            and out[-1].strip()
            and not BLOCK_START.match(line)
            and not out[-1].lstrip().startswith(("#", "```", "~~~", "|"))
            and not out[-1].endswith("  ")
        )
        if joinable:
            out[-1] = out[-1].rstrip() + " " + stripped
        else:
            out.append(line)
    return "\n".join(out)


def absolute_links(markdown: str, repo: str, tag: str) -> str:
    """A repón belüli relatív linkek (`](SECURITY.md#x)`) a tag szerinti abszolút GitHub-címre; a kiadási oldalon a
    relatív cím nem a repó fájljára mutatna."""
    if not REPO.fullmatch(repo):
        raise ReleaseError(f"érvénytelen repó: {repo!r}")
    return RELATIVE_LINK.sub(lambda m: f"](https://github.com/{repo}/blob/{tag}/{m.group(1).removeprefix('./')})", markdown)


def release_signers(properties_text: str) -> list[str]:
    for line in properties_text.splitlines():
        if line.strip().startswith("reposhelf.selfUpdate.signerSha256="):
            value = line.split("=", 1)[1].strip()
            signers = [s for s in value.split(",") if s]
            if not signers or not all(re.fullmatch(r"[0-9a-f]{64}", s) for s in signers):
                raise ReleaseError("a reposhelf.selfUpdate.signerSha256 nincs megadva vagy érvénytelen")
            return signers
    raise ReleaseError("a reposhelf.selfUpdate.signerSha256 hiányzik")


def verification_section(sums_text: str, signers: list[str]) -> str:
    lines = [line for line in sums_text.splitlines() if line.strip()]
    if not lines or not all(re.fullmatch(r"[0-9a-f]{64}  [A-Za-z0-9._-]+", line) for line in lines):
        raise ReleaseError("érvénytelen SHA256SUMS")
    certs = "\n".join(f"`{s}`" for s in signers)
    noun = "certificate" if len(signers) == 1 else "certificates"
    return (
        "\n### Verification\n\n"
        f"The APKs are signed with the RepoShelf release key (APK Signature Scheme v3). Signing {noun} SHA-256:\n\n"
        f"{certs}\n\n"
        "SHA-256 of the files (also in `SHA256SUMS`):\n\n"
        "```\n" + "\n".join(lines) + "\n```\n"
    )


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
    notes.add_argument("--repo", help="owner/name: a relatív linkek abszolút GitHub-címre cserélése")
    notes.add_argument("--out")
    sums = sub.add_parser("sums")
    sums.add_argument("files", nargs="+")
    sums.add_argument("--out")
    verification = sub.add_parser("verification")
    verification.add_argument("--sums", required=True)
    verification.add_argument("--properties", default=str(ROOT / "gradle.properties"))
    verification.add_argument("--out")
    version = sub.add_parser("version")
    version.add_argument("--properties", default=str(ROOT / "gradle.properties"))
    args = parser.parse_args(argv)
    try:
        if args.command == "notes":
            name, _ = read_version(pathlib.Path(args.properties).read_text(encoding="utf-8"))
            check_tag(args.tag, name)
            output = unwrap(changelog_section(pathlib.Path(args.changelog).read_text(encoding="utf-8"), name))
            if args.repo:
                output = absolute_links(output, args.repo, args.tag)
        elif args.command == "sums":
            output = sha256sums([pathlib.Path(f) for f in args.files])
        elif args.command == "verification":
            signers = release_signers(pathlib.Path(args.properties).read_text(encoding="utf-8"))
            output = verification_section(pathlib.Path(args.sums).read_text(encoding="utf-8"), signers)
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
