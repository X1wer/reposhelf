# SPDX-FileCopyrightText: 2026 RepoShelf contributors
# SPDX-License-Identifier: GPL-3.0-or-later
"""A rendszer telepítéskori split-ellenőrzésének próbája (docs: D-128, a felhasználó döntése: Q-011).

A RepoShelf a split-APK-készletből csak az alap-APK-t tudja telepítés előtt teljesen ellenőrizni, mert a rendszer
csomagelemzője az önálló splitet nem dolgozza fel. A kiegészítő állományoknál arra támaszkodunk, hogy a rendszer a
telepítéskor (PackageInstaller-session, ugyanaz az út, mint az `adb install-multiple`-é) elutasítja a készletet, ha egy
split aláírója, verziókódja vagy csomagneve eltér az alap-APK-étól. Ez a szkript ezt a feltevést ellenőrzi egy futó
emulátoron vagy tesztkészüléken.

Ideiglenes, csak erre a futásra generált teszt-kulcsokkal dolgozik (a futás végén törlődnek); a próbacsomagot
(`io.github.x1wer.splitcheck`) a végén eltávolítja. Valódi felhasználói készüléken nem kell futtatni.

Futtatás (a repó gyökeréből, Git Bash; egyetlen csatlakoztatott emulátor mellett):
    python -X utf8 -I tools/split_install_check.py
"""

from __future__ import annotations

import importlib.util
import os
import pathlib
import subprocess
import sys
import tempfile

HERE = pathlib.Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("generate_fixtures", HERE.parent / "core" / "apk" / "testdata" / "generate_fixtures.py")
gf = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(gf)


def _run_quietly(*args: object) -> None:
    # A JDK-eszközök kimenete a rendszer kódlapján jön (pl. Windows alatt nem UTF-8): bájtként kezeljük.
    subprocess.run([str(a) for a in args], check=True, capture_output=True)


gf.run = _run_quietly

ADB = pathlib.Path(os.environ.get("ADB", gf.SDK / "platform-tools" / "adb.exe"))
PKG = "io.github.x1wer.splitcheck"


def adb(*args: object) -> subprocess.CompletedProcess[str]:
    return subprocess.run([str(ADB), *map(str, args)], capture_output=True, text=True, timeout=180)


def install(*apks: pathlib.Path) -> tuple[bool, str]:
    result = adb("install-multiple", *apks)
    output = (result.stdout + result.stderr).strip()
    installed = result.returncode == 0 and "Success" in output
    adb("uninstall", PKG)
    return installed, output.splitlines()[-1] if output else ""


def main() -> int:
    sdk = adb("shell", "getprop", "ro.build.version.sdk").stdout.strip()
    if not sdk:
        print("Nincs elérhető emulátor vagy készülék (adb).")
        return 2
    with tempfile.TemporaryDirectory(prefix="reposhelf-splitcheck-") as tmp:
        work = pathlib.Path(tmp)
        gf.OUT = work / "out"
        gf.OUT.mkdir()
        key_x, key_y = gf.keystore(work, "keyX"), gf.keystore(work, "keyY")
        signed_x, signed_y = gf.signer_args(key_x), gf.signer_args(key_y)
        base = gf.sign(gf.build_unsigned(work, "base", PKG, 1), "base.apk", *signed_x)
        split = gf.build_unsigned(work, "split", PKG, 1, split="config.xxhdpi")
        cases = [
            ("azonos aláírójú split", [base, gf.sign(split, "split-x.apk", *signed_x)], True),
            ("más kulccsal aláírt split", [base, gf.sign(split, "split-y.apk", *signed_y)], False),
            ("eltérő verziókódú split",
             [base, gf.sign(gf.build_unsigned(work, "split-v2", PKG, 2, split="config.xxhdpi"), "split-v2.apk", *signed_x)], False),
            ("más csomagnevű split",
             [base, gf.sign(gf.build_unsigned(work, "split-other", PKG + ".other", 1, split="config.xxhdpi"), "split-other.apk", *signed_x)], False),
        ]
        adb("uninstall", PKG)
        failures = 0
        for name, apks, expected in cases:
            installed, detail = install(*apks)
            good = installed == expected
            failures += 0 if good else 1
            print(f"[{'OK' if good else 'HIBA'}] API {sdk}, {name}: {'települt' if installed else 'elutasítva'} – {detail}")
    print("Eredmény:", "a rendszer minden eltérő splitet elutasított" if failures == 0 else f"{failures} eset nem a várt módon alakult")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
