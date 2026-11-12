// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.model

/**
 * Forráskód-tárhely szolgáltató (C. 4.). Jelenleg csak a GitHub támogatott. A Codeberg (Forgejo) egy LETILTOTT bejegyzés:
 * azt ellenőrzi, hogy a réteg alkalmas-e egy második szolgáltatóra (B. 7.6., docs/ARCHITECTURE.md 5.2.). A letiltott
 * szolgáltatót a [fromId] nem adja vissza, így a katalógus, az import és a tárolt adatok sem hivatkozhatnak rá.
 */
enum class ProviderId(val id: String, val enabled: Boolean) {
    GITHUB("github", enabled = true),
    CODEBERG("codeberg", enabled = false),
    ;

    /**
     * Szolgáltatónkénti névszabályok a repó-útvonalra (owner/name).
     * A GitHub szabálya: a tulajdonos 1–39 karakter (betű, szám, kötőjel, nem kezdődhet és nem végződhet kötőjellel),
     * a repó neve 1–100 karakter (betű, szám, `.`, `_`, `-`), de nem lehet `.` vagy `..`.
     */
    fun isValidOwner(owner: String): Boolean = when (this) {
        GITHUB -> GITHUB_OWNER.matches(owner)
        CODEBERG -> FORGEJO_OWNER.matches(owner)
    }

    fun isValidRepoName(name: String): Boolean = when (this) {
        GITHUB -> GITHUB_REPO.matches(name) && name != "." && name != ".." && !name.endsWith(".git", ignoreCase = true)
        CODEBERG -> FORGEJO_REPO.matches(name) && name != "." && name != ".." && !name.endsWith(".git", ignoreCase = true)
    }

    companion object {
        private val GITHUB_OWNER = Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?$")
        private val GITHUB_REPO = Regex("^[A-Za-z0-9._-]{1,100}$")

        /** Forgejo-szabály: betű, szám, `_`, `-`, `.`; legfeljebb 40 (tulajdonos) / 100 (repó) karakter. */
        private val FORGEJO_OWNER = Regex("^[A-Za-z0-9_](?:[A-Za-z0-9_.-]{0,38}[A-Za-z0-9_])?$")
        private val FORGEJO_REPO = Regex("^[A-Za-z0-9_.-]{1,100}$")

        /** Csak engedélyezett szolgáltató; ismeretlen vagy letiltott azonosítónál `null`. */
        fun fromId(id: String): ProviderId? = entries.firstOrNull { it.id == id && it.enabled }
    }
}

/** Egy repó szöveges hivatkozása. A szöveges útvonal változhat (átnevezés), ezért a stabil azonosító külön: [ProviderRepoId]. */
data class RepoRef(val provider: ProviderId, val owner: String, val name: String) {
    init {
        require(provider.isValidOwner(owner)) { "Érvénytelen tulajdonosnév: $owner" }
        require(provider.isValidRepoName(name)) { "Érvénytelen repónév: $name" }
    }

    /** `owner/name` alak. */
    val path: String get() = "$owner/$name"

    /** Kis- és nagybetűtől független kulcs (a GitHub az útvonalban nem különbözteti meg a kis- és nagybetűt). */
    val key: String get() = "${provider.id}:${owner.lowercase()}/${name.lowercase()}"

    override fun toString(): String = "${provider.id}:$path"

    companion object {
        /** `owner/name` → [RepoRef], vagy `null`, ha nem érvényes. */
        fun parsePath(provider: ProviderId, path: String): RepoRef? {
            val parts = path.split('/')
            if (parts.size != 2) return null
            val (owner, name) = parts
            return if (provider.isValidOwner(owner) && provider.isValidRepoName(name)) RepoRef(provider, owner, name) else null
        }
    }
}

/** A szolgáltató stabil, numerikus repó-azonosítója (GitHub: `id`). Átnevezéskor és áthelyezéskor nem változik. */
@JvmInline
value class ProviderRepoId(val value: Long) {
    init {
        require(value > 0) { "A repó-azonosító pozitív szám kell legyen" }
    }
}
