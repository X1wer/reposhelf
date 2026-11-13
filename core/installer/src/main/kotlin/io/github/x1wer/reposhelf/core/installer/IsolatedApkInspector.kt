// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.installer

import android.app.ActivityManager
import android.app.Application
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import android.util.Log
import io.github.x1wer.reposhelf.core.apk.ApkFacts
import io.github.x1wer.reposhelf.core.apk.ApkInspection
import io.github.x1wer.reposhelf.core.apk.ApkInspector
import io.github.x1wer.reposhelf.core.apk.SignerInfo
import java.io.File
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Az elemző folyamat és a fő folyamat közötti üzenetek (Messenger; AIDL nélkül). A válasz egy `Bundle`, amelyet a fogadó
 * oldal szigorúan dekódol: hiányzó vagy hibás mező = érvénytelen eredmény (soha nem „érvényes” alapértékkel).
 */
internal object InspectionProtocol {
    const val MSG_INSPECT = 1
    const val MSG_RESULT = 2
    const val KEY_PATH = "path"

    private const val KEY_VALID = "valid"
    private const val KEY_REASON = "reason"
    private const val KEY_PACKAGE = "packageName"
    private const val KEY_VERSION_CODE = "versionCode"
    private const val KEY_VERSION_NAME = "versionName"
    private const val KEY_MIN_SDK = "minSdk"
    private const val KEY_TARGET_SDK = "targetSdk"
    private const val KEY_SPLIT = "splitName"
    private const val KEY_SIGNERS = "signersCurrent"
    private const val KEY_HISTORY = "signersHistory"
    private const val KEY_MULTIPLE = "multipleSigners"
    private val FINGERPRINT = Regex("^[0-9a-f]{64}$")
    private const val MAX_FINGERPRINTS = 64

    fun encode(result: ApkInspection): Bundle = Bundle().apply {
        when (result) {
            is ApkInspection.Invalid -> {
                putBoolean(KEY_VALID, false)
                putString(KEY_REASON, result.reason.take(300))
            }
            is ApkInspection.Valid -> {
                val facts = result.facts
                putBoolean(KEY_VALID, true)
                putString(KEY_PACKAGE, facts.packageName)
                putLong(KEY_VERSION_CODE, facts.versionCode)
                putString(KEY_VERSION_NAME, facts.versionName)
                putInt(KEY_MIN_SDK, facts.minSdk)
                putInt(KEY_TARGET_SDK, facts.targetSdk)
                putString(KEY_SPLIT, facts.splitName)
                putStringArrayList(KEY_SIGNERS, ArrayList(facts.signers.current))
                putStringArrayList(KEY_HISTORY, ArrayList(facts.signers.history))
                putBoolean(KEY_MULTIPLE, facts.signers.multipleSigners)
            }
        }
    }

    fun decode(bundle: Bundle): ApkInspection {
        if (!bundle.containsKey(KEY_VALID)) return ApkInspection.Invalid(REASON_BAD_REPLY)
        if (!bundle.getBoolean(KEY_VALID)) return ApkInspection.Invalid(bundle.getString(KEY_REASON)?.take(300) ?: REASON_BAD_REPLY)
        val packageName = bundle.getString(KEY_PACKAGE)
        val current = bundle.getStringArrayList(KEY_SIGNERS)
        val history = bundle.getStringArrayList(KEY_HISTORY)
        val complete = packageName != null && current != null && history != null &&
            bundle.containsKey(KEY_VERSION_CODE) && bundle.containsKey(KEY_MIN_SDK) && bundle.containsKey(KEY_TARGET_SDK) &&
            bundle.containsKey(KEY_MULTIPLE) && current.isNotEmpty() && history.isNotEmpty() &&
            current.size <= MAX_FINGERPRINTS && history.size <= MAX_FINGERPRINTS &&
            (current + history).all { it != null && FINGERPRINT.matches(it) }
        if (!complete) return ApkInspection.Invalid(REASON_BAD_REPLY)
        return ApkInspection.Valid(
            ApkFacts(
                packageName = checkNotNull(packageName),
                versionCode = bundle.getLong(KEY_VERSION_CODE),
                versionName = bundle.getString(KEY_VERSION_NAME),
                minSdk = bundle.getInt(KEY_MIN_SDK),
                targetSdk = bundle.getInt(KEY_TARGET_SDK),
                splitName = bundle.getString(KEY_SPLIT),
                signers = SignerInfo(checkNotNull(current).toList(), checkNotNull(history).toList(), bundle.getBoolean(KEY_MULTIPLE)),
            ),
        )
    }

