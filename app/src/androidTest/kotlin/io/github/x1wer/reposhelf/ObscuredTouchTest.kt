// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf

import android.app.Activity
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.x1wer.reposhelf.ui.common.ObscuredTouchGuard
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Az átfedésvédelem a valódi rendszeren (D-127, Android 9–11): egy átlátszó, érintést át nem vevő ablak takarja a
 * felületet, és a rendszer (InputDispatcher) a takart helyre érkező érintést `FLAG_WINDOW_IS_OBSCURED` jelzővel adja
 * tovább. Android 12-től a teszt kimarad: ott a rendszer véd (D-123).
 *
 * A fedőablak a teszt saját al-ablaka (TYPE_APPLICATION_PANEL): Android 9–11-en a rendszer a takarást ablaktulajdonostól
 * függetlenül jelzi, így egy idegen alkalmazás átfedésével azonos jelzőt kapunk.
 */
@RunWith(AndroidJUnit4::class)
class ObscuredTouchTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Before
    fun onlyBelowAndroid12() = assumeTrue(Build.VERSION.SDK_INT < Build.VERSION_CODES.S)

    @Test
    fun mainWindow_dropsTouchesUnderAnotherWindow_andShowsTheNotice() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var activity: MainActivity
            scenario.onActivity { activity = it }
            instrumentation.waitForIdleSync()
            val decor = activity.window.decorView
            // A felső sáv címe: nem kattintható terület, a koppintás nem navigál el.
            val density = activity.resources.displayMetrics.density
            val x = decor.width / 2f
            val y = 56 * density

            tap(x, y)
            assertEquals(0, activity.droppedObscuredTouches.value, "fedőablak nélkül nincs eldobott érintés")

            val overlay = addOverlay(activity, decor.windowToken)
            try {
                tap(x, y)
                assertEquals(1, activity.droppedObscuredTouches.value, "a takart helyre érkező érintés elveszik")
                tap(x, y)
                assertEquals(2, activity.droppedObscuredTouches.value)
            } finally {
                removeOverlay(activity, overlay)
            }
            // A tájékoztató sáv megjelent, és az utolsó eldobott érintés után még néhány másodpercig látszik; a TalkBack is
            // felolvassa (az akadálymentességi fában is ott van).
            // API 28-on az emulátor akadálymentességi pillanatképe a Compose-felületről elavult (CLAUDE.md): ott a teszt
            // képernyőképet ment (obscured-notice.png), amelyet kézzel ellenőriztünk (2026-11-13: a sáv látszott).
            val shown = waitForText(activity.getString(R.string.obscured_touch_notice))
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) assertTrue(shown, "a tájékoztató sáv nem jelent meg")
            tap(x, y)
            assertEquals(2, activity.droppedObscuredTouches.value, "a fedőablak eltűnése után az érintés újra átmegy")
        }
    }

    @Test
    fun dialogWindow_isProtectedByTheGuard() = dialogScenario(guarded = true)

    /** Kontroll: védelem nélkül a fedőablak alatti érintés átmegy (tehát a fenti tesztben a védelem dob el, nem a fedőablak). */
    @Test
    fun dialogWindow_withoutGuard_receivesTheTouch() = dialogScenario(guarded = false)

    private fun dialogScenario(guarded: Boolean) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var activity: MainActivity
            scenario.onActivity { activity = it }
            val clicks = AtomicInteger()
            lateinit var dialog: ComponentDialog
            instrumentation.runOnMainSync {
                dialog = ComponentDialog(activity)
                dialog.setContentView(
                    ComposeView(activity).apply {
                        setContent {
                            if (guarded) ObscuredTouchGuard()
                            Box(Modifier.size(240.dp).clickable { clicks.incrementAndGet() })
                        }
                    },
                )
                dialog.show()
            }
            instrumentation.waitForIdleSync()
            val decor = dialog.window!!.decorView
            val location = IntArray(2).also(decor::getLocationOnScreen)
            val x = location[0] + decor.width / 2f
            val y = location[1] + decor.height / 2f
            try {
                tap(x, y)
                assertEquals(1, clicks.get(), "fedőablak nélkül a gomb működik")

                val overlay = addOverlay(activity, decor.windowToken)
                try {
                    tap(x, y)
                    if (guarded) {
                        assertEquals(1, clicks.get(), "a párbeszédablakban is elveszik a takart helyre érkező érintés")
                    } else {
                        assertEquals(2, clicks.get(), "védelem nélkül az érintés átmegy a fedőablakon")
                    }
                } finally {
                    removeOverlay(activity, overlay)
                }
                tap(x, y)
                assertEquals(if (guarded) 2 else 3, clicks.get())
            } finally {
                instrumentation.runOnMainSync { dialog.dismiss() }
            }
        }
    }

    /** Átlátszó, érintést át nem vevő, teljes képernyős al-ablak a megadott ablak fölött (a tapjacking-átfedés mintája). */
    private fun addOverlay(activity: Activity, token: IBinder): View {
        val view = View(activity)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { this.token = token }
        instrumentation.runOnMainSync { activity.windowManager.addView(view, params) }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(300)
        return view
    }

    private fun removeOverlay(activity: Activity, view: View) {
        instrumentation.runOnMainSync { activity.windowManager.removeViewImmediate(view) }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(300)
    }

    private fun waitForText(text: String, timeoutMillis: Long = 4_000): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            root?.refresh()
            if (root?.findAccessibilityNodeInfosByText(text).orEmpty().isNotEmpty()) return true
            SystemClock.sleep(200)
        }
        // Diagnosztika: képernyőkép az app külső tárhelyére (adb pull).
        instrumentation.uiAutomation.takeScreenshot()?.let { shot ->
            val dir = instrumentation.targetContext.getExternalFilesDir(null)
            java.io.File(dir, "obscured-notice.png").outputStream().use { shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        return false
    }

    private fun tap(x: Float, y: Float) {
        val automation = instrumentation.uiAutomation
        val down = SystemClock.uptimeMillis()
        for ((action, time) in listOf(MotionEvent.ACTION_DOWN to down, MotionEvent.ACTION_UP to down + 40)) {
            val event = MotionEvent.obtain(down, time, action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            check(automation.injectInputEvent(event, true)) { "az érintés befecskendezése nem sikerült" }
            event.recycle()
        }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(150)
        instrumentation.waitForIdleSync()
    }
}
