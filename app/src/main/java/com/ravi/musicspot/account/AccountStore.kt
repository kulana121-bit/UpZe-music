package com.ravi.musicspot.account

import com.ravi.musicspot.core.SecretBox
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Minimal key-value persistence so the store can be tested on the JVM (prod: a private SharedPreferences file). */
interface KeyValue {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

@Serializable
data class Account(
    val id: String,
    val service: Service,
    val name: String? = null,
    val email: String? = null,
    val avatarUrl: String? = null,
    /** Secret material (cookie header or token). Never logged, never shown, encrypted at rest. */
    val secret: String,
    /** Service-specific non-secret data (server URL, user id). */
    val extra: String? = null,
) {
    enum class Service { YOUTUBE, SPOTIFY, SUBSONIC, JELLYFIN }
    override fun toString() = "Account($id, $service, name=$name, secret=<redacted>)"
}

/**
 * Thread-safe, encrypted account store (fixes OpenTune audit B1/S2).
 * - every mutation happens under one lock, and the published list is an immutable snapshot;
 * - the whole account list is sealed with [box] (AES-GCM, Android Keystore key) before it is written;
 * - the backing file is excluded from cloud backup and device transfer (res/xml/backup_rules + data_extraction_rules).
 */
class AccountStore(private val kv: KeyValue, private val box: SecretBox) {
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true }
    private val ser = ListSerializer(Account.serializer())
    private val _accounts = MutableStateFlow<List<Account>>(emptyList())
    val accounts: StateFlow<List<Account>> = _accounts
    @Volatile private var loaded = false

    /** Loads and decrypts. Call from a background thread. A value that fails to decrypt (key lost after restore) is dropped. */
    fun load(): Unit = synchronized(lock) {
        if (loaded) return
        val sealed = kv.get(KEY)
        _accounts.value = sealed?.let { runCatching { json.decodeFromString(ser, box.open(it)) }.getOrNull() } ?: emptyList()
        if (sealed != null && _accounts.value.isEmpty()) kv.put(KEY, null)
        loaded = true
    }

    fun get(service: Account.Service): Account? = accounts.value.firstOrNull { it.service == service }

    fun upsert(account: Account) = synchronized(lock) {
        val list = _accounts.value.filterNot { it.service == account.service && it.id == account.id } + account
        // One active account per service: the newest wins.
        commit(list.groupBy { it.service }.map { it.value.last() })
    }

    fun update(service: Account.Service, f: (Account) -> Account) = synchronized(lock) {
        commit(_accounts.value.map { if (it.service == service) f(it) else it })
    }

    fun remove(service: Account.Service) = synchronized(lock) {
        commit(_accounts.value.filterNot { it.service == service })
    }

    /** Last persistence failure (Keystore unusable on this device); the accounts then live in memory for this run. */
    @Volatile var lastError: Throwable? = null; private set

    private fun commit(list: List<Account>) {
        // A Keystore failure must not crash a sign-in: keep the session for this run and report it.
        runCatching { kv.put(KEY, if (list.isEmpty()) null else box.seal(json.encodeToString(ser, list))) }
            .onSuccess { lastError = null }.onFailure { lastError = it }
        _accounts.value = list.toList()
    }

    companion object { const val KEY = "accounts.sealed" }
}
