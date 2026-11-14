<!-- SPDX-FileCopyrightText: 2026 RepoShelf contributors -->
<!-- SPDX-License-Identifier: CC-BY-4.0 -->

# Adatkezelési tájékoztató

*English version: [PRIVACY_POLICY.md](PRIVACY_POLICY.md)*

Hatály: a RepoShelf Android-alkalmazás (a GitHub- és az F-Droid-változat is), 0.1.0-s és újabb verziók.
Utolsó módosítás: 2026-11-14.

> A tájékoztató az alkalmazás tényleges, a forráskóddal összevetett működését írja le. Jogász még nem nézte át; ez
> ajánlott.

## Összefoglalás

- A RepoShelfnek **nincs saját szervere**, **nincs fiók vagy regisztráció**, **nincs analitika, reklám, hibajelentés-küldés
  és követőkód**. Nem használja a Google Play-szolgáltatásokat és a Firebase-t.
- A RepoShelf projekt (a karbantartói) **semmilyen adatot nem kap rólad**.
- Az alkalmazáslistád, a beállításaid és az előzményeid **a készülékeden maradnak**.
- A működéshez az alkalmazás közvetlenül a **GitHubbal** kommunikál. A GitHub ezért megkapja az IP-címedet, és látja, mely
  nyilvános repókat kéri le az alkalmazás. Más weboldalak képei csak a kérésedre töltődnek be.

## Ki a felelős?

A RepoShelf önkéntesek által karbantartott nyílt forráskódú projekt. Mivel a projekt nem üzemeltet szolgáltatást, és nem
gyűjt vagy kap személyes adatot, az alkalmazás használatával kapcsolatban nem adatkezelő. Az itt leírt adatkezelés a
készülékeden, a te irányításod alatt történik, a hálózati kéréseknél pedig az alkalmazás által elért szolgáltatásoknál
(elsősorban a GitHubnál), amelyek a saját adatkezelésükért a saját tájékoztatójuk szerint felelnek.

Kérdés esetén nyiss egy issue-t a RepoShelf GitHub-repójában, érzékeny ügyben pedig használd a repó privát biztonsági
bejelentő űrlapját (lásd: [SECURITY.md](SECURITY.md)).

## Hálózati kapcsolatok

A RepoShelf csak HTTPS-t használ, és csak az alábbi hosztokhoz kapcsolódik. Más hosztra nem követi az átirányítást
(kivéve egy általad kifejezetten kért külső README-képet).

| Hoszt | Mire | Mikor |
|---|---|---|
| `raw.githubusercontent.com` | az aláírt alkalmazáskatalógus és aláírása; a repóban tárolt README-képek | katalógus: indításkor legfeljebb 6 óránként, a „Katalógus frissítése” gombra és a háttérellenőrzéssel; képek: az adatlap megnyitásakor |
| `api.github.com` | repóadatok, kiadások, README, a repó azonosságának ellenőrzése | adatlap megnyitásakor, repó hozzáadásakor, frissítésellenőrzéskor (kézzel vagy a háttérben), letöltés előtt |
| `github.com`, `*.githubusercontent.com` | APK-fájlok letöltése a GitHub Releases-ből (a GitHub a letöltőszervereire irányít); a GitHubon tárolt képek | amikor te (vagy, ha bekapcsoltad, az automatikus letöltés) letöltesz egy alkalmazást; amikor egy adatlap ilyen képet mutat |
| bármely más HTTPS-hoszt | más weboldalakról beágyazott README-képek | **csak** azután, hogy az adott adatlapon a „Külső képek betöltése” gombra koppintottál |
| a böngésződ | linkek a README-ben, „Repó megnyitása” | csak a link megérintésekor; a GitHubon kívülre mutató linkek előbb megerősítést kérnek |

**Mit kapnak meg ezek a szolgáltatások:** mint minden internetkapcsolatnál, az **IP-címedet**; magát a kérést (például
hogy melyik nyilvános repót vagy fájlt kéri le az alkalmazás – ebből kiderül, mely alkalmazásokat nézed, követed vagy
telepíted); valamint a `User-Agent: RepoShelf/<verzió> (Android)` technikai fejlécet, a GitHub API verzióját és a korábbi
válaszok gyorsítótár-azonosítóit (ETag). A RepoShelf **nem** küld eszközazonosítót, hirdetési azonosítót, fiókot, tokent,
a telepített alkalmazások listáját vagy helyadatot.

