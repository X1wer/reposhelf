// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.update

import io.github.x1wer.reposhelf.core.apk.ApkFacts
import io.github.x1wer.reposhelf.core.apk.AssetRejection
import io.github.x1wer.reposhelf.core.apk.CheckStatus
import io.github.x1wer.reposhelf.core.apk.SignerIdentity
import io.github.x1wer.reposhelf.core.apk.SignerInfo
import io.github.x1wer.reposhelf.core.apk.VerificationReport
import io.github.x1wer.reposhelf.core.apk.AssetSelection
import io.github.x1wer.reposhelf.core.apk.RejectedAsset
import io.github.x1wer.reposhelf.core.model.Finding
import io.github.x1wer.reposhelf.core.model.FindingKind
import io.github.x1wer.reposhelf.core.model.Severity
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A C. 20. minden esete a megfelelő súlyossággal; a kritikus blokkolja az automatikus telepítést. */
class WarningClassifierTest {

    private fun single(kind: FindingKind, severity: Severity = Severity.CRITICAL): WarningSpec =
        WarningClassifier.fromFindings(listOf(Finding(kind, severity, "d"))).single()

    @Test
    fun everyCaseOfSection20_hasTheExpectedSeverity() {
        // 1. Eltűnt repó.
        assertEquals(WarningSpec(WarningCategory.REPO_GONE, Severity.WARNING, "d"), single(FindingKind.REPO_NOT_FOUND, Severity.WARNING))
        // 2. Váratlanul megváltozott repó-azonosító.
        assertEquals(Severity.CRITICAL, single(FindingKind.REPO_ID_CHANGED).severity)
        // 3. Megváltozott aláíró (a telepítetthez vagy a rögzítetthez képest).
        assertEquals(WarningCategory.SIGNER_CHANGED, single(FindingKind.SIGNER_CHANGED).category)
        assertEquals(WarningCategory.SIGNER_CHANGED, single(FindingKind.SIGNER_MISMATCH_PINNED).category)
        // 4. Más csomagazonosító.
        assertEquals(WarningSpec(WarningCategory.PACKAGE_CHANGED, Severity.CRITICAL, "d"), single(FindingKind.PACKAGE_MISMATCH))
        // 5. Régebbi verziókód.
        assertEquals(WarningSpec(WarningCategory.DOWNGRADE, Severity.CRITICAL, "d"), single(FindingKind.VERSION_DOWNGRADE))
        // 6. Sérült letöltés.
        listOf(FindingKind.SIZE_MISMATCH, FindingKind.DIGEST_MISMATCH, FindingKind.INVALID_APK, FindingKind.INVALID_SIGNATURE).forEach {
            assertEquals(WarningSpec(WarningCategory.CORRUPTED, Severity.CRITICAL, "d"), single(it))
        }
        // 7. Inkompatibilis a készülékkel: tájékoztató.
        assertEquals(WarningSpec(WarningCategory.INCOMPATIBLE, Severity.INFO, "d"), single(FindingKind.INCOMPATIBLE_SDK))
        // 9. A forrás hitelessége nem ellenőrizhető: tájékoztató, nem riasztás.
        assertEquals(WarningSpec(WarningCategory.UNVERIFIABLE_SOURCE, Severity.INFO, "d"), single(FindingKind.FIRST_SEEN_SIGNER, Severity.INFO))
        // Tulajdonosváltás: figyelmeztetés.
        assertEquals(Severity.WARNING, single(FindingKind.REPO_OWNER_CHANGED, Severity.WARNING).severity)
    }

    @Test
    fun unexpectedFormat_andIncompatibility_comeFromTheCheck() {
        // 8. Csak AAB a kiadásban: váratlan formátum.
        val aab = asset(1, "app-release.aab")
        val check = UpdateCheck(
            UpdateStatus.NO_INSTALLABLE_RELEASE, null, null,
            CompatibilityAssessment(AssetSelection.NoInstallable(listOf(RejectedAsset(aab, AssetRejection.APP_BUNDLE))), emptyList()),
            emptyList(), null,
        )
        assertEquals(listOf(WarningSpec(WarningCategory.UNEXPECTED_FORMAT, Severity.INFO, "app-release.aab")), WarningClassifier.fromCheck(check))

        val incompatible = UpdateCheck(UpdateStatus.INCOMPATIBLE, null, null, null, emptyList(), null)
        assertEquals(WarningCategory.INCOMPATIBLE, WarningClassifier.fromCheck(incompatible).single().category)
    }

    @Test
    fun informationalFindings_areNotWarnings_andDuplicatesAreMerged() {
        val specs = WarningClassifier.fromFindings(
            listOf(
                Finding(FindingKind.REPO_RENAMED, Severity.INFO),
                Finding(FindingKind.NO_PROVIDER_DIGEST, Severity.INFO),
                Finding(FindingKind.FIRST_SEEN_SIGNER, Severity.INFO),
                Finding(FindingKind.UNVERIFIABLE_SOURCE, Severity.INFO),
                Finding(FindingKind.SIZE_MISMATCH, Severity.CRITICAL),
                Finding(FindingKind.INVALID_APK, Severity.CRITICAL),
            ),
        )
        assertEquals(listOf(WarningCategory.UNVERIFIABLE_SOURCE, WarningCategory.CORRUPTED), specs.map { it.category }, "egy esetből egy figyelmeztetés")
    }

