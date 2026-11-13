// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.apk

import io.github.x1wer.reposhelf.core.model.AssetFilterSyntax
import io.github.x1wer.reposhelf.core.model.ReleaseAsset

/** Miért nem választható egy állomány. */
enum class AssetRejection {
    APP_BUNDLE, SPLIT_ARCHIVE, INCOMPATIBLE_ABI, FILTERED_OUT,

    /**
     * Külön állományokként közzétett split-APK-készlet tagja (alap-APK + `split_*` / `config.*`), ha a hívó a készletet
     * nem fogadja el (`allowSplitSets = false`). Az alkalmazásokra a készlet támogatott (D-128).
     */
    SPLIT_SET,
}

data class RejectedAsset(val asset: ReleaseAsset, val reason: AssetRejection)

sealed interface AssetSelection {
    /** Egyértelmű választás. */
    data class Single(val asset: ReleaseAsset) : AssetSelection

    /** Split APK-készlet: alap-APK + az eszközhöz illő konfigurációs splitek, egy telepítési munkamenetben (C. 7.2.). */
    data class SplitSet(val base: ReleaseAsset, val splits: List<ReleaseAsset>) : AssetSelection

    /** Nem dönthető el automatikusan: a felhasználó választ (C. 7.2.). */
    data class Ambiguous(val candidates: List<ReleaseAsset>, val filterMismatch: Boolean) : AssetSelection

    /** Nincs telepíthető Android-csomag (C. 6.2.). */
    data class NoInstallable(val rejected: List<RejectedAsset>) : AssetSelection
}

/**
 * A kiadás állományai közül az eszközhöz legjobban illő APK kiválasztása (C. 7., 7.2.).
 *
 * Rangsor: az eszköz elsődleges ABI-jára készült APK > „universal” APK > ABI-jelölés nélküli APK > az eszköz
 * másodlagos ABI-jára készült APK. Az eszközzel inkompatibilis ABI-jú APK kiesik. Ha a legjobb csoportban több
 * állomány van (pl. két változat), a felhasználó dönt. A katalógus `assetFilter`-e szűkíti a jelölteket.
 */
object AssetSelector {
    private val SPLIT_NAME = Regex("(^|[-_.])(split[_-]|config\\.)", RegexOption.IGNORE_CASE)
    private val BASE_NAME = Regex("(^|[-_.])base\\.apk$", RegexOption.IGNORE_CASE)

    /**
     * A külön állományokként közzétett split-APK-készlet támogatása (D-128, a felhasználó döntése: Q-011). A rendszer
     * csomagelemzője az önálló split-APK-t nem dolgozza fel, ezért telepítés előtt csak az alap-APK ellenőrizhető
     * teljesen; a kiegészítő állományok csomagnevét, verziókódját és aláíróját a rendszer ellenőrzi a telepítéskor. Ezt a
     * felület telepítés előtt kifejezetten jelzi, és beavatkozás nélküli telepítés ilyenkor nincs.
     */
    const val SPLIT_SETS_SUPPORTED = true

