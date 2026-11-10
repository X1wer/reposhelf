# SPDX-FileCopyrightText: 2026 RepoShelf contributors
# SPDX-License-Identifier: GPL-3.0-or-later
"""A tiszta JVM-modulok (core:apk, core:catalog, core:model, core:network, core:source, core:update) lefordított
osztályainak platform-API-hivatkozásait összeveti az Android SDK api-versions.xml-jével (D-070).

Miért kell: az Android Lint NewApi-ellenőrzése a nem Android-modulokat nem vizsgálja (ellenőrizve), a JVM-modulok
pedig a JDK teljes API-jával fordulnak, így pl. az `InputStream.readNBytes` (Androidon csak API 33-tól) gond nélkül
lefordul, de minSdk 28-on futásidőben `NoSuchMethodError`-t dob.

Használat (Git Bash, az app/ mappából, a modulok lefordítása után: ./gradlew classes):
  python -I tools/check_jvm_api_level.py "$LOCALAPPDATA/Android/Sdk/platforms/android-37.0/data/api-versions.xml" \
      "$JAVA_HOME/bin/javap" 28 core/*/build/classes/kotlin/main
Kilépési kód: 0 = nincs minSdk-nál újabb hivatkozás, 1 = van (a lista a kimeneten).
"""
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET


def load_api(path):
    classes = {}
    for cls in ET.parse(path).getroot().iter("class"):
        since = float(cls.get("since", "1"))
        supers = [e.get("name") for e in cls if e.tag in ("extends", "implements")]
        members = {m.get("name"): float(m.get("since", str(since))) for m in cls if m.tag in ("method", "field")}
        classes[cls.get("name")] = (since, supers, members)
    return classes


def lookup(classes, owner, member, seen=None):
    """A tag API-szintje; ha a hivatkozott osztály nem deklarálja, az ősosztályokban és interfészekben keresi."""
    seen = seen if seen is not None else set()
    if owner in seen or owner not in classes:
        return None
    seen.add(owner)
    _, supers, members = classes[owner]
    if member in members:
        return members[member]
    for parent in supers:
        found = lookup(classes, parent, member, seen)
        if found is not None:
            return found
    return None


REF = re.compile(r"// (Method|InterfaceMethod|Field|class) ([\w/$]+)(?:\.(\"?[\w<>$]+\"?):(\S+))?")


def main():
    if len(sys.argv) < 5:
        print(__doc__)
        return 2
    api_xml, javap, min_sdk, *dirs = sys.argv[1:]
    min_sdk = int(min_sdk)
    classes = load_api(api_xml)
    files = [os.path.join(r, f) for d in dirs for r, _, fs in os.walk(d) for f in fs if f.endswith(".class")]
    if not files:
        print("Nincs lefordított osztály; előbb: ./gradlew classes")
        return 2
    problems = set()
    for i in range(0, len(files), 200):
        out = subprocess.run([javap, "-c", "-p", *files[i:i + 200]], capture_output=True, text=True,
                             errors="replace", check=True).stdout
        for kind, owner, name, desc in REF.findall(out):
            if not owner.startswith(("java/", "javax/", "android/")) or owner not in classes:
                continue
            if classes[owner][0] > min_sdk:
                problems.add(f"API {classes[owner][0]:g}: osztály {owner}")
            if not name:
                continue
            # api-versions.xml: metódus = név + teljes leíró, mező = név.
            key = name.strip('"') + desc if kind != "Field" else name.strip('"')
            since = lookup(classes, owner, key)
            if since is not None and since > min_sdk:
                problems.add(f"API {since:g}: {owner}.{key}")
    print(f"{len(files)} osztályfájl ellenőrizve (minSdk {min_sdk}).")
    for problem in sorted(problems):
        print(problem)
    print("Nincs minSdk-nál újabb API-hivatkozás." if not problems else f"{len(problems)} probléma.")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
