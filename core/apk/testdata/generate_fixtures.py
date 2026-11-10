# SPDX-FileCopyrightText: 2026 RepoShelf contributors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Teszt-APK-k (fixture-ök) előállítása a :core:apk egységtesztjeihez.

Csak a hivatalos Android build-tools eszközeit használja (aapt2, zipalign, apksigner) és a JDK keytool-ját.
A teszt-kulcsok egy ideiglenes mappában jönnek létre, és futás után törlődnek: a repóba csak a kész,
teszt-kulccsal aláírt APK-k kerülnek. Ezek NEM valódi alkalmazások, és nem telepítendők valódi készülékre.

Futtatás (a repó gyökeréből, Git Bash):
    python -I core/apk/testdata/generate_fixtures.py

Környezet: ANDROID_SDK (alapértelmezés: %LOCALAPPDATA%/Android/Sdk), JAVA_HOME (alapértelmezés: Android Studio JBR).
"""

from __future__ import annotations

import os
import pathlib
import shutil
import subprocess
import tempfile
import zipfile

BUILD_TOOLS = "37.0.0"
PLATFORM = "android-37.0"
PASSWORD = "test-only-not-secret"

HERE = pathlib.Path(__file__).resolve().parent
OUT = HERE.parent / "src" / "testFixtures" / "resources" / "fixtures"
SDK = pathlib.Path(os.environ.get("ANDROID_SDK", pathlib.Path(os.environ["LOCALAPPDATA"]) / "Android" / "Sdk"))
JAVA_HOME = pathlib.Path(os.environ.get("JAVA_HOME", r"C:\Program Files\Android\Android Studio\jbr"))
TOOLS = SDK / "build-tools" / BUILD_TOOLS
AAPT2 = TOOLS / "aapt2.exe"
ZIPALIGN = TOOLS / "zipalign.exe"
APKSIGNER_JAR = TOOLS / "lib" / "apksigner.jar"
ANDROID_JAR = SDK / "platforms" / PLATFORM / "android.jar"
JAVA = JAVA_HOME / "bin" / "java.exe"
KEYTOOL = JAVA_HOME / "bin" / "keytool.exe"


def run(*args: object) -> None:
    subprocess.run([str(a) for a in args], check=True, capture_output=True, text=True)


def manifest(work: pathlib.Path, package: str, split: str | None = None) -> pathlib.Path:
    split_attr = f' split="{split}"' if split else ""
    path = work / f"AndroidManifest-{package}-{split or 'base'}.xml"
    path.write_text(
        f'<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{package}"{split_attr}>\n'
        f'  <application android:hasCode="false" />\n'
        f"</manifest>\n",
        encoding="utf-8",
    )
    return path


def build_unsigned(work: pathlib.Path, name: str, package: str, version_code: int, min_sdk: int = 28,
                   abis: tuple[str, ...] = (), split: str | None = None) -> pathlib.Path:
    linked = work / f"{name}.linked.apk"
    run(AAPT2, "link", "-o", linked, "-I", ANDROID_JAR, "--manifest", manifest(work, package, split),
        "--min-sdk-version", min_sdk, "--target-sdk-version", 37,
        "--version-code", version_code, "--version-name", f"{version_code}.0")
    if abis:
        with zipfile.ZipFile(linked, "a", compression=zipfile.ZIP_DEFLATED) as z:
            for abi in abis:
                z.writestr(f"lib/{abi}/libdummy.so", b"\x7fELF-not-a-real-library")
    aligned = work / f"{name}.aligned.apk"
    run(ZIPALIGN, "-p", "-f", "4", linked, aligned)
    return aligned


def keystore(work: pathlib.Path, alias: str) -> pathlib.Path:
    path = work / f"{alias}.p12"
    run(KEYTOOL, "-genkeypair", "-keystore", path, "-storetype", "PKCS12", "-storepass", PASSWORD,
        "-alias", alias, "-keyalg", "EC", "-groupname", "secp256r1", "-validity", "36500",
        "-dname", f"CN=RepoShelf test key {alias}, O=TEST ONLY")
    return path


def signer_args(ks: pathlib.Path) -> list[object]:
    return ["--ks", ks, "--ks-pass", f"pass:{PASSWORD}", "--ks-type", "PKCS12"]


def sign(unsigned: pathlib.Path, out_name: str, *signer: object) -> pathlib.Path:
    out = OUT / out_name
    shutil.copyfile(unsigned, out)
    run(JAVA, "-jar", APKSIGNER_JAR, "sign", "--min-sdk-version", 28, *signer, out)
    idsig = out.with_suffix(out.suffix + ".idsig")
    if idsig.exists():
        idsig.unlink()
    return out


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    for old in OUT.glob("*.apk"):
        old.unlink()
    with tempfile.TemporaryDirectory(prefix="reposhelf-fixtures-") as tmp:
        work = pathlib.Path(tmp)
        key_a, key_b = keystore(work, "keyA"), keystore(work, "keyB")
        pkg, other = "io.github.x1wer.fixture", "io.github.x1wer.other"

        v1 = build_unsigned(work, "v1", pkg, 1)
        v2 = build_unsigned(work, "v2", pkg, 2)
        v3 = build_unsigned(work, "v3", pkg, 3)
        sign(v1, "fixture-v1-keyA.apk", *signer_args(key_a))
        v2_a = sign(v2, "fixture-v2-keyA.apk", *signer_args(key_a))
        sign(v2, "fixture-v2-keyB.apk", *signer_args(key_b))
        sign(build_unsigned(work, "other", other, 2), "other-v2-keyA.apk", *signer_args(key_a))
        sign(build_unsigned(work, "minsdk", pkg, 2, min_sdk=99), "fixture-v2-minsdk99-keyA.apk", *signer_args(key_a))
        sign(build_unsigned(work, "arm64", pkg, 2, abis=("arm64-v8a",)), "fixture-v2-arm64-keyA.apk", *signer_args(key_a))

        # Kulcsrotáció A → B (APK Signature Scheme v3, proof-of-rotation lineage).
        lineage = work / "lineage.bin"
        run(JAVA, "-jar", APKSIGNER_JAR, "rotate", "--out", lineage,
            "--old-signer", *signer_args(key_a), "--new-signer", *signer_args(key_b))
        sign(v3, "fixture-v3-rotatedAtoB.apk", *signer_args(key_a), "--next-signer", *signer_args(key_b),
             "--lineage", lineage, "--rotation-min-sdk-version", 28)

        # Két aláíró (v1/v2), rotáció nélkül: nem támogatott eset.
        sign(v2, "fixture-v2-twoSigners.apk", "--v3-signing-enabled", "false",
             *signer_args(key_a), "--next-signer", *signer_args(key_b))

        # Split APK-készlet: alap + ABI-konfigurációs split, azonos csomagnév és versionCode.
        sign(build_unsigned(work, "split-base", pkg, 4), "fixture-split-base-v4-keyA.apk", *signer_args(key_a))
        sign(build_unsigned(work, "split-arm64", pkg, 4, abis=("arm64-v8a",), split="config.arm64_v8a"),
             "fixture-split-config.arm64_v8a-v4-keyA.apk", *signer_args(key_a))

        # Sérült (csonka) letöltés és utólag módosított tartalom.
        data = v2_a.read_bytes()
        (OUT / "fixture-v2-truncated.apk").write_bytes(data[: len(data) * 6 // 10])
        tampered = bytearray(data)
        with zipfile.ZipFile(v2_a) as z:
            info = z.getinfo("AndroidManifest.xml")
        header_len = 30 + len(info.filename.encode()) + len(info.extra)
        tampered[info.header_offset + header_len + info.compress_size // 2] ^= 0x01
        (OUT / "fixture-v2-tampered.apk").write_bytes(bytes(tampered))

    for apk in sorted(OUT.glob("*.apk")):
        print(f"{apk.name:46s} {apk.stat().st_size:7d} bájt")


if __name__ == "__main__":
    main()
