// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.update

import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 2. lépés: verzió azonosítása (C. 11.; C. 34.: hibás verziószámozás, visszalépési kísérlet). */
class VersionResolverTest {
    private val apk = listOf(asset(1, "app.apk"))

    private fun installed(code: Long, name: String?, ours: InstalledRelease? = null) = InstalledVersion(code, name, ours)

    @Test
    fun notInstalled() {
        assertEquals(VersionVerdict.NOT_INSTALLED, VersionResolver.resolve(release(1, "v1.0", apk), null, null).verdict)
    }

    @Test
    fun knownVersionCode_hasPriority_overVersionName() {
        // A címke szerint régebbi, de a már elemzett APK versionCode-ja újabb: a versionCode dönt.
        val decision = VersionResolver.resolve(release(1, "v0.9", apk), candidateVersionCode = 120, installed = installed(110, "1.0"))
        assertEquals(VersionDecision(VersionVerdict.NEWER, VersionBasis.VERSION_CODE), decision)
        assertTrue(decision.confirmed)
        assertEquals(VersionVerdict.SAME, VersionResolver.resolve(release(1, "v9", apk), 110, installed(110, "1.0")).verdict)
        assertEquals(VersionVerdict.OLDER, VersionResolver.resolve(release(1, "v9", apk), 100, installed(110, "1.0")).verdict)
    }

    @Test
    fun releaseWeInstalled_isSame_onlyWhileInstalledVersionIsUnchanged() {
        val candidate = release(7, "nightly", apk)
        val ours = InstalledRelease(releaseId = 7, publishedAt = candidate.publishedAt, versionCode = 50)
        assertEquals(VersionDecision(VersionVerdict.SAME, VersionBasis.RELEASE_IDENTITY), VersionResolver.resolve(candidate, null, installed(50, "nightly", ours)))
        // Azóta máshonnan frissítették (más versionCode): a rögzített kiadás már nem mérvadó.
        assertEquals(VersionVerdict.UNKNOWN, VersionResolver.resolve(candidate, null, installed(51, "nightly", ours)).verdict)
    }

    @Test
    fun versionNames_withPrefixesAndPrereleases_areCompared() {
        assertEquals(VersionDecision(VersionVerdict.NEWER, VersionBasis.VERSION_NAME), VersionResolver.resolve(release(1, "v3.4.3", apk), null, installed(81, "3.4.2")))
        assertEquals(VersionVerdict.SAME, VersionResolver.resolve(release(1, "v3.4.3", apk), null, installed(82, "3.4.3")).verdict)
        // Visszalépés: a kiadás régebbi a telepítettnél → nem ajánlunk frissítést.
        assertEquals(VersionVerdict.OLDER, VersionResolver.resolve(release(1, "v3.4.1", apk), null, installed(82, "3.4.3")).verdict)
        assertEquals(VersionVerdict.NEWER, VersionResolver.resolve(release(1, "v2.0.0", apk), null, installed(82, "2.0.0-rc1")).verdict)
        assertFalse(VersionResolver.resolve(release(1, "v3.4.4", apk), null, installed(82, "3.4.3")).confirmed)
    }

    @Test
    fun malformedVersions_fallBackToReleaseDate_thenUnknown() {
        val ours = InstalledRelease(releaseId = 3, publishedAt = Instant.parse("2026-09-01T00:00:00Z"), versionCode = 5)
        val newer = release(4, "latest-build", apk, published = "2026-10-01T00:00:00Z")
        assertEquals(VersionDecision(VersionVerdict.NEWER, VersionBasis.RELEASE_DATE), VersionResolver.resolve(newer, null, installed(5, "nightly", ours)))
        // Nincs értelmezhető verziószám és nincs saját telepítési adat: ismeretlen, nem hiba.
        assertEquals(VersionDecision(VersionVerdict.UNKNOWN, VersionBasis.NONE), VersionResolver.resolve(newer, null, installed(5, "nightly")))
        assertEquals(VersionVerdict.UNKNOWN, VersionResolver.resolve(newer, null, installed(5, null)).verdict)
    }
}