    const val REASON_BAD_REPLY = "Az elemző folyamat válasza nem értelmezhető"
    const val REASON_TIMEOUT = "Az állomány elemzése túllépte az időkorlátot"
    const val REASON_DIED = "Az elemző folyamat leállt az állomány feldolgozása közben"
    const val REASON_FORBIDDEN_PATH = "Az állomány nem az alkalmazás saját tárhelyén van"
}

/**
 * Az APK platform-alapú elemzése KÜLÖN folyamatban (D-031, docs/ARCHITECTURE.md 10.3.). A rendszer csomagelemzője
 * megbízhatatlan, kívülről letöltött állományon dolgozik: ha egy rosszindulatúan kialakított állomány összeomlasztja
 * vagy megakasztja, az csak ezt a segédfolyamatot érinti, a fő alkalmazást (felület, háttérfeladat) nem.
 *
 * Nem exportált, és csak az alkalmazás saját adatkönyvtárában lévő fájlt hajlandó megnyitni.
 */
class ApkInspectionService : Service() {
    private lateinit var thread: HandlerThread
    private lateinit var messenger: Messenger

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("apk-inspection").apply { start() }
        val inspector = PlatformApkInspector(applicationContext)
        val root = applicationContext.dataDir
        messenger = Messenger(
            Handler(thread.looper) { message ->
                if (message.what == InspectionProtocol.MSG_INSPECT) reply(message, inspect(inspector, root, message.data?.getString(InspectionProtocol.KEY_PATH)))
                true
            },
        )
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        thread.quitSafely()
        super.onDestroy()
    }

    private fun inspect(inspector: ApkInspector, root: File, path: String?): ApkInspection {
        val file = path?.let(::File)
        if (file == null || !isInside(file, root) || !file.isFile) return ApkInspection.Invalid(InspectionProtocol.REASON_FORBIDDEN_PATH)
        return inspector.inspect(file)
    }

    private fun reply(request: Message, result: ApkInspection) {
        val reply = Message.obtain(null, InspectionProtocol.MSG_RESULT).apply { data = InspectionProtocol.encode(result) }
        try {
            request.replyTo?.send(reply)
        } catch (_: RemoteException) {
            // A kérdező közben megszűnt: nincs kinek válaszolni.
        }
    }

    companion object {
        /** A segédfolyamat nevének végződése (a manifestben: `android:process=":apkinspect"`). */
        const val PROCESS_SUFFIX = ":apkinspect"

        /**
         * `true`, ha ez a folyamat az elemző segédfolyamat. Az `Application.onCreate` ebben a folyamatban is lefut:
         * ott az alkalmazás szolgáltatásait (adatbázis, ütemezés, saját frissítés egyeztetése) NEM szabad elindítani.
         */
        fun isInspectionProcess(): Boolean = Application.getProcessName().endsWith(PROCESS_SUFFIX)

        internal fun isInside(file: File, root: File): Boolean = try {
            val canonicalRoot = root.canonicalPath.trimEnd(File.separatorChar) + File.separator
            file.canonicalPath.startsWith(canonicalRoot)
        } catch (_: IOException) {
            false
        }
    }
}

/**
 * Az [ApkInspector] a külön folyamatban futó [ApkInspectionService]-szel, időkorláttal (D-031).
 *
 * - Időtúllépés: a segédfolyamatot leállítjuk, az állomány érvénytelennek számít (soha nem „érvényes”).
 * - A segédfolyamat leáll az elemzés közben: egyszer újrapróbáljuk friss folyamattal (a rendszer memóriahiány miatt is
 *   leállíthatta); ha újra leáll, az állomány érvénytelen.
 * - Ha a szolgáltatás egyáltalán nem érhető el (nem köthető), a [fallback] (folyamaton belüli elemzés) fut: ugyanaz az
 *   ellenőrzés, csak az elszigetelés nélkül. A már elindult elemzés hibája után NINCS folyamaton belüli újrapróbálás.
 *
 * Egyszerre egy elemzés fut, hogy egy rosszindulatú állomány miatti leállás ne egy másik állomány elutasítását okozza.
 * Blokkoló hívás: a fő szálról nem hívható (a kapcsolat visszahívásai ott érkeznek).
 */
