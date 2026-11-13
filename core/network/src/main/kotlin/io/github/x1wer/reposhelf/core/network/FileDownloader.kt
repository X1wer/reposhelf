// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.network

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.coroutines.executeAsync
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext

/** Egy állomány letöltésének leírása. */
data class DownloadRequest(
    val url: HttpUrl,
    val policy: HttpPolicy,
    /** A végleges fájl (az alkalmazás privát könyvtárában). Letöltés közben `<target>.part` a neve. */
    val target: File,
    /** A szolgáltató által közölt pontos méret. A letöltött fájlnak bájtra ezzel kell egyeznie. */
    val expectedSize: Long,
)

enum class DownloadFailure {
    INSECURE_URL, HOST_NOT_ALLOWED, TOO_MANY_REDIRECTS, INVALID_REDIRECT, HTTP_ERROR, SIZE_MISMATCH, TOO_LARGE, NETWORK,

    /** Váratlan belső hiba az állomány feldolgozása közben (a hívó nem omlik össze; a letöltés sikertelen). */
    INTERNAL,
}

sealed interface DownloadResult {
    data class Completed(val file: File, val sha256: String, val size: Long) : DownloadResult

    /** [resumable]: a részleges fájl megmaradt, a letöltés később folytatható (C. 26.: megszakított letöltés folytatása). */
    data class Failed(val reason: DownloadFailure, val detail: String?, val resumable: Boolean) : DownloadResult
}

/**
 * Telepítőállományok letöltése (docs/ARCHITECTURE.md 9. pont, 4. lépés; C. 7., 18., 21.).
 *
 * - Csak HTTPS, és csak az engedélyezett hosztok; az átirányítás minden lépése ellenőrzött, HTTP-re nem követjük.
 * - A méret bájtra egyezik a szolgáltató által közölttel; ennél többet nem fogadunk el (méretkorlát).
 * - A SHA-256 letöltés közben számolódik; a fájl csak teljes, ellenőrzött letöltés után kapja meg a végleges nevét.
 * - Megszakadt letöltés `Range` + `If-Range` kéréssel folytatható. A rövid életű, aláírt CDN-URL-ek miatt mindig az
 *   eredeti URL-ről indulunk újra.
 * - Nincs DownloadManager: az nem ellenőriz hash-t, és csak külső tárba ír (D-061).
 */
