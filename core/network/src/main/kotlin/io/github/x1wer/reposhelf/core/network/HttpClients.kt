// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.network

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Akkor dobódik, ha egy kérés nem HTTPS-en menne ki. Ez második védvonal; az első a [HttpPolicy] ellenőrzése. */
class InsecureRequestException(message: String) : IOException(message)

/** Minden kérést elutasít, amely nem HTTPS. Az automatikus átirányítás ki van kapcsolva, így minden lépés áthalad rajta. */
internal object HttpsOnlyInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        if (!url.isHttps) throw InsecureRequestException("Nem HTTPS kérés elutasítva: ${url.host}")
        return chain.proceed(chain.request())
    }
}

internal class UserAgentInterceptor(private val userAgent: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build())
}

object HttpClients {
    /**
     * Az alkalmazás egyetlen OkHttp-kliense (docs/ARCHITECTURE.md 6. pont):
     * - nincs automatikus átirányítás-követés: azt a [HttpFetcher] végzi, ellenőrzött hosztlistával;
     * - csak HTTPS;
     * - rögzített timeoutok;
     * - saját, személyes adatot nem tartalmazó User-Agent.
     */
    fun create(userAgent: String, configure: OkHttpClient.Builder.() -> Unit = {}): OkHttpClient =
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(HttpsOnlyInterceptor)
            .addInterceptor(UserAgentInterceptor(userAgent))
            .apply(configure)
            .build()

    /**
     * Állományletöltéshez: a TELJES hívásra nincs időkorlát (egy nagy APK lassú hálózaton percekig tart), a kapcsolódási
     * és az olvasási – inaktivitási – időkorlát viszont megmarad, így egy megakadt kapcsolat nem tartja nyitva a
     * letöltést (docs/ARCHITECTURE.md 6. pont).
     */
    fun forDownloads(base: OkHttpClient): OkHttpClient = base.newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).build()
}

/**
 * A README-képek kliense (D-013): átirányítást követ (a GitHub-képek aláírt CDN-URL-re irányítanak), de minden
 * lépésben csak HTTPS-t enged, és a válasz méretét korlátozza. Hogy egy külső hosztú kép egyáltalán betöltődik-e,
 * azt a felhasználó dönti el a felületen (adatvédelem, C. 31.).
 */
object ImageHttpClient {
    const val MAX_IMAGE_BYTES = 10L * 1024 * 1024

    fun create(userAgent: String): OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(UserAgentInterceptor(userAgent))
        // A ténylegesen beolvasott (kitömörített) bájtok korlátja: a deklarált hossz hiányozhat vagy hamis lehet.
        .addInterceptor(ImageBodyLimitInterceptor)
        // Hálózati interceptorok: az átirányítás minden egyes lépésén lefutnak.
        .addNetworkInterceptor(HttpsOnlyInterceptor)
        .addNetworkInterceptor(ImageSizeInterceptor)
        .build()
}

/** A képválasz törzsét legfeljebb [ImageHttpClient.MAX_IMAGE_BYTES] bájtig engedi beolvasni (kitömörítés után számolva). */
internal object ImageBodyLimitInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val body = response.body
        val limited = object : ForwardingSource(body.source()) {
            private var total = 0L

            override fun read(sink: Buffer, byteCount: Long): Long {
                val read = super.read(sink, byteCount)
                if (read > 0) {
                    total += read
                    if (total > ImageHttpClient.MAX_IMAGE_BYTES) throw IOException("A kép túl nagy")
                }
                return read
            }
        }
        return response.newBuilder().body(limited.buffer().asResponseBody(body.contentType(), -1L)).build()
    }
}

internal object ImageSizeInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val length = response.body.contentLength()
        if (length > ImageHttpClient.MAX_IMAGE_BYTES) {
            response.close()
            throw IOException("A kép túl nagy: $length bájt")
        }
        return response
    }
}