class IsolatedApkInspector(
    private val context: Context,
    private val fallback: ApkInspector,
    private val serviceClass: Class<out Service> = ApkInspectionService::class.java,
    private val timeoutMillisFor: (File) -> Long = ::defaultTimeoutMillis,
    private val bindTimeoutMillis: Long = 10_000,
) : ApkInspector {
    private val lock = ReentrantLock()
    private val replyThread by lazy { HandlerThread("apk-inspection-replies").apply { start() } }

    private sealed interface Outcome {
        data class Reply(val data: Bundle) : Outcome
        data object Died : Outcome
    }

    private enum class Attempt { UNAVAILABLE, DIED }

    override fun inspect(file: File): ApkInspection {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Az APK-elemzés nem futhat a fő szálon" }
        lock.withLock {
            repeat(MAX_ATTEMPTS) {
                when (val result = attempt(file)) {
                    is ApkInspection -> return result
                    Attempt.UNAVAILABLE -> {
                        Log.w(TAG, "Az elemző folyamat nem érhető el; az elemzés a fő folyamatban fut")
                        return fallback.inspect(file)
                    }
                    else -> Unit // leállt: még egy próba friss folyamattal
                }
            }
            return ApkInspection.Invalid(InspectionProtocol.REASON_DIED)
        }
    }

    /** Egy próbálkozás: [ApkInspection], vagy [Attempt], ha a folyamat nem érhető el / leállt. */
    private fun attempt(file: File): Any {
        val outcomes = ArrayBlockingQueue<Outcome>(4)
        val connected = CountDownLatch(1)
        var service: IBinder? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = binder
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                outcomes.offer(Outcome.Died)
            }

            override fun onBindingDied(name: ComponentName?) {
                outcomes.offer(Outcome.Died)
            }

            override fun onNullBinding(name: ComponentName?) {
                connected.countDown()
            }
        }
        val bound = try {
            context.bindService(Intent(context, serviceClass), connection, Context.BIND_AUTO_CREATE)
        } catch (_: SecurityException) {
            false
        }
        try {
            if (!bound || !connected.await(bindTimeoutMillis, TimeUnit.MILLISECONDS)) return Attempt.UNAVAILABLE
            val binder = service ?: return Attempt.UNAVAILABLE
            val replies = Messenger(
                Handler(replyThread.looper) { message ->
                    if (message.what == InspectionProtocol.MSG_RESULT) outcomes.offer(Outcome.Reply(message.data ?: Bundle.EMPTY))
                    true
                },
            )
            val request = Message.obtain(null, InspectionProtocol.MSG_INSPECT).apply {
                data = Bundle().apply { putString(InspectionProtocol.KEY_PATH, file.absolutePath) }
                replyTo = replies
            }
            try {
                Messenger(binder).send(request)
            } catch (_: RemoteException) {
                return Attempt.DIED
            }
            return when (val outcome = outcomes.poll(timeoutMillisFor(file), TimeUnit.MILLISECONDS)) {
                is Outcome.Reply -> InspectionProtocol.decode(outcome.data)
                Outcome.Died -> Attempt.DIED
                null -> {
                    killServiceProcess()
                    ApkInspection.Invalid(InspectionProtocol.REASON_TIMEOUT)
                }
            }
        } finally {
            runCatching { context.unbindService(connection) }
        }
    }

    /** A megakadt segédfolyamat leállítása (saját UID-jű folyamat; a szolgáltatás folyamatneve a manifestből). */
    private fun killServiceProcess() {
        val processName = try {
            context.packageManager.getServiceInfo(ComponentName(context, serviceClass), 0).processName
        } catch (_: Exception) {
            return
        }
        // Soha nem a saját (fő) folyamatunkat: ha a szolgáltatás tévedésből a fő folyamatban futna, nem állítunk le semmit.
        if (processName == Application.getProcessName()) return
        val manager = context.getSystemService(ActivityManager::class.java) ?: return
        manager.runningAppProcesses?.filter { it.processName == processName && it.pid != Process.myPid() }?.forEach { Process.killProcess(it.pid) }
    }

    companion object {
        private const val TAG = "IsolatedApkInspector"
        private const val MAX_ATTEMPTS = 2
        private const val BASE_TIMEOUT_MILLIS = 30_000L
        private const val MAX_TIMEOUT_MILLIS = 300_000L
        private const val BYTES_PER_EXTRA_SECOND = 4L * 1024 * 1024

        /** 30 mp + 1 mp minden 4 MiB után (a v1-aláírás ellenőrzése a teljes tartalmat olvassa), legfeljebb 5 perc. */
        fun defaultTimeoutMillis(file: File): Long =
            (BASE_TIMEOUT_MILLIS + file.length() / BYTES_PER_EXTRA_SECOND * 1000).coerceAtMost(MAX_TIMEOUT_MILLIS)
    }
}
