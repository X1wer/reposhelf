// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.catalog

import io.github.x1wer.reposhelf.core.model.Catalog
import java.time.Duration
import java.time.Instant

/** A jelenleg elfogadott katalógus azonosító adatai. */
data class AcceptedCatalogInfo(val serial: Long, val contentSha256: String)

sealed interface CatalogDecision {
    data object Accept : CatalogDecision

    /** Ugyanaz a katalógus, mint a tárolt (azonos sorszám és tartalom). */
    data object NoChange : CatalogDecision

    /** Régebbi sorszámú katalógus: visszagörgetési kísérlet vagy elavult tükör (D-022). */
    data class RejectRollback(val currentSerial: Long, val candidateSerial: Long) : CatalogDecision

    /** Azonos sorszám, de eltérő tartalom: a katalógust sorszámemelés nélkül módosították. */
    data class RejectSerialReuse(val serial: Long) : CatalogDecision

    /** A sorszám az appba fordított minimum alatt van (egy frissen telepített kliens se fogadjon el nagyon régi katalógust). */
    data class RejectBelowMinimum(val minimum: Long, val candidateSerial: Long) : CatalogDecision

    /**
     * A katalógus már lejárt: nem bizonyítja a frissességet, ezért nem válthatja le a meglévőt, és nem hozhat új
     * bizalmi adatot (visszajátszott régi katalógus, befagyasztás; D-022). A meglévő katalógus marad.
     */
    data class RejectExpired(val expiredAtEpochSecond: Long) : CatalogDecision

    /** A katalógus a jövőben készült (az óraeltérési tűrésen túl): hibás vagy manipulált időbélyeg. */
    data class RejectNotYetValid(val generatedAtEpochSecond: Long) : CatalogDecision
}

/** A katalógus elfogadásának szabályai (docs/ARCHITECTURE.md 7.3., D-022). */
object CatalogAcceptance {
    /** Ennyi óraeltérést tűrünk el a készülék és a katalógus készítője között. */
    val CLOCK_SKEW: Duration = Duration.ofHours(24)

    /**
     * [allowExpired]: csak az APK-ba csomagolt kezdőkatalógusnál `true` (egy régi telepítőcsomag katalógusa lejárt
     * lehet, de katalógus nélkül az app használhatatlan lenne; a felület ilyenkor jelzi, hogy a katalógus elavult).
     * Letöltött katalógusnál a lejárt jelölt elutasítva: a meglévő (akár beépített) katalógus marad.
     */
    fun decide(current: AcceptedCatalogInfo?, candidate: Catalog, minimumSerial: Long, now: Instant, allowExpired: Boolean = false): CatalogDecision = when {
        candidate.serial < minimumSerial -> CatalogDecision.RejectBelowMinimum(minimumSerial, candidate.serial)
        candidate.generatedAt.isAfter(now.plus(CLOCK_SKEW)) -> CatalogDecision.RejectNotYetValid(candidate.generatedAt.epochSecond)
        !allowExpired && !candidate.expiresAt.isAfter(now) -> CatalogDecision.RejectExpired(candidate.expiresAt.epochSecond)
        current == null -> CatalogDecision.Accept
        candidate.serial < current.serial -> CatalogDecision.RejectRollback(current.serial, candidate.serial)
        candidate.serial == current.serial ->
            if (candidate.contentSha256 == current.contentSha256) CatalogDecision.NoChange
            else CatalogDecision.RejectSerialReuse(candidate.serial)
        else -> CatalogDecision.Accept
    }
}
