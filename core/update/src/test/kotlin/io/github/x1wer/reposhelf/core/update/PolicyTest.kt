// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.update

import io.github.x1wer.reposhelf.core.apk.ApkFacts
import io.github.x1wer.reposhelf.core.apk.AssetSelection
import io.github.x1wer.reposhelf.core.apk.CheckStatus
import io.github.x1wer.reposhelf.core.apk.SignerIdentity
import io.github.x1wer.reposhelf.core.apk.SignerInfo
import io.github.x1wer.reposhelf.core.apk.VerificationReport
import io.github.x1wer.reposhelf.core.model.Finding
import io.github.x1wer.reposhelf.core.model.FindingKind
import io.github.x1wer.reposhelf.core.model.ProviderRepoId
import io.github.x1wer.reposhelf.core.model.Severity
import io.github.x1wer.reposhelf.core.source.ProviderRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Beállítások, felülírások, háttérdöntés és a felhasználói beavatkozás nélküli telepítés feltételei (C. 12.2–12.6.). */
class PolicyTest {

    @Test
    fun defaults_areTheSafeOnes() {
        val defaults = GlobalSettings()
        assertEquals(CheckFrequency.DAILY, defaults.checkFrequency)
        assertFalse(defaults.autoDownload)
        assertFalse(defaults.autoInstall)
        assertFalse(defaults.includePrereleases)
        assertTrue(defaults.requestUpdateOwnership)
    }

    @Test
    fun overrides_winOverGlobal_andNullMeansGlobal() {
        val global = GlobalSettings(autoDownload = true, includePrereleases = false, releaseDelayDays = 3, notifyUpdates = true)
        val inherited = PolicyResolver.resolve(global, null)
        assertTrue(inherited.checkEnabled)
        assertTrue(inherited.autoDownload)
        assertEquals(Duration.ofDays(3), inherited.releaseDelay)

        val overridden = PolicyResolver.resolve(global, AppOverrides(checkEnabled = false, includePrereleases = true, autoDownload = false, notify = false, releaseDelayDays = 0))
        assertFalse(overridden.checkEnabled)
        assertTrue(overridden.includePrereleases)
        assertFalse(overridden.autoDownload)
        assertFalse(overridden.notify)
        assertEquals(Duration.ZERO, overridden.releaseDelay)
    }

    @Test
    fun autoInstall_impliesDownload_andDelayIsBounded() {
        val policy = PolicyResolver.resolve(GlobalSettings(), AppOverrides(autoInstall = true, releaseDelayDays = 999))
        assertTrue(policy.autoInstall)
        assertTrue(policy.autoDownload, "telepíteni csak letöltött állományt lehet")
        assertEquals(Duration.ofDays(30), policy.releaseDelay)
    }

    private fun check(status: UpdateStatus, selection: AssetSelection = AssetSelection.Single(asset(1, "app.apk"))) =
        UpdateCheck(status, null, null, CompatibilityAssessment(selection, emptyList()), emptyList(), null)

