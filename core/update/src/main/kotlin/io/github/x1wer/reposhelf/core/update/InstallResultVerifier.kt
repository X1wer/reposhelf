// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.update

import io.github.x1wer.reposhelf.core.apk.ApkFacts
import io.github.x1wer.reposhelf.core.apk.InstalledAppInfo
import io.github.x1wer.reposhelf.core.model.Finding
import io.github.x1wer.reposhelf.core.model.FindingKind
import io.github.x1wer.reposhelf.core.model.Severity

/** A rendszer telepítési hibájának oka, platformfüggetlenül (docs/ARCHITECTURE.md 11.1., hibakód-leképezés). */
enum class InstallFailureKind {
    /** Aláírás-ütközés a telepített példánnyal (`STATUS_FAILURE_CONFLICT`). */
    CONFLICT,

    /** Érvénytelen csomag vagy visszalépés (`STATUS_FAILURE_INVALID`). */
    INVALID,

    /** Az eszközzel nem kompatibilis (`STATUS_FAILURE_INCOMPATIBLE`). */
    INCOMPATIBLE,

    /** Szabályzat vagy verifier tiltotta (`STATUS_FAILURE_BLOCKED`). */
    BLOCKED,

    /** Kevés a tárhely (`STATUS_FAILURE_STORAGE`). */
    STORAGE,

    /** A felhasználó megszakította (`STATUS_FAILURE_ABORTED`, developer verification ok nélkül). */
    ABORTED,

    /** A developer verification utasította el (Android 16 QPR2+; docs/ARCHITECTURE.md 11.3.). */
    DEVELOPER_VERIFICATION,
    OTHER,
}

/** A telepítő jelzése a munkamenet végén. */
sealed interface InstallSignal {
    data class Success(val packageName: String?) : InstallSignal
    data class Failure(val kind: InstallFailureKind, val message: String?) : InstallSignal
}

sealed interface InstallOutcome {
    /** Telepítve, és a készüléken lévő csomag pontosan az, amit ellenőriztünk: az aláíró rögzíthető. */
    data class Verified(val packageName: String, val versionCode: Long, val versionName: String?, val signerHistory: List<String>) : InstallOutcome

    /** A rendszer sikert jelzett, de a telepített csomag nem egyezik a vizsgálttal: az aláírót NEM rögzítjük. */
    data class Unverified(val packageName: String, val finding: Finding) : InstallOutcome

    data object CancelledByUser : InstallOutcome

    data class Failed(val kind: InstallFailureKind, val message: String?) : InstallOutcome
}

/**
 * 8. lépés: a telepítési eredmény ellenőrzése (docs/ARCHITECTURE.md 9. pont). Siker esetén a TÉNYLEGESEN telepített
 * csomag versionCode-ját és aláíróját összeveti a vizsgálttal; csak egyezéskor adja vissza a rögzítendő aláírót.
 */
object InstallResultVerifier {

    fun verify(signal: InstallSignal, expected: ApkFacts, actual: InstalledAppInfo?): InstallOutcome = when (signal) {
        is InstallSignal.Failure ->
            if (signal.kind == InstallFailureKind.ABORTED) InstallOutcome.CancelledByUser else InstallOutcome.Failed(signal.kind, signal.message)
        is InstallSignal.Success -> {
            val expectedSigners = expected.signers.current.map { it.lowercase() }
            val actualHistory = actual?.signingHistory?.map { it.lowercase() }.orEmpty()
            when {
                actual == null || actual.packageName != expected.packageName ->
                    InstallOutcome.Unverified(expected.packageName, Finding(FindingKind.INSTALLED_STATE_MISMATCH, Severity.WARNING, "not visible"))
                actual.versionCode != expected.versionCode ->
                    InstallOutcome.Unverified(
                        expected.packageName,
                        Finding(FindingKind.INSTALLED_STATE_MISMATCH, Severity.WARNING, "${actual.versionCode} ≠ ${expected.versionCode}"),
                    )
                expectedSigners.none { it in actualHistory } ->
                    InstallOutcome.Unverified(expected.packageName, Finding(FindingKind.INSTALLED_STATE_MISMATCH, Severity.CRITICAL, "signer"))
                else -> InstallOutcome.Verified(actual.packageName, actual.versionCode, actual.versionName, actualHistory)
            }
        }
    }
}
