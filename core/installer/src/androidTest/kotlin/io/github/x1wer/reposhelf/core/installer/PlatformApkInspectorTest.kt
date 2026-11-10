// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.installer

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.x1wer.reposhelf.core.apk.ApkFacts
import io.github.x1wer.reposhelf.core.apk.ApkInspection
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A platform-verifier (D-031) valódi készüléken / emulátoron: ugyanazokkal a teszt-kulccsal aláírt APK-kkal, mint a
 * `core:apk` JVM-tesztjei. Az elvárt tanúsítvány-ujjlenyomatok független forrásból (apksigner) származnak.
 * Minden támogatott API-szinten futtatandó (legalább 28 és a legújabb); a 28-as szinten ez fogta meg a D-071 hibát.
 */
@RunWith(AndroidJUnit4::class)
class PlatformApkInspectorTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val inspector = PlatformApkInspector(instrumentation.targetContext)

    private fun fixture(name: String): File {
        val out = File(instrumentation.targetContext.cacheDir, "fixtures/$name")
        out.parentFile!!.mkdirs()
        instrumentation.context.assets.open("fixtures/$name").use { input -> out.outputStream().use { input.copyTo(it) } }
        return out
    }

    private fun inspect(name: String): ApkInspection =
        inspector.inspect(fixture(name)).also { Log.i(TAG, "API ${Build.VERSION.SDK_INT} $name -> $it") }

    private fun valid(name: String): ApkFacts = when (val result = inspect(name)) {
        is ApkInspection.Valid -> result.facts
        else -> fail("$name: érvényesnek kellett volna lennie, de: $result")
    }

    @Test
    fun validApk_factsAndSignerMatchApksigner() {
        val facts = valid("fixture-v2-keyA.apk")
        assertEquals("io.github.x1wer.fixture", facts.packageName)
        assertEquals(2L, facts.versionCode)
        assertEquals("2.0", facts.versionName)
        assertEquals(28, facts.minSdk)
        assertEquals(37, facts.targetSdk)
        assertNull(facts.splitName)
        assertEquals(listOf(CERT_A), facts.signers.current)
        assertEquals(listOf(CERT_A), facts.signers.history)
        assertFalse(facts.signers.multipleSigners)

        assertEquals(listOf(CERT_B), valid("fixture-v2-keyB.apk").signers.current)
        assertEquals("io.github.x1wer.other", valid("other-v2-keyA.apk").packageName)
    }

    @Test
    fun keyRotation_lineageIsVisible() {
        val facts = valid("fixture-v3-rotatedAtoB.apk")
        assertEquals(listOf(CERT_B), facts.signers.current)
        assertEquals(listOf(CERT_A, CERT_B), facts.signers.history)
    }

    @Test
    fun tamperedOrTruncatedApk_isRejected() {
        assertIs<ApkInspection.Invalid>(inspect("fixture-v2-tampered.apk"))
        assertIs<ApkInspection.Invalid>(inspect("fixture-v2-truncated.apk"))
    }

    @Test
    fun twoSigners_areReportedAsMultiple() {
        val signers = valid("fixture-v2-twoSigners.apk").signers
        assertTrue(signers.multipleSigners)
        assertEquals(setOf(CERT_A, CERT_B), signers.current.toSet())
    }

    @Test
    fun tooHighMinSdk_isNeverAcceptedSilently() {
        // A rendszer vagy elutasítja az elemzést, vagy jelzi a minSdk-t (a vizsgálat ekkor blokkol).
        when (val result = inspect("fixture-v2-minsdk99-keyA.apk")) {
            is ApkInspection.Invalid -> Unit
            is ApkInspection.Valid -> assertEquals(99, result.facts.minSdk)
        }
    }

    @Test
    fun splitApk_isNeverMistakenForBaseApk() {
        assertNull(valid("fixture-split-base-v4-keyA.apk").splitName)
        // Android 9-en (API 28, emulátoron ellenőrizve) a rendszer az önálló split-APK-t nem dolgozza fel: ekkor a
        // split-készletet a vizsgálat biztonságosan elutasítja. Ha feldolgozza, a split nevének vissza kell jönnie.
        // Tilos: érvényes eredmény split-név nélkül (azt alap-APK-nak tekintenénk).
        when (val result = inspect("fixture-split-config.arm64_v8a-v4-keyA.apk")) {
            is ApkInspection.Invalid -> Unit
            is ApkInspection.Valid -> {
                assertEquals("config.arm64_v8a", result.facts.splitName)
                assertEquals(listOf(CERT_A), result.facts.signers.current)
            }
        }
    }

    private companion object {
        const val TAG = "PlatformApkInspectorTest"
        const val CERT_A = "204fb1276af1d0c8cee4ac8628d6e51d9617180fd305fbaae87c9a5787687de2"
        const val CERT_B = "8bfcb3f58a08e2b1d028c374e97ba110f84035e82b1d1fe87589a7443ff4f06a"
    }
}