**A GitHubot** a GitHub, Inc. üzemelteti. Az adatkezelését a
[GitHub General Privacy Statement](https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement)
írja le. A GitHub az adatokat az EU-n kívül is kezelheti. A RepoShelf a GitHub API-t **névtelenül** használja; a
GitHub-tokennel történő bejelentkezés nincs megvalósítva.

**A külső README-képek** felfedhetik az IP-címedet az őket tároló weboldalnak, és követésre is használhatók; ezért
blokkoltak, amíg az adott oldalon nem engedélyezed őket.

**A saját frissítés keresése** (csak a GitHub-változatban): csak ha az első kérdésre igent mondtál, vagy bekapcsoltad „A
RepoShelf frissítéseinek automatikus keresése” lehetőséget, a RepoShelf a saját repójáról is kérdez az `api.github.com`-tól,
ugyanúgy, mint a többi alkalmazásnál. Az F-Droid-változat ilyen kérést nem küld.

**Maga az Android** (nem a RepoShelf) a telepítéskor Google-szolgáltatásokhoz fordulhat – például a Google Play Protect
vizsgálatához, vagy 2027-től a tanúsított eszközökön a developer verificationhöz. Ezekre a Google feltételei és a
készüléked beállításai vonatkoznak.

## A készüléken tárolt adatok

Az alábbi adatok a RepoShelf privát tárhelyén vannak, amelyet más alkalmazás nem olvashat. **Az Android felhőalapú
biztonsági mentéséből és az eszközök közötti átvitelből ki vannak zárva.**

| Adat | Cél | Meddig |
|---|---|---|
| A katalógus (nevek, repó-útvonalak, kulcsszavak, kategóriák) | böngészés, offline keresés | amíg újabb érvényes katalógus nem érkezik |
| A követett és kedvenc alkalmazások, saját források a repó-azonosítókkal, az utolsó ellenőrzés eredményével, a megtanult csomagazonosítókkal | az alkalmazásaid kezelése és frissítése | amíg el nem távolítod a listából, vagy nem törlöd az alkalmazás adatait |
| Csomagonként a rögzített aláíró tanúsítványok (SHA-256) | a más kulccsal aláírt frissítések elutasítása | az alkalmazás adatainak törléséig (az „Eltávolítás a listából” után is szándékosan megmarad) |
| Az APK-vizsgálatok eredménye (csomagazonosító, verzió, aláíró-ujjlenyomat, döntés) | hogy ugyanazt az elutasított fájlt ne töltse le és ne ajánlja fel újra | az alkalmazás adatainak törléséig |
| Mentett adatlapok (repóadatok, kiadások, README-szöveg) | offline mód, kevesebb hálózati kérés | legfeljebb 300 bejegyzés; a legrégebbiek törlődnek |
| Biztonsági figyelmeztetések | a problémák megjelenítése és nyugtázása | az alkalmazás adatainak törléséig |
| Frissítési előzmények (alkalmazásnév, csomagazonosító, verziók, időpont, eredmény, rövid ok, módszer) | az előzmények képernyő | az utolsó 1000 bejegyzés |
| Értesítési napló (melyik állapotról ment már értesítés) | az ismétlődő értesítések elkerülése | az alkalmazás adatainak törléséig |
| Beállítások (frissítésellenőrzés, automatizmusok, megjelenés, nyelv, a saját frissítésre adott válasz) | a beállításaid | az alkalmazás adatainak törléséig |
| Letöltött APK-fájlok | ellenőrzés és telepítés | telepítés után törlődnek; a maradványok 2 nap (kész fájl), illetve 7 nap (megszakadt letöltés) után törlődnek |

A frissítési előzmények soha nem tartalmaznak URL-t, fájlútvonalat vagy tokent. A RepoShelf csak néhány technikai sort ír
az Android rendszernaplójába (például hogy a háttérütemezés nem állítható be); ezekben nincs személyes adat.

