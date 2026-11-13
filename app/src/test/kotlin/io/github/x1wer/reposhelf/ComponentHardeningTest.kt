// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf

import android.content.Intent
import android.os.Bundle
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Biztonsági audit: az exportált komponens bemenete (CMP-01), a mentési szabályok (STO-01) és a manifest alapvető
 * beállításai. A szabályfájlokat és a manifestet forrásból olvassa, hogy egy későbbi módosítás ne lazíthasson rajtuk
 * észrevétlenül.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ComponentHardeningTest {

    private fun xml(path: String): Element =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(File(path)).documentElement

    private fun Element.children(tag: String): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }

    private fun Element.android(name: String): String = getAttributeNS("http://schemas.android.com/apk/res/android", name)

    @Test
    fun navigationDeepLinkExtrasFromOtherApps_areRemoved() {
        val intent = Intent(Intent.ACTION_MAIN)
            .putExtra("android-support-nav:controller:deepLinkIds", intArrayOf(1, 2, 3))
            .putExtra("android-support-nav:controller:deepLinkArgs", arrayListOf(Bundle()))
            .putExtra("android-support-nav:controller:deepLinkExtras", Bundle())
            .putExtra("android-support-nav:controller:deepLinkHandled", false)
            .putExtra("io.github.x1wer.reposhelf.extra.OPEN_TRACKED", 7L)
        IntentSanitizer.sanitize(intent)
        val keys = intent.extras?.keySet().orEmpty()
        assertTrue(keys.none { it.startsWith(IntentSanitizer.NAVIGATION_EXTRA_PREFIX) }, keys.toString())
        assertEquals(7L, intent.getLongExtra("io.github.x1wer.reposhelf.extra.OPEN_TRACKED", -1), "a saját, validált extráink megmaradnak")
        // Extrák nélkül és null intenttel sem dob.
        IntentSanitizer.sanitize(Intent(Intent.ACTION_MAIN))
        IntentSanitizer.sanitize(null)
        assertNull(Intent(Intent.ACTION_MAIN).also(IntentSanitizer::sanitize).extras)
    }

    @Test
    fun nothingIsBackedUpOrTransferred() {
        val domains = setOf("root", "file", "database", "sharedpref", "external")
        val rules = xml("src/main/res/xml/data_extraction_rules.xml")
        for (section in listOf("cloud-backup", "device-transfer")) {
            val element = rules.children(section).single()
            assertTrue(element.children("include").isEmpty(), "$section: nincs engedélylista")
            val excluded = element.children("exclude").filter { it.getAttribute("path") == "." }.map { it.getAttribute("domain") }.toSet()
            assertEquals(domains, excluded, section)
        }
        val legacy = xml("src/main/res/xml/backup_rules.xml")
        assertTrue(legacy.children("include").isEmpty())
        assertEquals(domains, legacy.children("exclude").filter { it.getAttribute("path") == "." }.map { it.getAttribute("domain") }.toSet())
    }

    @Test
    fun manifest_keepsTheHardenedDefaults() {
        val manifest = xml("src/main/AndroidManifest.xml")
        val application = manifest.children("application").single()
        assertEquals("false", application.android("allowBackup"))
        assertEquals("@xml/network_security_config", application.android("networkSecurityConfig"))
        assertFalse(application.hasAttributeNS("http://schemas.android.com/apk/res/android", "usesCleartextTraffic"))
        assertFalse(application.hasAttributeNS("http://schemas.android.com/apk/res/android", "debuggable"))
        // Saját komponenseink közül csak az indító-activity exportált, és nincs content provider.
        val exported = (application.children("activity") + application.children("receiver") + application.children("service") + application.children("provider"))
            .filter { it.android("exported") == "true" }.map { it.android("name") }
        assertEquals(listOf(".MainActivity"), exported)
        assertTrue(application.children("provider").isEmpty())
        val installer = xml("../core/installer/src/main/AndroidManifest.xml").children("application").single()
        assertTrue((installer.children("receiver") + installer.children("service")).all { it.android("exported") == "false" })
        // Az APK-elemző szolgáltatás külön folyamatban fut.
        assertEquals(":apkinspect", installer.children("service").single().android("process"))
        // A hálózatbiztonsági beállítás: nincs titkosítatlan forgalom, csak rendszer-CA.
        val network = xml("src/main/res/xml/network_security_config.xml").children("base-config").single()
        assertEquals("false", network.getAttribute("cleartextTrafficPermitted"))
        val anchors = network.children("trust-anchors").single().children("certificates").map { it.getAttribute("src") }
        assertEquals(listOf("system"), anchors)
    }
}
