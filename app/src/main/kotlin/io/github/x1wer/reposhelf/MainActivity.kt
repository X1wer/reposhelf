// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import io.github.x1wer.reposhelf.ui.common.AppLocales
import io.github.x1wer.reposhelf.ui.common.ThemeMode
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.github.x1wer.reposhelf.background.Notifier
import io.github.x1wer.reposhelf.background.UpdateCheckWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.flow.MutableStateFlow
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.x1wer.reposhelf.ui.common.ObscuredTouchNotice
import io.github.x1wer.reposhelf.ui.common.ObscuredTouches
import kotlinx.coroutines.flow.update
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.x1wer.reposhelf.core.installer.InstallEvents
import io.github.x1wer.reposhelf.ui.RepoShelfApp
import io.github.x1wer.reposhelf.ui.theme.RepoShelfTheme
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    /** Értesítésből kért megnyitás: a követett alkalmazás belső azonosítója (validálva). */
    private val openTracked = MutableStateFlow<Long?>(null)

    /** Értesítésből kért megnyitás: a saját frissítés (Beállítások). */
    private val openSelf = MutableStateFlow(false)

    /** Az átfedés miatt eldobott érintések (gesztusok) száma (D-127): a tájékoztató sáv jelzése; a műszeres teszt is ezt olvassa. */
    internal val droppedObscuredTouches = MutableStateFlow(0)

    /** Android 9–12: a választott nyelv (D-132); Android 13-tól a rendszer kezeli. */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocales.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Az exportált indító-activity bejövő intentje megbízhatatlan: a Navigation belső deep link extráit eltávolítjuk,
        // mielőtt a navigációs gráf feldolgozhatná őket (IntentSanitizer).
        IntentSanitizer.sanitize(intent)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Átfedés (tapjacking) elleni védelem (D-123, D-127): Android 12-től a nem rendszer-átfedések nem látszanak, amíg az
        // app előtérben van; Android 9–11-en a takart helyre érkező érintést a dispatchTouchEvent dobja el.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) window.setHideOverlayWindows(true)
        if (savedInstanceState == null) handleOpenIntent(intent)
        val appearance = (application as RepoShelfApplication).container.appearance
        setContent {
            val themeMode by appearance.theme.collectAsState()
            val dynamicColor by appearance.dynamicColor.collectAsState()
            val dark = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // A rendszersávok ikonjai a választott témához igazodnak (nem a rendszeréhez).
            DisposableEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose { }
            }
            RepoShelfTheme(darkTheme = dark, dynamicColor = dynamicColor) {
                val request by openTracked.collectAsState()
                val selfRequest by openSelf.collectAsState()
                val dropped by droppedObscuredTouches.collectAsState()
                Box(Modifier.fillMaxSize()) {
                    RepoShelfApp(
                        openTrackedRequest = request,
                        onOpenHandled = { openTracked.value = null },
                        openSelfUpdateRequest = selfRequest,
                        onOpenSelfUpdateHandled = { openSelf.value = false },
                    )
                    ObscuredTouchNotice(dropped, Modifier.align(Alignment.TopCenter))
                }
            }
        }
        // A PackageInstaller jóváhagyási felületét csak előtérben lévő activity indíthatja (docs/ARCHITECTURE.md 11.1.).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                InstallEvents.pendingConfirmation.filterNotNull().collect { event ->
                    InstallEvents.consumePendingConfirmation(event)
                    try {
                        startActivity(event.intent)
                    } catch (_: ActivityNotFoundException) {
                        // A rendszer saját felülete hiányzik: a telepítés nem folytatható; a session időtúllépéssel lezárul.
                    } catch (_: SecurityException) {
                    }
                }
            }
        }
    }

    /**
     * Android 9–11: az az érintés, amelynek a helyén egy másik alkalmazás ablaka látszik, nem jut el a felülethez (D-127).
     * A jelző a gesztus minden eseményén azonos (a rendszer a gesztus kezdetekor számolja), így az egész gesztus elveszik.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ObscuredTouches.shouldDrop(ev.flags)) {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) droppedObscuredTouches.update { it + 1 }
            return false
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onNewIntent(intent: Intent) {
        IntentSanitizer.sanitize(intent)
        super.onNewIntent(intent)
        handleOpenIntent(intent)
    }

    /** Csak a saját értesítéseink belső azonosítója fogadható el (pozitív szám); minden más bemenet figyelmen kívül marad. */
    private fun handleOpenIntent(intent: Intent?) {
        val id = intent?.getLongExtra(Notifier.EXTRA_OPEN_TRACKED, -1L) ?: -1L
        if (id > 0) openTracked.value = id
        if (intent?.getBooleanExtra(Notifier.EXTRA_OPEN_SELF, false) == true) openSelf.value = true
        // Csak debug buildben: a háttérellenőrzés azonnali futtatása emulátoros teszthez (adb), mert a WorkManager a
        // periodikus munkát az esedékesség előtt nem futtatja. Kiadott buildben ez az ág nem létezik (BuildConfig.DEBUG).
        if (BuildConfig.DEBUG && intent?.getBooleanExtra(EXTRA_DEBUG_RUN_BACKGROUND_CHECK, false) == true) {
            WorkManager.getInstance(this).enqueueUniqueWork(
                "reposhelf-debug-check",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<UpdateCheckWorker>().build(),
            )
        }
        // Csak debug buildben: a saját frissítés szimulációja helyi APK-val (B. 6.5.). Csak a saját privát
        // könyvtárunkban lévő, egyszerű nevű fájl fogadható el (adb run-as-szal kerül oda).
        val localName = if (BuildConfig.DEBUG) intent?.getStringExtra(EXTRA_DEBUG_SELF_UPDATE_FROM) else null
        if (localName != null && LOCAL_APK_NAME.matches(localName)) {
            val dir = File(filesDir, "debug-selfupdate")
            val file = File(dir, localName)
            if (file.isFile && file.canonicalFile.parentFile == dir.canonicalFile) {
                val auto = intent?.getBooleanExtra(EXTRA_DEBUG_SELF_UPDATE_UNATTENDED, false) == true
                (application as RepoShelfApplication).container.selfUpdater.debugLoadLocal(file, autoInstall = auto)
                openSelf.value = true
            }
        }
    }

    private companion object {
        const val EXTRA_DEBUG_RUN_BACKGROUND_CHECK = "io.github.x1wer.reposhelf.debug.RUN_BACKGROUND_CHECK"
        const val EXTRA_DEBUG_SELF_UPDATE_FROM = "io.github.x1wer.reposhelf.debug.SELF_UPDATE_FROM"
        const val EXTRA_DEBUG_SELF_UPDATE_UNATTENDED = "io.github.x1wer.reposhelf.debug.SELF_UPDATE_UNATTENDED"
        val LOCAL_APK_NAME = Regex("^[A-Za-z0-9._-]{1,64}[.]apk$")
    }
}