    @Test
    fun aSuccessfulCheck_resolvesRepoAndCompatibilityWarnings_butATransientErrorDoesNot() {
        val ok = UpdateCheck(UpdateStatus.UP_TO_DATE, null, null, null, emptyList(), null)
        assertTrue(WarningCategory.REPO_GONE in WarningClassifier.resolvedByCheck(ok))
        assertTrue(WarningCategory.INCOMPATIBLE in WarningClassifier.resolvedByCheck(ok))
        assertFalse(WarningCategory.SIGNER_CHANGED in WarningClassifier.resolvedByCheck(ok), "az aláíró-figyelmeztetést nem oldja fel egy ellenőrzés")
        val stillGone = UpdateCheck(UpdateStatus.REPO_NOT_FOUND, null, null, null, listOf(Finding(FindingKind.REPO_NOT_FOUND, Severity.WARNING)), null)
        assertFalse(WarningCategory.REPO_GONE in WarningClassifier.resolvedByCheck(stillGone))
        assertTrue(WarningClassifier.resolvedByCheck(UpdateCheck(UpdateStatus.RATE_LIMITED, null, null, null, emptyList(), null)).isEmpty())
    }

    @Test
    fun aCleanNewerFile_resolvesReleaseBoundCases_butARejectedOneDoesNot() {
        val facts = ApkFacts("com.example", 3, "3", 26, 36, null, SignerInfo(listOf("aa"), listOf("aa"), false))
        fun report(findings: List<Finding>) = VerificationReport(emptyList(), CheckStatus.OK, SignerIdentity.MATCHES_INSTALLED, emptyList(), facts, emptyList(), emptySet(), findings, listOf(facts))
        assertEquals(
            setOf(WarningCategory.SIGNER_CHANGED, WarningCategory.PACKAGE_CHANGED, WarningCategory.DOWNGRADE, WarningCategory.CORRUPTED),
            WarningClassifier.resolvedByReport(report(emptyList())),
        )
        val rejected = report(listOf(Finding(FindingKind.SIGNER_CHANGED, Severity.CRITICAL)))
        assertTrue(WarningClassifier.resolvedByReport(rejected).isEmpty(), "az elutasított állomány semmit nem old fel")
    }

    @Test
    fun openWarnings_blockAutomaticInstall() {
        val global = GlobalSettings(autoDownload = true, autoInstall = true)
        val check = UpdateCheck(UpdateStatus.UPDATE_AVAILABLE, null, null, CompatibilityAssessment(AssetSelection.Single(asset(1, "a.apk")), emptyList()), emptyList(), null)
        val plan = BackgroundDecision.plan(check, PolicyResolver.resolve(global, null), global, NetworkState(true, false), installed = true, blockingWarnings = true)
        assertTrue(plan.download)
        assertFalse(plan.install, "nyugtázatlan figyelmeztetésnél nincs automatikus telepítés")
    }

    // ---- SIG-04: kritikus figyelmeztetést csak igazolt azonosságú, ÚJABB állomány oldhat fel ----

    private fun installableReport(identity: SignerIdentity, vararg findings: Finding) = VerificationReport(
        integrity = emptyList(), signatureValid = CheckStatus.OK, identity = identity, compatibility = emptyList(),
        facts = ApkFacts("com.example", 11, "1.1", 28, 37, null, SignerInfo(listOf("aa"), listOf("aa"), false)),
        splitFacts = emptyList(), nativeAbis = emptySet(), findings = findings.toList(),
    )

    @Test
    fun criticalWarnings_areNotResolvedByAttackerControlledReports() {
        val all = setOf(WarningCategory.SIGNER_CHANGED, WarningCategory.PACKAGE_CHANGED, WarningCategory.DOWNGRADE, WarningCategory.CORRUPTED)
        // Igazolt azonosságú, újabb állomány: feloldja a kiadáshoz kötött eseteket.
        assertEquals(all, WarningClassifier.resolvedByReport(installableReport(SignerIdentity.MATCHES_INSTALLED)))
        assertEquals(all, WarningClassifier.resolvedByReport(installableReport(SignerIdentity.MATCHES_PINNED)))
        // A már telepített APK újabb címkével közzétéve (azonos verziókód): semmit nem old fel.
        val sameVersion = installableReport(SignerIdentity.MATCHES_INSTALLED, Finding(FindingKind.SAME_VERSION, Severity.WARNING))
        assertTrue(sameVersion.isInstallable)
        assertEquals(emptySet(), WarningClassifier.resolvedByReport(sameVersion))
        // Nem igazolt azonosság (első látás): csak a sérült letöltés oldódik fel, az aláíróváltás nem.
        assertEquals(setOf(WarningCategory.CORRUPTED), WarningClassifier.resolvedByReport(installableReport(SignerIdentity.FIRST_SEEN)))
        // Nem telepíthető állomány semmit nem old fel.
        val blocked = installableReport(SignerIdentity.MISMATCH, Finding(FindingKind.SIGNER_CHANGED, Severity.CRITICAL))
        assertEquals(emptySet(), WarningClassifier.resolvedByReport(blocked))
    }
}
