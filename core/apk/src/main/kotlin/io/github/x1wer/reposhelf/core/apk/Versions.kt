// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.apk

/**
 * Verziónév-összehasonlítás ELŐZETES becsléshez (C. 11.: nem kizárólag szöveges összehasonlítás).
 * A végleges döntést mindig a letöltött APK `versionCode`-ja hozza (docs/ARCHITECTURE.md 9. pont, 2. lépés).
 *
 * Kezelt alakok: `v1.2.3`, `1.2`, `1.2.3-beta.2`, `2026.09.29-32-android`, `release-1.4`.
 * Az előzetes kiadás (`alpha` < `beta` < `rc`) régebbi, mint ugyanaz a végleges verzió.
 * Ha valamelyik oldalon nincs értelmezhető szám, az eredmény `null` (nem összehasonlítható).
 */
object VersionNames {
    private val LEADING_NOISE = Regex("^[^0-9]*?(?=\\d)")
    private val NUMBERS = Regex("^\\d+(?:[._]\\d+)*")
    private val PRE_TAGS = listOf("dev", "alpha", "a", "beta", "b", "pre", "preview", "rc")

    data class Parsed(val numbers: List<Long>, val preRank: Int, val preNumber: Long)

    fun parse(version: String): Parsed? {
        val trimmed = version.trim().lowercase().take(100)
        val start = LEADING_NOISE.find(trimmed)?.value?.length ?: return null
        val core = trimmed.substring(start)
        val numberPart = NUMBERS.find(core)?.value ?: return null
        val numbers = numberPart.split('.', '_').mapNotNull { it.toLongOrNull() }
        if (numbers.isEmpty()) return null
        val suffix = core.substring(numberPart.length)
        var preRank = Int.MAX_VALUE // végleges kiadás
        var preNumber = 0L
        val tag = PRE_TAGS.withIndex().filter { (_, t) -> Regex("(^|[^a-z])$t(\\d|[^a-z]|$)").containsMatchIn(suffix) }.maxByOrNull { it.value.length }
        if (tag != null) {
            preRank = tag.index
            preNumber = Regex("${tag.value}[^0-9]*(\\d+)").find(suffix)?.groupValues?.get(1)?.toLongOrNull() ?: 0
        }
        return Parsed(numbers, preRank, preNumber)
    }

    /** Negatív, ha [a] régebbi; pozitív, ha újabb; 0, ha egyenlő; `null`, ha nem összehasonlítható. */
    fun compare(a: String, b: String): Int? {
        val pa = parse(a) ?: return null
        val pb = parse(b) ?: return null
        val length = maxOf(pa.numbers.size, pb.numbers.size)
        for (i in 0 until length) {
            val x = pa.numbers.getOrElse(i) { 0 }
            val y = pb.numbers.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        if (pa.preRank != pb.preRank) return pa.preRank.compareTo(pb.preRank)
        return pa.preNumber.compareTo(pb.preNumber)
    }
}
