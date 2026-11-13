// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.catalog

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.AlgorithmParameters
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** Egy megbízható, az alkalmazásba fordított nyilvános kulcs (docs/ARCHITECTURE.md 7.3.). */
class TrustedCatalogKey(val keyId: String, val publicKey: PublicKey)

sealed interface CatalogSignatureCheck {
    data class Valid(val keyId: String) : CatalogSignatureCheck

    /** Nincs aláírásfájl: a katalógus nem fogadható el. */
    data object Missing : CatalogSignatureCheck

    data class Invalid(val reason: Reason) : CatalogSignatureCheck {
        enum class Reason {
            /** Hibás vagy túl nagy aláírásfájl, ismeretlen formátum. */
            MALFORMED,

            /** Egyik aláírás sem ismert, megbízható kulccsal és támogatott algoritmussal készült. */
            NO_TRUSTED_SIGNATURE,

            /** Van megbízható kulccsal készült aláírás, de egyik sem érvényes (módosított tartalom). */
            BAD_SIGNATURE,
        }
    }
}

/**
 * A katalógus leválasztott aláírásának ellenőrzése (C. 3.2., docs/ARCHITECTURE.md 7.3., D-020).
 *
 * Formátum: `{"format":1,"signatures":[{"keyId","algorithm":"ecdsa-p256-sha256","value":"<base64 DER>"}]}`. A katalógus
 * akkor hiteles, ha legalább egy aláírás egy megbízható kulccsal érvényes a katalógusfájl PONTOS bájtjain. Az ismeretlen
 * algoritmusú vagy kulcsú aláírás kimarad (kulcsrotáció, későbbi algoritmus). A tartalom a GitHubon való elhelyezés
 * miatt önmagában nem megbízható.
 */
class CatalogSignatureVerifier(private val trustedKeys: List<TrustedCatalogKey>) {

    /** Megbízható-e (még) ez a kulcsazonosító: egy korábban elfogadott katalógus csak addig érvényes, amíg a kulcsa az. */
    fun trusts(keyId: String): Boolean = trustedKeys.any { it.keyId == keyId }

    fun verify(catalog: ByteArray, signatureFile: ByteArray?): CatalogSignatureCheck {
        if (signatureFile == null) return CatalogSignatureCheck.Missing
        if (signatureFile.size > MAX_SIGNATURE_FILE) return invalid(CatalogSignatureCheck.Invalid.Reason.MALFORMED)
        val root = try {
            Json.parseToJsonElement(String(signatureFile, Charsets.UTF_8)) as? JsonObject
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } ?: return invalid(CatalogSignatureCheck.Invalid.Reason.MALFORMED)
        if ((root["format"] as? JsonPrimitive)?.intOrNull != FORMAT) return invalid(CatalogSignatureCheck.Invalid.Reason.MALFORMED)
        val signatures = root["signatures"] as? JsonArray ?: return invalid(CatalogSignatureCheck.Invalid.Reason.MALFORMED)
        if (signatures.size > MAX_SIGNATURES) return invalid(CatalogSignatureCheck.Invalid.Reason.MALFORMED)

        var triedTrusted = false
        for (element in signatures) {
            val entry = element as? JsonObject ?: continue
            val algorithm = (entry["algorithm"] as? JsonPrimitive)?.contentOrNull
            val keyId = (entry["keyId"] as? JsonPrimitive)?.contentOrNull
            val value = (entry["value"] as? JsonPrimitive)?.contentOrNull
            if (algorithm != ALGORITHM || keyId == null || value == null) continue
            val key = trustedKeys.firstOrNull { it.keyId == keyId } ?: continue
            triedTrusted = true
            val der = try {
                Base64.getDecoder().decode(value)
            } catch (_: IllegalArgumentException) {
                continue
            }
            if (verifyOne(key.publicKey, catalog, der)) return CatalogSignatureCheck.Valid(keyId)
        }
        return invalid(if (triedTrusted) CatalogSignatureCheck.Invalid.Reason.BAD_SIGNATURE else CatalogSignatureCheck.Invalid.Reason.NO_TRUSTED_SIGNATURE)
    }

    private fun verifyOne(key: PublicKey, data: ByteArray, der: ByteArray): Boolean = try {
        Signature.getInstance(JCA_ALGORITHM).run {
            initVerify(key)
            update(data)
            verify(der)
        }
    } catch (_: GeneralSecurityException) {
        false
    }

    private fun invalid(reason: CatalogSignatureCheck.Invalid.Reason) = CatalogSignatureCheck.Invalid(reason)

    companion object {
        const val FORMAT = 1
        const val ALGORITHM = "ecdsa-p256-sha256"
        private const val JCA_ALGORITHM = "SHA256withECDSA"
        const val MAX_SIGNATURE_FILE = 16 * 1024
        private const val MAX_SIGNATURES = 8
        private val KEY_ID = Regex("^[a-z0-9][a-z0-9._-]{1,63}$")

        /**
         * A buildbe fordított kulcslista (`BuildConfig.CATALOG_PUBLIC_KEYS`): `keyId:base64(SubjectPublicKeyInfo)`
         * elemek vesszővel elválasztva. Csak P-256 EC-kulcs fogadható el; hibás elemnél kivétel (build-hiba, nem csendes).
         */
        fun parseKeys(spec: String): List<TrustedCatalogKey> {
            val keys = spec.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { item ->
                val keyId = item.substringBefore(':')
                require(KEY_ID.matches(keyId) && item.contains(':')) { "Érvénytelen katalóguskulcs-azonosító: $keyId" }
                val der = Base64.getDecoder().decode(item.substringAfter(':'))
                val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))
                require(key is ECPublicKey && isP256(key)) { "Csak P-256 (secp256r1) kulcs fogadható el: $keyId" }
                TrustedCatalogKey(keyId, key)
            }
            require(keys.map { it.keyId }.toSet().size == keys.size) { "Ismétlődő katalóguskulcs-azonosító" }
            return keys
        }

        private val P256: ECParameterSpec by lazy {
            AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }.getParameterSpec(ECParameterSpec::class.java)
        }

        /** A kulcs pontosan a secp256r1 görbén van-e (a 256 bites mezőméret önmagában más görbére is igaz lenne). */
        internal fun isP256(key: ECPublicKey): Boolean {
            val params = key.params
            return params.curve == P256.curve && params.generator == P256.generator && params.order == P256.order && params.cofactor == P256.cofactor
        }
    }
}
