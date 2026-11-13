<!-- SPDX-FileCopyrightText: 2026 RepoShelf contributors -->
<!-- SPDX-License-Identifier: CC-BY-4.0 -->

# Felelősségi és használati tájékoztató

*English version: [DISCLAIMER.md](DISCLAIMER.md)*

Utolsó módosítás: 2026-11-14.

> A szöveget jogász még nem nézte át. Az első nyilvános kiadás előtt ajánlott jogi szakemberrel átnézetni, különösen azon
> országok fogyasztóvédelmi szabályai szempontjából, ahol a RepoShelf elérhető lesz.

## Harmadik féltől származó alkalmazások

A RepoShelf a GitHubon közzétett Android-alkalmazások megtalálásában, telepítésében és frissítésében segít. Kérjük, vedd
figyelembe:

1. **Az alkalmazásokat független fejlesztők készítik.** A RepoShelf projekt nem fejleszti, nem vizsgálja felül, nem
   ajánlja és nem terjeszti őket. Minden alkalmazásra a fejlesztője által megadott saját licenc és feltételek vonatkoznak.
2. **A katalógus üzemeltetői nem garantálják**, hogy egy alkalmazás hibamentes, a leírás szerint működik, bármilyen célra
   alkalmas, vagy hogy továbbra is elérhető és karbantartott marad.
3. **Az, hogy egy alkalmazás elérhető a GitHubon, nem bizonyítja a biztonságosságát.** A GitHubon bárki közzétehet kódot és
   kiadást. A RepoShelf katalógusában való szereplés sem biztonsági tanúsítás: a katalógus csak azt rögzíti, melyik repóból
   származik egy alkalmazás, és egyes alkalmazásoknál a várt csomagazonosítót és aláíró kulcsot.
4. **A forrás mindig megismerhető.** Minden adatlap mutatja a forrásrepót, annak licencét (ahogy a GitHub közli) és az
   aláíró tanúsítvány ujjlenyomatát, és a repóra mutató linket ad, így telepítés előtt átnézheted a kódot, a kiadásokat és
   a fejlesztőt.
5. **A RepoShelf technikai ellenőrzései nem egyenértékűek egy teljes biztonsági audittal.** A RepoShelf a letöltött APK
   sértetlenségét, szerkezetét, csomagazonosítóját, verzióját és aláírását ellenőrzi, valamint azt, hogy az aláíró
   egyezik-e a már ismerttel. Ezek az ellenőrzések felismerik a sérült fájlokat, a váratlan aláíróváltást és több ismert
   támadási mintát. Azt viszont nem tudják megállapítani, hogy egy alkalmazás kódja rosszindulatú-e, gyűjti-e az adataidat,
   vagy van-e benne sérülékenység. A fejlesztő valódi kulcsával aláírt alkalmazás is lehet ártalmas – például ha a
   fejlesztőt vagy a kulcsát feltörték.

Csak megbízható alkalmazást telepíts. Hogy mit telepítesz, te döntöd el; az Android minden első telepítéshez a
jóváhagyásodat kéri.

## Hogyan kezeli a RepoShelf a harmadik féltől származó fájlokat?

- Az APK-fájlok **közvetlenül a fejlesztő GitHub Releases oldaláról** töltődnek le. A RepoShelf katalógusa nem tárol és nem
  tükröz harmadik féltől származó APK-fájlokat.
- A RepoShelf a harmadik féltől származó APK-fájlokat **nem csomagolja újra, nem módosítja és nem írja alá újra**. Az
  ellenőrzött fájlt változatlanul adja át az Android telepítőjének.
- A RepoShelf nem fordít alkalmazást forráskódból.

Ezek az intézkedések és a [SECURITY.md](SECURITY.md)-ben leírt ellenőrzések a RepoShelf tényleges védelmei. Ez a
tájékoztató nem helyettesíti őket, csak a fennmaradó korlátokat írja le.

## A RepoShelfre magára nincs garancia

A RepoShelf szabad szoftver, a GNU General Public License 3-as vagy későbbi változata alatt. A licenc 15. és 16. pontja
szerint **az alkalmazandó jog által megengedett mértékben** a RepoShelf „úgy, ahogy van” alapon, mindennemű garancia
nélkül érhető el, és a szerzői jogok jogosultjai és a közreműködők nem felelnek a használatából eredő károkért. A pontos
feltételek: [LICENSE](LICENSE) (angol nyelvű, ez az irányadó szöveg).

**Ez a tájékoztató nem korlátozza és nem zárja ki azt a felelősséget, amely az alkalmazandó jog szerint nem korlátozható
vagy nem zárható ki** – ilyen lehet a joghatóságtól függően például a szándékosan, súlyos gondatlansággal vagy bűncselekménnyel okozott,
illetve az életet, testi épséget vagy egészséget megkárosító szerződésszegésért való felelősség (Magyarországon lásd a
Polgári Törvénykönyv 6:152. §-át), valamint a fogyasztókat a kötelező fogyasztóvédelmi szabályok alapján megillető jogok.

## Védjegyek és kapcsolat más szervezetekkel

A RepoShelf független projekt. Nem áll kapcsolatban a GitHub, Inc.-vel, a Google LLC-vel vagy a listázott alkalmazások
fejlesztőivel, és ezek nem is támogatják vagy ajánlják. A „GitHub” és az „Android” a jogosultjaik védjegye. Az
alkalmazások neve és ikonja a jogosultjaiké; csak az alkalmazások azonosítására jelennek meg.

## Probléma bejelentése egy listázott alkalmazással kapcsolatban

Ha egy katalógusbeli alkalmazás rosszindulatúnak, jogsértőnek vagy elhagyottnak tűnik, jelentsd a katalógus repójában (lásd
a beküldési útmutatót). Magával a RepoShelffel kapcsolatos biztonsági sérülékenységet a [SECURITY.md](SECURITY.md) szerint,
nem nyilvánosan jelents.