class FileDownloader(
    private val client: OkHttpClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun download(request: DownloadRequest, onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> }): DownloadResult =
        withContext(ioDispatcher) {
            // Egy célfájlra egyszerre egy letöltés: a felület és a háttérfeladat nem írhatja egyszerre ugyanazt a részfájlt.
            lockFor(request.target).withLock { downloadInternal(request, onProgress) }
        }

    private suspend fun downloadInternal(request: DownloadRequest, onProgress: (Long, Long) -> Unit): DownloadResult {
        // 0 méretű (pl. még fel nem töltött) állomány: nem programhiba, hanem megbízhatatlan bemenet.
        if (request.expectedSize <= 0) return fail(DownloadFailure.SIZE_MISMATCH, "${request.expectedSize}")
        if (!request.url.isHttps) return fail(DownloadFailure.INSECURE_URL)
        if (!request.policy.isHostAllowed(request.url.host)) return fail(DownloadFailure.HOST_NOT_ALLOWED)
        if (request.expectedSize > request.policy.maxBodyBytes) return fail(DownloadFailure.TOO_LARGE, "${request.expectedSize}")

        val part = File(request.target.path + PART_SUFFIX)
        val meta = File(request.target.path + META_SUFFIX)
        request.target.parentFile?.mkdirs()
        var offset = if (part.isFile) part.length() else 0L
        if (offset >= request.expectedSize) {
            part.delete()
            offset = 0
        }
        val previousEtag = if (offset > 0) safeValidator(meta.takeIf { it.isFile && it.length() <= 1024 }?.readText()?.trim()) else null
        if (offset > 0 && previousEtag == null) {
            // ETag nélkül nem bizonyítható, hogy a részleges fájl ugyanahhoz a tartalomhoz tartozik: újrakezdjük.
            part.delete()
            offset = 0
        }

        var url = request.url
        var redirects = 0
        while (true) {
            coroutineContext.ensureActive()
            val builder = Request.Builder().url(url).get().header("Accept", "application/octet-stream")
            if (offset > 0 && previousEtag != null) {
                builder.header("Range", "bytes=$offset-").header("If-Range", previousEtag)
            }
            val response = try {
                client.newCall(builder.build()).executeAsync()
            } catch (e: IOException) {
                return fail(DownloadFailure.NETWORK, e.javaClass.simpleName, resumable = part.isFile && part.length() > 0)
            }
            response.use { r ->
                if (r.code in REDIRECT_CODES) {
                    val target = r.header("Location")?.let(url::resolve) ?: return fail(DownloadFailure.INVALID_REDIRECT)
                    if (!target.isHttps) return fail(DownloadFailure.INSECURE_URL)
                    if (!request.policy.isHostAllowed(target.host)) return fail(DownloadFailure.HOST_NOT_ALLOWED, target.host)
                    if (++redirects > request.policy.maxRedirects) return fail(DownloadFailure.TOO_MANY_REDIRECTS)
                    url = target
                    return@use
                }
                val range = contentRange(r.header("Content-Range"))
                val responseEtag = safeValidator(r.header("ETag"))
                // Folytatás csak akkor, ha a szerver pontosan a kért helytől küld, a teljes méret a várt, és (ha közli) a
                // tartalom ugyanaz, mint amihez a részfájl tartozik. Így két különböző tartalom nem fűzhető össze.
                val append = r.code == 206 && offset > 0 && range != null && range.first == offset && range.second == request.expectedSize &&
                    (responseEtag == null || responseEtag == previousEtag)
                if (!r.isSuccessful || (r.code == 206 && !append)) {
                    if (r.code == 416 || r.code == 206) {
                        // A részfájl nem folytatható: töröljük, hogy a következő próbálkozás tisztán, elölről induljon.
                        part.delete()
                        meta.delete()
                    }
                    return fail(DownloadFailure.HTTP_ERROR, "HTTP ${r.code}", resumable = false)
                }
                val startAt = if (append) offset else 0L
                val declared = r.body.contentLength()
                if (declared >= 0 && startAt + declared != request.expectedSize) {
                    return fail(DownloadFailure.SIZE_MISMATCH, "${startAt + declared} / ${request.expectedSize}", resumable = false)
                }
                // Előbb a régi részfájl törlése, utána az új validátor: fordított sorrendnél egy folyamatleállás régi
                // bájtokat hagyhatna az ÚJ ETag mellett, és a következő futás hibás tartalomra folytatna.
                if (!append) part.delete()
                responseEtag?.let { meta.writeText(it) } ?: meta.delete()

                val digest = MessageDigest.getInstance("SHA-256")
                if (append) part.inputStream().use { input -> updateDigest(digest, input) }
                var written = startAt
                try {
                    FileOutputStream(part, append).use { out ->
                        val source = r.body.byteStream()
                        val buffer = ByteArray(BUFFER)
                        var lastReported = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = source.read(buffer)
                            if (read == -1) break
                            written += read
                            if (written > request.expectedSize) {
                                // A szerver többet küld, mint a közölt méret: nem fogadjuk el.
                                out.close()
                                part.delete()
                                meta.delete()
                                return fail(DownloadFailure.SIZE_MISMATCH, "> ${request.expectedSize}", resumable = false)
                            }
                            out.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            if (written - lastReported >= PROGRESS_STEP || written == request.expectedSize) {
                                onProgress(written, request.expectedSize)
                                lastReported = written
                            }
                        }
                        out.fd.sync()
                    }
                } catch (e: IOException) {
                    return fail(DownloadFailure.NETWORK, e.javaClass.simpleName, resumable = part.isFile && part.length() > 0)
                }
                if (written != request.expectedSize) {
                    return fail(DownloadFailure.SIZE_MISMATCH, "$written / ${request.expectedSize}", resumable = written < request.expectedSize)
                }
                request.target.delete()
                if (!part.renameTo(request.target)) return fail(DownloadFailure.NETWORK, "rename", resumable = false)
                meta.delete()
                val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
                return DownloadResult.Completed(request.target, sha256, written)
            }
        }
    }

    private fun updateDigest(digest: MessageDigest, input: java.io.InputStream) {
        val buffer = ByteArray(BUFFER)
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            digest.update(buffer, 0, read)
        }
    }

    /** `bytes <kezdet>-<vég>/<teljes>` → (kezdet, teljes méret); minden más alak (pl. ismeretlen, csillaggal jelölt teljes méret) `null`. */
    private fun contentRange(value: String?): Pair<Long, Long>? {
        val groups = value?.let { CONTENT_RANGE.find(it) }?.groupValues ?: return null
        val start = groups[1].toLongOrNull() ?: return null
        val total = groups[2].toLongOrNull() ?: return null
        return start to total
    }

    private fun fail(reason: DownloadFailure, detail: String? = null, resumable: Boolean = false) =
        DownloadResult.Failed(reason, detail, resumable)

    companion object {
        const val PART_SUFFIX = ".part"
        const val META_SUFFIX = ".part.etag"
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private val CONTENT_RANGE = Regex("^bytes (\\d+)-\\d+/(\\d+)$")
        private val locks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

        private fun lockFor(target: File): Mutex = locks.getOrPut(target.absolutePath) { Mutex() }
        private const val BUFFER = 64 * 1024
        private const val PROGRESS_STEP = 256L * 1024
    }
}
