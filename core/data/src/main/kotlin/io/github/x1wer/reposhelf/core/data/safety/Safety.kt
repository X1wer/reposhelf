// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.data.safety

import io.github.x1wer.reposhelf.core.data.db.AppDatabase
import io.github.x1wer.reposhelf.core.data.db.SecurityWarningEntity
import io.github.x1wer.reposhelf.core.data.db.UpdateHistoryEntity
import io.github.x1wer.reposhelf.core.model.Severity
import io.github.x1wer.reposhelf.core.update.WarningCategory
import io.github.x1wer.reposhelf.core.update.WarningSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.Instant

/** Egy megjelenítendő biztonsági figyelmeztetés (C. 20.). */
data class SecurityWarning(
    val id: Long,
    val trackedAppId: Long?,
    val packageName: String?,
    val appName: String,
    val category: WarningCategory,
    val severity: Severity,
    val detail: String?,
    val firstSeenAt: Instant,
    val lastSeenAt: Instant,
    val acknowledged: Boolean,
)

/** Kit érint a figyelmeztetés vagy az előzmény (a követett sor, ha van; különben a csomag). */
data class AppRef(val trackedAppId: Long?, val packageName: String?, val appName: String) {
    val key: String get() = trackedAppId?.let { "t$it" } ?: "p:${packageName.orEmpty()}"
}

/**
 * Biztonsági figyelmeztetések (C. 20.): rögzítés deduplikációval, feloldás (ha az eset megszűnt), nyugtázás.
 * A nyugtázatlan figyelmeztetés vagy kritikus megállapítás megállítja az automatikus telepítést ([hasBlocking]).
 */
class SecurityWarnings(db: AppDatabase, private val clock: Clock = Clock.systemUTC()) {
    private val dao = db.safetyDao()

    /** A felület figyelmeztetés-listája: a nyitott, nyugtázatlan WARNING és CRITICAL (az INFO csak az adatlapon). */
    fun observeOpen(): Flow<List<SecurityWarning>> =
        dao.observeOpen().map { rows -> rows.mapNotNull { it.toModel() }.filter { it.severity >= Severity.WARNING } }

    /** Egy alkalmazás fennálló figyelmeztetései (minden súlyosság, a nyugtázottak is). */
    fun observeForApp(trackedAppId: Long): Flow<List<SecurityWarning>> = dao.observeForApp(trackedAppId).map { rows -> rows.mapNotNull { it.toModel() } }

    /** Mint [observeForApp], követett sor nélküli alkalmazásra (a RepoShelf saját frissítése). */
    fun observeFor(app: AppRef): Flow<List<SecurityWarning>> =
        app.trackedAppId?.let(::observeForApp) ?: dao.observeForPackage(app.packageName.orEmpty()).map { rows -> rows.mapNotNull { it.toModel() } }

    /**
     * Rögzítés. Ugyanaz az eset nem kerül be újra: csak az utolsó észlelés ideje frissül. Ha egy korábban feloldott eset
     * újra előfordul, ismét nyitott (és nyugtázatlan) lesz. Visszaadja az ÚJ (vagy újranyitott) figyelmeztetéseket.
     */
    suspend fun record(app: AppRef, specs: List<WarningSpec>): List<WarningSpec> {
        val now = clock.millis()
        val fresh = mutableListOf<WarningSpec>()
        for (spec in specs) {
            val key = "${app.key}:${spec.dedupSuffix}".take(MAX_KEY)
            val existing = dao.warningByKey(key)
            when {
                existing == null -> {
                    dao.insertWarning(
                        SecurityWarningEntity(
                            trackedAppId = app.trackedAppId, packageName = app.packageName, appName = app.appName.take(100),
                            category = spec.category.name, severity = spec.severity.name, dedupKey = key, detail = spec.detail?.take(200),
                            firstSeenAtMillis = now, lastSeenAtMillis = now, acknowledgedAtMillis = null, resolvedAtMillis = null,
                        ),
                    )
                    fresh += spec
                }
                existing.resolvedAtMillis != null -> {
                    dao.updateWarning(existing.copy(lastSeenAtMillis = now, resolvedAtMillis = null, acknowledgedAtMillis = null, severity = spec.severity.name))
                    fresh += spec
                }
                else -> dao.updateWarning(existing.copy(lastSeenAtMillis = now))
            }
        }
        return fresh
    }

    /** Az eset megszűnt (pl. a repó ismét elérhető): a figyelmeztetés feloldva, nem jelenik meg tovább. */
    suspend fun resolve(trackedAppId: Long, categories: Set<WarningCategory>) = resolve(AppRef(trackedAppId, null, ""), categories)

    suspend fun resolve(app: AppRef, categories: Set<WarningCategory>) {
        if (categories.isEmpty()) return
        val now = clock.millis()
        unresolved(app).filter { it.category in categories.map(WarningCategory::name) }
            .forEach { dao.updateWarning(it.copy(resolvedAtMillis = now)) }
    }

    /** A felhasználó tudomásul vette (pl. tulajdonosváltás): az automatikus telepítést tovább nem tartja vissza. */
    suspend fun acknowledge(id: Long) = dao.acknowledge(id, clock.millis())

    /** Van-e nyugtázatlan figyelmeztetés vagy kritikus eset, amely miatt nem telepítünk automatikusan. */
    suspend fun hasBlocking(trackedAppId: Long): Boolean = hasBlocking(AppRef(trackedAppId, null, ""))

