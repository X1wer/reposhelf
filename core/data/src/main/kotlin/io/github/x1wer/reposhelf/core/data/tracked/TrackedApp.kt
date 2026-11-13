// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.data.tracked

import io.github.x1wer.reposhelf.core.apk.ResolvedReleases
import io.github.x1wer.reposhelf.core.model.CatalogApp
import io.github.x1wer.reposhelf.core.model.ProviderRepoId
import io.github.x1wer.reposhelf.core.model.Release
import io.github.x1wer.reposhelf.core.model.RepoMetadata
import io.github.x1wer.reposhelf.core.model.RepoRef
import io.github.x1wer.reposhelf.core.source.UnavailableReason
import io.github.x1wer.reposhelf.core.update.AppOverrides
import io.github.x1wer.reposhelf.core.update.InstalledRelease
import io.github.x1wer.reposhelf.core.update.UpdateCheck
import io.github.x1wer.reposhelf.core.update.UpdateStatus
import java.time.Instant

/** Honnan került a listába (C. 9.: központi katalógus vagy saját hozzáadás). */
enum class AppOrigin { CATALOG, USER }

/** A repó azonossága a legutóbbi ellenőrzéskor (C. 19.). */
enum class RepoState { OK, RENAMED, OWNER_CHANGED, ID_CHANGED, NOT_FOUND, UNAVAILABLE }

enum class PinSource { FIRST_INSTALL_VIA_US, EXISTING_INSTALL, CATALOG }

data class LatestRelease(
    val releaseId: Long,
    val tag: String,
    val name: String?,
    val publishedAt: Instant?,
    val prerelease: Boolean,
    val htmlUrl: String?,
)

/** Egy követett vagy kedvenc alkalmazás (C. 9–10.). A telepítettséget a felület a PackageManagertől kérdezi le. */
data class TrackedApp(
    val id: Long,
    val origin: AppOrigin,
    val catalogId: String?,
    val repo: RepoRef,
    val repoId: ProviderRepoId?,
    val ownerId: Long?,
    val packageName: String?,
    val displayName: String,
    val isFollowed: Boolean,
    val isFavorite: Boolean,
    val installedViaUs: Boolean,
    val everInstalled: Boolean,
    val addedAt: Instant,
    val lastCheckAt: Instant?,
    /** `null`: még nem volt ellenőrzés. */
    val status: UpdateStatus?,
    val statusConfirmed: Boolean,
    val lastErrorKind: String?,
    val lastErrorAt: Instant?,
    val repoState: RepoState,
    val repoStateDetail: String?,
    val latest: LatestRelease?,
    val installedRelease: InstalledRelease?,
    /** Frissítésfigyelés (alkalmazásonkénti felülírás vagy a globális alapérték: be). */
    val checkEnabled: Boolean,
    /** Előzetes kiadások követése: az alkalmazásonkénti felülírás (ha nincs, `false`; a tényleges érték: [overrides] + globális). */
    val includePrereleases: Boolean,
    /** Alkalmazásonkénti felülírások (C. 12.6.); a tényleges szabály: `TrackedAppsRepository.policyFor`. */
    val overrides: AppOverrides = AppOverrides(),
)

/** Egy URL-ből hozzáadandó repó előnézete (C. 9.: 1–5. lépés). Még semmi nem kerül az adatbázisba. */
sealed interface AddRepoPreview {
    /** 1. lépés: a bemenet nem ismerhető fel repó-URL-ként (vagy nem támogatott szolgáltató). */
    data object InvalidUrl : AddRepoPreview

    data class AlreadyTracked(val id: Long, val followed: Boolean) : AddRepoPreview

    /** 2. lépés: a repó nem létezik vagy nem nyilvános. */
    data class NotFound(val ref: RepoRef) : AddRepoPreview

    data class Unavailable(val ref: RepoRef, val reason: UnavailableReason) : AddRepoPreview

    data class RateLimited(val until: Instant?) : AddRepoPreview

    data class Failed(val detail: String?) : AddRepoPreview

    /**
     * 3–5. lépés: alapadatok, kiadások, és hogy van-e telepíthető csomag. A [check] ugyanaz a kiértékelés, mint a
     * frissítésellenőrzésé ([UpdateStatus.NO_INSTALLABLE_RELEASE], ha nincs APK).
     */
    data class Found(
        val repo: RepoMetadata,
        val resolved: ResolvedReleases,
        val candidate: Release?,
        val check: UpdateCheck,
        /** Ha a repó a központi katalógusban is szerepel, annak adatai (csomagnév, rögzített aláíró). */
        val catalogApp: CatalogApp?,
    ) : AddRepoPreview
}

/** Az „összes ellenőrzése” állapota (C. 12.1.). */
data class CheckProgress(val running: Boolean = false, val done: Int = 0, val total: Int = 0)

data class CheckSummary(
    val checked: Int,
    val updatesAvailable: Int,
    val failed: Int,
    /** Az API-keret miatt most ki nem hagyott alkalmazások száma, és mikor folytatható. */
    val notChecked: Int,
    val resumesAt: Instant?,
)

/**
 * Az import eredménye (C. 25.): új és már meglévő alkalmazások; hány új alkalmazásnál nem vettünk át automatizmust
 * bekapcsoló beállítást (automatikus telepítés / letöltés, előzetes kiadások); hány meglévő alkalmazás beállításai
 * maradtak változatlanok, bár a fájl mást tartalmazott.
 */
data class ImportSummary(val added: Int, val updated: Int, val automationNotImported: Int, val existingSettingsKept: Int = 0)

/** Az import előnézete megerősítéshez: már a listán lévők, új katalógusbeli appok, új saját források (repó-útvonalak). */
data class ImportPreview(val existing: Int, val newFromCatalog: Int, val newOwnSources: List<String>)
