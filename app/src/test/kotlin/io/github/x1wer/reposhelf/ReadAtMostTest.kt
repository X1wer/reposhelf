// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf

import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

/** A korlátos beolvasás (az API 33 előtti Androidon hiányzó `readNBytes` helyett). */
class ReadAtMostTest {
    private fun bytes(size: Int) = ByteArray(size) { (it % 251).toByte() }

    @Test
    fun readsWholeStream_whenWithinLimit() {
        val data = bytes(20_000)
        assertContentEquals(data, ByteArrayInputStream(data).readAtMost(20_000))
        assertContentEquals(data, ByteArrayInputStream(data).readAtMost(50_000))
    }

    @Test
    fun returnsNull_whenOneByteOverLimit() {
        assertNull(ByteArrayInputStream(bytes(20_001)).readAtMost(20_000))
    }

    @Test
    fun emptyStreamAndZeroLimit() {
        assertContentEquals(ByteArray(0), ByteArrayInputStream(ByteArray(0)).readAtMost(0))
        assertNull(ByteArrayInputStream(bytes(1)).readAtMost(0))
    }

    @Test
    fun handlesShortReads() {
        val data = bytes(10_000)
        // Egyszerre legfeljebb 7 bájtot ad vissza, mint egy lassú hálózati vagy asset-folyam.
        val trickle = object : InputStream() {
            private val source = ByteArrayInputStream(data)
            override fun read(): Int = source.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = source.read(b, off, minOf(len, 7))
        }
        assertContentEquals(data, trickle.readAtMost(10_000))
    }
}
