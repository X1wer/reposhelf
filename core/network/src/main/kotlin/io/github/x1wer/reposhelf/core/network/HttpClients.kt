// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.network

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
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
}
