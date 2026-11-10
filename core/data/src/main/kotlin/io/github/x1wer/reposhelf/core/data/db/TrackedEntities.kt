// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

/**
 * Minden alkalmazás, amellyel a felhasználónak kapcsolata van: követett, kedvenc, saját hozzáadású vagy általunk
 * telepített (docs/ARCHITECTURE.md 4. pont, C. 9–10.). A „telepített” állapot igazságforrása a PackageManager, nem ez
 * a tábla; itt csak az `everInstalled` jelzés van, hogy a „már nem telepített” állapot felismerhető legyen.
 */
@Entity(
    tableName = "tracked_app",
    indices = [
        Index(value = ["repoKey"], unique = true),
        Index(value = ["provider", "repoId"], unique = true),
        Index(value = ["packageName"]),
        Index(value = ["catalogId"]),
    ],
)
data class TrackedAppEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** `CATALOG` (a katalógusból követve) vagy `USER` (saját hozzáadás URL-ből). */
    val origin: String,
    val catalogId: String?,
    val provider: String,
    val repoOwner: String,
    val repoName: String,
    /** `provider:owner/name` kisbetűvel (egyedi). */
    val repoKey: String,
    /** A szolgáltató stabil repó-azonosítója (C. 19.); az első sikeres lekéréskor rögzül. */
    val repoId: Long?,
    val repoOwnerId: Long?,
    val packageName: String?,
    val displayName: String,
    val isFollowed: Boolean,
    val isFavorite: Boolean,
    val installedViaUs: Boolean,
    val everInstalled: Boolean,
    val addedAtMillis: Long,
    /** Az utolsó LEFUTOTT ellenőrzés ideje; az átmeneti hiba (hálózat, rate limit) ezt nem írja felül. */
    val lastCheckAtMillis: Long?,
    /** `UpdateStatus` neve; `null`, ha még nem volt ellenőrzés. */
    val checkStatus: String?,
    val checkConfirmed: Boolean,
    /** Az utolsó átmeneti hiba (pl. `RATE_LIMITED`, `NETWORK`) és ideje; sikeres ellenőrzés törli. */
    val lastErrorKind: String?,
    val lastErrorAtMillis: Long?,
    /** `OK`, `RENAMED`, `OWNER_CHANGED`, `ID_CHANGED`, `NOT_FOUND`, `UNAVAILABLE`. */
    val repoState: String,
    val repoStateDetail: String?,
    val latestReleaseId: Long?,
    val latestTag: String?,
    val latestName: String?,
    val latestPublishedAtMillis: Long?,
    val latestIsPrerelease: Boolean,
    val latestHtmlUrl: String?,
    /** A kiadás, amelyből a jelenlegi verziót MI telepítettük (a verzió-azonosítás tartaléka). */
    val installedReleaseId: Long?,
    val installedReleasePublishedAtMillis: Long?,
    val installedReleaseVersionCode: Long?,
)

/** Alkalmazásonkénti felülírások; `null` = a globális beállítás érvényes (C. 12.6.). Az 5. munkamenet bővíti. */
@Entity(
    tableName = "app_settings_override",
    foreignKeys = [ForeignKey(entity = TrackedAppEntity::class, parentColumns = ["id"], childColumns = ["trackedAppId"], onDelete = ForeignKey.CASCADE)],
)
data class AppSettingsOverrideEntity(
    @PrimaryKey val trackedAppId: Long,
    val checkEnabled: Boolean?,
    val includePrereleases: Boolean?,
)

/**
 * Rögzített aláíró CSOMAGONKÉNT (C. 19.). Szándékosan nem a követett sorhoz kötött: a követés megszüntetése és
 * újbóli felvétele nem nullázhatja le az aláíró-folytonosságot.
 */
@Entity(tableName = "signer_pin")
data class SignerPinEntity(
    @PrimaryKey val packageName: String,
    /** Vesszővel elválasztott, kisbetűs SHA-256 ujjlenyomatok (az ellenőrzött rotációs láncból). */
    val certsSha256: String,
    /** `FIRST_INSTALL_VIA_US`, `EXISTING_INSTALL` vagy `CATALOG`. */
    val source: String,
    val pinnedAtMillis: Long,
    val lastVerifiedAtMillis: Long?,
)

/** Egy letöltött kiadási állomány vizsgálatának eredménye: ugyanazt az állományt nem kell újra letölteni (ARCH 9., 11.1.). */
@Entity(tableName = "apk_analysis", primaryKeys = ["provider", "assetId"], indices = [Index(value = ["repoKey"])])
data class ApkAnalysisEntity(
    val provider: String,
    val assetId: Long,
    /** A repó kulcsa (`provider:owner/name`): így a követés megszüntetése után is visszaállítható a csomagnév. */
    val repoKey: String,
    val releaseId: Long,
    val sha256: String,
    val packageName: String?,
    val versionCode: Long?,
    val versionName: String?,
    val signerSha256: String?,
    /** `OK` vagy `REJECTED`. */
    val verdict: String,
    /** Elutasításnál a kritikus megállapítások fajtái, vesszővel. */
    val reason: String?,
    val analyzedAtMillis: Long,
)

data class TrackedAppWithSettings(
    @Embedded val app: TrackedAppEntity,
    @Relation(parentColumn = "id", entityColumn = "trackedAppId") val override: AppSettingsOverrideEntity?,
)
