// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.ui.common

import android.os.Build
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.view.doOnAttach
import io.github.x1wer.reposhelf.R
import kotlinx.coroutines.delay

/**
 * Átfedés (tapjacking) elleni védelem (D-123, D-127).
 *
 * Android 12-től (API 31) a rendszer maga tiltja az idegen, átlátszó ablakon átmenő érintést, és a RepoShelf a nem
 * rendszer-átfedéseket el is rejti, amíg előtérben van (`setHideOverlayWindows`). Alatta (Android 9–11) az app maga dobja
 * el azt az érintést, amelynek a helyén egy MÁSIK ablak látszik (`MotionEvent.FLAG_WINDOW_IS_OBSCURED`).
 *
 * A csak részben takart ablak (`FLAG_WINDOW_IS_PARTIALLY_OBSCURED`: az érintés helyét semmi nem takarja) nem számít,
 * különben egy csevegőbuborék az egész appot letiltaná. A teljes képernyős szűrők (éjszakai fény, képernyősötétítő) alatt
 * viszont minden érintés elveszik; erről a fő ablak tájékoztató sávot mutat ([ObscuredTouchNotice]).
 */
object ObscuredTouches {
    fun filterNeeded(sdkInt: Int = Build.VERSION.SDK_INT): Boolean = sdkInt < Build.VERSION_CODES.S

    fun shouldDrop(flags: Int, sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        filterNeeded(sdkInt) && (flags and MotionEvent.FLAG_WINDOW_IS_OBSCURED) != 0

    /**
     * A nézetet tartalmazó ablak nézetláncán (a nézettől az ablak gyökeréig) bekapcsolja a platform szűrőjét
     * (`filterTouchesWhenObscured`). A Compose saját nézete a szűrőt maga nem vizsgálja, a szülő `ViewGroup`-ok igen; a
     * gyökérnél eldobott érintés így egyik gyermekhez sem jut el.
     */
    fun protect(view: View) {
        if (!filterNeeded()) return
        view.doOnAttach { attached ->
            var current: View? = attached
            while (current != null) {
                current.filterTouchesWhenObscured = true
                current = current.parent as? View
            }
        }
    }
}

/**
 * A párbeszédablakok és a lenyíló menük tartalmába helyezendő (D-127): ezek külön ablakban vannak, ezért a fő ablak
 * szűrése nem vonatkozik rájuk. Nem rajzol semmit.
 */
@Composable
fun ObscuredTouchGuard() {
    val view = LocalView.current
    DisposableEffect(view) {
        ObscuredTouches.protect(view)
        onDispose { }
    }
}

/**
 * Tájékoztató sáv az eldobott érintésről (D-127): enélkül a felhasználó csak annyit látna, hogy az app nem reagál.
 * A [signal] minden eldobott érintésnél nő; a sáv az utolsó után [NOTICE_MILLIS] ideig látszik. Animáció nincs.
 */
@Composable
fun ObscuredTouchNotice(signal: Int, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(signal) {
        if (signal > 0) {
            visible = true
            delay(NOTICE_MILLIS)
            visible = false
        }
    }
    if (!visible) return
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        shadowElevation = 4.dp,
        modifier = modifier.statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth(),
    ) {
        Text(
            text = stringResource(R.string.obscured_touch_notice),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(12.dp).semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

private const val NOTICE_MILLIS = 6_000L
