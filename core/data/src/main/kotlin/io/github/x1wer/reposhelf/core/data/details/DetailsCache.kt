// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.data.details

import io.github.x1wer.reposhelf.core.data.db.AppDatabase
import io.github.x1wer.reposhelf.core.data.db.DetailsCacheEntity
import io.github.x1wer.reposhelf.core.model.ProviderId
import io.github.x1wer.reposhelf.core.model.ProviderRepoId
import io.github.x1wer.reposhelf.core.model.Readme
import io.github.x1wer.reposhelf.core.model.Release
import io.github.x1wer.reposhelf.core.model.ReleaseAsset
import io.github.x1wer.reposhelf.core.model.RepoMetadata
import io.github.x1wer.reposhelf.core.model.RepoRef
import io.github.x1wer.reposhelf.core.network.Validators
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.Instant

/** Egy tartósan tárolt lekérés (az adatlap offline megjelenítéséhez, C. 24.). */
class StoredDetails(val payload: String, val validators: Validators?, val fetchedAt: Instant)

/** Az adatlap tartós tárolója. A memóriabeli gyorsítótár mögött áll: indulás után és offline innen jön az adat. */
interface DetailsStore {
    suspend fun read(key: String): StoredDetails?
    suspend fun write(key: String, details: StoredDetails)
}

/** Room-alapú tároló felső korláttal (a legrégebben frissítettek törlődnek). */
class RoomDetailsStore(db: AppDatabase, private val maxEntries: Int = MAX_ENTRIES) : DetailsStore {
    private val dao = db.detailsCacheDao()

    override suspend fun read(key: String): StoredDetails? = dao.get(key)?.let {
        StoredDetails(it.payload, if (it.etag == null && it.lastModified == null) null else Validators(it.etag, it.lastModified), Instant.ofEpochMilli(it.fetchedAtMillis))
    }

    override suspend fun write(key: String, details: StoredDetails) {
        if (details.payload.length > MAX_PAYLOAD_CHARS) return
        dao.upsert(DetailsCacheEntity(key, details.payload, details.validators?.etag, details.validators?.lastModified, details.fetchedAt.toEpochMilli()))
        dao.prune(maxEntries)
    }

    companion object {
        /** Három bejegyzés adatlaponként: kb. 100 adatlap. */
        const val MAX_ENTRIES = 300
        private const val MAX_PAYLOAD_CHARS = 2 * 1024 * 1024
    }
}

/**
 * A modellek tárolt alakja. Külön DTO-k, hogy a modell osztályai szerializációs függőség nélkül maradjanak, és a tárolt
 * formátum változása ne a modellt érintse. Visszaolvasáskor minden érték újra validálódik (a [RepoRef] konstruktora).
 */
internal object DetailsCodecs {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    class Codec<T>(val encode: (T) -> String, val decode: (String) -> T?)

    private fun <D, T> codec(serializer: KSerializer<D>, to: (T) -> D, from: (D) -> T): Codec<T> = Codec(
        encode = { json.encodeToString(serializer, to(it)) },
        decode = { text ->
            try {
                from(json.decodeFromString(serializer, text))
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        },
    )

    val repo: Codec<RepoMetadata> = codec(RepoDto.serializer(), RepoDto::of, RepoDto::toModel)
    val releases: Codec<List<Release>> = codec(ListSerializer(ReleaseDto.serializer()), { list -> list.map(ReleaseDto::of) }, { list -> list.map(ReleaseDto::toModel) })
    val readme: Codec<Readme> = codec(ReadmeDto.serializer(), { ReadmeDto(it.markdown, it.path) }, { Readme(it.markdown, it.path) })

    @Serializable
    data class RepoDto(
        val provider: String, val owner: String, val name: String, val id: Long, val ownerId: Long? = null,
        val description: String? = null, val license: String? = null, val defaultBranch: String? = null, val htmlUrl: String? = null,
        val archived: Boolean = false, val disabled: Boolean = false, val fork: Boolean = false,
    ) {
        fun toModel() = RepoMetadata(
            RepoRef(requireNotNull(ProviderId.fromId(provider)), owner, name), ProviderRepoId(id), ownerId, description, license,
            defaultBranch, htmlUrl, archived, disabled, fork,
        )

        companion object {
            fun of(m: RepoMetadata) = RepoDto(
                m.ref.provider.id, m.ref.owner, m.ref.name, m.id.value, m.ownerId, m.description, m.licenseSpdx, m.defaultBranch, m.htmlUrl,
                m.isArchived, m.isDisabled, m.isFork,
            )
        }
    }

    @Serializable
    data class AssetDto(
        val id: Long, val name: String, val label: String? = null, val contentType: String? = null, val size: Long,
        val downloadUrl: String, val sha256: String? = null, val state: String? = null,
    )

    @Serializable
    data class ReleaseDto(
        val id: Long, val tag: String, val name: String? = null, val prerelease: Boolean = false, val draft: Boolean = false,
        val createdAt: Long? = null, val publishedAt: Long? = null, val body: String? = null, val htmlUrl: String? = null,
        val immutable: Boolean? = null, val assets: List<AssetDto> = emptyList(),
    ) {
        fun toModel() = Release(
            id, tag, name, prerelease, draft, createdAt?.let(Instant::ofEpochMilli), publishedAt?.let(Instant::ofEpochMilli), body, htmlUrl, immutable,
            assets.map { ReleaseAsset(it.id, it.name, it.label, it.contentType, it.size, it.downloadUrl, it.sha256, it.state) },
        )

        companion object {
            fun of(r: Release) = ReleaseDto(
                r.id, r.tagName, r.name, r.isPrerelease, r.isDraft, r.createdAt?.toEpochMilli(), r.publishedAt?.toEpochMilli(), r.body, r.htmlUrl,
                r.isImmutable, r.assets.map { AssetDto(it.id, it.name, it.label, it.contentType, it.size, it.downloadUrl, it.sha256, it.state) },
            )
        }
    }

    @Serializable
    data class ReadmeDto(val markdown: String, val path: String? = null)
}
