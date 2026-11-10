// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.apk

import io.github.x1wer.reposhelf.core.model.Release
import java.time.Instant

/** A kiadások közül a megjelenítendők (C. 6., 11.). */
data class ResolvedReleases(
    /** A legújabb végleges kiadás, amelyben van APK. */
    val latestStable: Release?,
    /** A legújabb előzetes kiadás APK-val, ha újabb a végleges kiadásnál. */
    val latestPrerelease: Release?,
    /** Van-e egyáltalán (nem draft) kiadás. Ha van, de APK nincs, azt külön jelezni kell (C. 6.2.). */
    val hasAnyRelease: Boolean,
)

object ReleaseResolver {
    fun resolve(releases: List<Release>): ResolvedReleases {
        val published = releases.filter { !it.isDraft }
        val installable = published.filter { release -> release.assets.any { AssetClassifier.classify(it) == AssetKind.APK } }
            .sortedByDescending { it.date() }
        val stable = installable.firstOrNull { !it.isPrerelease }
        val pre = installable.firstOrNull { it.isPrerelease }?.takeIf { stable == null || it.date() > stable.date() }
        return ResolvedReleases(stable, pre, published.isNotEmpty())
    }

    /** A kiadás időpontja; ha nincs közzétételi idő, a létrehozásé; ha az sincs, a legrégebbi lehetséges. */
    fun Release.date(): Instant = publishedAt ?: createdAt ?: Instant.EPOCH
}

/** Egy előzetes (letöltés előtti) kompatibilitási becslés tétele. Nem garancia (C. 7.1.). */
data class PreliminaryCheck(val check: PreCheck, val status: CheckStatus, val detail: String? = null)

enum class PreCheck { FORMAT, ABI, ANDROID_VERSION, STORAGE, RELEASE_TYPE }

object PreliminaryCompatibility {
    /**
     * Csak a letöltés előtt is ismert adatokból becsül. A minimális Android-verzió, a csomagazonosító és az aláírás
     * csak a letöltött APK-ból derül ki, ezért ezek itt „nem ellenőrzött” állapotúak.
     */
    fun assess(selection: AssetSelection, release: Release, device: DeviceProfile): List<PreliminaryCheck> {
        val checks = mutableListOf<PreliminaryCheck>()
        val chosen = when (selection) {
            is AssetSelection.Single -> listOf(selection.asset)
            is AssetSelection.SplitSet -> listOf(selection.base) + selection.splits
            else -> emptyList()
        }
        checks += when (selection) {
            is AssetSelection.Single, is AssetSelection.SplitSet -> PreliminaryCheck(PreCheck.FORMAT, CheckStatus.OK)
            is AssetSelection.Ambiguous -> PreliminaryCheck(PreCheck.FORMAT, CheckStatus.WARNING, "${selection.candidates.size}")
            is AssetSelection.NoInstallable -> PreliminaryCheck(PreCheck.FORMAT, CheckStatus.FAILED)
        }
        if (chosen.isNotEmpty()) {
            val tags = chosen.map { AbiDetector.detect(it.name) }
            val deviceAbis = device.supportedAbis.map(AbiDetector::normalize)
            val primary = deviceAbis.firstOrNull()
            checks += when {
                tags.any { it is AbiTag.Specific && primary != null && primary in it.abis } -> PreliminaryCheck(PreCheck.ABI, CheckStatus.OK, primary)
                tags.any { it is AbiTag.Specific } -> PreliminaryCheck(PreCheck.ABI, CheckStatus.WARNING, deviceAbis.joinToString())
                else -> PreliminaryCheck(PreCheck.ABI, CheckStatus.NOT_CHECKED)
            }
            val size = chosen.sumOf { it.size }
            val free = device.freeBytes
            checks += when {
                free == null -> PreliminaryCheck(PreCheck.STORAGE, CheckStatus.NOT_CHECKED)
                free >= size * 3 -> PreliminaryCheck(PreCheck.STORAGE, CheckStatus.OK)
                free >= size -> PreliminaryCheck(PreCheck.STORAGE, CheckStatus.WARNING)
                else -> PreliminaryCheck(PreCheck.STORAGE, CheckStatus.FAILED)
            }
        }
        checks += PreliminaryCheck(PreCheck.ANDROID_VERSION, CheckStatus.NOT_CHECKED, device.sdkInt.toString())
        if (release.isPrerelease) checks += PreliminaryCheck(PreCheck.RELEASE_TYPE, CheckStatus.WARNING)
        return checks
    }
}