    suspend fun hasBlocking(app: AppRef): Boolean = unresolved(app).any { row ->
        val severity = runCatching { Severity.valueOf(row.severity) }.getOrDefault(Severity.CRITICAL)
        severity == Severity.CRITICAL || (severity == Severity.WARNING && row.acknowledgedAtMillis == null)
    }

    private suspend fun unresolved(app: AppRef): List<SecurityWarningEntity> =
        app.trackedAppId?.let { dao.unresolvedForApp(it) } ?: dao.unresolvedForPackage(app.packageName.orEmpty())

    private fun SecurityWarningEntity.toModel(): SecurityWarning? {
        val category = runCatching { WarningCategory.valueOf(category) }.getOrNull() ?: return null
        val severity = runCatching { Severity.valueOf(severity) }.getOrNull() ?: return null
        return SecurityWarning(id, trackedAppId, packageName, appName, category, severity, detail, Instant.ofEpochMilli(firstSeenAtMillis), Instant.ofEpochMilli(lastSeenAtMillis), acknowledgedAtMillis != null)
    }

    private companion object {
        const val MAX_KEY = 300
    }
}

enum class HistoryOutcome { SUCCESS, FAILED, CANCELLED_BY_USER, BLOCKED }

/** Telepítési módszer (C. 15.). */
enum class InstallMethod {
    /** A felhasználó indította az adatlapról. */
    MANUAL,

    /** A háttérfeladat telepítette felhasználói beavatkozás nélkül. */
    AUTOMATIC,

    /** A háttérfeladat készítette elő, a felhasználó hagyta jóvá. */
    AUTOMATIC_PROMPTED,

    /** A RepoShelf saját frissítése. */
    SELF_UPDATE,
}

data class Version(val name: String?, val code: Long?)

data class HistoryEntry(
    val id: Long,
    val trackedAppId: Long?,
    val appName: String,
    val packageName: String?,
    val from: Version?,
    val to: Version?,
    val at: Instant,
    val outcome: HistoryOutcome,
    val failureCode: String?,
    val failureMessage: String?,
    val method: InstallMethod,
)

/** Frissítési előzmények (C. 15.): rögzítés minden úton, felső korláttal; érzékeny adat nélkül. */
class UpdateHistory(db: AppDatabase, private val clock: Clock = Clock.systemUTC()) {
    private val dao = db.safetyDao()

    fun observe(limit: Int = MAX_ENTRIES): Flow<List<HistoryEntry>> = dao.observeHistory(limit).map { rows -> rows.mapNotNull { it.toModel() } }

    suspend fun record(
        app: AppRef,
        from: Version?,
        to: Version?,
        outcome: HistoryOutcome,
        method: InstallMethod,
        failureCode: String? = null,
        failureMessage: String? = null,
    ) {
        dao.insertHistory(
            UpdateHistoryEntity(
                trackedAppId = app.trackedAppId, appName = app.appName.take(100), packageName = app.packageName,
                fromVersionName = from?.name?.take(100), fromVersionCode = from?.code, toVersionName = to?.name?.take(100), toVersionCode = to?.code,
                atMillis = clock.millis(), outcome = outcome.name, failureCode = failureCode?.take(60), failureMessage = sanitize(failureMessage),
                method = method.name,
            ),
        )
        dao.pruneHistory(MAX_ENTRIES)
    }

    private fun UpdateHistoryEntity.toModel(): HistoryEntry? {
        val outcome = runCatching { HistoryOutcome.valueOf(outcome) }.getOrNull() ?: return null
        val method = runCatching { InstallMethod.valueOf(method) }.getOrNull() ?: return null
        return HistoryEntry(
            id, trackedAppId, appName, packageName,
            if (fromVersionName == null && fromVersionCode == null) null else Version(fromVersionName, fromVersionCode),
            if (toVersionName == null && toVersionCode == null) null else Version(toVersionName, toVersionCode),
            Instant.ofEpochMilli(atMillis), outcome, failureCode, failureMessage, method,
        )
    }

    companion object {
        const val MAX_ENTRIES = 1000

        /** A rendszer hibaüzenetéből csak rövid, URL- és útvonalmentes szöveg marad (ne kerüljön bele érzékeny adat). */
        fun sanitize(message: String?): String? = message
            ?.take(MAX_RAW_MESSAGE)
            ?.replace(CONTROL_OR_FORMAT, " ")
            ?.replace(ANY_URL, "<url>")
            ?.replace(ABSOLUTE_PATH, "<path>")
            ?.trim()
            ?.take(200)
            ?.ifBlank { null }

        private const val MAX_RAW_MESSAGE = 1000

        /** Vezérlő- és formázó karakterek (sortörés, irányváltó, nulla szélességű): a megjelenített szöveget nem rendezhetik át. */
        private val CONTROL_OR_FORMAT = Regex("[\\p{Cc}\\p{Cf}]+")

        /** Bármilyen sémájú URL (http, https, content, file…), kis- és nagybetűtől függetlenül. */
        private val ANY_URL = Regex("(?i)\\b[a-z][a-z0-9+.-]{1,15}://\\S+")

        /** Abszolút útvonal bármely gyökér alatt (legalább egy könyvtárszinttel), nem csak a /data alatt. */
        private val ABSOLUTE_PATH = Regex("(?<![\\w<.])/(?:[\\w.@+~-]+/)+[\\w.@+~-]*")
    }
}
