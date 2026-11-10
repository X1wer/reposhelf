# :core:apk

APK-döntési logika tiszta JVM-ben (docs/ARCHITECTURE.md 2., 9., 10.3. pont):
- `AssetClassifier`, `AbiDetector`, `AssetSelector`: a kiadás állományainak osztályozása és az eszközhöz illő APK kiválasztása (D-030);
- `VersionNames`: verziónevek előzetes összehasonlítása (a végleges döntést a versionCode hozza);
- `ReleaseResolver`, `PreliminaryCompatibility`: a megjelenítendő kiadás és a letöltés előtti becslés;
- `ZipInspector`, `ApkVerifier`: a letöltött APK tényleges vizsgálata, az aláírás három szintjével külön (C. 18.).

A kriptográfiai munkát az `ApkInspector` interfész végzi: Androidon a platform verifierje (`:core:installer`), a JVM-tesztekben az apksig (`src/test`).
A teszt-APK-k előállítása: `python -I core/apk/testdata/generate_fixtures.py` (D-017).
