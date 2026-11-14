<!-- SPDX-FileCopyrightText: 2026 RepoShelf contributors -->
<!-- SPDX-License-Identifier: CC-BY-4.0 -->

# RepoShelf – felhasználói kézikönyv

*English version: [USER_GUIDE.md](USER_GUIDE.md)*

A kézikönyv a RepoShelf 0.1.0-s (fejlesztés alatt álló) változatát írja le. Az idézőjelben álló szövegek a magyar
felület feliratai. Ahol egy funkció tervezett, de még nem érhető el, a kézikönyv ezt kifejezetten jelzi.

Tartalom:
[1. Telepítés](#1-telepítés) ·
[2. Első indítás](#2-első-indítás) ·
[3. A katalógus böngészése](#3-a-katalógus-böngészése) ·
[4. Alkalmazások telepítése](#4-alkalmazások-telepítése) ·
[5. Saját repó hozzáadása](#5-saját-repó-hozzáadása) ·
[6. Alkalmazások kezelése](#6-alkalmazások-kezelése) ·
[7. Frissítésellenőrzés](#7-frissítésellenőrzés) ·
[8. Automatikus frissítések](#8-automatikus-frissítések) ·
[9. Értesítések](#9-értesítések) ·
[10. Beállítások](#10-beállítások) ·
[11. Biztonsági figyelmeztetések](#11-biztonsági-figyelmeztetések) ·
[12. Gyakori hibák](#12-gyakori-hibák) ·
[13. Hibaelhárítás](#13-hibaelhárítás)

---

## 1. Telepítés

**Feltételek:** Android 9 vagy újabb, valamint szabad tárhely a RepoShelfnek (az APK kb. 5 MB) és a letöltendő
alkalmazásoknak.

A RepoShelf APK-ként, a GitHub Releases oldalán jelenik meg. *Nyilvános kiadás még nincs; ez a fejezet azt írja le, hogyan
fog működni a telepítés.*

1. Töltsd le az APK-t a kiadás oldaláról. Minden kiadásban van egy `SHA256SUMS` fájl is; ha szeretnéd, vesd össze vele a
   letöltött fájl ellenőrzőösszegét.
2. Nyisd meg az APK-t. Az Android engedélyt kér, hogy a böngésződ vagy fájlkezelőd alkalmazást telepíthessen
   („Ismeretlen alkalmazások telepítése”) – csak annak az egy alkalmazásnak add meg.
3. Hagyd jóvá a telepítést az Android ablakában.

Két változat van: az alap (GitHub-) változat, amely saját magát is frissítheti, és az F-Droid-változat
(`io.github.x1wer.reposhelf.fdroid`) saját frissítés nélkül. A kettő külön alkalmazás, nem frissítik egymást. Az
F-Droid-változat még nincs beküldve az F-Droidba.

> **Android developer verification (2027):** a Google bejelentette, hogy 2027-től a tanúsított Android-eszközök csak
> regisztrált fejlesztők alkalmazásait telepítik a szokásos módon; a nem regisztrált fejlesztők alkalmazásaihoz egy
> „advanced flow” bekapcsolása kell. Arról, hogy a RepoShelf regisztrálva lesz-e, még nincs döntés. Ha az Android
> megakadályozza a RepoShelf vagy egy vele telepített alkalmazás telepítését, lásd: [Gyakori hibák](#12-gyakori-hibák).

## 2. Első indítás

- **Katalógus:** a RepoShelf egy beépített, aláírt katalógussal indul, így azonnal, internet nélkül is böngészhetsz.
  Internetkapcsolatnál megpróbál újabb katalógust letölteni (indításkor legfeljebb 6 óránként). A Felfedezés képernyő
  állapotsora mutatja, honnan származik a katalógus („Beépített katalógus – még nem frissült az internetről.” vagy
  „Katalógus frissítve: …”).
- **Kérdés a saját frissítésről (csak a GitHub-változatban):** a RepoShelf egyszer megkérdezi, kereshet-e új verziót a saját
  GitHub-repójában („A RepoShelf saját frissítései”). A „Keressen frissítést” és a „Ne keressen” egyenrangú választás; a
  döntést később a Beállítások → „A RepoShelf frissítése” részben módosíthatod. A beleegyezésed nélkül semmi nem töltődik
  le.
- **Nincs fiók:** a RepoShelf nem kér regisztrációt vagy bejelentkezést. A GitHub API-t névtelenül használja.
- **A háttérellenőrzés** alapból be van kapcsolva (naponta). Az automatikus letöltés és az automatikus telepítés alapból
  ki van kapcsolva.
- **Értesítések:** Android 13-tól a RepoShelf az első követéskor (vagy amikor a Beállításokban bekapcsolod az
  értesítéseket) kéri az értesítési engedélyt, előtte elmagyarázza, miért – nem az első indításkor.

A fő képernyők: **Felfedezés**, **Alkalmazásaim** (a navigációs sávban „Appjaim”), **Frissítések**, **Saját források**
(a sávban „Forrásaim”) és **Beállítások**. Széles képernyőn és fekvő helyzetben a navigáció oldalsó sávban jelenik meg.

## 3. A katalógus böngészése

A **Felfedezés** képernyő a katalógust mutatja.

- **Keresés** („Alkalmazások keresése”): név, kulcsszó vagy kategória szerint. Szórészletre is talál („gall” → „Gallery”),
  több szó szűkíti a találatokat, az ékezet és a kis- és nagybetű nem számít („terkep” → „Térkép”). A keresés csak a
  készüléken lévő adatokat használja.
- **Kategóriák:** a kategóriaszűrő egy kategóriára szűkíti a listát; az „Összes” megszünteti a szűrést.
- **Katalógus frissítése** (a frissítés ikon): ha van újabb katalógus, letölti. Katalógust csak érvényes aláírással fogad
  el; különben az utolsó érvényes katalógus marad érvényben, és látszik az ok (például „a katalógus aláírása hiányzik vagy
  érvénytelen, ezért nem fogadtuk el”).
- Ha a katalógust a karbantartói régóta nem újították meg, ez látszik: „A katalógus régóta nem frissült, lehet, hogy
  elavult.” A katalógus ettől még használható.

> „A katalógusban szereplés nem jelent biztonsági tanúsítást.” A katalógus csak azt rögzíti, melyik repóból származik egy
> alkalmazás (és egyes alkalmazásoknál a csomagazonosítót és az aláíró kulcsot). Lásd: [DISCLAIMER.hu.md](DISCLAIMER.hu.md).

Egy alkalmazásra koppintva megnyílik az **adatlapja**. Az adatlap a GitHubról töltődik be: fejlesztő, repó, licenc,
leírás, legújabb kiadás és dátuma, kiadási megjegyzések, letöltési méret és a projekt README-je. Ha valami hiányzik, az
adatlap ezt kiírja („A repónak nincs leírása.”, „Licenc: nincs megadva”, „Ikon nem érhető el”) – a RepoShelf nem talál ki
adatot. Telepítés előtt az alkalmazásoknak többnyire nincs ikonja; képernyőképek helyett a README képei látszanak.

**A README megjelenítése:** a README formázott szövegként, böngészőmotor nélkül jelenik meg. A GitHubon tárolt képek
automatikusan betöltődnek; más weboldalak képei csak a „Külső képek betöltése” gombra, mert a betöltésük felfedi az
IP-címedet az adott szervereknek. Az SVG-képek nem jelennek meg. A GitHubon kívülre mutató linkek a teljes címet mutatják,
és megnyitás előtt rákérdeznek („Megnyitod ezt a külső hivatkozást?”).

A **Megosztás** (az adatlapon) a repó webcímét küldi el egy másik alkalmazásnak.

## 4. Alkalmazások telepítése

Az adatlap **Telepítés** részében:

1. Az **Előzetes becslés (letöltés előtt)** azt mutatja, ami letöltés nélkül tudható: van-e telepíthető csomag, a
   készülék processzorához készült-e, van-e elég tárhely, és előzetes kiadás-e. „Nem garancia”: a minimális
   Android-verzió, a csomagazonosító és az aláírás csak a letöltés után ellenőrizhető.
2. Koppints a **Letöltés és ellenőrzés** gombra. Először a RepoShelf elmagyarázza, hogy az „Ismeretlen alkalmazások
   telepítése” engedélyre van szüksége; a „Beállítások megnyitása” a rendszer kapcsolójához visz. Engedélyezd a
   RepoShelfnek. Minden telepítéshez ezután is a te jóváhagyásod kell.
3. Letöltés előtt a RepoShelf ellenőrzi, hogy a repó még mindig ugyanaz-e (az állandó GitHub-azonosítója alapján). Ha ez
   épp nem ellenőrizhető (nincs kapcsolat, elfogyott a kérési keret), semmi nem töltődik le.
4. A letöltés közvetlenül a GitHubról, HTTPS-en történik. Ha megszakad, az „Újra” onnan folytatja, ahol abbamaradt.
5. A **Tényleges vizsgálat (letöltött csomag)** minden ellenőrzést az eredményével mutat („Rendben” / „Figyelmeztetés” /
   „Sikertelen” / „Nem ellenőrzött”): fájlméret, a letöltött fájl SHA-256-ja, a GitHub által közölt ellenőrzőösszeg,
   csomagszerkezet, csomagazonosító, verziókód, minimális és cél-Android-verzió, processzor-architektúra, valamint
   **az aláírás három külön szinten**:
   1. *Az aláírás technikai érvényessége* – az Android saját ellenőrzője vizsgálja.
   2. *Az aláíró a korábban ismerthez képest* – „Ugyanaz az aláíró, mint a telepített alkalmazásé”, „Egyezik a
      katalógusban rögzített aláíróval” vagy „Első telepítés: az aláíró most kerül rögzítésre (trust on first use)”.
   3. *A kiadó megbízhatósága* – „Kriptográfiailag nem ellenőrizhető”. A RepoShelf a forrást mutatja, „biztonságos”
      minősítést nem ad.
   Az aláíró tanúsítvány SHA-256 ujjlenyomata is látszik, így összevetheted azzal, amit a fejlesztő közzétesz.
6. Koppints a **Telepítés** gombra. Az Android a saját jóváhagyó ablakát mutatja. Ha kritikus problémát talált, a gomb
   helyén ez áll: „A telepítés tiltva: kritikus problémát találtunk.”
7. Telepítés után a RepoShelf visszaolvassa és megerősíti a telepített verziót. A „Megnyitás” elindítja az alkalmazást.
   Ha az indítód támogatja, a RepoShelf felajánlja a „Parancsikon hozzáadása” lehetőséget – erre csak akkor van szükség,
   ha az indító magától nem tett ki ikont.

**Több csomag egy kiadásban:** a RepoShelf a processzorodhoz illő csomagot választja (például `arm64-v8a` vagy
„universal” APK). Ha nem tud dönteni, megkérdez („Válassz telepítőcsomagot”). Az app bundle (`.aab`) és az olyan
archívumok, mint az `.apks` vagy az `.xapk`, nem telepíthetők; ezek magyarázattal kimaradnak.

**Split-APK-készletek:** egyes kiadások egy alap-APK-t és külön „split” fájlokat tesznek közzé. A RepoShelf az
alapcsomagot teljesen ellenőrzi, a split fájlokat azonban csak az Android tudja ellenőrizni a telepítéskor. Ezért a
RepoShelf előbb rákérdez („Telepíted a split-APK-készletet?” → „Telepítés így is”), és ilyen készletet soha nem telepít
automatikusan. Ha a kiadásban önálló APK is van, azt választja.

**Előzetes kiadások:** ha van újabb előzetes (béta) kiadás, az adatlap jelzi, és felajánlja az „Előzetes kiadás
választása” lehetőséget. Az előzetes kiadások instabilak lehetnek.

## 5. Saját repó hozzáadása

Bármely **nyilvános GitHub-repót** hozzáadhatsz, akkor is, ha nincs a katalógusban.

1. Nyisd meg a **Saját források** képernyőt.
2. Írj be egy GitHub-URL-t (`https://github.com/tulajdonos/repo`) vagy `tulajdonos/név` alakot, és koppints az
   **Ellenőrzés** gombra.
3. A RepoShelf lépésenként mutatja: a repó azonosítva, elérhető és nyilvános, az alapadatok betöltve, vannak-e kiadások
   (és melyik a legújabb), van-e telepíthető Android-csomag, és illik-e a készülékhez. Az archivált repót jelzi.
4. Válaszd a **Követés és megnyitás** (az adatlap megnyílik, onnan telepíthetsz) vagy a **Kiadások követése** (csak az új
   kiadásokat követed, telepítés nélkül) lehetőséget.

Ha a repó a katalógusban is szerepel, a katalógusban rögzített adatok (csomagazonosító, aláíró) érvényesek. Az általad
hozzáadott repók az „Általad hozzáadott” listában vannak, az **Alkalmazásaim** képernyőn pedig „Saját forrás” jelölést
kapnak.

Lehetséges hibák: „Nem ismerhető fel GitHub-repóként”, „A repó nem található, vagy nem nyilvános.”, „A repó nem érhető
el”, valamint a GitHub kérési korlátja („Túl sok kérés … Próbáld újra ekkor: …”).

*Tervezett, még nem érhető el:* GitLab- és Codeberg-repók, valamint GitHub-bejelentkezés a magasabb kérési keretért.

## 6. Alkalmazások kezelése

Az **Alkalmazásaim** képernyő minden olyan alkalmazást mutat, amelyet követsz, kedvencnek jelöltél, vagy a RepoShelf-fel
telepítettél.

Szűrők: „Mind”, „Telepített”, „Csak követett”, „Kedvencek”, „Saját források”, „Már nem telepített”.

Minden sor mutatja a nevet, az ikont (telepített alkalmazásnál), a forrásrepót, a telepített és a legújabb verziót (az
előzetes kiadást jelölve), az utolsó ellenőrzés idejét, a frissítési állapotot, azt, hogy be van-e kapcsolva az
automatikus frissítés, és az utolsó átmeneti hibát, ha volt. A fő gomb az állapottól függően „Frissítés”, „Telepítés”
vagy „Megnyitás”.

A **⋮ menü** („További műveletek”): „Ellenőrzés most”, „Repó megnyitása”, „Alkalmazásinformáció” és „Alkalmazás
eltávolítása” (telepített alkalmazásnál; az eltávolítás az Android saját megerősítő ablakát nyitja meg),
„Frissítésfigyelés kikapcsolása” / „Frissítésfigyelés bekapcsolása”, „Kedvenc” / „Eltávolítás a kedvencek közül” és
„Eltávolítás a listából”.

- Az **Eltávolítás a listából** csak a RepoShelf listájáról veszi le az alkalmazást, az telepítve marad. A rögzített
  aláírója megmarad, így egy később más kulccsal aláírt frissítést továbbra is elutasít.
- A **kedvencek** függetlenek a telepítéstől: telepítetlen katalógusbeli alkalmazást is megjelölhetsz.
- **A telepítettséget** maga az Android adja meg. A RepoShelf csak az indítóikonnal rendelkező alkalmazásokat látja (nem
  kér engedélyt az összes telepített alkalmazás lekérdezésére). Egy más úton telepített katalógusbeli alkalmazást az első
  ellenőrzött letöltés után ismer fel; addig ezt mutatja: „Nincs telepítve (vagy a RepoShelf számára nem látható)”.

Az adatlapon a **Frissítési szabályok ehhez az alkalmazáshoz** részben felülbírálhatod a globális beállításokat (lásd:
[Beállítások](#10-beállítások)).

## 7. Frissítésellenőrzés

A RepoShelf csak a **GitHub Releases** kiadásait nézi – a commitokat és a tageket nem. Egy újabb kiadás akkor frissítés,
ha a csomagjának nagyobb a **verziókódja** (az Android belső verziószáma). Mivel a verziókód csak letöltés után ismert,
egy kiadás először így jelenhet meg: „Valószínűleg van frissítés (a letöltés után derül ki)”. Ha a verziónevek nem
hasonlíthatók össze, a RepoShelf ezt jelzi ahelyett, hogy találgatna. Egy egyszer már az ellenőrzésen elbukott
frissítést nem ajánl fel újra.

- Az **Összes ellenőrzése** (az **Alkalmazásaim** képernyőn) egymás után ellenőrzi az összes követett alkalmazást, a
  legrégebben ellenőrzöttel kezdve. Az „Ellenőrzés most” (⋮ menü) egy alkalmazást ellenőriz.
- A RepoShelf **bejelentkezés nélkül** használja a GitHub API-t: óránként kb. **60 kérés**, amelyen a hálózatodon (az
  IP-címeden) lévő összes eszköz osztozik. Egy alkalmazás ellenőrzése kb. 2 kérés. A RepoShelf mindig tartalékol
  kéréseket a megnyitott képernyőknek, ezért egy nagy „Összes ellenőrzése” korábban megállhat, és kiírja, hány alkalmazás
  maradt ki, és mikor folytatható.
- Az átmeneti hiba (nincs kapcsolat, kérési korlát) nem írja felül a korábbi eredményt; a sor külön mutatja az utolsó
  hibát.
- A **Frissítések** képernyő az elérhető frissítésű és a problémás alkalmazásokat mutatja. A követett, de nem telepített
  alkalmazás itt jelenik meg, ha új kiadása van. Az „Előzmények” (a Frissítések képernyőn) a frissítési előzményeket nyitja
  meg.

## 8. Automatikus frissítések

A háttérellenőrzés a Beállítások → „Frissítésellenőrzés” részben megadott gyakorisággal fut. A pontos időpontot az
Android határozza meg (akkumulátorkímélés), ezért a beállítottnál később is futhat. Hálózati kapcsolat és nem alacsony
akkumulátortöltés kell hozzá.

Hogy mi történik egy talált frissítéssel, a beállításaidtól függ (globálisan vagy alkalmazásonként):

| Beállítás | Hatás |
|---|---|
| Csak értesítés (alapértelmezett) | „Frissítés érhető el” értesítés; az adatlapról telepítesz. |
| „Frissítések automatikus letöltése” | A frissítés a háttérben letöltődik és ellenőrződik (mobiladaton csak a „Mobiladaton is” beállítással). „Telepítésre kész” értesítést kapsz; az adatlap újbóli letöltés nélkül telepíti. |
| „Frissítések automatikus telepítése, ahol az Android engedi” | A letöltött és ellenőrzött frissítés jóváhagyás nélkül települ – **csak ott, ahol az Android ezt engedi** (lásd lent). Különben értesítést kapsz, és a szokásos módon jóváhagyod. |

### Mikor NEM engedi az Android a jóváhagyás nélküli telepítést?

A jóváhagyás nélküli telepítés szigorú feltételekhez kötött Android-funkció. Ha bármelyik feltétel nem teljesül, az
Android jóváhagyást kér – **ez normál működés, nem hiba.** A RepoShelf ilyenkor „Hagyd jóvá a frissítést” értesítést
küld.

1. **Első telepítés.** Új alkalmazás telepítéséhez mindig a jóváhagyásod kell. Csak frissítés lehet automatikus.
2. **12-esnél régebbi Android.** Android 9–11-en nincs ilyen funkció; minden frissítés jóváhagyást kér.
3. **Nem a RepoShelf az alkalmazás telepítője.** Az Android csak akkor engedi, ha az alkalmazást a RepoShelf telepítette
   („installer of record”), vagy ha a RepoShelf az alkalmazás „update owner”-e. Ha az alkalmazás másik áruházból,
   böngészőből vagy számítógépről (adb) került fel, az első RepoShelf-es frissítés jóváhagyást kér; utána már a RepoShelf
   a telepítő. Ha egy másik áruház az alkalmazás update ownere, a RepoShelf jóváhagyás nélkül nem frissítheti.
4. **Az alkalmazás régi Android-verziót céloz.** A frissített alkalmazásnak elég új Android-verziót kell céloznia (target
   SDK): legalább API 29-et Android 12-n, 30-at Android 13-on, 31-et Android 14-en, 33-at Android 15-ön, 34-et Android
   16-on és 35-öt Android 17-en. A RepoShelf a dokumentáció szigorúbb értékeivel számol.
5. **Az Android másképp dönt.** Ha minden feltétel teljesül, az Android akkor is kérhet jóváhagyást – például ugyanannak
   az alkalmazásnak rövid időn belüli ismételt frissítésénél, vagy a készülék, illetve a gyártó szabályai miatt. 2027-től a
   developer verification további ellenőrzéseket hozhat.
6. **A Play Protect** a számára ismeretlen alkalmazásoknál saját vizsgáló ablakot mutathat. Ez a RepoShelfen kívül esik.

A RepoShelf ezeken felül saját feltételeket is támaszt: a frissítésnek minden ellenőrzésen át kell mennie, **ugyanazzal
az aláíróval, mint a telepített alkalmazás**; az alkalmazásnál nem lehet kritikus, sem nyugtázatlan biztonsági
figyelmeztetés; a **split-APK-készletek soha nem települnek automatikusan**. Biztonsági problémás frissítés soha nem
települ automatikusan.

*Még nem érhető el:* az automatikus frissítés elhalasztása addig, amíg az alkalmazás használatban van („gentle update”).
Egy automatikus frissítés ezért éppen nyitott alkalmazást is újraindíthat.

## 9. Értesítések

A RepoShelf hét értesítési csatornát használ; ezeket az Android értesítési beállításaiban egyenként be- és kikapcsolhatod:

| Csatorna | Mikor |
|---|---|
| Elérhető alkalmazásfrissítések | Egy követett, telepített alkalmazásnak frissítése van; egy frissítés telepítésre kész; az Android jóváhagyást kér egy frissítéshez. |
| Telepített frissítések | Egy frissítés települt és ellenőrződött. |
| Sikertelen letöltés vagy telepítés | Egy háttérbeli letöltés vagy telepítés nem sikerült. |
| Biztonsági figyelmeztetések | Egy biztonsági probléma a döntésedet igényli (lásd: [11. fejezet](#11-biztonsági-figyelmeztetések)). |
| Katalógusfrissítés | A katalógus frissült. |
| A RepoShelf frissítése | A RepoShelf saját frissítései (GitHub-változat, ha engedélyezted a keresést). |
| Letöltés folyamata | Technikai, alacsony prioritású csatorna. |

- Változatlan állapotról a RepoShelf nem küld újra értesítést.
- Az értesítésre koppintva az érintett alkalmazás adatlapja nyílik meg.
- A Beállítások „Értesítés az elérhető frissítésekről” kapcsolója kikapcsolja a frissítési értesítéseket; alkalmazásonként
  a „Frissítési értesítések” szabály ugyanezt teszi.
- **Ha az értesítések ki vannak kapcsolva,** a Frissítések képernyő és a Beállítások ezt mutatja: „Az értesítések ki vannak
  kapcsolva. A frissítések és a biztonsági figyelmeztetések az alkalmazásban továbbra is látszanak.”, az „Értesítési
  beállítások” linkkel.

## 10. Beállítások

**Frissítésellenőrzés**
- „Automatikus ellenőrzés”: 6 óránként, 12 óránként, naponta (alapértelmezett), hetente vagy „Csak kézzel”.
- „Ellenőrzés csak Wi-Fi-n (forgalomkorlát nélküli hálózaton)”.
- Az utolsó háttérellenőrzés ideje és eredménye.
- Az „Akkumulátor-optimalizálás beállításai” az Android beállításait nyitja meg. A RepoShelf nem kéri, hogy kivételt
  kapjon az akkumulátor-optimalizálás alól; egyes készülékek erős energiatakarékossága késleltetheti vagy kihagyathatja a
  háttérellenőrzést.

**Letöltés és telepítés**
- „Frissítések automatikus letöltése” (alapból ki) és „Mobiladaton is” (alapból ki).
- „Frissítések automatikus telepítése, ahol az Android engedi” (alapból ki) – lásd: [8. fejezet](#8-automatikus-frissítések).
- „Más telepítők csak a jóváhagyásommal frissíthessenek (update ownership, Android 14+)” (alapból be): amikor a RepoShelf
  először telepít egy alkalmazást, más áruházak csak a jóváhagyásoddal frissíthetik.

**Kiadások**
- „Előzetes (béta) kiadások követése” (alapból ki).
- „Új kiadások késleltetése”: nincs késleltetés, 1, 3 vagy 7 nap. Egy kiadást csak ennyi idővel a megjelenése után vesz
  figyelembe.

**Értesítések**
- „Értesítés az elérhető frissítésekről” és link a rendszer értesítési beállításaihoz.

**Megjelenés**
- „Téma”: a rendszer szerint, világos vagy sötét. „A háttérkép színeinek használata” (Android 12+).
- „Nyelv”: a rendszer szerint, English vagy Magyar. Android 13-tól ez ugyanaz, mint az alkalmazás nyelve az Android
  beállításaiban.
- A betűméret a rendszer betűméret-beállítását követi.

**Exportálás és importálás**
- Az „Exportálás…” a követett és kedvenc alkalmazásokat, a saját forrásokat és az alkalmazásonkénti szabályokat egy
  általad választott fájlba menti. A fájlban nincs token, aláíró kulcs vagy telepítési adat.
- Az „Importálás…” beolvas egy ilyen fájlt. Mielőtt bármi változna, a RepoShelf összegzést mutat, és felsorolja, milyen
  saját forrásokat adna hozzá a fájl („Importálod ezt a listát?”). Az importálás semmit nem telepít és nem tölt le, a
  listádon már szereplő alkalmazásokat nem módosítja, és soha nem kapcsolja be az automatikus letöltést, az automatikus
  telepítést vagy az előzetes kiadásokat. Csak megbízható fájlt importálj.
- A beállítások, az előzmények és a rögzített aláírók nem kerülnek az exportba. A RepoShelf ki van zárva az Android
  biztonsági mentéséből és az eszközök közötti átvitelből; új készülékre az export/import segítségével viheted át a
  listádat.

**A RepoShelf frissítése** (GitHub-változat)
- „A RepoShelf frissítéseinek automatikus keresése” (az első indításkor adott válaszod), „Keresés most”, majd „Letöltés és
  ellenőrzés” és „Telepítés”. A RepoShelf ugyanazokkal az ellenőrzésekkel frissíti magát, mint a többi alkalmazást; az
  aláíró kulcsnak egyeznie kell a telepített RepoShelfével. Az F-Droid-változatban ez áll: „Ebben a változatban nincs
  saját frissítés.”

**Névjegy**
- A telepített verzió, rövid licencmegjegyzés, „Licenc” (a GNU General Public License szövege) és „Nyílt forráskódú
  licencek” (a RepoShelf által használt komponensek és licenceik).

**Alkalmazásonkénti szabályok** (adatlap → „Frissítési szabályok ehhez az alkalmazáshoz”): „Frissítésfigyelés”,
„Automatikus letöltés”, „Automatikus telepítés”, „Előzetes kiadások”, „Frissítési értesítések”, „Kiadás-késleltetés”.
Mindegyik lehet „Alapértelmezett (…)” – ez a zárójelben látható globális beállítást követi –, „Be” vagy „Ki”.

## 11. Biztonsági figyelmeztetések

A RepoShelf figyelmeztet, ha valami gyanús. A figyelmeztetéseknek három szintje van:

- **Kritikus** – „Az automatikus telepítés tiltva.” Az érintett frissítés nem települ.
- **Figyelmeztetés** – „Az automatikus telepítés szünetel, amíg nem nyugtázod.”
- **Tájékoztatás** – ott látszik, ahol releváns (például a vizsgálati eredményben), külön riasztásként nem.

| Figyelmeztetés | Szint | Mit jelent / mit tegyél |
|---|---|---|
| Azonos néven egy másik repó található | Kritikus | Az eredeti repót törölték vagy átnevezték, és egy másik vette át a nevét (lehetséges „repojacking”). A telepítés és a frissítés tiltva. Csak akkor fogadd el („Az új repó elfogadása”), ha biztos vagy benne; az aláíró-ellenőrzés ettől még érvényes. Ha a repót a katalógus rögzíti, ez nem bírálható felül. |
| Megváltozott az aláíró kulcs | Kritikus | A frissítést más kulcs írta alá, mint a telepített alkalmazást. A RepoShelf soha nem telepíti, és nem ajánlja fel az eltávolítást és újratelepítést. Kérdezd meg a fejlesztőt; a jogos kulcscsere az Android kulcsrotációját használja, amelyet a RepoShelf elfogad. |
| A frissítés más csomagazonosítót tartalmaz | Kritikus | A letöltés egy másik alkalmazást tartalmaz. |
| A frissítés verziókódja régebbi, mint a telepítetté | Kritikus | Visszalépés, amely már javított hibákat hozhat vissza. |
| A letöltött állomány sérült vagy érvénytelen | Kritikus | A fájl törlődött; próbáld újra később. |
| A csomag régebbi egy olyan verziónál, amely korábban már telepítve volt ezen a készüléken | Figyelmeztetés (az ellenőrzés eredményében, telepítés előtt) | Az alkalmazás most nincs telepítve, de korábban egy újabb verziója fent volt. Lehet, hogy a fejlesztő visszavont egy újabb kiadást, de az is lehet, hogy egy régi kiadást tettek közzé újra. Csak akkor telepítsd, ha erre számítottál. |
| Megváltozott a repó tulajdonosa | Figyelmeztetés | A repó mostantól valaki másé. Ellenőrizd, hogy ez várható volt-e, majd „Tudomásul vettem”. |
| A repó eltűnt vagy nem érhető el | Figyelmeztetés | Az ellenőrzés szünetel; a telepített alkalmazást nem érinti. |
| Nem kompatibilis a készülékkel / váratlan formátum / a forrás hitelessége nem ellenőrizhető | Tájékoztatás | A vizsgálati eredményben látszik. A „nem ellenőrizhető” első telepítésnél, katalógusban rögzített aláíró nélkül természetes. |

A nyitott figyelmeztetések a **Frissítések** képernyőn („Tudomásul vettem” gombbal), az adatlapon és – a RepoShelf saját
frissítésénél – a Beállításokban jelennek meg. Egy adott kiadásra vonatkozó figyelmeztetés akkor oldódik fel, ha egy
újabb kiadás az igazolt aláíróval minden ellenőrzésen átmegy. A figyelmeztetések kikapcsolt értesítések mellett is
látszanak az alkalmazásban.

A repó átnevezését a RepoShelf automatikusan követi („A repót átnevezték; a RepoShelf az új nevet követi.”).

*Még nem érhető el:* figyelmeztetés, ha egy frissítés új érzékeny engedélyt kér, illetve ha egy katalógusbejegyzés
megváltozik.

**Frissítési előzmények** (Frissítések → „Előzmények”): minden telepítési kísérlet bekerül: alkalmazás, csomagazonosító,
régi és új verzió, időpont, eredmény („Sikeres”, „Sikertelen”, „Megszakítva”, „Letiltva”), rövid ok és a módszer („Kézi”,
„Automatikus”, „Automatikus, jóváhagyással”, „A RepoShelf saját frissítése”). Szűrhető és kereshető; az utolsó 1000
bejegyzést őrzi meg.

## 12. Gyakori hibák

| Hiba | Magyarázat |
|---|---|
| „Engedély szükséges” a letöltés előtt | Engedélyezd a RepoShelfet az „Ismeretlen alkalmazások telepítése” beállításban („Beállítások megnyitása”). |
| Egy telepített alkalmazásnál ez áll: „Nincs telepítve (vagy a RepoShelf számára nem látható)” | A RepoShelf csak az indítóikonos alkalmazásokat látja, és a katalógusbeli alkalmazások csomagazonosítóját az első ellenőrzött letöltésből tanulja meg. Használd egyszer a „Letöltés és ellenőrzés” gombot. |
| „Elérted a GitHub API korlátját” | A névtelen keret IP-címenként óránként 60 kérés, amelyen a hálózatodon lévő többi eszköz is osztozik (például a mobilszolgáltatók közös címein). Várj a kiírt időpontig. |
| „Ennek a repónak nincs olyan kiadása, amely telepíthető Android-csomagot (APK) tartalmaz.” | A projekt nem tesz közzé APK-t a GitHub Releases oldalon. A RepoShelf forráskódból nem fordít. |
| A kiadásban csak `.aab`, `.apks` vagy `.xapk` van | Ezeket a formátumokat a RepoShelf nem tudja telepíteni. |
| „Az aláíró eltér a telepített alkalmazásétól” | Lásd: [Biztonsági figyelmeztetések](#11-biztonsági-figyelmeztetések). Jellemzően az alkalmazás más forrásból került fel (például az F-Droidból, amely saját kulccsal ír alá). A RepoShelf nem tudja frissíteni; ez szándékos. |
| Az automatikus telepítés mégis jóváhagyást kér | Normál működés – lásd: [8. fejezet](#8-automatikus-frissítések). |
| A háttérellenőrzés nem fut időben | Az Android az akkumulátor kímélése miatt késlelteti a háttérmunkát. Nézd meg az akkumulátor-optimalizálás beállításait; szükség esetén használd az „Összes ellenőrzése” gombot. |
| Semmi nem reagál az érintésre (Android 9–11), és egy sáv szerint „Egy másik alkalmazás … takarja a RepoShelfet” | Képernyőszűrő, éjszakai fény vagy hasonló, a képernyőre rajzoló alkalmazás aktív. A RepoShelf a más alkalmazás ablaka alatti érintéseket a megtévesztő átfedések (tapjacking) elleni védelem miatt figyelmen kívül hagyja. Kapcsold ki az átfedő alkalmazást. |
| A telepítés ezzel hiúsul meg: „a fejlesztő nincs ellenőrizve (Android developer verification)” | Az Android developer verification letiltotta az alkalmazást. Kövesd az Android saját útmutatását; a RepoShelf nem ajánl megkerülési módot. |
| „a katalógus aláírása hiányzik vagy érvénytelen” | A letöltött katalógust a RepoShelf elutasította; az utolsó érvényes katalógus marad. Többnyire átmeneti. |
| „Újabb katalógusformátum érhető el” | A katalógusfrissítésekhez frissítsd a RepoShelfet; a jelenlegi katalógus továbbra is működik. |

## 13. Hibaelhárítás

1. **Ellenőrizd a kapcsolatot.** Offline egy sáv mutatja, mi működik továbbra is (katalógus, az alkalmazásaid, a mentett
   adatlapok, az előzmények, a beállítások); az ellenőrzéshez, letöltéshez és telepítéshez kapcsolat kell.
2. **Olvasd el az okot.** A RepoShelf hibaüzenetei megnevezik az okot (például „méreteltérés (hiányos vagy módosított
   fájl)”). A frissítési előzmények a sikertelen telepítések okát is rögzítik.
3. **Folytasd a megszakadt letöltést.** Az „Újra” onnan folytatja, ahol abbamaradt. A befejezetlen letöltések egy hét után
   automatikusan törlődnek.
4. **Másutt telepített alkalmazás nem látszik:** lásd: [Gyakori hibák](#12-gyakori-hibák).
5. **A RepoShelf alaphelyzetbe állítása:** Android-beállítások → Alkalmazások → RepoShelf → Tárhely → „Tárhely törlése”.
   Ez a RepoShelf minden adatát törli: a listádat, a beállításokat, az előzményeket és a rögzített aláírókat. Előtte
   exportáld a listádat. A telepített alkalmazásokat nem érinti.
6. **A RepoShelf eltávolítása:** a vele telepített alkalmazások telepítve maradnak.
7. **Hiba bejelentése:** nyiss egy issue-t a RepoShelf GitHub-repójában az Android-verzióval, a RepoShelf verziójával
   (Android-beállítások → Alkalmazások → RepoShelf) és a pontos üzenettel. Személyes adatot ne írj bele. A biztonsági
   sérülékenységeket ne nyilvánosan jelentsd – lásd: [SECURITY.md](SECURITY.md).
