// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.data.transfer

import io.github.x1wer.reposhelf.core.model.ProviderId
import io.github.x1wer.reposhelf.core.model.RepoRef
import io.github.x1wer.reposhelf.core.update.AppOverrides
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.Instant

/**
 * Egy exportált / importált alkalmazás (C. 25.): követés, kedvenc, saját forrás (katalógusazonosító nélkül) és az
 * alkalmazásonkénti frissítési beállítások. Szándékosan NINCS benne: token, csomagnév, rögzített aláíró, telepítési
 * állapot, előzmény. Az aláíró és a csomagnév bizalmi adat: egy idegen fájl nem írhatja felül (C. 19.).
 */
data class AppListEntry(
    val repo: RepoRef,
    val repositoryId: Long?,
    val catalogId: String?,
    val name: String,
    val followed: Boolean,
    val favorite: Boolean,
    val overrides: AppOverrides?,
)

sealed interface AppListDecodeResult {
    data class Decoded(val entries: List<AppListEntry>, val skipped: List<SkippedEntry>) : AppListDecodeResult

    data class Invalid(val reason: Reason) : AppListDecodeResult {
        enum class Reason { TOO_LARGE, MALFORMED, NOT_AN_APP_LIST, UNSUPPORTED_VERSION, TOO_MANY_ENTRIES }
    }
}

data class SkippedEntry(val index: Int, val reason: String)

/**
 * A verziózott export-formátum (`reposhelf-app-list`, 1. verzió):
 * ```
 * {"format":"reposhelf-app-list","version":1,"exportedAt":"…","apps":[
 *   {"source":{"provider":"github","repository":"owner/name","repositoryId":1},"catalogId":"aegis","name":"Aegis",
 *    "followed":true,"favorite":false,"settings":{"checkEnabled":true,"autoInstall":false,"releaseDelayDays":3}}]}
 * ```
 * A beolvasás szigorú: méretkorlát, ismert formátum és verzió, bejegyzésenkénti validáció (a hibás bejegyzés kimarad,
 * okkal), ismeretlen mezők figyelmen kívül. A beolvasás semmit nem telepít és nem kér le.
 */
object AppListFormat {
    const val FORMAT = "reposhelf-app-list"
    const val VERSION = 1
    const val MAX_BYTES = 1024 * 1024
    /** Egy import legfeljebb ennyi bejegyzést tartalmazhat (a névtelen API-keret mellett ennél több nem is követhető érdemben). */
    const val MAX_ENTRIES = 500

    /** Az érvényes lista 4 szint mély (gyökér → apps → bejegyzés → source / settings); ennél jóval mélyebb fájl hibás. */
    private const val MAX_DEPTH = 8
    private val CATALOG_ID = Regex("^[a-z0-9][a-z0-9._-]{1,63}$")
    private val pretty = Json { prettyPrint = true }

    fun encode(entries: List<AppListEntry>, exportedAt: Instant): ByteArray {
        val root = buildJsonObject {
            put("format", FORMAT)
            put("version", VERSION)
            put("exportedAt", exportedAt.toString())
            put(
                "apps",
                buildJsonArray {
                    entries.forEach { e ->
                        add(
                            buildJsonObject {
                                put("source", buildJsonObject {
                                    put("provider", e.repo.provider.id)
                                    put("repository", e.repo.path)
                                    e.repositoryId?.let { put("repositoryId", it) }
                                })
                                e.catalogId?.let { put("catalogId", it) }
                                put("name", e.name)
                                put("followed", e.followed)
                                put("favorite", e.favorite)
                                e.overrides?.takeUnless { it.isEmpty }?.let { o ->
                                    put("settings", buildJsonObject {
                                        o.checkEnabled?.let { put("checkEnabled", it) }
                                        o.includePrereleases?.let { put("includePrereleases", it) }
                                        o.autoDownload?.let { put("autoDownload", it) }
                                        o.autoInstall?.let { put("autoInstall", it) }
                                        o.notify?.let { put("notify", it) }
                                        o.releaseDelayDays?.let { put("releaseDelayDays", it) }
                                    })
                                }
                            },
                        )
                    }
                },
            )
        }
        return (pretty.encodeToString(JsonObject.serializer(), root) + "\n").toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray): AppListDecodeResult {
        if (bytes.size > MAX_BYTES) return AppListDecodeResult.Invalid(AppListDecodeResult.Invalid.Reason.TOO_LARGE)
        val text = String(bytes, Charsets.UTF_8)
        // A túl mélyen egymásba ágyazott szerkezet a feldolgozó rekurzióját merítené ki: még a feldolgozás előtt elutasítjuk.
        if (exceedsDepth(text, MAX_DEPTH)) return AppListDecodeResult.Invalid(AppListDecodeResult.Invalid.Reason.MALFORMED)
        val root = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } ?: return AppListDecodeResult.Invalid(AppListDecodeResult.Invalid.Reason.MALFORMED)
        if (root.string("format") != FORMAT) return AppListDecodeResult.Invalid(AppListDecodeResult.Invalid.Reason.NOT_AN_APP_LIST)
        val version = (root["version"] as? JsonPrimitive)?.intOrNull ?: return AppListDecodeResult.Invalid(AppListDecodeResult.Invalid.Reason.MALFORMED)
        if (version != VERSION) return AppListDecodeResult.Invalid(AppListDecodeResult.Invalid.Reason.UNSUPPORTED_VERSION)
        val apps = root["apps"] as? JsonArray ?: return AppListDecodeResult.Invalid(AppListDecodeResult.Invalid.Reason.MALFORMED)
        if (apps.size > MAX_ENTRIES) return AppListDecodeResult.Invalid(AppListDecodeResult.Invalid.Reason.TOO_MANY_ENTRIES)

