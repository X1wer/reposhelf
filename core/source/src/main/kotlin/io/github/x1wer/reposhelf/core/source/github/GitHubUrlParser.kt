// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.source.github

import io.github.x1wer.reposhelf.core.model.ProviderId
import io.github.x1wer.reposhelf.core.model.RepoRef
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * GitHub-repó felismerése felhasználói bemenetből (C. 9.: „elegendő legyen megadnia egy GitHub-URL-t”).
 *
 * Elfogadott alakok:
 * - `owner/name`;
 * - `github.com/owner/name`;
 * - `https://github.com/owner/name`, opcionálisan `.git` végződéssel vagy további útvonallal (`/releases`, `/tree/main`).
 *
 * A bemenetből kizárólag az `owner/name` pár lesz. Kérés soha nem megy a felhasználó által megadott URL-re,
 * hanem mindig a rögzített API-hosztra, így tetszőleges URL nem okozhat kérést tetszőleges hosztra (C. 21.).
 */
object GitHubUrlParser {
    private const val MAX_INPUT = 300
    private val HOSTS = setOf("github.com", "www.github.com")

    /** Nem felhasználónevek, hanem GitHub-oldalak (pl. `github.com/settings/...`). */
    private val RESERVED_OWNERS = setOf(
        "about", "apps", "collections", "contact", "enterprise", "explore", "features", "login", "logout",
        "marketplace", "new", "notifications", "orgs", "organizations", "pricing", "search", "settings",
        "sponsors", "topics", "trending", "users", "codespaces", "issues", "pulls",
    )

    fun parse(input: String): RepoRef? {
        val text = input.trim()
        if (text.isEmpty() || text.length > MAX_INPUT) return null
        if (text.any { it.isWhitespace() || it.isISOControl() }) return null

        val segments: List<String> = if (!text.contains("://") && !text.startsWith("github.com", ignoreCase = true) &&
            !text.startsWith("www.github.com", ignoreCase = true)
        ) {
            // `owner/name` alak (legfeljebb egy perjel, záró perjel nélkül).
            text.split('/').takeIf { it.size == 2 } ?: return null
        } else {
            val withScheme = if (text.contains("://")) text else "https://$text"
            val url = withScheme.toHttpUrlOrNull() ?: return null
            if (url.scheme != "https" && url.scheme != "http") return null
            if (url.host.lowercase() !in HOSTS) return null
            if (url.port != url.defaultPortForScheme()) return null
            if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
            url.pathSegments.filter { it.isNotEmpty() }.takeIf { it.size >= 2 } ?: return null
        }

        val owner = segments[0]
        val name = segments[1].removeSuffix(".git").removeSuffix(".GIT")
        if (owner.lowercase() in RESERVED_OWNERS) return null
        return RepoRef.parsePath(ProviderId.GITHUB, "$owner/$name")
    }

    private fun okhttp3.HttpUrl.defaultPortForScheme(): Int = okhttp3.HttpUrl.defaultPort(scheme)
}
