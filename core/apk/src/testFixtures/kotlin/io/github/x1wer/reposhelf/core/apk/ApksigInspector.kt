// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.apk

import com.android.apksig.apk.ApkUtils
import com.android.apksig.internal.apk.AndroidBinXmlParser
import com.android.apksig.util.DataSources
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest
import com.android.apksig.ApkVerifier as ApksigVerifier

/**
 * JVM-es [ApkInspector] az apksig könyvtárral – csak tesztekhez (D-031). Az appban a platform verifierje dolgozik.
 * Az apksig valódi kriptográfiai ellenőrzést végez (v1/v2/v3), és visszaadja a rotációs láncot.
 */
class ApksigInspector : ApkInspector {

    override fun inspect(file: File): ApkInspection = try {
        val result = ApksigVerifier.Builder(file).build().verify()
        if (!result.isVerified) {
            ApkInspection.Invalid(result.errors.joinToString().take(200).ifEmpty { "nem ellenőrizhető aláírás" })
        } else {
            val current = result.signerCertificates.map { sha256(it.encoded) }
            val lineage = result.signingCertificateLineage?.certificatesInLineage?.map { sha256(it.encoded) }
            val manifest = RandomAccessFile(file, "r").use { ApkUtils.getAndroidManifest(DataSources.asDataSource(it)) }
            val facts = parseManifest(manifest.duplicate())
            ApkInspection.Valid(
                ApkFacts(
                    packageName = facts.packageName,
                    versionCode = facts.versionCode,
                    versionName = facts.versionName,
                    minSdk = ApkUtils.getMinSdkVersionFromBinaryAndroidManifest(manifest.duplicate()),
                    targetSdk = ApkUtils.getTargetSdkVersionFromBinaryAndroidManifest(manifest.duplicate()),
                    splitName = facts.split,
                    signers = SignerInfo(current = current, history = lineage ?: current, multipleSigners = current.size > 1),
                ),
            )
        }
    } catch (e: Exception) {
        ApkInspection.Invalid(e.javaClass.simpleName)
    }

    private data class ManifestFacts(val packageName: String, val versionCode: Long, val versionName: String?, val split: String?)

    private fun parseManifest(buffer: ByteBuffer): ManifestFacts {
        val parser = AndroidBinXmlParser(buffer)
        while (parser.eventType != AndroidBinXmlParser.EVENT_END_DOCUMENT) {
            if (parser.eventType == AndroidBinXmlParser.EVENT_START_ELEMENT && parser.depth == 1 && parser.name == "manifest") {
                var pkg: String? = null
                var versionCode = 0L
                var versionName: String? = null
                var split: String? = null
                for (i in 0 until parser.attributeCount) {
                    when {
                        parser.getAttributeName(i) == "package" -> pkg = parser.getAttributeStringValue(i)
                        parser.getAttributeName(i) == "split" -> split = parser.getAttributeStringValue(i)
                        parser.getAttributeNameResourceId(i) == VERSION_CODE_ATTR -> versionCode = parser.getAttributeIntValue(i).toLong()
                        parser.getAttributeNameResourceId(i) == VERSION_NAME_ATTR -> versionName = parser.getAttributeStringValue(i)
                    }
                }
                return ManifestFacts(checkNotNull(pkg), versionCode, versionName, split)
            }
            parser.next()
        }
        error("nincs manifest elem")
    }

    private companion object {
        const val VERSION_CODE_ATTR = 0x0101021b
        const val VERSION_NAME_ATTR = 0x0101021c

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
