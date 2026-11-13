// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.apk

import java.io.File
import java.io.IOException
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * Az aláírók adatai, SHA-256 tanúsítvány-ujjlenyomatként (kisbetűs hex).
 * [history]: a rotációs lánc a legrégebbitől az aktuálisig (rotáció nélkül = [current]).
 */
data class SignerInfo(val current: List<String>, val history: List<String>, val multipleSigners: Boolean)

/** Egy APK ténylegesen kiolvasott adatai (a technikailag érvényes aláírás után). */
data class ApkFacts(
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val minSdk: Int,
    val targetSdk: Int,
    /** Split APK esetén a split neve (pl. `config.arm64_v8a`); alap-APK-nál `null`. */
    val splitName: String?,
    val signers: SignerInfo,
)

sealed interface ApkInspection {
    data class Valid(val facts: ApkFacts) : ApkInspection

    /** Az APK nem olvasható, vagy az aláírása technikailag érvénytelen (1. szint). */
    data class Invalid(val reason: String) : ApkInspection
}

/**
 * Az APK kriptográfiai ellenőrzése és adatainak kiolvasása (D-031).
 * Androidon a platform saját verifierje (`PackageManager.getPackageArchiveInfo`), a JVM-tesztekben az apksig adja.
 */
fun interface ApkInspector {
    fun inspect(file: File): ApkInspection
}

/** A ZIP-szerkezet gyors, kicsomagolás nélküli vizsgálata (csak a központi könyvtár; C. 21. – zip-bomb, path traversal). */
data class ZipFacts(val entryCount: Int, val hasManifest: Boolean, val nativeAbis: Set<String>, val suspiciousEntry: String?)

object ZipInspector {
    private const val MAX_ENTRIES = 65_535
    private const val MAX_TOTAL_UNCOMPRESSED = 4L * 1024 * 1024 * 1024
    private val NATIVE_LIB = Regex("^lib/([^/]+)/[^/]+\\.so$")

    /** `null`, ha a fájl nem olvasható ZIP-ként. */
    fun inspect(file: File): ZipFacts? = try {
        ZipFile(file).use { zip ->
            var count = 0
            var total = 0L
            var hasManifest = false
            var suspicious: String? = null
            val abis = LinkedHashSet<String>()
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                count++
                if (count > MAX_ENTRIES) return ZipFacts(count, hasManifest, abis, "túl sok bejegyzés")
                val name = entry.name
                if (suspicious == null && (name.startsWith("/") || name.contains('\\') || name.split('/').any { it == ".." })) {
                    suspicious = name.take(100)
                }
                if (entry.size > 0) total += entry.size
                if (total > MAX_TOTAL_UNCOMPRESSED && suspicious == null) suspicious = "túl nagy kicsomagolt méret"
                if (name == "AndroidManifest.xml") hasManifest = true
                NATIVE_LIB.matchEntire(name)?.let { abis += it.groupValues[1] }
            }
            ZipFacts(count, hasManifest, abis, suspicious)
        }
    } catch (_: ZipException) {
        null
    } catch (_: IOException) {
        null
    } catch (_: RuntimeException) {
        // Hibás bejegyzésnév vagy szerkezet a megbízhatatlan állományban (pl. érvénytelen kódolás): nem érvényes APK.
        null
    } catch (_: RuntimeException) {
        // Hibás bejegyzésnév vagy szerkezet a megbízhatatlan állományban (pl. érvénytelen kódolás): nem érvényes APK.
        null
    }
}