**Telepített alkalmazások:** a telepített verziók megjelenítéséhez és az aláírók összevetéséhez a RepoShelf kiolvassa az
indítóikonnal rendelkező telepített alkalmazások verzióját és aláíró tanúsítványát. Nem kér engedélyt az összes telepített
alkalmazás lekérdezésére, és ez az információ nem hagyja el a készülékedet.

## Exportálás

Az „Exportálás…” egy általad, az Android fájlválasztójával kiválasztott helyre ír fájlt. A fájl a követett és kedvenc
alkalmazásokat, a saját forrásokat és az alkalmazásonkénti szabályokat tartalmazza – tokent, aláíró kulcsot, telepítési
adatot és előzményt nem. Hogy a fájllal utána mi történik, rajtad múlik.

## Engedélyek

| Engedély | Miért |
|---|---|
| Internet, hálózati állapot | kapcsolódás a GitHubhoz; a hálózat elérhetőségének ellenőrzése a háttérellenőrzés előtt |
| Alkalmazások telepítése (`REQUEST_INSTALL_PACKAGES`) | telepítés az Android telepítőjével; ezen felül az „Ismeretlen alkalmazások telepítése” engedélyt is meg kell adnod |
| Frissítés felhasználói beavatkozás nélkül (`UPDATE_PACKAGES_WITHOUT_USER_ACTION`) | automatikus frissítés ott, ahol az Android engedi, és csak ha bekapcsoltad |
| Update ownership (`ENFORCE_UPDATE_OWNERSHIP`) | más telepítők csak a jóváhagyásoddal frissíthessenek (Android 14+); kikapcsolható |
| Eltávolítás kérése (`REQUEST_DELETE_PACKAGES`) | az „Alkalmazás eltávolítása” az Android megerősítő ablakát nyitja meg; a háttérben semmi nem törlődik |
| Értesítések (`POST_NOTIFICATIONS`) | frissítési és biztonsági értesítések; csak szükség esetén kéri (Android 13+) |
| Átfedések elrejtése (`HIDE_OVERLAY_WINDOWS`) | más alkalmazások átfedéseinek elrejtése, amíg a RepoShelf nyitva van (Android 12+), a tapjacking ellen |
| Indítás rendszerindításkor, ébren tartás, előtérszolgáltatás | az Android WorkManager könyvtára használja, hogy a háttérellenőrzés ütemezése újraindítás után is megmaradjon és megbízhatóan fusson |

A RepoShelf nem kér hely-, névjegy-, kamera-, mikrofon-, tárhely- vagy „összes csomag lekérdezése” engedélyt.

## Választási lehetőségeid és jogaid

- **A nem kívánt hálózati forgalom kikapcsolása:** állítsd az „Automatikus ellenőrzés” értékét „Csak kézzel”-re, hagyd
  blokkolva a külső képeket, és ne kapcsold be a saját frissítés keresését.
- **Adatok törlése:** az „Eltávolítás a listából” törli az alkalmazás bejegyzését (a rögzített aláíró a védelmed érdekében
  megmarad). Minden adat törlése: Android-beállítások → Alkalmazások → RepoShelf → Tárhely → „Tárhely törlése”, vagy a
  RepoShelf eltávolítása.
- **GDPR:** ha az EU-ban vagy az EGT-ben élsz, az általános adatvédelmi rendelet jogokat biztosít neked (például
  hozzáférés, helyesbítés, törlés). Mivel a RepoShelf projekt nem tárol rólad adatot, a GitHub vagy más weboldalak által
  kezelt adatokkal kapcsolatos kérelmet hozzájuk kell címezni. A készülékeden lévő adatok a fentiek szerint teljesen a te
  irányításod alatt vannak. Panaszt a helyi adatvédelmi hatóságnál tehetsz (Magyarországon: Nemzeti Adatvédelmi és
  Információszabadság Hatóság, NAIH).

## Gyermekek

A RepoShelf általános célú eszköz, nem gyermekeknek szól. Tudatosan senkitől nem gyűjt adatot.

## Változások

A tájékoztató változásai a repó előzményeiben követhetők, és a [CHANGELOG.md](CHANGELOG.md) összefoglalja őket. Ha egy
későbbi verzió olyan funkciót hoz, amely az adatkezelést megváltoztatja (például opcionális GitHub-bejelentkezés), a
tájékoztató a kiadás előtt frissül.
