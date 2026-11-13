// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.model

/** Biztonsági és kompatibilitási megállapítás súlyossága (C. 20.). A [CRITICAL] minden útvonalon blokkolja a telepítést. */
enum class Severity { INFO, WARNING, CRITICAL }

/** A megállapítások fajtái (docs/ARCHITECTURE.md 10.4.). */
enum class FindingKind {
    /** A letöltött fájl mérete eltér a szolgáltató által közölttől (megszakadt vagy sérült letöltés). */
    SIZE_MISMATCH,

    /** A letöltött fájl SHA-256-ja eltér a szolgáltató által közölt `digest`-től. */
    DIGEST_MISMATCH,

    /** A szolgáltató nem közölt ellenőrzőösszeget (tájékoztató). */
    NO_PROVIDER_DIGEST,

    /** A fájl nem érvényes APK (nem ZIP, nincs manifest, gyanús bejegyzés). */
    INVALID_APK,

    /** Az APK aláírása technikailag érvénytelen (1. szint). */
    INVALID_SIGNATURE,

    /** Több aláíró (nem támogatott). */
    MULTIPLE_SIGNERS,

    /** Az aláíró nem egyezik a telepített alkalmazáséval, és nincs érvényes kulcsrotáció (2. szint). */
    SIGNER_CHANGED,

    /** Az aláíró nem egyezik a katalógusban vagy korábban rögzített ujjlenyomattal (2. szint). */
    SIGNER_MISMATCH_PINNED,

    /** Első találkozás: az aláíró most kerül rögzítésre (trust on first use, tájékoztató). */
    FIRST_SEEN_SIGNER,

    /** A kiadó hitelessége kriptográfiailag nem ellenőrizhető (3. szint, tájékoztató). */
    UNVERIFIABLE_SOURCE,

    /** Más csomagazonosító, mint a várt. */
    PACKAGE_MISMATCH,

    /** Régebbi verziókód a telepítettnél. */
    VERSION_DOWNGRADE,

    /** Azonos verziókód a telepítettel (újratelepítés). */
    SAME_VERSION,

    /** A verziókód a katalógusban rögzített minimum alatt van. */
    BELOW_MIN_VERSION,

    /** Az alkalmazás magasabb Android-verziót igényel, mint az eszközé. */
    INCOMPATIBLE_SDK,

    /** Az alkalmazás túl régi Android-verziót céloz, ezért a rendszer nem telepíti. */
    TARGET_SDK_TOO_OLD,

    /** A natív könyvtárak egyike sem fut az eszköz CPU-architektúráján. */
    INCOMPATIBLE_ABI,

    /** A split APK-készlet nem egységes (csomagnév, verziókód, aláíró vagy alap-APK). */
    SPLIT_SET_INCONSISTENT,

    /**
     * Split-készlet: a kiegészítő állományok csomagnevét, verziókódját és aláíróját csak a rendszer ellenőrzi a
     * telepítéskor (D-128). Telepítés előtt kifejezett megerősítés kell; beavatkozás nélküli telepítés nincs.
     */
    SPLITS_VERIFIED_BY_SYSTEM,

    /** A kiadási állomány nem a várt formátumú (pl. AAB, .apks). */
    UNEXPECTED_FORMAT,

    /** Előzetes (prerelease) kiadás. */
    PRERELEASE,

    /** A követett repó nem található (törölték, priváttá tették vagy átnevezték átirányítás nélkül). */
    REPO_NOT_FOUND,

    /** A repó jogi vagy szabályzati okból nem érhető el (451, letiltva). */
    REPO_UNAVAILABLE,

    /** Azonos néven MÁS repó (eltérő stabil azonosító): lehetséges repó-eltérítés (C. 19.). */
    REPO_ID_CHANGED,

    /** A repó más tulajdonoshoz került (azonos stabil azonosító, eltérő tulajdonos; C. 19.). */
    REPO_OWNER_CHANGED,

    /** A repót átnevezték (azonos azonosító és tulajdonos, tájékoztató). */
    REPO_RENAMED,

    /** Nincs telepíthető Android-csomagot tartalmazó kiadás. */
    NO_INSTALLABLE_RELEASE,

    /** A telepítés után a készüléken lévő csomag nem az, amit telepítettünk (verziókód vagy aláíró eltér). */
    INSTALLED_STATE_MISMATCH,
}

data class Finding(val kind: FindingKind, val severity: Severity, val detail: String? = null)
