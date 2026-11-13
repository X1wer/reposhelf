// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf

import android.content.Intent
import android.os.Bundle

/**
 * Az exportált indító-activity bejövő intentjének megtisztítása (C. 21.: exportált komponensek védelme).
 *
 * A [MainActivity]-t bármely alkalmazás elindíthatja tetszőleges extrákkal. A Navigation könyvtár az activity intentjében
 * lévő saját, belső „deep link” extráit (`android-support-nav:controller:…`) automatikusan feldolgozná, így egy idegen
 * alkalmazás tetszőleges belső képernyőre vihetné a felhasználót. Ezeket az extrákat az alkalmazás soha nem használja
 * (az értesítéseink a saját, validált extráinkkal nyitnak), ezért bejövő intentből mindig eltávolítjuk őket.
 */
internal object IntentSanitizer {
    const val NAVIGATION_EXTRA_PREFIX = "android-support-nav:controller:"

    fun sanitize(intent: Intent?) {
        intent ?: return
        try {
            val extras = intent.extras ?: return
            extras.keySet().filter { it.startsWith(NAVIGATION_EXTRA_PREFIX) }.forEach(intent::removeExtra)
        } catch (_: RuntimeException) {
            // Feldolgozhatatlan (rosszindulatúan kialakított) extrák: mindet eldobjuk, az activity nem omlik össze.
            intent.replaceExtras(Bundle())
        }
    }
}
