package com.akari.ppx.utils

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Process
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** Provider method names shared by the host process and the module UI. */
object LogBridge {
    const val METHOD_APPEND = "log_append"
    const val METHOD_READ = "log_read"
    const val METHOD_CLEAR = "log_clear"

    fun uri(): Uri = Uri.parse("content://${com.akari.ppx.BuildConfig.APPLICATION_ID}.CP/")
}

/**
 * Process-wide runtime log.
 *
 * Every call to [Log] is mirrored here *and* to logcat, so users can open the
 * module's own 运行日志 screen instead of pulling adb logs. The hooked 皮皮虾
 * process cannot write the module's private storage, so its lines are batched
 * and handed to the module's ContentProvider, which appends them to the same
 * file the settings screen reads.
 *
 * The logger must never throw and never re-enter itself: it is used from inside
 * hook callbacks and from storage helpers that log their own failures.
 */
object RuntimeLog {
    const val MAX_LINES = 2000
    private const val MAX_FILE_BYTES = 1_500_000L
    private const val MAX_MESSAGE = 1600
    private const val PUSH_MIN_LINES = 40
    private const val PUSH_INTERVAL_MS = 12_000L
    private const val PUSH_TICK_MS = 2_000L

    /** Remote-preferences group both processes can read. */
    /** File inside the module's private files dir, written by the host. */
    const val HOST_LOG_NAME = "host.log"

    const val PREFS_GROUP = "runtime_log"
    private const val PREFS_KEY = "host_log_lines"
    private const val PREFS_BLOCK = 200

    private val lock = Any()
    private val ring = ArrayDeque<String>()
    private val pending = ArrayDeque<String>()
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val reentrant = ThreadLocal<Boolean>()

    @Volatile private var role = "app"
    @Volatile private var file: File? = null
    @Volatile private var bridgeContext: Context? = null
    @Volatile private var lastPush = 0L
    @Volatile private var pushFailures = 0
    @Volatile private var pusherStarted = false
    @Volatile private var bridgeMode = 0 // 0 unknown, 1 provider call, 2 explicit ContentResolver

    /**
     * @param roleName short process label shown in every line ("host"/"module")
     * @param logFile file this process writes directly
     * @param bridge module context used to forward host lines to the module app
     */
    @Synchronized
    fun install(roleName: String, logFile: File?, bridge: Context? = null) {
        role = roleName
        if (logFile != null) file = logFile
        // Never drop a bridge that was already installed: host code calls this
        // again from several lifecycle points, only the first one has a context.
        if (bridge != null) {
            bridgeContext = bridge
            lastPush = 0L
        }
        if (bridge != null && !pusherStarted) {
            pusherStarted = true
            startPusher()
        }
    }

    fun isInstalled(): Boolean = file != null

    fun path(): String? = file?.absolutePath

    fun role(): String = role

    fun write(level: Char, tag: String, message: String) {
        if (reentrant.get() == true) return
        // 20k-char stack traces drown the useful lines; keep the head and tail.
        if (message.length > MAX_MESSAGE) {
            val head = message.take(MAX_MESSAGE / 2)
            val tail = message.takeLast(MAX_MESSAGE / 2)
            return write(level, tag, head + "\n…省略 " + (message.length - MAX_MESSAGE) + " 字符…\n" + tail)
        }
        reentrant.set(true)
        try {
            val text = message
            val line = "${stamp.format(Date())} $level/$role(${Process.myPid()}): [$tag] $text"
            synchronized(lock) {
                ring.addLast(line)
                while (ring.size > MAX_LINES) ring.removeFirst()
                appendLocal(line)
                // Always buffer: in the host process the log file is unreadable
                // for the module UI, and the bridge is installed a few
                // milliseconds after the first lines (before Context exists).
                pending.addLast(line)
                while (pending.size > 600) pending.removeFirst()
            }
        } catch (t: Throwable) {
            android.util.Log.w("PPXPPX", "RuntimeLog failed: ${t.message}")
        } finally {
            reentrant.set(false)
        }
    }

    /** Asks the background pusher to forward buffered lines on its next tick. */
    fun requestFlush() {
        lastPush = 0L
    }

    fun lines(limit: Int = 600): List<String> {
        val target = file
        val fromFile = runCatching {
            if (target != null && target.isFile && target.length() > 0L) {
                target.readLines().takeLast(limit)
            } else {
                emptyList()
            }
        }.getOrDefault(emptyList())
        if (fromFile.isNotEmpty()) return fromFile
        synchronized(lock) { return ring.toList().takeLast(limit) }
    }

    /**
     * Merges the module log and the host log into one chronological list.
     * Identical frame lines are collapsed so a repeated exception does not fill
     * the screen with hundreds of "Caused by: ENOENT" rows.
     */
    fun mergeLines(own: List<String>, host: List<String>, limit: Int): List<String> {
        val seen = HashSet<String>(own.size + host.size)
        val merged = ArrayList<String>(own.size + host.size)
        (own + host).forEach { line ->
            val body = line.substringAfter("] ", line)
            // Keep every distinct line, but only once per identical body.
            if (body.startsWith("\tat ") || body.startsWith("Caused by:")) {
                if (!seen.add("frame|" + body)) return@forEach
            } else if (!seen.add(body)) {
                return@forEach
            }
            merged += line
        }
        return merged.sortedBy { it.take(18) }.takeLast(limit)
    }

