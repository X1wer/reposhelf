// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.update

import io.github.x1wer.reposhelf.core.apk.AssetSelection
import io.github.x1wer.reposhelf.core.apk.AssetSelector
import io.github.x1wer.reposhelf.core.apk.CheckStatus
import io.github.x1wer.reposhelf.core.apk.DeviceProfile
import io.github.x1wer.reposhelf.core.apk.PreliminaryCheck
import io.github.x1wer.reposhelf.core.apk.PreliminaryCompatibility
import io.github.x1wer.reposhelf.core.model.Release
import io.github.x1wer.reposhelf.core.model.ReleaseAsset

/** A 3. lépés eredménye: a választott állomány(ok) és az ELŐZETES becslés (nem garancia; C. 7.1.). */
data class CompatibilityAssessment(val selection: AssetSelection, val preliminary: List<PreliminaryCheck>) {
    /** Egyértelmű választásnál a letöltendő állományok; ha a felhasználónak kell választania, üres. */
    val chosenAssets: List<ReleaseAsset>
        get() = when (selection) {
            is AssetSelection.Single -> listOf(selection.asset)
            is AssetSelection.SplitSet -> listOf(selection.base) + selection.splits
            is AssetSelection.Ambiguous, is AssetSelection.NoInstallable -> emptyList()
        }

    /** Van-e telepíthető jelölt (egyértelmű, vagy a felhasználó választhat), és nem bukott-e el előzetes ellenőrzés. */
    val installable: Boolean
        get() = selection !is AssetSelection.NoInstallable && preliminary.none { it.status == CheckStatus.FAILED }

    val needsUserChoice: Boolean get() = selection is AssetSelection.Ambiguous
}

/** 3. lépés: kompatibilitás megállapítása (docs/ARCHITECTURE.md 9. pont): formátum, ABI, katalógus-szűrő, tárhely. */
object CompatibilityAssessor {
    fun assess(release: Release, device: DeviceProfile, assetFilter: String?): CompatibilityAssessment {
        val selection = AssetSelector.select(release.assets, device.supportedAbis, assetFilter)
        return CompatibilityAssessment(selection, PreliminaryCompatibility.assess(selection, release, device))
    }
}
