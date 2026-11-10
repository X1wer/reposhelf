// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.network

import okhttp3.HttpUrl

/**
 * Hoszt-minta: vagy pontos hosztnév (`api.github.com`), vagy aldomain-helyettesítő (`*.githubusercontent.com`).
 * Az utóbbi csak valódi aldomainre illeszkedik: az `evilgithubusercontent.com` NEM illeszkedik.
 */
class HostPattern private constructor(private val exact: String?, private val dotSuffix: String?) {

    fun matches(host: String): Boolean {
        val h = host.lowercase()
        return if (exact != null) {
            h == exact
        } else {
            val suffix = checkNotNull(dotSuffix)
            h.length > suffix.length && h.endsWith(suffix)
        }
    }

    override fun toString(): String = exact ?: "*${dotSuffix}"

    companion object {
        fun parse(pattern: String): HostPattern {
            val p = pattern.trim().lowercase()
            require(p.isNotEmpty() && !p.contains('/') && !p.contains(':')) { "Érvénytelen hoszt-minta: $pattern" }
            return if (p.startsWith("*.")) {
                require(p.length > 2) { "Érvénytelen hoszt-minta: $pattern" }
                HostPattern(null, p.substring(1))
            } else {
                HostPattern(p, null)
            }
        }
    }
}

/**
 * Egy kéréscsoport hálózati szabályai (docs/ARCHITECTURE.md 6. pont).
 * Csak HTTPS, csak engedélyezett hosztok (az átirányítások minden lépésében is), méretkorlát a válasz törzsére.
 */
data class HttpPolicy(
    val allowedHosts: List<HostPattern>,
    val maxBodyBytes: Long,
    val maxRedirects: Int = 5,
) {
    init {
        require(allowedHosts.isNotEmpty()) { "Legalább egy engedélyezett hoszt kell" }
        require(maxBodyBytes > 0) { "A méretkorlát pozitív kell legyen" }
        require(maxRedirects in 0..10) { "Az átirányítások száma 0 és 10 között lehet" }
    }

    fun isHostAllowed(host: String): Boolean = allowedHosts.any { it.matches(host) }

    fun isAllowed(url: HttpUrl): Boolean = url.isHttps && isHostAllowed(url.host)

    companion object {
        const val KIB = 1024L
        const val MIB = 1024L * 1024L

        fun of(vararg hosts: String, maxBodyBytes: Long, maxRedirects: Int = 5): HttpPolicy =
            HttpPolicy(hosts.map(HostPattern::parse), maxBodyBytes, maxRedirects)
    }
}

/** HTTP-validátorok a feltételes kérésekhez (ETag / Last-Modified, C. 16.). */
data class Validators(val etag: String?, val lastModified: String?) {
    val isEmpty: Boolean get() = etag == null && lastModified == null

    companion object {
        val NONE = Validators(null, null)
    }
}
