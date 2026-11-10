# :core:update

A frissítési folyamat 8 lépése (docs/ARCHITECTURE.md 9. pont, C. 34.), platformfüggőség nélkül, külön-külön tesztelve:

1. `ReleaseDiscovery` – frissítés keresése (repó-azonosság, kiadások)
2. `VersionResolver` – verzió azonosítása (versionCode > kiadás-azonosság > verziónév > dátum)
3. `CompatibilityAssessor` – állományválasztás és előzetes becslés
4. `ApkDownloader` – letöltés
5–6. `ApkVerifier.checkIntegrity` / `checkSignature` (`:core:apk`) – integritás és az aláírás három szintje
7. `InstallInitiator` – a telepítés kezdeményezése (`PackageInstallPort` mögött a platform)
8. `InstallResultVerifier` – a telepítési eredmény ellenőrzése

Az `UpdateChecker` az 1–3. lépést futtatja egymás után (letöltés nélküli ellenőrzés).