    @Test
    fun backgroundPlan_respectsSettingsNetworkAndChoice() {
        val wifi = NetworkState(connected = true, metered = false)
        val mobile = NetworkState(connected = true, metered = true)
        val manualOnly = PolicyResolver.resolve(GlobalSettings(), null)
        assertEquals(BackgroundPlan(notify = true, download = false, install = false), BackgroundDecision.plan(check(UpdateStatus.UPDATE_AVAILABLE), manualOnly, GlobalSettings(), wifi, installed = true))

        val global = GlobalSettings(autoDownload = true, autoInstall = true)
        val auto = PolicyResolver.resolve(global, null)
        assertEquals(BackgroundPlan(true, true, true), BackgroundDecision.plan(check(UpdateStatus.UPDATE_AVAILABLE), auto, global, wifi, true))
        // Mobiladaton csak engedéllyel.
        assertEquals(BackgroundPlan(true, false, false), BackgroundDecision.plan(check(UpdateStatus.UPDATE_AVAILABLE), auto, global, mobile, true))
        val metered = global.copy(autoDownloadOnMetered = true)
        assertTrue(BackgroundDecision.plan(check(UpdateStatus.UPDATE_AVAILABLE), auto, metered, mobile, true).download)
        // Ha a felhasználónak kell választania az állományok közül, nem töltünk le automatikusan.
        val ambiguous = check(UpdateStatus.UPDATE_AVAILABLE, AssetSelection.Ambiguous(listOf(asset(1, "a.apk"), asset(2, "b.apk")), false))
        assertFalse(BackgroundDecision.plan(ambiguous, auto, global, wifi, true).download)
        // Nincs frissítés, nincs telepítve, vagy a figyelés ki van kapcsolva: semmi.
        assertEquals(BackgroundPlan.NOTHING, BackgroundDecision.plan(check(UpdateStatus.UP_TO_DATE), auto, global, wifi, true))
        assertEquals(BackgroundPlan.NOTHING, BackgroundDecision.plan(check(UpdateStatus.UPDATE_AVAILABLE), auto, global, wifi, installed = false))
        val off = PolicyResolver.resolve(global, AppOverrides(checkEnabled = false))
        assertEquals(BackgroundPlan.NOTHING, BackgroundDecision.plan(check(UpdateStatus.UPDATE_AVAILABLE), off, global, wifi, true))
    }

    private val us = "io.github.x1wer.reposhelf"

    private fun report(targetSdk: Int = 36, identity: SignerIdentity = SignerIdentity.MATCHES_INSTALLED, findings: List<Finding> = emptyList()): VerificationReport {
        val facts = ApkFacts("com.example", 20, "2.0", 26, targetSdk, null, SignerInfo(listOf("aa"), listOf("aa"), false))
        return VerificationReport(emptyList(), CheckStatus.OK, identity, emptyList(), facts, emptyList(), emptySet(), findings, listOf(facts))
    }

    @Test
    fun unattended_targetSdkThresholds() {
        assertNull(UnattendedInstallPolicy.minimumTargetSdk(30))
        assertEquals(29, UnattendedInstallPolicy.minimumTargetSdk(31))
        assertEquals(29, UnattendedInstallPolicy.minimumTargetSdk(32))
        assertEquals(30, UnattendedInstallPolicy.minimumTargetSdk(33))
        assertEquals(31, UnattendedInstallPolicy.minimumTargetSdk(34))
        assertEquals(33, UnattendedInstallPolicy.minimumTargetSdk(35))
        assertEquals(34, UnattendedInstallPolicy.minimumTargetSdk(36))
        assertEquals(35, UnattendedInstallPolicy.minimumTargetSdk(37))
    }

    @Test
    fun unattended_allConditionsMustHold() {
        val ours = InstallSource(us, null)
        assertEquals(UnattendedReason.ALLOWED, UnattendedInstallPolicy.evaluate(true, 37, us, ours, report()))
        assertEquals(UnattendedReason.ALLOWED, UnattendedInstallPolicy.evaluate(true, 37, us, InstallSource("com.other.store", us), report()), "update owner mi vagyunk")
        assertEquals(UnattendedReason.DISABLED_BY_USER, UnattendedInstallPolicy.evaluate(false, 37, us, ours, report()))
        assertEquals(UnattendedReason.ANDROID_TOO_OLD, UnattendedInstallPolicy.evaluate(true, 28, us, ours, report()))
        assertEquals(UnattendedReason.FIRST_INSTALL, UnattendedInstallPolicy.evaluate(true, 37, us, null, report()))
        assertEquals(UnattendedReason.NOT_INSTALLER, UnattendedInstallPolicy.evaluate(true, 37, us, InstallSource(null, null), report()))
        assertEquals(UnattendedReason.OWNED_BY_OTHER, UnattendedInstallPolicy.evaluate(true, 37, us, InstallSource(us, "com.other.store"), report()))
        assertEquals(UnattendedReason.TARGET_SDK_TOO_LOW, UnattendedInstallPolicy.evaluate(true, 37, us, ours, report(targetSdk = 34)))
        assertEquals(UnattendedReason.ALLOWED, UnattendedInstallPolicy.evaluate(true, 34, us, ours, report(targetSdk = 31)))
        assertEquals(UnattendedReason.NOT_VERIFIED, UnattendedInstallPolicy.evaluate(true, 37, us, ours, report(identity = SignerIdentity.FIRST_SEEN)))
        assertEquals(
            UnattendedReason.NOT_VERIFIED,
            UnattendedInstallPolicy.evaluate(true, 37, us, ours, report(findings = listOf(Finding(FindingKind.SIGNER_CHANGED, Severity.CRITICAL)))),
        )
    }