    fun clear() {
        synchronized(lock) {
            ring.clear()
            pending.clear()
        }
        runCatching {
            val target = file
            if (target != null) {
                target.parentFile?.mkdirs()
                FileOutputStream(target, false).close()
            }
        }.onFailure { android.util.Log.w("PPXPPX", "RuntimeLog clear failed: ${it.message}") }
    }

    private fun appendLocal(line: String) {
        val target = file ?: return
        runCatching {
            target.parentFile?.mkdirs()
            if (target.length() > MAX_FILE_BYTES) {
                val rotated = File(target.parentFile, target.name + ".1")
                runCatching { rotated.delete() }
                target.renameTo(rotated)
            }
            FileOutputStream(target, true).use { out ->
                out.write((line + "\n").toByteArray())
            }
        }.onFailure { android.util.Log.w("PPXPPX", "RuntimeLog write failed: ${it.message}") }
    }

    /**
     * Delivers a batch to the shared log file.
     *
     * The hooked process can only write its OWN `Android/media` folder, so that
     * file is the single cross-process channel. Nothing else works here:
     *  - `openRemoteFile` is opened read-only for injected modules;
     *  - remote preferences are a read-only snapshot in the host process
     *    (`edit()` throws "Read only implementation");
     *  - the module ContentProvider is invisible to the host on Android 11+
     *    (package visibility => "Unknown authority").
     */
    private fun deliver(batch: List<String>): Boolean {
        val context = bridgeContext ?: return false
        SharedBridge.appendHostLog(context, batch)
        val target = SharedBridge.hostLogFile(context) ?: return false
        return target.isFile && target.length() > 0L
    }

    private fun startPusher() {
        val thread = Thread({
            while (true) {
                try {
                    Thread.sleep(PUSH_TICK_MS)
                    maybeFlush()
                } catch (t: InterruptedException) {
                    return@Thread
                } catch (t: Throwable) {
                    android.util.Log.w("PPXPPX", "RuntimeLog pusher: ${t.message}")
                }
            }
        }, "ppx-log-push")
        thread.isDaemon = true
        thread.start()
    }

    private fun maybeFlush() {
        val context = bridgeContext
        val now = System.currentTimeMillis()
        val batch: List<String>
        synchronized(lock) {
            if (pending.isEmpty()) return
            val due = pending.size >= PUSH_MIN_LINES || now - lastPush >= PUSH_INTERVAL_MS
            if (!due) return
            batch = pending.toList()
            pending.clear()
            lastPush = now
        }
        runCatching {
            check(deliver(batch)) { "host log file unreachable" }
            pushFailures = 0
        }.onFailure {
            pushFailures++
            if (pushFailures == 1 || pushFailures % 20 == 0) {
                android.util.Log.w("PPXPPX", "RuntimeLog bridge unavailable (${pushFailures}): ${it.message}")
            }
            // Put the lines back so a late-installed module app still gets them.
            synchronized(lock) {
                batch.asReversed().forEach { pending.addFirst(it) }
                while (pending.size > 600) pending.removeFirst()
            }
        }
    }
}

/**
 * Provider call with a package-visibility fallback.
 *
 * On Android 11+ a process may get "Unknown authority" from
 * ContentResolver.call for a package it cannot see, even when the provider is
 * exported. Resolving the provider by package name and calling it through an
 * explicit ContentResolver bypasses that check. The working mode is cached.
 */
fun bridgeCall(context: Context, uri: Uri, method: String, extras: Bundle?): Bundle? {
    val authority = uri.authority ?: return null
    var callError: Throwable? = null
    runCatching { context.contentResolver.call(uri, method, null, extras) }
        .onSuccess { return it }
        .onFailure { callError = it }
    // Package-visibility fallback is intentionally absent: resolving the provider
    // by package still yields Unknown authority from the hooked host process on
    // Android 11+. Remote preferences are the supported cross-process channel.
    android.util.Log.w("PPXPPX", "bridgeCall failed $authority/$method: ${callError?.message}")
    throw (callError ?: IllegalStateException("provider unavailable: $authority"))
}

/** Module-side storage for the merged log file shown in the UI. */
object LogStore {
    private const val MAX_BYTES = 1_500_000L
    private const val FILE_NAME = "runtime.log"

    fun file(context: Context): File = File(context.filesDir, "logs/$FILE_NAME")

    @Synchronized
    fun append(context: Context, lines: List<String>) {
        if (lines.isEmpty()) return
        val target = file(context)
        target.parentFile?.mkdirs()
        if (target.length() > MAX_BYTES) {
            val rotated = File(target.parentFile, "$FILE_NAME.1")
            runCatching { rotated.delete() }
            target.renameTo(rotated)
        }
        FileOutputStream(target, true).bufferedWriter().use { writer ->
            lines.forEach { writer.appendLine(it) }
        }
    }

    @Synchronized
    fun read(context: Context, limit: Int = 800): List<String> = runCatching {
        val target = file(context)
        if (!target.isFile) emptyList() else target.readLines().takeLast(limit)
    }.getOrDefault(emptyList())

    @Synchronized
    fun clear(context: Context): Boolean = runCatching {
        val target = file(context)
        target.parentFile?.mkdirs()
        target.writeText("")
        true
    }.getOrDefault(false)
}
