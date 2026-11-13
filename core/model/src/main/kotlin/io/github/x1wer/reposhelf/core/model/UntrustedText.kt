// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.model

/**
 * Megbízhatatlan forrásból (katalógus, importfájl) érkező, a felületen megjelenő rövid szövegek (név, kulcsszó)
 * ellenőrzése (C. 17.). A láthatatlan vagy a megjelenítést átrendező karakterek névhamisításra használhatók
 * (pl. irányváltó vagy nulla szélességű karakter), és a jóváhagyó sem látja őket minden nézetben.
 */
object DisplayText {
    private val FORBIDDEN_TYPES = setOf(
        Character.CONTROL.toInt(),
        Character.FORMAT.toInt(),
        Character.SURROGATE.toInt(),
        Character.PRIVATE_USE.toInt(),
        Character.UNASSIGNED.toInt(),
        Character.LINE_SEPARATOR.toInt(),
        Character.PARAGRAPH_SEPARATOR.toInt(),
    )

    /**
     * `true`, ha a szövegben nincs vezérlő-, formázó (irányváltó, nulla szélességű), sor- vagy bekezdéselválasztó,
     * magáncélú vagy ki nem osztott karakter, sem árva surrogate. Kódpontonként vizsgál (a kiegészítő síkok
     * karakterei, pl. az emojik, megengedettek).
     */
    fun isSafe(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val codePoint = text.codePointAt(i)
            if (Character.getType(codePoint) in FORBIDDEN_TYPES) return false
            i += Character.charCount(codePoint)
        }
        return true
    }
}

/**
 * A katalógus `assetFilter` mezőjének megengedett reguláriskifejezés-részhalmaza (C. 17., 21.).
 *
 * A szűrő a katalógusból jön, és az eszközön a REPÓ TULAJDONOSA által megadott állományneveken fut. Egy visszalépéses
 * (backtracking) motorban egy ártatlannak látszó minta és egy célzottan elnevezett állomány exponenciális futási időt
 * adhat (ReDoS), ami megakasztaná az adatlapot és a háttérellenőrzést. Ezért csak olyan minta fogadható el, amelynek a
 * legrosszabb esetben bejárható útjainak száma kiszámíthatóan korlátos:
 *
 * - megengedett: literálok, `.`, karakterosztály (`[...]`, beágyazás és metszet nélkül), `\d` `\w` `\s`, kiléptetett
 *   írásjel, `^` `$`, választás (`|`), csoport (`(...)`, `(?:...)`), előretekintés (`(?=...)`, `(?!...)`);
 * - ismétlés (`*`, `+`, `?`) CSAK egyetlen karakterre vagy karakterosztályra alkalmazható (csoportra nem), és nincs lusta,
 *   birtokos vagy számlált (`{n,m}`) ismétlés, visszahivatkozás, hátratekintés, névvel ellátott csoport, kapcsoló;
 * - költségkorlát: minden `*` / `+` legfeljebb az állománynév hosszával (≤ [MAX_INPUT] = 2^8), minden `?` és `|`
 *   legfeljebb kettővel, a nem `^`-val rögzített minta még egyszer az állománynév hosszával szorozza az utak számát;
 *   a szorzat legfeljebb 2^[MAX_COST] lehet.
 *
 * Ugyanezt a szabályt alkalmazza a katalógusrepó validátora (`tools/build_catalog.py`), így a CI és a kliens nem térhet el.
 * A részhalmaz jelentése a Java-, az Android- (ICU) és a Python-motorban azonos.
 */
object AssetFilterSyntax {
    const val MAX_LENGTH = 200

    /** Az illesztett állománynév legnagyobb hossza; a költségkorlát ezzel számol (2^8). */
    const val MAX_INPUT = 255
    private const val MAX_COST = 22
    private const val CLASS_ESCAPES = "dws"

    /** A minta hibája, vagy `null`, ha megengedett. */
    fun violation(pattern: String): String? {
        if (pattern.isEmpty() || pattern.length > MAX_LENGTH) return "a hossz 1 és $MAX_LENGTH karakter között lehet"
        val n = pattern.length
        var i = 0
        var depth = 0
        var stars = 0
        var choices = 0
        var topLevelAlternation = false
        // Az előző elem egyetlen karakter vagy karakterosztály volt-e (csak az ismételhető).
        var quantifiable = false
        while (i < n) {
            val c = pattern[i]
            when (c) {
                '\\' -> {
                    escapeViolation(pattern.getOrNull(i + 1))?.let { return it }
                    i += 2
                    quantifiable = true
                }
                '[' -> {
                    var j = i + 1
                    if (j < n && pattern[j] == '^') j++
                    if (j < n && pattern[j] == ']') j++
                    while (j < n && pattern[j] != ']') {
                        when (pattern[j]) {
                            '\\' -> {
                                escapeViolation(pattern.getOrNull(j + 1))?.let { return it }
                                j += 2
                            }
                            '[' -> return "beágyazott karakterosztály nem megengedett"
                            '&' -> {
                                if (pattern.getOrNull(j + 1) == '&') return "karakterosztály-metszet nem megengedett"
                                j++
                            }
                            else -> j++
                        }
                    }
                    if (j >= n) return "lezáratlan karakterosztály"
                    i = j + 1
                    quantifiable = true
                }
                '(' -> {
                    if (pattern.getOrNull(i + 1) == '?') {
                        val kind = pattern.getOrNull(i + 2)
                        if (kind != ':' && kind != '=' && kind != '!') return "csak (?:…), (?=…) és (?!…) csoport megengedett"
                        i += 3
                    } else {
                        i++
                    }
                    depth++
                    quantifiable = false
                }
                ')' -> {
                    if (--depth < 0) return "párosítatlan zárójel"
                    i++
                    quantifiable = false
                }
                '|' -> {
                    choices++
                    if (depth == 0) topLevelAlternation = true
                    i++
                    quantifiable = false
                }
                '*', '+', '?' -> {
                    if (!quantifiable) return "ismétlés csak egyetlen karakterre vagy karakterosztályra alkalmazható"
                    if (c == '?') choices++ else stars++
                    val next = pattern.getOrNull(i + 1)
                    if (next == '?' || next == '+' || next == '*' || next == '{') return "lusta, birtokos vagy halmozott ismétlés nem megengedett"
                    i++
                    quantifiable = false
                }
                '{', '}' -> return "számlált ismétlés nem megengedett"
                '^', '$' -> {
                    i++
                    quantifiable = false
                }
                else -> {
                    if (c.isISOControl()) return "vezérlőkarakter nem megengedett"
                    i++
                    quantifiable = true
                }
            }
        }
        if (depth != 0) return "lezáratlan csoport"
        val anchored = pattern.startsWith("^") && !topLevelAlternation
        val cost = 8 * stars + choices + if (anchored) 0 else 8
        return if (cost > MAX_COST) "túl sok ismétlés vagy választás: a futási idő nem korlátozható (rögzítsd a mintát ^ jellel, vagy egyszerűsítsd)" else null
    }

    private fun escapeViolation(next: Char?): String? = when {
        next == null -> "csonka kiléptetés"
        next.isLetterOrDigit() && next !in CLASS_ESCAPES -> "nem támogatott kiléptetés: \\$next"
        next.isISOControl() -> "vezérlőkarakter nem megengedett"
        else -> null
    }

    /** A lefordított minta, vagy `null`, ha a minta nem megengedett vagy nem fordítható le. */
    fun compileOrNull(pattern: String): Regex? = if (violation(pattern) != null) {
        null
    } else {
        try {
            Regex(pattern)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
