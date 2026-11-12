// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.source.codeberg

import io.github.x1wer.reposhelf.core.model.ProviderId
import io.github.x1wer.reposhelf.core.model.RepoRef
import io.github.x1wer.reposhelf.core.source.Fetch
import io.github.x1wer.reposhelf.core.source.ProviderRegistry
import io.github.x1wer.reposhelf.core.source.SourceProvider
import io.github.x1wer.reposhelf.core.source.github.GitHubUrlParser
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** B. 7.6.: a szolgáltatóréteg egy második (letiltott) szolgáltatóval is működik, és a letiltott nem szivárog be. */
class ProviderPortabilityTest {
    /** Csak a GitHub URL-felismerése kell (hálózat nélkül). */
    private val github = object : SourceProvider by CodebergStubProvider() {
        override val id = ProviderId.GITHUB
        override fun parseRepoUrl(input: String): RepoRef? = GitHubUrlParser.parse(input)
    }
    private val registry = ProviderRegistry(listOf(github, CodebergStubProvider()))

    @Test
    fun eachProviderRecognisesOnlyItsOwnUrls() {
        assertEquals(RepoRef(ProviderId.GITHUB, "beemdevelopment", "Aegis"), registry.parseRepoUrl("https://github.com/beemdevelopment/Aegis"))
        assertEquals(RepoRef(ProviderId.CODEBERG, "Freeyourgadget", "Gadgetbridge"), registry.parseRepoUrl("https://codeberg.org/Freeyourgadget/Gadgetbridge/releases"))
        assertNull(registry.parseRepoUrl("http://codeberg.org/a/b"), "csak HTTPS")
        assertNull(registry.parseRepoUrl("https://gitlab.com/group/sub/project"), "GitLab nincs (beágyazott csoportok: ARCH 5.2.)")
        assertEquals("codeberg:freeyourgadget/gadgetbridge", RepoRef(ProviderId.CODEBERG, "Freeyourgadget", "Gadgetbridge").key)
    }

    @Test
    fun theDisabledProvider_isNotResolvable_andTheStubMakesNoRequests(): Unit = runBlocking {
        assertNull(ProviderId.fromId("codeberg"), "a katalógus és az import nem hivatkozhat letiltott szolgáltatóra")
        assertEquals(ProviderId.GITHUB, ProviderId.fromId("github"))
        assertIs<Fetch.Failed>(CodebergStubProvider().releases(RepoRef(ProviderId.CODEBERG, "a", "b")))
    }
}
