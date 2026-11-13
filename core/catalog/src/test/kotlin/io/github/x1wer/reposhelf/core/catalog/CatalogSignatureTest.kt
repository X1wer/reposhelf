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

    // ---- CAT-13: csak secp256r1 kulcs; CAT-03: a kulcs megbízhatósága lekérdezhető ----

    private fun spec(id: String, pair: KeyPair) = "$id:" + Base64.getEncoder().encodeToString(pair.public.encoded)

    @Test
    fun keyList_rejectsDuplicateIds_andReportsTrust() {
        val keys = CatalogSignatureVerifier.parseKeys(spec("catalog-a", primary) + "," + spec("catalog-b", other))
        assertEquals(listOf("catalog-a", "catalog-b"), keys.map { it.keyId })
        assertFailsWith<IllegalArgumentException> { CatalogSignatureVerifier.parseKeys(spec("catalog-a", primary) + "," + spec("catalog-a", other)) }
        val trusting = CatalogSignatureVerifier(keys)
        assertEquals(true, trusting.trusts("catalog-b"))
        assertEquals(false, trusting.trusts("catalog-c"))
        assertEquals(false, trusting.trusts(""))
    }

    @Test
    fun onlyTheP256Curve_isAccepted_notJustA256BitField() {
        assertEquals(true, CatalogSignatureVerifier.isP256(primary.public as java.security.interfaces.ECPublicKey))
        // secp256k1: szintén 256 bites mező, de más görbe (a korábbi, csak mezőméretet néző ellenőrzés átengedte volna).
        fun hex(value: String) = java.math.BigInteger(value, 16)
        val k1 = java.security.spec.ECParameterSpec(
            java.security.spec.EllipticCurve(
                java.security.spec.ECFieldFp(hex("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F")), java.math.BigInteger.ZERO, java.math.BigInteger.valueOf(7),
            ),
            java.security.spec.ECPoint(
                hex("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798"), hex("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8"),
            ),
            hex("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141"), 1,
        )
        val foreign = object : java.security.interfaces.ECPublicKey {
            override fun getParams() = k1
            override fun getW() = k1.generator
            override fun getAlgorithm() = "EC"
            override fun getFormat() = "X.509"
            override fun getEncoded() = ByteArray(0)
        }
        assertEquals(256, foreign.params.curve.field.fieldSize)
        assertEquals(false, CatalogSignatureVerifier.isP256(foreign))
        // P-384 kulcs a kulcslistában: elutasítva.
        val p384 = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp384r1")) }.generateKeyPair()
        assertFailsWith<IllegalArgumentException> { CatalogSignatureVerifier.parseKeys(spec("catalog-x", p384)) }
    }
}
