package com.onionhost.app.chat.discovery

import com.onionhost.app.tor.TorManager
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException

class UsernameDiscoveryRepositoryTest {
    private val now = 1_800_000_000_000L
    private val onion = TorManager.generateValidV3OnionAddress(ByteArray(32) { (it + 5).toByte() })
    private val nodeA = TorManager.generateValidV3OnionAddress(ByteArray(32) { (it + 10).toByte() })
    private val nodeB = TorManager.generateValidV3OnionAddress(ByteArray(32) { (it + 50).toByte() })

    @Test fun parsesMultipleOnionNodesAndOptionalPorts() {
        assertEquals(listOf(DiscoveryNode(nodeA), DiscoveryNode(nodeB, 8080)), DiscoveryNode.parseList("$nodeA\n$nodeB:8080"))
    }

    @Test fun rejectsClearnetOrMalformedNodes() {
        assertFalse(runCatching { DiscoveryNode.parseList("https://example.com") }.isSuccess)
        assertFalse(runCatching { DiscoveryNode.parseList("bad.onion") }.isSuccess)
    }

    @Test fun returnsVerifiedRecordFromConfiguredNode() = runBlocking {
        val record = signed("rahim")
        val repo = UsernameDiscoveryRepository(DiscoveryNodeClient { _, _ -> listOf(UsernameRegistration.encode(record)) })
        val result = repo.lookup("RAHIM", nodeA, now)
        assertTrue(result is UsernameLookupResult.Found)
        val found = result as UsernameLookupResult.Found
        assertEquals("rahim", found.record.username)
        assertEquals(UsernameRegistration.verify(record, now).let { (it as UsernameRegistration.Verification.Verified).fingerprint }, found.fingerprint)
        assertFalse(found.isPinned)
    }

    @Test fun returnsNotFoundOnlyAfterAConfiguredNodeReplies() = runBlocking {
        val repo = UsernameDiscoveryRepository(DiscoveryNodeClient { _, _ -> emptyList() })
        assertEquals(UsernameLookupResult.NotFound(1, 0), repo.lookup("rahim", nodeA, now))
        assertEquals(UsernameLookupResult.NotConfigured, repo.lookup("rahim", "", now))
    }

    @Test fun rejectsBadSignatureAndExpiredRecord() = runBlocking {
        val good = signed("rahim")
        val invalid = good.copy(signature = "AAAAAAAA")
        val invalidRepo = UsernameDiscoveryRepository(DiscoveryNodeClient { _, _ -> listOf(UsernameRegistration.encode(invalid)) })
        assertTrue(invalidRepo.lookup("rahim", nodeA, now) is UsernameLookupResult.InvalidRecords)

        val expired = sign("rahim", now - 10_000, now - 1)
        val expiredRepo = UsernameDiscoveryRepository(DiscoveryNodeClient { _, _ -> listOf(UsernameRegistration.encode(expired)) })
        assertTrue(expiredRepo.lookup("rahim", nodeA, now) is UsernameLookupResult.InvalidRecords)
    }

    @Test fun rejectsMalformedWireAndReportsConflictingIdentities() = runBlocking {
        val malformed = UsernameDiscoveryRepository(DiscoveryNodeClient { _, _ -> listOf("not-a-record") })
        assertTrue(malformed.lookup("rahim", nodeA, now) is UsernameLookupResult.InvalidRecords)

        val first = signed("rahim")
        val second = sign("rahim", now - 1, now + 10_000, ByteArray(32) { (it + 20).toByte() })
        val conflict = UsernameDiscoveryRepository(DiscoveryNodeClient { node, _ ->
            if (node.onionAddress == nodeA) listOf(UsernameRegistration.encode(first))
            else listOf(UsernameRegistration.encode(second))
        })
        assertTrue(conflict.lookup("rahim", "$nodeA\n$nodeB", now) is UsernameLookupResult.Conflict)
    }

    @Test fun preventsSilentReplacementOfPinnedIdentity() = runBlocking {
        val first = signed("rahim")
        val replacement = sign("rahim", now - 1, now + 10_000, ByteArray(32) { (it + 70).toByte() })
        val repo = UsernameDiscoveryRepository(DiscoveryNodeClient { _, _ -> listOf(UsernameRegistration.encode(replacement)) })
        val result = repo.lookup("rahim", nodeA, now, UsernameRegistration.verify(first, now).let { (it as UsernameRegistration.Verification.Verified).fingerprint })
        assertTrue(result is UsernameLookupResult.IdentityChanged)
    }

    @Test fun distinguishesTimeoutFromOfflineNode() = runBlocking {
        val timeout = UsernameDiscoveryRepository(DiscoveryNodeClient { _, _ -> throw SocketTimeoutException() })
        assertEquals(UsernameLookupResult.Timeout, timeout.lookup("rahim", nodeA, now))
        val offline = UsernameDiscoveryRepository(DiscoveryNodeClient { _, _ -> throw ConnectException() })
        assertEquals(UsernameLookupResult.Offline, offline.lookup("rahim", nodeA, now))
    }

    @Test fun cancellationPropagatesAndLookupCanBeRetried() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cancellable = UsernameDiscoveryRepository(DiscoveryNodeClient { _, _ ->
            started.complete(Unit)
            awaitCancellation()
        })
        val job = launch { cancellable.lookup("rahim", nodeA, now) }
        started.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)

        var attempts = 0
        val record = signed("rahim")
        val retryable = UsernameDiscoveryRepository(DiscoveryNodeClient { _, _ ->
            attempts++
            if (attempts == 1) throw ConnectException()
            listOf(UsernameRegistration.encode(record))
        })
        assertEquals(UsernameLookupResult.Offline, retryable.lookup("rahim", nodeA, now))
        assertTrue(retryable.lookup("rahim", nodeA, now) is UsernameLookupResult.Found)
    }

    private fun signed(username: String): UsernameRegistration.SignedRecord = sign(username, now - 1_000, now + 20_000)

    private fun sign(
        username: String,
        issued: Long,
        expires: Long,
        seed: ByteArray = ByteArray(32) { (it + 1).toByte() }
    ): UsernameRegistration.SignedRecord {
        val privateParams = org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(seed, 0)
        val identity = UsernameRegistration.IdentityKeyPair(privateParams.generatePublicKey().encoded, privateParams.encoded)
        return UsernameRegistration.sign(username, onion, identity, issued, expires)
    }
}
