// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.source.github

import io.github.x1wer.reposhelf.core.model.ProviderId
import io.github.x1wer.reposhelf.core.model.RepoRef
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GitHubUrlParserTest {

    private fun ref(owner: String, name: String) = RepoRef(ProviderId.GITHUB, owner, name)

    @Test
    fun acceptsCommonForms() {
        val expected = ref("ImranR98", "Obtainium")
        listOf(
            "ImranR98/Obtainium",
            "github.com/ImranR98/Obtainium",
            "https://github.com/ImranR98/Obtainium",
            "https://github.com/ImranR98/Obtainium/",
            "https://github.com/ImranR98/Obtainium.git",
            "https://www.github.com/ImranR98/Obtainium/releases/latest",
            "https://github.com/ImranR98/Obtainium/tree/main/lib",
            "http://github.com/ImranR98/Obtainium",
            "  https://github.com/ImranR98/Obtainium  ",
        ).forEach { assertEquals(expected, GitHubUrlParser.parse(it), it) }
    }

    @Test
    fun rejectsOtherHostsAndMalformedInput() {
        listOf(
            "",
            "Obtainium",
            "a/b/c",
            "https://gitlab.com/owner/repo",
            "https://github.com.evil.example/owner/repo",
            "https://evil.example/github.com/owner/repo",
            "https://user:pass@github.com/owner/repo",
            "https://github.com:8443/owner/repo",
            "https://github.com/owner",
            "https://github.com/settings/profile",
            "https://github.com/orgs/foo",
            "ftp://github.com/owner/repo",
            "owner/..",
            "-bad/repo",
            "owner/re po",
            "owner/re\npo",
            "owner/re\u0000po",
            "o".repeat(40) + "/repo",
            "https://github.com/" + "x".repeat(400),
        ).forEach { assertNull(GitHubUrlParser.parse(it), "Elutasítandó: '$it'") }
    }

    @Test
    fun repoRefKeyIsCaseInsensitive() {
        assertEquals(ref("Owner", "Repo").key, ref("owner", "REPO").key)
    }
}
