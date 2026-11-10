# :core:installer

Android-platformréteg (docs/ARCHITECTURE.md 11. pont):
- `PlatformApkInspector`: APK-ellenőrzés a platform verifierjével (D-031);
- `InstalledPackages`: telepített csomagok, „ismeretlen források” engedély, eszközprofil;
- `PackageInstallerGateway`, `InstallResultReceiver`, `InstallEvents`: telepítés PackageInstaller-sessionnel;
- `InstallCoordinator`: letöltés → ellenőrzés → telepítés;
- `Shortcuts`: kezdőképernyős parancsikon (C. 8.).
