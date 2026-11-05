package com.ravi.musicspot

import com.ravi.musicspot.account.Account
import com.ravi.musicspot.account.AccountStore
import com.ravi.musicspot.account.KeyValue
import com.ravi.musicspot.core.GcmBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator

private class MemKv : KeyValue {
    val map = ConcurrentHashMap<String, String>()
    override fun get(key: String) = map[key]
    override fun put(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
}

class AccountStoreTest {
    private fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private fun acc(s: Account.Service, id: String, secret: String = "SAPISID=secret-$id") = Account(id, s, name = "n$id", secret = secret)

    @Test fun roundTripEncryptedAtRest() {
        val kv = MemKv(); val k = key()
        val s = AccountStore(kv, GcmBox { k }).also { it.load() }
        s.upsert(acc(Account.Service.YOUTUBE, "1", "SAPISID=topsecret"))
        s.upsert(acc(Account.Service.SPOTIFY, "2", "sp_dc=alsosecret"))
        val sealed = kv.get(AccountStore.KEY)!!
        assertFalse(sealed.contains("topsecret")); assertFalse(sealed.contains("alsosecret")); assertFalse(sealed.contains("SPOTIFY"))
        val reloaded = AccountStore(kv, GcmBox { k }).also { it.load() }
        assertEquals(s.accounts.value.toSet(), reloaded.accounts.value.toSet())
        assertEquals("SAPISID=topsecret", reloaded.get(Account.Service.YOUTUBE)?.secret)
        assertFalse(reloaded.get(Account.Service.YOUTUBE).toString().contains("topsecret"))
    }

    @Test fun oneAccountPerServiceNewestWins() {
        val s = AccountStore(MemKv(), GcmBox(key().let { k -> { k } })).also { it.load() }
        s.upsert(acc(Account.Service.YOUTUBE, "a")); s.upsert(acc(Account.Service.YOUTUBE, "b"))
        assertEquals(listOf("b"), s.accounts.value.map { it.id })
        s.update(Account.Service.YOUTUBE) { it.copy(name = "Ravi") }
        assertEquals("Ravi", s.get(Account.Service.YOUTUBE)?.name)
        s.remove(Account.Service.YOUTUBE)
        assertNull(s.get(Account.Service.YOUTUBE))
    }

    @Test fun removingLastAccountClearsStorage() {
        val kv = MemKv()
        val s = AccountStore(kv, GcmBox(key().let { k -> { k } })).also { it.load() }
        s.upsert(acc(Account.Service.JELLYFIN, "j")); s.remove(Account.Service.JELLYFIN)
        assertNull(kv.get(AccountStore.KEY))
    }

    @Test fun undecryptableDataIsDropped() {
        val kv = MemKv()
        AccountStore(kv, GcmBox(key().let { k -> { k } })).also { it.load() }.upsert(acc(Account.Service.YOUTUBE, "1"))
        val other = AccountStore(kv, GcmBox(key().let { k -> { k } })).also { it.load() } // key lost (e.g. restored to new device)
        assertTrue(other.accounts.value.isEmpty())
        assertNull(kv.get(AccountStore.KEY))
    }

    @Test fun concurrentMutationsStayConsistent() {
        val kv = MemKv(); val k = key()
        val s = AccountStore(kv, GcmBox { k }).also { it.load() }
        val services = Account.Service.entries
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val errors = ConcurrentHashMap.newKeySet<Throwable>()
        repeat(8) { t ->
            pool.execute {
                start.await()
                try {
                    repeat(150) { i ->
                        val svc = services[(t + i) % services.size]
                        when (i % 5) {
                            0, 1, 2 -> s.upsert(acc(svc, "t$t-$i"))
                            3 -> s.update(svc) { it.copy(name = "u$t") }
                            else -> if (t % 4 == 0) s.remove(svc) else s.upsert(acc(svc, "x$t-$i"))
                        }
                        // Readers always see a valid snapshot: never two accounts for one service.
                        val snap = s.accounts.value
                        if (snap.groupBy { it.service }.any { it.value.size > 1 }) error("duplicate service in snapshot")
                    }
                } catch (e: Throwable) { errors += e }
            }
        }
        start.countDown(); pool.shutdown(); assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS))
        assertTrue(errors.toString(), errors.isEmpty())
        // Persisted state equals the in-memory state.
        val reloaded = AccountStore(kv, GcmBox { k }).also { it.load() }
        assertEquals(s.accounts.value.sortedBy { it.service }, reloaded.accounts.value.sortedBy { it.service })
        assertTrue(s.accounts.value.map { it.service }.toSet().size == s.accounts.value.size)
    }
}
