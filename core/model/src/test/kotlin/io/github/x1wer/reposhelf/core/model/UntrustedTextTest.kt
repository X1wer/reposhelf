// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.model

import org.junit.Test
import kotlin.system.measureTimeMillis
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A megbízhatatlan szövegek szabályai (biztonsági audit: CAT-02 – assetFilter ReDoS, CAT-11 / IMP-04 – névhamisítás). */
class UntrustedTextTest {

    @Test
    fun displayText_rejectsInvisibleAndReorderingCharacters() {
        assertTrue(DisplayText.isSafe("Aegis Authenticator"))
        assertTrue(DisplayText.isSafe("Térkép – offline (béta) 2.0"))
        assertTrue(DisplayText.isSafe("日本語のアプリ"))
        assertTrue(DisplayText.isSafe("Rocket 🚀"), "a kiegészítő síkok karakterei (emoji) megengedettek")
        assertFalse(DisplayText.isSafe("Signal‮gpj.exe"), "irányváltó (RLO)")
        assertFalse(DisplayText.isSafe("Sig​nal"), "nulla szélességű szóköz")
        assertFalse(DisplayText.isSafe("Sig⁦nal⁩"), "irányelkülönítő")
        assertFalse(DisplayText.isSafe("a\u0007b"), "vezérlőkarakter")
        assertFalse(DisplayText.isSafe("a\u0085b"), "C1 vezérlőkarakter")
        assertFalse(DisplayText.isSafe("a b"), "sorelválasztó")
        assertFalse(DisplayText.isSafe("a\uD800b"), "árva surrogate")
        assertFalse(DisplayText.isSafe("ab"), "magáncélú karakter")
        assertFalse(DisplayText.isSafe("a﻿b"), "BOM / nulla szélességű nem törő szóköz")
    }

    @Test
    fun assetFilter_acceptsTheUsualPatterns() {
        for (pattern in listOf(
            // A példakatalógus szűrői.
            "^KeePassDX-.*-libre\\.apk$",
            "^app-release-universal\\.apk$",
            "^(?!.*fdroid).*\\.apk$",
            // Jellemző további alakok.
            "^cardduel-.*\\.apk$",
            "^NewPipe_v[0-9.]+\\.apk$",
            "^app-(arm64-v8a|armeabi-v7a|x86_64|x86|universal)-release\\.apk$",
            "arm64.*release",
            "^[A-Za-z]+-[0-9.]+-release\\.apk$",
            "^app-\\d\\d?\\.\\d\\d?-release\\.apk$",
            "^app[-_]?(?:foss|libre)\\.apk$",
            "^[^-]+-[\\w.]+\\.apk$",
        )) {
            assertNull(AssetFilterSyntax.violation(pattern), pattern)
            assertNotNull(AssetFilterSyntax.compileOrNull(pattern), pattern)
        }
        val filter = assertNotNull(AssetFilterSyntax.compileOrNull("^(?!.*fdroid).*\\.apk$"))
        assertTrue(filter.containsMatchIn("app-arm64-v8a-release.apk"))
        assertFalse(filter.containsMatchIn("app-fdroid-release.apk"))
    }

    @Test
    fun assetFilter_rejectsPatternsWithUnboundedBacktracking() {
        for (pattern in listOf(
            "(a+)+$", // egymásba ágyazott ismétlés
            "^(app-?)+release\\.apk$", // ismételt csoport
            "^(a|a)*$",
            "^(.*a){10}$", // számlált ismétlés
            "^a{1,50}$",
            "^(a)\\1$", // visszahivatkozás
            "(?<=a)b", // hátratekintés
            "(?<name>a)", // névvel ellátott csoport
            "(?i)abc", // kapcsoló
            "(?>a+)b", // atomi csoport
            "^.*?a$", // lusta
            "^.*+a$", // birtokos
            "^a**$",
            "^[a[b]]$", // beágyazott karakterosztály
            "^[a-z&&[^b]]$", // metszet
            "^\\p{L}+$", // Unicode-osztály
            "^\\x41$",
            "^.*a.*b.*c$", // három korlátlan ismétlés
            "^app-\\d+\\.\\d+\\.\\d+-release\\.apk$", // szintén három (helyette: [0-9.]+)
            "^app(?:-foss)?\\.apk$", // választható CSOPORT (helyette: (?:app|app-foss))
            ".*a.*b", // rögzítetlen, két korlátlan ismétlés
            "^a|.*b.*c$", // a legfelső szintű választás miatt nem rögzített
            "^" + "a?".repeat(23) + "$", // túl sok választható elem
            "^(" + "a|".repeat(23) + "b)$", // túl sok választás
            "^abc(", "^abc)", "^[abc$", "^abc\\", "*abc", "^(*a)$", "",
            "a".repeat(AssetFilterSyntax.MAX_LENGTH + 1),
            "^a\u0000b$",
        )) {
            assertNotNull(AssetFilterSyntax.violation(pattern), "el kellett volna utasítani: $pattern")
            assertNull(AssetFilterSyntax.compileOrNull(pattern), pattern)
        }
    }

    @Test
    fun assetFilter_worstAcceptedPatternsStayFastOnHostileNames() {
        // A költségkorlát határán lévő minták, a leghosszabb megengedett, célzottan rossz állománynévvel.
        val hostile = "a".repeat(AssetFilterSyntax.MAX_INPUT - 1) + "!"
        val patterns = listOf(
            "^.*.*" + "a?".repeat(6) + "b$",
            "^(?!.*zz).*a" + "(?:a|b|c)" + "a?a?a?a?x$",
            "a.*" + "a?".repeat(6) + "b",
            "^" + "a?".repeat(22) + "b$",
        )
        for (pattern in patterns) {
            val regex = assertNotNull(AssetFilterSyntax.compileOrNull(pattern), pattern + " -> " + AssetFilterSyntax.violation(pattern))
            val elapsed = measureTimeMillis { repeat(20) { assertFalse(regex.containsMatchIn(hostile)) } }
            assertTrue(elapsed < 5_000, "$pattern: $elapsed ms 20 illesztésre")
        }
        assertEquals(255, AssetFilterSyntax.MAX_INPUT)
    }
}
