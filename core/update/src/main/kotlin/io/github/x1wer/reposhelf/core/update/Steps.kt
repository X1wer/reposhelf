// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.update

import io.github.x1wer.reposhelf.core.model.Finding
import io.github.x1wer.reposhelf.core.network.DownloadFailure
import io.github.x1wer.reposhelf.core.source.UnavailableReason
import java.time.Instant

/**
 * A frissítési folyamat egy lépésének eredménye (docs/ARCHITECTURE.md 9. pont, C. 34.).
 * - [Ok]: a lépés lefutott; a [findings] tájékoztató vagy figyelmeztető megállapításokat hordozhat.
 * - [Blocked]: a lépés biztonsági okból megállította a folyamatot (legalább egy KRITIKUS megállapítás).
 * - [Failed]: a lépés technikai okból nem tudott lefutni (hálózat, rate limit, jogosultság…).
 */
sealed interface StepResult<out T> {
    data class Ok<T>(val value: T, val findings: List<Finding> = emptyList()) : StepResult<T>
    data class Blocked(val findings: List<Finding>) : StepResult<Nothing>
    data class Failed(val failure: UpdateFailure, val findings: List<Finding> = emptyList()) : StepResult<Nothing>
}

sealed interface UpdateFailure {
    /** A szolgáltató API-kerete elfogyott; [until] a várható folytatás ideje (C. 12.1., 16.1.). */
    data class RateLimited(val until: Instant?) : UpdateFailure

    data class Network(val detail: String?) : UpdateFailure

    data object RepoNotFound : UpdateFailure

    data class RepoUnavailable(val reason: UnavailableReason) : UpdateFailure

    data class Download(val reason: DownloadFailure, val detail: String?, val resumable: Boolean) : UpdateFailure

    /** Hiányzik az „ismeretlen forrásból telepítés” engedély (C. 34.: telepítési jogosultság hiánya). */
    data object NoInstallPermission : UpdateFailure

    data class InstallStartFailed(val reason: String) : UpdateFailure

    data class Other(val detail: String) : UpdateFailure
}
