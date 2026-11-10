package com.ravi.musicspot.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Process-wide background scope for persistence. */
/**
 * 1.0.7: background work that fails must not kill the app. Without a handler, any exception escaping a launch on a
 * SupervisorJob scope goes to the thread's uncaught handler, i.e. a crash (for example a sync error right after
 * signing in). It is logged instead; CancellationException never reaches a handler.
 */
val BackgroundFaults = kotlinx.coroutines.CoroutineExceptionHandler { ctx, e ->
    runCatching { android.util.Log.w("UpZe", "background job failed (${ctx[kotlinx.coroutines.CoroutineName]?.name ?: "io"})", e) }
}

val IoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + BackgroundFaults)

/**
 * A JSON file written atomically (tmp → fsync → rename) and debounced.
 *
 * 1.0.5: every write runs on Dispatchers.IO, one at a time per file (a coroutine [Mutex] orders the writers; the
 * disk section itself is also guarded so the legacy blocking [read] can never interleave with a rename). Rapid
 * [save] calls are coalesced: only the newest value is written, and it is never dropped (a writer that is already
 * on disk when a newer save arrives is followed by one more write). [flush] / [flushAll] write the pending value at
 * once; [flushAll] runs automatically when the app goes to the background (ProcessLifecycleOwner ON_STOP).
 *
 * [read] stays blocking for existing callers that already run on Dispatchers.IO (Graph.init); [load] is the
 * suspending form that hops to IO itself. With [holdWritesUntilRead], saves made before the first read are held
 * (not written) so an edit made during startup can never overwrite the file with a nearly empty state before the
 * file was loaded.
 */
class JsonFile<T>(
    private val file: File,
    private val serializer: KSerializer<T>,
    private val debounceMs: Long = 400,
    /**
     * 1.0.5: default on. Every store that reads its file (settings, library, play stats, downloads, seed artists,
     * cached songs) reads it once at startup; until then a save is held instead of replacing the file on disk.
     * A file that is written without ever being read (QueueStore) passes false.
     */
    private val holdWritesUntilRead: Boolean = true,
) {
    // 1.0.5: was a single `synchronized(lock)` used for reads, writes and job bookkeeping.
    private val mutex = Mutex()          // orders writers/loaders (coroutines)
    private val diskLock = Any()         // the actual file operations (also taken by the blocking read())
    private val stateLock = Any()        // pending job + latest value
    private var pending: Job? = null
    private var latest: (() -> T)? = null
    @Volatile private var readDone = !holdWritesUntilRead

    init { register(this) }

    private val tmp get() = File(file.parentFile, file.name + ".tmp")

    /** Blocking read; call it from a background thread only (Dispatchers.IO). */
    fun read(): T? {
        val v = synchronized(diskLock) { readLocked() }
        markRead()
        return v
    }

    /** 1.0.5: suspending read on Dispatchers.IO, ordered with the writes of this file. */
    suspend fun load(): T? = withContext(Dispatchers.IO) { mutex.withLock { read() } }

    private fun readLocked(): T? {
        // v2.0.14: a crash between delete and rename can leave only the .tmp; recover from it.
        val t = tmp
        val candidates = listOfNotNull(file.takeIf { it.isFile }, t.takeIf { it.isFile })
        for (src in candidates) {
            val v = runCatching { Http.json.decodeFromString(serializer, src.readText()) }.getOrNull()
            if (v != null) return v
            // A corrupt file is set aside rather than silently overwritten.
            runCatching { src.renameTo(File(src.parentFile, src.name + ".corrupt")) } // v2.0.14: set aside whichever was read
            // 1.0.5: a corrupt main file no longer hides a valid .tmp next to it (the loop tries the .tmp too).
        }
        return null
    }

    private fun markRead() {
        if (readDone) return
        val resume = synchronized(stateLock) { readDone = true; latest != null }
        if (resume) schedule()
    }

    /** Immediate blocking write (background thread only). */
    fun writeNow(value: T) = synchronized(diskLock) { writeLocked(value) }

    private fun writeLocked(value: T) {
        file.parentFile?.mkdirs()
        val t = tmp
        FileOutputStream(t).use { out ->
            out.write(Http.json.encodeToString(serializer, value).toByteArray())
            out.flush(); out.fd.sync()
        }
        if (!t.renameTo(file)) { file.delete(); if (!t.renameTo(file)) throw java.io.IOException("rename failed: ${file.name}") }
    }

    /** Debounced background write of the latest value. Never blocks the caller. */
    fun save(value: () -> T) {
        synchronized(stateLock) {
            latest = value
            if (!readDone) return // 1.0.5: held until the file was read once (see holdWritesUntilRead)
        }
        schedule()
    }

    private fun schedule() {
        synchronized(stateLock) {
            pending?.cancel()
            pending = IoScope.launch { delay(debounceMs); writeLatest() }
        }
    }

    /** 1.0.5: takes the newest value and writes it; a value that arrives meanwhile gets its own write. */
    private suspend fun writeLatest() {
        mutex.withLock {
            val f = synchronized(stateLock) { latest.also { latest = null } } ?: return
            val ok = runCatching { val v = f(); synchronized(diskLock) { writeLocked(v) } }
                .onFailure { CrashGuard.nonFatal(null, "JsonFile.write ${file.name}", it) }.isSuccess
            // A failed write keeps its value for the next save/flush instead of losing it.
            if (!ok) synchronized(stateLock) { if (latest == null) latest = f }
        }
    }

    /** 1.0.5: write the pending value now (no debounce). Suspends on Dispatchers.IO; never blocks the main thread. */
    suspend fun flush() {
        if (!readDone) return
        synchronized(stateLock) { pending?.cancel(); pending = null }
        withContext(Dispatchers.IO) { writeLatest() }
    }

    companion object {
        private val all: MutableSet<JsonFile<*>> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))
        private val hookInstalled = AtomicBoolean(false)

        private fun register(f: JsonFile<*>) { all.add(f); installBackgroundFlush() }

        /** 1.0.5: flush every JSON store on IO (app went to the background, service is going away). */
        fun flushAll(): Job = IoScope.launch {
            val files = synchronized(all) { all.toList() }
            files.forEach { runCatching { it.flush() } }
        }

        /** 1.0.5: ProcessLifecycleOwner ON_STOP (app in the background) → [flushAll]. Main-thread registration. */
        private fun installBackgroundFlush() {
            if (!hookInstalled.compareAndSet(false, true)) return
            runCatching {
                val main = android.os.Looper.getMainLooper() ?: return // JVM unit tests: no main looper
                android.os.Handler(main).post {
                    runCatching {
                        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
                            override fun onStop(owner: androidx.lifecycle.LifecycleOwner) { flushAll() }
                        })
                    }
                }
            }
        }
    }
}
