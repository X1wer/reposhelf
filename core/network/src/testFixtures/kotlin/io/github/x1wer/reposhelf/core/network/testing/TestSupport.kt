// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.network.testing

import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.net.InetAddress
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Kézzel léptethető óra a tesztekhez. */
class MutableClock(private var now: Instant) : Clock() {
    override fun instant(): Instant = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    fun advance(duration: Duration) {
        now = now.plus(duration)
    }
}

/**
 * Helyi HTTPS-tesztszerver: az alkalmazás csak HTTPS-t enged (docs/ARCHITECTURE.md 6. pont),
 * ezért a tesztek is valódi TLS-kapcsolaton futnak, egy csak a teszthez létrehozott tanúsítvánnyal.
 */
class HttpsTestServer : AutoCloseable {
    // Kifejezetten IPv4 loopbackre kötünk: Windows alatt a „localhost” először ::1-re oldódik fel.
    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")
    private val certificate: HeldCertificate = HeldCertificate.Builder()
        .addSubjectAlternativeName("127.0.0.1")
        .addSubjectAlternativeName("localhost")
        .build()
    private val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
    private val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()

    val server: MockWebServer = MockWebServer().apply {
        useHttps(serverCertificates.sslSocketFactory())
        start(loopback, 0)
    }

    /** A szerver hosztneve, ahogy az URL-ekben megjelenik (az engedélyezett hosztlistához). */
    val host: String get() = server.url("/").host

    /** A klienskonfigurációhoz: a tesztszerver tanúsítványában bízik meg, és csak abban. */
    fun trustTestCertificate(builder: OkHttpClient.Builder): OkHttpClient.Builder =
        builder.sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)

    override fun close() {
        server.close()
    }
}