    fun select(
        assets: List<ReleaseAsset>,
        deviceAbis: List<String>,
        assetFilter: String?,
        allowSplitSets: Boolean = SPLIT_SETS_SUPPORTED,
    ): AssetSelection {
        val rejected = mutableListOf<RejectedAsset>()
        val apks = mutableListOf<ReleaseAsset>()
        for (asset in assets) {
            when (AssetClassifier.classify(asset)) {
                AssetKind.APK -> apks += asset
                AssetKind.APP_BUNDLE -> rejected += RejectedAsset(asset, AssetRejection.APP_BUNDLE)
                AssetKind.SPLIT_ARCHIVE -> rejected += RejectedAsset(asset, AssetRejection.SPLIT_ARCHIVE)
                AssetKind.OTHER -> Unit
            }
        }
        if (apks.isEmpty()) return AssetSelection.NoInstallable(rejected)

        var candidates: List<ReleaseAsset> = apks
        var filterMismatch = false
        if (assetFilter != null) {
            // Második védvonal (a katalógus-feldolgozó után): csak a korlátos futási idejű mintát futtatjuk le a repó
            // tulajdonosa által megadott állományneveken (ReDoS ellen; AssetFilterSyntax).
            val regex = AssetFilterSyntax.compileOrNull(assetFilter)
            val filtered = if (regex == null) emptyList() else apks.filter { it.name.length <= AssetFilterSyntax.MAX_INPUT && regex.containsMatchIn(it.name) }
            if (filtered.isEmpty()) {
                filterMismatch = true
            } else {
                apks.filterNot { it in filtered }.forEach { rejected += RejectedAsset(it, AssetRejection.FILTERED_OUT) }
                candidates = filtered
            }
        }

        val normalizedDevice = deviceAbis.map(AbiDetector::normalize)
        val set = splitSet(candidates, deviceAbis)
        if (set != null) {
            val members = candidates.filter { it == set.base || SPLIT_NAME.containsMatchIn(it.name) }
            val standalone = candidates.filterNot { it in members }
            // Az eszközön futó önálló (pl. universal) APK telepítés előtt TELJESEN ellenőrizhető, ezért elsőbbséget kap a
            // készlettel szemben, amelynek a kiegészítő állományait csak a rendszer ellenőrzi (D-128).
            val standaloneUsable = standalone.any { rank(AbiDetector.detect(it.name), normalizedDevice) != null }
            if (allowSplitSets && !standaloneUsable) return set
            members.forEach { rejected += RejectedAsset(it, AssetRejection.SPLIT_SET) }
            candidates = standalone
            if (candidates.isEmpty()) return AssetSelection.NoInstallable(rejected)
        }

        val scored = candidates.mapNotNull { asset ->
            val rank = rank(AbiDetector.detect(asset.name), normalizedDevice)
            if (rank == null) {
                rejected += RejectedAsset(asset, AssetRejection.INCOMPATIBLE_ABI)
                null
            } else {
                asset to rank
            }
        }
        if (scored.isEmpty()) return AssetSelection.NoInstallable(rejected)
        if (filterMismatch) return AssetSelection.Ambiguous(scored.sortedBy { it.second }.map { it.first }, filterMismatch = true)

        val best = scored.minOf { it.second }
        val top = scored.filter { it.second == best }.map { it.first }
        return if (top.size == 1) AssetSelection.Single(top.single()) else AssetSelection.Ambiguous(top, filterMismatch = false)
    }

    /** Kisebb szám = jobb. `null` = az eszközön nem futtatható. */
    private fun rank(tag: AbiTag, deviceAbis: List<String>): Int? = when (tag) {
        is AbiTag.Specific -> {
            val index = deviceAbis.indexOfFirst { it in tag.abis }
            when {
                index < 0 -> null
                index == 0 -> 0
                else -> 3 + index
            }
        }
        AbiTag.Universal -> 1
        AbiTag.Unknown -> 2
    }

    private fun splitSet(apks: List<ReleaseAsset>, deviceAbis: List<String>): AssetSelection.SplitSet? {
        val bases = apks.filter { BASE_NAME.containsMatchIn(it.name) }
        if (bases.size != 1) return null
        val splits = apks.filter { it != bases.single() && SPLIT_NAME.containsMatchIn(it.name) }
        if (splits.isEmpty()) return null
        val normalizedDevice = deviceAbis.map(AbiDetector::normalize)
        // Az ABI-konfigurációs splitekből csak az eszköz elsődleges illeszkedő ABI-ja kell; a többi split (nyelv, sűrűség) mind.
        val abiSplits = splits.associateWith { AbiDetector.detect(it.name) }
        val chosenAbi = normalizedDevice.firstOrNull { abi -> abiSplits.values.any { it is AbiTag.Specific && abi in it.abis } }
        val selected = splits.filter { split ->
            when (val tag = abiSplits.getValue(split)) {
                is AbiTag.Specific -> chosenAbi != null && chosenAbi in tag.abis
                else -> true
            }
        }
        return AssetSelection.SplitSet(bases.single(), selected)
    }
}