        val entries = mutableListOf<AppListEntry>()
        val skipped = mutableListOf<SkippedEntry>()
        val seen = HashSet<String>()
        apps.forEachIndexed { index, element ->
            when (val result = entry(element)) {
                is Result.Ok -> if (seen.add(result.entry.repo.key)) entries += result.entry else skipped += SkippedEntry(index, "duplicate")
                is Result.Bad -> skipped += SkippedEntry(index, result.reason)
            }
        }
        return AppListDecodeResult.Decoded(entries, skipped)
    }

    /** A JSON beágyazási mélysége meghaladja-e a korlátot (a karakterláncokon belüli zárójelek nem számítanak). */
    internal fun exceedsDepth(text: String, limit: Int): Boolean {
        var depth = 0
        var inString = false
        var escaped = false
        for (c in text) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{', '[' -> if (++depth > limit) return true
                '}', ']' -> depth--
            }
        }
        return false
    }

    private sealed interface Result {
        data class Ok(val entry: AppListEntry) : Result
        data class Bad(val reason: String) : Result
    }

    private fun entry(element: JsonElement): Result {
        val obj = element as? JsonObject ?: return Result.Bad("not an object")
        val source = obj["source"] as? JsonObject ?: return Result.Bad("source")
        val provider = source.string("provider")?.let(ProviderId::fromId) ?: return Result.Bad("provider")
        val repo = source.string("repository")?.let { RepoRef.parsePath(provider, it) } ?: return Result.Bad("repository")
        val repositoryId = when (val raw = source["repositoryId"]) {
            null, JsonNull -> null
            else -> (raw as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it > 0 } ?: return Result.Bad("repositoryId")
        }
        val catalogId = when (val raw = obj["catalogId"]) {
            null, JsonNull -> null
            else -> obj.string("catalogId")?.takeIf { CATALOG_ID.matches(it) } ?: return Result.Bad("catalogId")
        }
        val name = (obj.string("name") ?: repo.name).trim()
        // Vezérlő- és formázó karakter (pl. irányváltó, nulla szélességű) nem lehet a névben.
        if (name.isEmpty() || name.length > 100 || name.any { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }) return Result.Bad("name")
        val followed = obj.bool("followed") ?: return Result.Bad("followed")
        val favorite = obj.bool("favorite") ?: false
        if (!followed && !favorite) return Result.Bad("neither followed nor favorite")
        val overrides = when (val raw = obj["settings"]) {
            null, JsonNull -> null
            is JsonObject -> overrides(raw) ?: return Result.Bad("settings")
            else -> return Result.Bad("settings")
        }
        return Result.Ok(AppListEntry(repo, repositoryId, catalogId, name, followed, favorite, overrides))
    }

    private fun overrides(o: JsonObject): AppOverrides? {
        fun flag(key: String): Pair<Boolean, Boolean?> = when (val raw = o[key]) {
            null, JsonNull -> true to null
            else -> (raw as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull?.let { true to it } ?: (false to null)
        }
        val fields = listOf("checkEnabled", "includePrereleases", "autoDownload", "autoInstall", "notify").map(::flag)
        if (fields.any { !it.first }) return null
        val delay = when (val raw = o["releaseDelayDays"]) {
            null, JsonNull -> null
            else -> (raw as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf { it in 0..30 } ?: return null
        }
        return AppOverrides(fields[0].second, fields[1].second, fields[2].second, fields[3].second, fields[4].second, delay)
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
}
