// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.update

import io.github.x1wer.reposhelf.core.apk.ReleaseResolver.date
import io.github.x1wer.reposhelf.core.apk.VersionNames
import io.github.x1wer.reposhelf.core.model.Release
import java.time.Instant

/** A készüléken telepített példány verziója, és ha mi telepítettük, a hozzá tartozó kiadás. */
data class InstalledVersion(
    val versionCode: Long,
    val versionName: String?,
    /** A kiadás, amelyből a jelenleg telepített verziót MI telepítettük; `null`, ha nem tudjuk. */
    val installedRelease: InstalledRelease?,
)

/** Egy általunk telepített kiadás azonosítói. Csak akkor érvényes, ha a telepített versionCode azóta nem változott. */
data class InstalledRelease(val releaseId: Long, val publishedAt: Instant?, val versionCode: Long)

enum class VersionVerdict { NOT_INSTALLED, NEWER, SAME, OLDER, UNKNOWN }

/** Mire épül a döntés, a megbízhatóság sorrendjében (C. 11.: a verziókód és az APK-metaadatok elsőbbsége). */
enum class VersionBasis {
    /** A kiválasztott állomány már elemzett versionCode-ja (megerősített). */
    VERSION_CODE,

    /** Ugyanaz a kiadás, amelyből a telepített példány származik (megerősített). */
    RELEASE_IDENTITY,

    /** A kiadás címkéje/neve és a telepített versionName összevetése (valószínű). */
    VERSION_NAME,

    /** A kiadás dátuma újabb, mint az általunk telepítetté (valószínű, tartalék). */
    RELEASE_DATE,
    NONE,
}

data class VersionDecision(val verdict: VersionVerdict, val basis: VersionBasis) {
    /** Megerősített-e (versionCode vagy kiadás-azonosság), vagy csak becslés. */
    val confirmed: Boolean get() = basis == VersionBasis.VERSION_CODE || basis == VersionBasis.RELEASE_IDENTITY
}

/**
 * 2. lépés: verzió azonosítása (docs/ARCHITECTURE.md 9. pont). A versionCode csak a letöltött APK-ból ismert, ezért
 * letöltés előtt a döntés gyakran csak „valószínű”; a végleges döntést a 6. lépés hozza. Hibás vagy nem szabványos
 * verziószámozásnál az eredmény [VersionVerdict.UNKNOWN], ami nem hiba (C. 11., 34.).
 */
object VersionResolver {

    fun resolve(candidate: Release, candidateVersionCode: Long?, installed: InstalledVersion?): VersionDecision {
        if (installed == null) return VersionDecision(VersionVerdict.NOT_INSTALLED, VersionBasis.NONE)

        if (candidateVersionCode != null) {
            val verdict = when {
                candidateVersionCode > installed.versionCode -> VersionVerdict.NEWER
                candidateVersionCode == installed.versionCode -> VersionVerdict.SAME
                else -> VersionVerdict.OLDER
            }
            return VersionDecision(verdict, VersionBasis.VERSION_CODE)
        }

        // A rögzített kiadás csak akkor számít, ha a telepített példány azóta nem változott (pl. máshonnan frissítették).
        val ours = installed.installedRelease?.takeIf { it.versionCode == installed.versionCode }
        if (ours != null && ours.releaseId == candidate.id) return VersionDecision(VersionVerdict.SAME, VersionBasis.RELEASE_IDENTITY)

        val installedName = installed.versionName
        if (installedName != null) {
            val comparison = listOfNotNull(candidate.tagName, candidate.name).firstNotNullOfOrNull { VersionNames.compare(it, installedName) }
            if (comparison != null) {
                val verdict = when {
                    comparison > 0 -> VersionVerdict.NEWER
                    comparison == 0 -> VersionVerdict.SAME
                    else -> VersionVerdict.OLDER
                }
                return VersionDecision(verdict, VersionBasis.VERSION_NAME)
            }
        }

        val oursDate = ours?.publishedAt
        if (oursDate != null && candidate.publishedAt != null) {
            return VersionDecision(if (candidate.date() > oursDate) VersionVerdict.NEWER else VersionVerdict.SAME, VersionBasis.RELEASE_DATE)
        }
        return VersionDecision(VersionVerdict.UNKNOWN, VersionBasis.NONE)
    }
}
