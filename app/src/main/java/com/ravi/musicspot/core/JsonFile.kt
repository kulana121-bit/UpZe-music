package com.ravi.musicspot.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import java.io.File
import java.io.FileOutputStream

/** Process-wide background scope for persistence. */
val IoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * A JSON file written atomically (tmp → fsync → rename) and debounced. Reads happen on the caller's
 * thread, so callers load from Dispatchers.IO only (never from the main thread at startup).
 */
class JsonFile<T>(private val file: File, private val serializer: KSerializer<T>, private val debounceMs: Long = 400) {
    private val lock = Any()
    private var pending: Job? = null

    fun read(): T? = synchronized(lock) {
        // v2.0.14: a crash between delete and rename can leave only the .tmp; recover from it.
        val tmp = File(file.parentFile, file.name + ".tmp")
        val src = if (file.isFile) file else if (tmp.isFile) tmp else return null
        runCatching { Http.json.decodeFromString(serializer, src.readText()) }.getOrElse {
            // A corrupt file is set aside rather than silently overwritten.
            runCatching { src.renameTo(File(src.parentFile, src.name + ".corrupt")) } // v2.0.14: set aside whichever was read
            null
        }
    }

    fun writeNow(value: T) = synchronized(lock) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(Http.json.encodeToString(serializer, value).toByteArray())
            out.flush(); out.fd.sync()
        }
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    /** Debounced background write of the latest value. */
    fun save(value: () -> T) {
        synchronized(lock) {
            pending?.cancel()
            pending = IoScope.launch { delay(debounceMs); runCatching { writeNow(value()) } }
        }
    }
}
