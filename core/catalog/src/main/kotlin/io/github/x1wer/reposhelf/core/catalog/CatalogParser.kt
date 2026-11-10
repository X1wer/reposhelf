// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.catalog

import io.github.x1wer.reposhelf.core.model.Catalog
import io.github.x1wer.reposhelf.core.model.CatalogApp
import io.github.x1wer.reposhelf.core.model.CatalogCategory
import io.github.x1wer.reposhelf.core.model.ProviderId
import io.github.x1wer.reposhelf.core.model.ProviderRepoId
import io.github.x1wer.reposhelf.core.model.RepoRef
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.regex.PatternSyntaxException

sealed interface CatalogParseResult {
    /** Érvényes katalógus. A [rejected] a kihagyott, hibás bejegyzések listája (a teljes katalógus ettől még érvényes). */
    data class Parsed(val catalog: Catalog, val rejected: List<RejectedEntry>) : CatalogParseResult

    /** A katalógus újabb fő sémaverziójú, mint amit ez a kliens ismer (C. 3.1.: „frissítsd az appot”). */
    data class UnsupportedSchema(val schemaVersion: Int) : CatalogParseResult

    data class Invalid(val reason: InvalidReason, val detail: String? = null) : CatalogParseResult
}

enum class InvalidReason { MALFORMED_JSON, MISSING_FIELD, BAD_HEADER, TOO_MANY_ENTRIES, TOO_MANY_INVALID_ENTRIES }

data class RejectedEntry(val index: Int, val id: String?, val reason: String)

/**
 * A katalógus (docs/ARCHITECTURE.md 7.1–7.2.) szigorú, hibatűrő feldolgozása:
 * - a fejléc (`schemaVersion`, `catalogSerial`, `generatedAt`, `expiresAt`) kötelező;
 * - minden bejegyzés külön validálódik, a hibás bejegyzés kimarad, és naplózásra kerül;
 * - ha a hibás bejegyzések aránya [maxInvalidRatio] fölött van, az egész katalógus elutasítva (az előző marad);
 * - az ismeretlen mezőket figyelmen kívül hagyja (visszafelé kompatibilis bővíthetőség).
 *
 * A katalógus tartalma megbízhatatlan (C. 17.), és a katalógusban szereplés nem biztonsági tanúsítás (C. 3.3.).
 */
