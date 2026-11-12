// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.catalog

import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** A katalógus-aláírás ellenőrzése (B. 7.8.: érvényes, hibás, hiányzó). A kulcsokat a teszt generálja. */
class CatalogSignatureTest {
    private fun keyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private val primary = keyPair()
    private val other = keyPair()
    private val catalog = """{"schemaVersion":1,"catalogSerial":5}""".toByteArray()

    private fun sign(pair: KeyPair, data: ByteArray): String =
        Base64.getEncoder().encodeToString(Signature.getInstance("SHA256withECDSA").run { initSign(pair.private); update(data); sign() })

    private fun sigFile(vararg signatures: Triple<String, String, String>): ByteArray =
        """{"format":1,"signatures":[${signatures.joinToString(",") { (id, alg, v) -> """{"keyId":"$id","algorithm":"$alg","value":"$v"}""" }}]}""".toByteArray()

    private val verifier = CatalogSignatureVerifier(listOf(TrustedCatalogKey("catalog-a", primary.public)))

    @Test
    fun validSignature_isAccepted() {
        assertEquals(CatalogSignatureCheck.Valid("catalog-a"), verifier.verify(catalog, sigFile(Triple("catalog-a", "ecdsa-p256-sha256", sign(primary, catalog)))))
    }

    @Test
    fun missingSignature_isRejected() {
        assertEquals(CatalogSignatureCheck.Missing, verifier.verify(catalog, null))
    }

    @Test
    fun tamperedContent_orForeignKey_isRejected() {
        val signature = sign(primary, catalog)
        val tampered = """{"schemaVersion":1,"catalogSerial":6}""".toByteArray()
        assertEquals(
            CatalogSignatureCheck.Invalid(CatalogSignatureCheck.Invalid.Reason.BAD_SIGNATURE),
            verifier.verify(tampered, sigFile(Triple("catalog-a", "ecdsa-p256-sha256", signature))),
        )
        // Más kulccsal készült aláírás a megbízható kulcs nevével: hamisítás.
        assertEquals(
            CatalogSignatureCheck.Invalid(CatalogSignatureCheck.Invalid.Reason.BAD_SIGNATURE),
            verifier.verify(catalog, sigFile(Triple("catalog-a", "ecdsa-p256-sha256", sign(other, catalog)))),
        )
        // Ismeretlen kulcs vagy algoritmus: nincs megbízható aláírás.
        assertEquals(
            CatalogSignatureCheck.Invalid(CatalogSignatureCheck.Invalid.Reason.NO_TRUSTED_SIGNATURE),
            verifier.verify(catalog, sigFile(Triple("attacker", "ecdsa-p256-sha256", sign(other, catalog)), Triple("catalog-a", "ed25519", "AAAA"))),
        )
    }

    @Test
    fun rotation_oneValidTrustedSignatureIsEnough() {
        val rotating = CatalogSignatureVerifier(listOf(TrustedCatalogKey("catalog-a", primary.public), TrustedCatalogKey("catalog-b", other.public)))
        val file = sigFile(Triple("catalog-a", "ecdsa-p256-sha256", "bm90LWEtc2lnbmF0dXJl"), Triple("catalog-b", "ecdsa-p256-sha256", sign(other, catalog)))
        assertEquals(CatalogSignatureCheck.Valid("catalog-b"), rotating.verify(catalog, file))
    }

    @Test
    fun malformedOrOversizedSignatureFile_isRejected() {
        listOf("nem json", """{"format":2,"signatures":[]}""", """{"format":1}""", """[1,2]""").forEach {
            assertIs<CatalogSignatureCheck.Invalid>(verifier.verify(catalog, it.toByteArray()), it)
        }
        assertEquals(
            CatalogSignatureCheck.Invalid(CatalogSignatureCheck.Invalid.Reason.MALFORMED),
            verifier.verify(catalog, ByteArray(CatalogSignatureVerifier.MAX_SIGNATURE_FILE + 1) { ' '.code.toByte() }),
        )
        assertEquals(
            CatalogSignatureCheck.Invalid(CatalogSignatureCheck.Invalid.Reason.BAD_SIGNATURE),
            verifier.verify(catalog, sigFile(Triple("catalog-a", "ecdsa-p256-sha256", "!!nem base64!!"))),
        )
    }

    @Test
    fun keySpec_isParsedStrictly() {
        val spki = Base64.getEncoder().encodeToString(primary.public.encoded)
        val keys = CatalogSignatureVerifier.parseKeys(" catalog-a:$spki , ")
        assertEquals(listOf("catalog-a"), keys.map { it.keyId })
        assertFailsWith<IllegalArgumentException> { CatalogSignatureVerifier.parseKeys("Bad Id:$spki") }
        val rsa = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        assertFailsWith<Exception> { CatalogSignatureVerifier.parseKeys("rsa-key:${Base64.getEncoder().encodeToString(rsa.public.encoded)}") }
    }
}