    @Test
    fun unattended_selfUpdate_doesNotNeedToBeTheInstallerOfRecord_butEverythingElseStillHolds() {
        // A saját frissítés: a rendszer a „saját magát frissítő” telepítőt is elfogadja (C. 14.).
        val self = "com.example"
        assertEquals(UnattendedReason.ALLOWED, UnattendedInstallPolicy.evaluate(true, 37, self, InstallSource(null, null), report()))
        assertEquals(UnattendedReason.ALLOWED, UnattendedInstallPolicy.evaluate(true, 37, self, InstallSource("com.android.shell", "com.other.store"), report()))
        assertEquals(UnattendedReason.ANDROID_TOO_OLD, UnattendedInstallPolicy.evaluate(true, 30, self, InstallSource(null, null), report()))
        assertEquals(UnattendedReason.TARGET_SDK_TOO_LOW, UnattendedInstallPolicy.evaluate(true, 37, self, InstallSource(null, null), report(targetSdk = 34)))
        assertEquals(
            UnattendedReason.NOT_VERIFIED,
            UnattendedInstallPolicy.evaluate(true, 37, self, InstallSource(null, null), report(findings = listOf(Finding(FindingKind.SIGNER_MISMATCH_PINNED, Severity.CRITICAL)))),
            "a saját frissítésnél is minden ellenőrzés kötelező",
        )
    }

    @Test
    fun releaseDelay_skipsTooFreshReleases() = runTest {
        val provider = FakeProvider().apply { byName = { fresh(metadata("owner/app", 42, 7)) } }
        val now = Instant.parse("2026-10-10T12:00:00Z")
        val discovery = ReleaseDiscovery(ProviderRegistry(listOf(provider)), clock = Clock.fixed(now, ZoneOffset.UTC))
        val old = release(1, "v1.0", listOf(asset(1, "app.apk")), published = "2026-10-01T10:00:00Z")
        val fresh = release(2, "v1.1", listOf(asset(2, "app.apk")), published = "2026-10-09T10:00:00Z")
        provider.releaseList = { fresh(listOf(fresh, old)) }
        val source = TrackedSource(ref("owner/app"), ProviderRepoId(42), 7, includePrereleases = false)

        val immediate = discovery.discover(source) as StepResult.Ok
        assertEquals(fresh, immediate.value.candidate)
        val delayed = discovery.discover(source.copy(releaseDelay = Duration.ofDays(3))) as StepResult.Ok
        assertEquals(old, delayed.value.candidate, "a 3 napnál frissebb kiadás még nem jelölt")

        provider.releaseList = { fresh(listOf(fresh)) }
        val onlyFresh = discovery.discover(source.copy(releaseDelay = Duration.ofDays(3))) as StepResult.Ok
        assertNull(onlyFresh.value.candidate)
        assertTrue(onlyFresh.findings.any { it.kind == FindingKind.NO_INSTALLABLE_RELEASE && it.detail == "delayed" })
    }
}