class CatalogParser(
    private val maxApps: Int = 20_000,
    private val maxInvalidRatio: Double = 0.2,
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    fun parse(bytes: ByteArray): CatalogParseResult {
        val root: JsonObject = try {
            Json.parseToJsonElement(String(bytes, Charsets.UTF_8)) as? JsonObject
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } ?: return CatalogParseResult.Invalid(InvalidReason.MALFORMED_JSON)

        val schemaVersion = root.primitive("schemaVersion")?.intOrNull
            ?: return CatalogParseResult.Invalid(InvalidReason.MISSING_FIELD, "schemaVersion")
        if (schemaVersion > SUPPORTED_SCHEMA_VERSION) return CatalogParseResult.UnsupportedSchema(schemaVersion)
        if (schemaVersion < 1) return CatalogParseResult.Invalid(InvalidReason.BAD_HEADER, "schemaVersion")

        val serial = root.primitive("catalogSerial")?.longOrNull
            ?: return CatalogParseResult.Invalid(InvalidReason.MISSING_FIELD, "catalogSerial")
        if (serial < 1) return CatalogParseResult.Invalid(InvalidReason.BAD_HEADER, "catalogSerial")
        val generatedAt = root.instant("generatedAt")
            ?: return CatalogParseResult.Invalid(InvalidReason.MISSING_FIELD, "generatedAt")
        val expiresAt = root.instant("expiresAt")
            ?: return CatalogParseResult.Invalid(InvalidReason.MISSING_FIELD, "expiresAt")
        if (!expiresAt.isAfter(generatedAt)) return CatalogParseResult.Invalid(InvalidReason.BAD_HEADER, "expiresAt")

        val entries = root["apps"] as? JsonArray ?: return CatalogParseResult.Invalid(InvalidReason.MISSING_FIELD, "apps")
        if (entries.size > maxApps) return CatalogParseResult.Invalid(InvalidReason.TOO_MANY_ENTRIES, entries.size.toString())

        val accepted = ArrayList<CatalogApp>(entries.size)
        val rejected = ArrayList<RejectedEntry>()
        val seenIds = HashSet<String>()
        val seenRepos = HashSet<String>()
        entries.forEachIndexed { index, element ->
            when (val result = validateEntry(element)) {
                is EntryResult.Rejected -> rejected += RejectedEntry(index, result.id, result.reason)
                is EntryResult.Ok -> {
                    val app = result.app
                    when {
                        !seenIds.add(app.id) -> rejected += RejectedEntry(index, app.id, "duplikált azonosító")
                        !seenRepos.add(app.repo.key) -> rejected += RejectedEntry(index, app.id, "duplikált repó: ${app.repo.path}")
                        else -> accepted += app
                    }
                }
            }
        }

        if (entries.isNotEmpty() && (accepted.isEmpty() || rejected.size > entries.size * maxInvalidRatio)) {
            return CatalogParseResult.Invalid(
                InvalidReason.TOO_MANY_INVALID_ENTRIES,
                "${rejected.size}/${entries.size} hibás bejegyzés",
            )
        }

        val catalog = Catalog(
            schemaVersion = schemaVersion,
            serial = serial,
            generatedAt = generatedAt,
            expiresAt = expiresAt,
            apps = accepted,
            contentSha256 = sha256Hex(bytes),
        )
        return CatalogParseResult.Parsed(catalog, rejected)
    }

    private sealed interface EntryResult {
        data class Ok(val app: CatalogApp) : EntryResult
        data class Rejected(val id: String?, val reason: String) : EntryResult
    }

    private fun validateEntry(element: JsonElement): EntryResult {
        val rawId = (element as? JsonObject)?.primitive("id")?.contentOrNull?.take(80)
        val dto = try {
            json.decodeFromJsonElement(CatalogEntryDto.serializer(), element)
        } catch (_: SerializationException) {
            return EntryResult.Rejected(rawId, "hibás szerkezet vagy hiányzó kötelező mező")
        } catch (_: IllegalArgumentException) {
            return EntryResult.Rejected(rawId, "hibás szerkezet")
        }
        fun reject(reason: String) = EntryResult.Rejected(rawId, reason)

        if (!ID_PATTERN.matches(dto.id)) return reject("érvénytelen azonosító")
        val name = dto.name.trim()
        if (name.isEmpty() || name.length > MAX_NAME || name.any { it.isISOControl() }) return reject("érvénytelen név")

        val provider = ProviderId.fromId(dto.source.provider) ?: return reject("nem támogatott szolgáltató: ${dto.source.provider.take(20)}")
        val repo = RepoRef.parsePath(provider, dto.source.repository) ?: return reject("érvénytelen repó-útvonal")
        val repositoryId = dto.source.repositoryId?.let { if (it > 0) ProviderRepoId(it) else return reject("érvénytelen repositoryId") }

        val packageName = dto.packageName?.let {
            if (it.length <= MAX_PACKAGE && PACKAGE_PATTERN.matches(it)) it else return reject("érvénytelen packageName")
        }
        if (dto.signingCertSha256.size > MAX_CERTS) return reject("túl sok signingCertSha256")
        if (dto.signingCertSha256.any { !SHA256_PATTERN.matches(it) }) {
            return reject("a signingCertSha256 kisbetűs, 64 jegyű hex legyen")
        }
        val minVersionCode = dto.minVersionCode?.let { if (it >= 0) it else return reject("negatív minVersionCode") }

        if (dto.keywords.size > MAX_KEYWORDS) return reject("túl sok kulcsszó")
        val keywords = dto.keywords.map { it.trim() }
        if (keywords.any { it.isEmpty() || it.length > MAX_KEYWORD || it.any(Char::isISOControl) }) return reject("érvénytelen kulcsszó")

        val assetFilter = dto.assetFilter?.let {
            if (it.length > MAX_ASSET_FILTER) return reject("túl hosszú assetFilter")
            try {
                Regex(it)
            } catch (_: PatternSyntaxException) {
                return reject("érvénytelen assetFilter")
            }
            it
        }

        return EntryResult.Ok(
            CatalogApp(
                id = dto.id,
                name = name,
                repo = repo,
                repositoryId = repositoryId,
                packageName = packageName,
                signingCertSha256 = dto.signingCertSha256.distinct(),
                minVersionCode = minVersionCode,
                keywords = keywords.distinct(),
                category = CatalogCategory.fromId(dto.category),
                assetFilter = assetFilter,
            ),
        )
    }

    private fun JsonObject.primitive(key: String): JsonPrimitive? = this[key] as? JsonPrimitive

    private fun JsonObject.instant(key: String): Instant? = try {
        primitive(key)?.takeIf { it.isString }?.content?.let(Instant::parse)
    } catch (_: DateTimeParseException) {
        null
    }

    companion object {
        const val SUPPORTED_SCHEMA_VERSION = 1
        private val ID_PATTERN = Regex("^[a-z0-9][a-z0-9._-]{1,63}$")
        private val PACKAGE_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
        private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
        private const val MAX_NAME = 100
        private const val MAX_PACKAGE = 255
        private const val MAX_CERTS = 10
        private const val MAX_KEYWORDS = 30
        private const val MAX_KEYWORD = 40
        private const val MAX_ASSET_FILTER = 200

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
