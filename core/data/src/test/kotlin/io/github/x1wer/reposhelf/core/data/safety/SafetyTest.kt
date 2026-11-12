// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.data.safety

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.x1wer.reposhelf.core.data.db.AppDatabase
import io.github.x1wer.reposhelf.core.model.Severity
import io.github.x1wer.reposhelf.core.network.testing.MutableClock
import io.github.x1wer.reposhelf.core.update.WarningCategory
import io.github.x1wer.reposhelf.core.update.WarningSpec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Figyelmeztetések (C. 20.) és előzmények (C. 15.) tárolása (B. 6.4.). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SafetyTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).allowMainThreadQueries().build()
    private val clock = MutableClock(Instant.parse("2026-11-10T10:00:00Z"))
    private val warnings = SecurityWarnings(db, clock)
    private val history = UpdateHistory(db, clock)
    private val app = AppRef(7, "com.example", "Example")

    @After
    fun tearDown() = db.close()

    private val ownerChanged = WarningSpec(WarningCategory.REPO_OWNER_CHANGED, Severity.WARNING, "a/x → b/x")
    private val signerChanged = WarningSpec(WarningCategory.SIGNER_CHANGED, Severity.CRITICAL, null)
    private val firstSeen = WarningSpec(WarningCategory.UNVERIFIABLE_SOURCE, Severity.INFO, null)

    @Test
    fun sameCase_isRecordedOnce_andReopensAfterResolution(): Unit = runBlocking {
        assertEquals(listOf(ownerChanged), warnings.record(app, listOf(ownerChanged)))
        clock.advance(Duration.ofHours(1))
        assertTrue(warnings.record(app, listOf(ownerChanged)).isEmpty(), "ismétlés nem új figyelmeztetés")
        val open = warnings.observeOpen().first().single()
        assertEquals(Instant.parse("2026-11-10T11:00:00Z"), open.lastSeenAt)
        assertEquals(Instant.parse("2026-11-10T10:00:00Z"), open.firstSeenAt)

        warnings.resolve(7, setOf(WarningCategory.REPO_OWNER_CHANGED))
        assertTrue(warnings.observeOpen().first().isEmpty())
        assertEquals(listOf(ownerChanged), warnings.record(app, listOf(ownerChanged)), "újra előfordult: ismét nyitott")
    }

    @Test
    fun infoIsNotListedGlobally_andAcknowledgementReleasesAWarningButNotACriticalCase(): Unit = runBlocking {
        warnings.record(app, listOf(firstSeen))
        assertTrue(warnings.observeOpen().first().isEmpty(), "az INFO csak az adatlapon látszik")
        assertEquals(1, warnings.observeForApp(7).first().size)
        assertFalse(warnings.hasBlocking(7))

        warnings.record(app, listOf(ownerChanged))
        assertTrue(warnings.hasBlocking(7), "nyugtázatlan figyelmeztetés: nincs automatikus telepítés")
        warnings.acknowledge(warnings.observeOpen().first().single().id)
        assertFalse(warnings.hasBlocking(7))

        warnings.record(app, listOf(signerChanged))
        warnings.acknowledge(warnings.observeOpen().first().single().id)
        assertTrue(warnings.hasBlocking(7), "kritikus eset nyugtázás után is blokkol")
    }

    @Test
    fun history_recordsAllFields_newestFirst_withoutSensitiveData(): Unit = runBlocking {
        history.record(app, Version("1.0", 1), Version("1.1", 2), HistoryOutcome.SUCCESS, InstallMethod.AUTOMATIC)
        clock.advance(Duration.ofMinutes(5))
        history.record(
            app, Version("1.1", 2), Version("1.2", 3), HistoryOutcome.FAILED, InstallMethod.MANUAL,
            failureCode = "CONFLICT", failureMessage = "INSTALL_FAILED at https://example.org/x?token=abc in /data/app/com.example-1/base.apk",
        )
        val entries = history.observe().first()
        assertEquals(listOf(HistoryOutcome.FAILED, HistoryOutcome.SUCCESS), entries.map { it.outcome })
        val failed = entries.first()
        assertEquals(Version("1.1", 2), failed.from)
        assertEquals(Version("1.2", 3), failed.to)
        assertEquals("CONFLICT", failed.failureCode)
        assertEquals(InstallMethod.MANUAL, failed.method)
        assertEquals("INSTALL_FAILED at <url> in <path>", failed.failureMessage)
        assertEquals(Instant.parse("2026-11-10T10:05:00Z"), failed.at)
    }

    @Test
    fun history_isBounded(): Unit = runBlocking {
        repeat(UpdateHistory.MAX_ENTRIES + 5) {
            history.record(app, null, Version("v$it", it.toLong()), HistoryOutcome.SUCCESS, InstallMethod.MANUAL)
            clock.advance(Duration.ofSeconds(1))
        }
        val entries = history.observe(limit = 5000).first()
        assertEquals(UpdateHistory.MAX_ENTRIES, entries.size)
        assertEquals("v${UpdateHistory.MAX_ENTRIES + 4}", entries.first().to?.name, "a legújabbak maradnak meg")
    }
}
