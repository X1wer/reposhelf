// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.apk

import java.io.File
import java.security.MessageDigest

/**
 * A `testdata/generate_fixtures.py` által előállított, teszt-kulccsal aláírt APK-k (D-017) és az apksignerrel
 * megállapított tanúsítvány-ujjlenyomataik. Minden fixture csomagneve `io.github.x1wer.fixture`, kivéve az `other-*`-t.
 */
object ApkFixtures {
    const val PACKAGE = "io.github.x1wer.fixture"
    const val CERT_A = "204fb1276af1d0c8cee4ac8628d6e51d9617180fd305fbaae87c9a5787687de2"
    const val CERT_B = "8bfcb3f58a08e2b1d028c374e97ba110f84035e82b1d1fe87589a7443ff4f06a"

    /** A fixture-ök a testFixtures-jarban vannak, ezért egy ideiglenes könyvtárba másoljuk őket (futásonként egyszer). */
    private val dir: File by lazy { kotlin.io.path.createTempDirectory("reposhelf-fixtures").toFile().apply { deleteOnExit() } }

    @Synchronized
    fun file(name: String): File {
        require(name.matches(Regex("[A-Za-z0-9._-]+"))) { name }
        val target = File(dir, name)
        if (!target.exists()) {
            val input = checkNotNull(ApkFixtures::class.java.getResourceAsStream("/fixtures/$name")) { name }
            input.use { source -> target.outputStream().use { source.copyTo(it) } }
            target.deleteOnExit()
        }
        return target
    }

    fun bytes(name: String): ByteArray = file(name).readBytes()

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
