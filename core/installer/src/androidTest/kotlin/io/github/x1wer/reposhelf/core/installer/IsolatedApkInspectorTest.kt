// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.installer

import android.app.ActivityManager
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Messenger
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.x1wer.reposhelf.core.apk.ApkInspection
import io.github.x1wer.reposhelf.core.apk.ApkInspector
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/** Soha nem válaszol: a megakadt csomagelemzőt utánozza. */
class HangingInspectionService : Service() {
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { true })
    override fun onBind(intent: Intent?): IBinder = messenger.binder
}

/** Az első kérésre leállítja a saját folyamatát: az összeomló csomagelemzőt utánozza. */
class CrashingInspectionService : Service() {
    private val messenger = Messenger(
        Handler(Looper.getMainLooper()) {
            Process.killProcess(Process.myPid())
            true
        },
    )
    override fun onBind(intent: Intent?): IBinder = messenger.binder
}

/**
 * A külön folyamatban futó APK-elemzés (D-031) valódi eszközön / emulátoron: ugyanazt az eredményt adja, mint a
 * folyamaton belüli elemzés; a megakadt vagy összeomló elemző nem állítja meg a hívót, és az állomány ilyenkor
 * érvénytelennek számít (soha nem érvényesnek).
 */
@RunWith(AndroidJUnit4::class)
class IsolatedApkInspectorTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val inProcess = PlatformApkInspector(context)
    private val neverFallback = ApkInspector { fail("A folyamaton belüli tartalék nem futhat, ha a szolgáltatás elérhető") }

    private fun fixture(name: String): File {
        val out = File(context.cacheDir, "fixtures/$name")
        out.parentFile!!.mkdirs()
        instrumentation.context.assets.open("fixtures/$name").use { input -> out.outputStream().use { input.copyTo(it) } }
        return out
    }

    private fun runningProcesses(): List<String> =
        context.getSystemService(ActivityManager::class.java).runningAppProcesses.orEmpty().map { it.processName }

    @Test
    fun isolatedResult_equalsInProcessResult_forEveryFixture() {
        val isolated = IsolatedApkInspector(context, fallback = neverFallback)
        val names = listOf(
            "fixture-v2-keyA.apk", "fixture-v2-keyB.apk", "other-v2-keyA.apk", "fixture-v1-keyA.apk", "fixture-v2-arm64-keyA.apk",
            "fixture-v3-rotatedAtoB.apk", "fixture-v2-twoSigners.apk", "fixture-v2-tampered.apk", "fixture-v2-truncated.apk",
            "fixture-v2-minsdk99-keyA.apk", "fixture-split-base-v4-keyA.apk", "fixture-split-config.arm64_v8a-v4-keyA.apk",
        )
        for (name in names) {
            val file = fixture(name)
            assertEquals(inProcess.inspect(file), isolated.inspect(file), name)
        }
        assertIs<ApkInspection.Valid>(isolated.inspect(fixture("fixture-v2-keyA.apk")))
        assertIs<ApkInspection.Invalid>(isolated.inspect(fixture("fixture-v2-tampered.apk")))
        // Az elemzés tényleg a segédfolyamatban futott.
        assertTrue(runningProcesses().any { it.endsWith(ApkInspectionService.PROCESS_SUFFIX) }, runningProcesses().toString())
        assertFalse(ApkInspectionService.isInspectionProcess())
    }

    @Test
    fun fileOutsideOwnStorage_isRefusedByTheService() {
        // Érvényes, aláírt rendszer-APK, de nem a saját tárhelyünkön van: a segédfolyamat nem nyitja meg.
        val system = File("/system/framework/framework-res.apk")
        val result = IsolatedApkInspector(context, fallback = neverFallback).inspect(system)
        assertEquals(ApkInspection.Invalid(InspectionProtocol.REASON_FORBIDDEN_PATH), result)
        val missing = IsolatedApkInspector(context, fallback = neverFallback).inspect(File(context.cacheDir, "fixtures/missing.apk"))
        assertEquals(ApkInspection.Invalid(InspectionProtocol.REASON_FORBIDDEN_PATH), missing)
    }

    @Test
    fun hangingInspector_timesOut_isKilled_andFileIsInvalid() {
        val inspector = IsolatedApkInspector(context, neverFallback, HangingInspectionService::class.java, timeoutMillisFor = { 1_500 })
        val started = System.nanoTime()
        val result = inspector.inspect(fixture("fixture-v2-keyA.apk"))
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        assertEquals(ApkInspection.Invalid(InspectionProtocol.REASON_TIMEOUT), result)
        assertTrue(elapsedMillis < 15_000, "A hívó nem akadhat meg: $elapsedMillis ms")
        // A megakadt segédfolyamat le lett állítva.
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && runningProcesses().any { it.endsWith(":hanginspect") }) Thread.sleep(100)
        assertFalse(runningProcesses().any { it.endsWith(":hanginspect") }, runningProcesses().toString())
        // A teszt folyamata él, és a valódi elemző utána is működik.
        assertIs<ApkInspection.Valid>(IsolatedApkInspector(context, fallback = neverFallback).inspect(fixture("fixture-v2-keyA.apk")))
    }

    @Test
    fun crashingInspector_doesNotCrashCaller_andFileIsInvalid() {
        val inspector = IsolatedApkInspector(context, neverFallback, CrashingInspectionService::class.java, timeoutMillisFor = { 20_000 })
        val result = inspector.inspect(fixture("fixture-v2-keyA.apk"))
        assertEquals(ApkInspection.Invalid(InspectionProtocol.REASON_DIED), result)
    }

    @Test
    fun malformedReply_isNeverDecodedAsValid() {
        assertEquals(ApkInspection.Invalid(InspectionProtocol.REASON_BAD_REPLY), InspectionProtocol.decode(android.os.Bundle()))
        val valid = InspectionProtocol.encode(inProcess.inspect(fixture("fixture-v2-keyA.apk")))
        assertIs<ApkInspection.Valid>(InspectionProtocol.decode(valid))
        // Hiányzó vagy hibás aláíró-adat: érvénytelen.
        for (key in listOf("signersCurrent", "signersHistory", "packageName", "versionCode", "minSdk", "targetSdk", "multipleSigners")) {
            val broken = android.os.Bundle(valid).apply { remove(key) }
            assertEquals(ApkInspection.Invalid(InspectionProtocol.REASON_BAD_REPLY), InspectionProtocol.decode(broken), key)
        }
        val badFingerprint = android.os.Bundle(valid).apply { putStringArrayList("signersCurrent", arrayListOf("not-a-fingerprint")) }
        assertEquals(ApkInspection.Invalid(InspectionProtocol.REASON_BAD_REPLY), InspectionProtocol.decode(badFingerprint))
        val empty = android.os.Bundle(valid).apply { putStringArrayList("signersHistory", arrayListOf()) }
        assertEquals(ApkInspection.Invalid(InspectionProtocol.REASON_BAD_REPLY), InspectionProtocol.decode(empty))
    }
}
