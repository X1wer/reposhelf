// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.apk

import io.github.x1wer.reposhelf.core.model.ReleaseAsset

/** Egy kiadási állomány fajtája, a neve (és a tartalomtípusa) alapján. */
enum class AssetKind {
    APK,

    /** Android App Bundle: közvetlenül nem telepíthető (C. 7.2.). */
    APP_BUNDLE,

    /** Split-archívum (.apks, .xapk, .apkm): nem szabványos formátum, ezért nem támogatott (D-030). */
    SPLIT_ARCHIVE,

    /** Minden más (forráskód-archívum, checksum-fájl, aláírás stb.). */
    OTHER,
}

object AssetClassifier {
    private const val APK_MIME = "application/vnd.android.package-archive"

    fun classify(asset: ReleaseAsset): AssetKind = classify(asset.name, asset.contentType)

    fun classify(name: String, contentType: String?): AssetKind {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".apk") -> AssetKind.APK
            lower.endsWith(".aab") -> AssetKind.APP_BUNDLE
            lower.endsWith(".apks") || lower.endsWith(".xapk") || lower.endsWith(".apkm") -> AssetKind.SPLIT_ARCHIVE
            // A név a mérvadó; a tartalomtípus csak akkor számít, ha a név nem árulkodó, de a kiterjesztés hiányzik.
            !lower.contains('.') && contentType.equals(APK_MIME, ignoreCase = true) -> AssetKind.APK
            else -> AssetKind.OTHER
        }
    }
}

/** A fájlnévből kikövetkeztetett CPU-architektúra (előzetes becslés; a végleges a letöltött APK `lib/` mappájából jön). */
sealed interface AbiTag {
    data class Specific(val abis: Set<String>) : AbiTag
    data object Universal : AbiTag
    data object Unknown : AbiTag
}

object AbiDetector {
    const val ARM64 = "arm64-v8a"
    const val ARMV7 = "armeabi-v7a"
    const val X86 = "x86"
    const val X86_64 = "x86_64"

    // A sorrend fontos: a hosszabb, specifikusabb minták előbb (x86_64 az x86 előtt, arm64 az arm előtt).
    private val PATTERNS: List<Pair<Regex, String>> = listOf(
        Regex("(^|[^a-z0-9])(x86[_-]64|amd64|x64)([^a-z0-9]|$)") to X86_64,
        Regex("(^|[^a-z0-9])(arm64[_-]v8a|arm64|aarch64|armv8a?)([^a-z0-9]|$)") to ARM64,
        Regex("(^|[^a-z0-9])(armeabi[_-]v7a|armeabi|armv7a?|arm[_-]?v7a?|arm32|armhf)([^a-z0-9]|$)") to ARMV7,
        Regex("(^|[^a-z0-9])(x86|i[36]86|ia32)([^a-z0-9]|$)") to X86,
    )
    private val UNIVERSAL = Regex("(^|[^a-z0-9])(universal|fat|all[_-]?abis?|noarch)([^a-z0-9]|$)")

    fun detect(fileName: String): AbiTag {
        val name = fileName.lowercase().removeSuffix(".apk")
        if (UNIVERSAL.containsMatchIn(name)) return AbiTag.Universal
        val found = LinkedHashSet<String>()
        var rest = name
        for ((pattern, abi) in PATTERNS) {
            if (pattern.containsMatchIn(rest)) {
                found += abi
                // A már felismert részt kivesszük, hogy pl. az „x86_64” ne illeszkedjen az x86-ra is.
                rest = pattern.replace(rest, " ")
            }
        }
        return if (found.isEmpty()) AbiTag.Unknown else AbiTag.Specific(found)
    }

    /** Az ABI-azonosító normalizálása (a split-nevekben `arm64_v8a` alak szerepel). */
    fun normalize(abi: String): String = abi.lowercase().replace('_', '-').let { if (it == "x86-64") X86_64 else it }
}
