// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf

import androidx.test.core.app.ApplicationProvider
import io.github.x1wer.reposhelf.ui.common.AppLanguage
import io.github.x1wer.reposhelf.ui.common.AppLocales
import io.github.x1wer.reposhelf.ui.common.AppearanceSettings
import io.github.x1wer.reposhelf.ui.common.ThemeMode
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A magyar és az angol felület teljessége (C. 22.2., B. 9.4.): minden szöveg erőforrásban van, mindkét nyelven, azonos
 * helyőrzőkkel és többesszám-alakokkal; a nyelvválasztó a valóban meglévő nyelveket kínálja.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalizationTest {
    private sealed interface Res {
        data class Str(val text: String) : Res
        data class Plural(val items: Map<String, String>) : Res
    }

    private fun Element.children(): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>()

    private fun load(dir: String): Map<String, Res> {
        val files = File("src/main/res/$dir").listFiles { f -> f.name.startsWith("strings") && f.extension == "xml" }.orEmpty()
        assertTrue(files.isNotEmpty(), dir)
        val result = mutableMapOf<String, Res>()
        val factory = DocumentBuilderFactory.newInstance()
        for (file in files) {
            for (element in factory.newDocumentBuilder().parse(file).documentElement.children()) {
                if (element.getAttribute("translatable") == "false") continue
                val name = element.getAttribute("name")
                val value = when (element.tagName) {
                    "string" -> Res.Str(element.textContent)
                    "plurals" -> Res.Plural(element.children().associate { it.getAttribute("quantity") to it.textContent })
                    else -> continue
                }
                assertTrue(result.put(name, value) == null, "$dir: kétszer szerepel: $name")
            }
        }
        return result
    }

    private val placeholder = Regex("%(\\d+\\$)?[sdf]")

    private fun placeholders(text: String) = placeholder.findAll(text).map { it.value }.sorted().toList()

    @Test
    fun hungarianAndEnglish_haveTheSameStringsAndPlaceholders() {
        val en = load("values")
        val hu = load("values-hu")
        assertTrue(en.size > 400)
        assertEquals(emptySet(), en.keys - hu.keys, "magyar fordítás hiányzik")
        assertEquals(emptySet(), hu.keys - en.keys, "angol szöveg hiányzik")
        for ((name, english) in en) {
            val hungarian = hu.getValue(name)
            when (english) {
                is Res.Str -> {
                    val magyar = hungarian as Res.Str
                    assertEquals(placeholders(english.text), placeholders(magyar.text), "helyőrzők: $name")
                    assertTrue(magyar.text.isNotBlank(), "üres fordítás: $name")
                }
                is Res.Plural -> {
                    val magyar = hungarian as Res.Plural
                    // Mindkét nyelv nyelvtana az „one” és az „other” alakot használja.
                    assertEquals(setOf("one", "other"), english.items.keys, name)
                    assertEquals(setOf("one", "other"), magyar.items.keys, name)
                    for (quantity in english.items.keys) {
                        assertEquals(placeholders(english.items.getValue(quantity)), placeholders(magyar.items.getValue(quantity)), "$name/$quantity")
                    }
                }
            }
        }
    }

    @Test
    fun noHardcodedUserVisibleText() {
        // Szövegliterál betűkkel, interpoláció nélkül: csak erőforrásból jöhet. Adatok összefűzése megengedett.
        val literalText = Regex("""\bText\(\s*"(?![^"]*\$)[^"]*\p{L}""")
        val literalDescription = Regex("""contentDescription\s*=\s*"""")
        val sources = File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val offenders = sources.flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line ->
                if (literalText.containsMatchIn(line) || literalDescription.containsMatchIn(line)) "${file.name}:${i + 1}" else null
            }
        }
        assertEquals(emptyList(), offenders)
    }

    @Test
    fun languagePicker_offersExactlyTheTranslatedLocales() {
        val config = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(File("src/main/res/xml/locales_config.xml")).documentElement
        val declared = config.children().map { it.getAttributeNS("http://schemas.android.com/apk/res/android", "name") }.toSet()
        val translated = File("src/main/res").listFiles { f -> f.isDirectory && f.name.matches(Regex("values-[a-z]{2}")) }.orEmpty()
            .map { it.name.removePrefix("values-") }.toSet() + "en"
        assertEquals(translated, declared)
        assertEquals(declared, AppLanguage.entries.mapNotNull { it.tag }.toSet())
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:localeConfig=\"@xml/locales_config\""))
    }

    @Test
    fun languageTags_mapToChoices() {
        assertEquals(AppLanguage.HUNGARIAN, AppLocales.fromTag("hu"))
        assertEquals(AppLanguage.ENGLISH, AppLocales.fromTag("en"))
        assertEquals(AppLanguage.SYSTEM, AppLocales.fromTag("de"))
        assertEquals(AppLanguage.SYSTEM, AppLocales.fromTag(null))
    }

    @Test
    fun appearanceChoices_arePersisted() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val first = AppearanceSettings(context)
        assertEquals(ThemeMode.SYSTEM, first.theme.value)
        assertTrue(first.dynamicColor.value)
        first.setTheme(ThemeMode.DARK)
        first.setDynamicColor(false)
        val second = AppearanceSettings(context)
        assertEquals(ThemeMode.DARK, second.theme.value)
        assertEquals(false, second.dynamicColor.value)
    }
}
