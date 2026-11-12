// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.ui.history

import io.github.x1wer.reposhelf.core.data.safety.HistoryEntry
import io.github.x1wer.reposhelf.core.data.safety.HistoryOutcome
import io.github.x1wer.reposhelf.core.data.safety.InstallMethod
import io.github.x1wer.reposhelf.core.data.safety.Version
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals

/** Az előzmények szűrése és keresése (C. 15.). */
class HistoryFilterTest {
    private fun entry(id: Long, name: String, outcome: HistoryOutcome, method: InstallMethod, pkg: String? = null) =
        HistoryEntry(id, null, name, pkg, Version("1", 1), Version("2", 2), Instant.EPOCH, outcome, null, null, method)

    private val entries = listOf(
        entry(1, "Aegis", HistoryOutcome.SUCCESS, InstallMethod.MANUAL, "com.beemdevelopment.aegis"),
        entry(2, "Aegis", HistoryOutcome.FAILED, InstallMethod.AUTOMATIC),
        entry(3, "RepoShelf", HistoryOutcome.SUCCESS, InstallMethod.SELF_UPDATE),
        entry(4, "Antennapod", HistoryOutcome.CANCELLED_BY_USER, InstallMethod.AUTOMATIC_PROMPTED),
        entry(5, "Fossify", HistoryOutcome.BLOCKED, InstallMethod.MANUAL),
    )

    private fun ids(query: String, filter: HistoryFilter) = filterHistory(entries, query, filter).map { it.id }

    @Test
    fun filtersByOutcomeAndMethod() {
        assertEquals(listOf(1L, 2, 3, 4, 5), ids("", HistoryFilter.ALL))
        assertEquals(listOf(1L, 3), ids("", HistoryFilter.SUCCESS))
        assertEquals(listOf(2L), ids("", HistoryFilter.FAILED))
        assertEquals(listOf(4L), ids("", HistoryFilter.CANCELLED))
        assertEquals(listOf(5L), ids("", HistoryFilter.BLOCKED))
        assertEquals(listOf(2L, 4), ids("", HistoryFilter.AUTOMATIC))
    }

    @Test
    fun searchIsCaseInsensitive_matchesNameOrPackage_andCombinesWithTheFilter() {
        assertEquals(listOf(1L, 2), ids("  aEg ", HistoryFilter.ALL))
        assertEquals(listOf(1L), ids("beemdevelopment", HistoryFilter.ALL))
        assertEquals(listOf(2L), ids("aegis", HistoryFilter.FAILED))
        assertEquals(emptyList(), ids("nincs ilyen", HistoryFilter.ALL))
    }
}
