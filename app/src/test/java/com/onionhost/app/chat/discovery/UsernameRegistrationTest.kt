package com.onionhost.app.chat.discovery

import com.onionhost.app.tor.TorManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class UsernameRegistrationTest {
    private val now = 1_800_000_000_000L
    private val address = TorManager.generateValidV3OnionAddress(ByteArray(32) { (it + 1).toByte() })

    @Test
    fun normalizesAsciiNamesAndRejectsAmbiguousCharacters() {
        assertEquals("securi3ytalent", UsernameRegistration.normalizeUsername("Securi3yTalent"))
        assertNull(UsernameRegistration.normalizeUsername("ab"))
        assertNull(UsernameRegistration.normalizeUsername("1mehedi"))
        assertNull(UsernameRegistration.normalizeUsername("mehedi.name"))
        assertNull(UsernameRegistration.normalizeUsername("mēhedi"))
        assertNull(UsernameRegistration.normalizeUsername(" user "))
    }

    @Test
    fun signedRecordRoundTripsAndVerifies() {
        val identity = UsernameRegistration.generateIdentity()
        val record = UsernameRegistration.sign(
            "Mehedi", address, identity, now, now + 60_000
        )

        val decoded = UsernameRegistration.decode(UsernameRegistration.encode(record))
        assertNotNull(decoded)
        assertEquals(record, decoded)
        assertTrue(UsernameRegistration.verify(record, now) is UsernameRegistration.Verification.Verified)
    }

    @Test
    fun changingSignedFieldsInvalidatesSignature() {
        val identity = UsernameRegistration.generateIdentity()
        val record = UsernameRegistration.sign("rahim", address, identity, now, now + 60_000)

        val modified = record.copy(onionAddress = TorManager.generateValidV3OnionAddress(ByteArray(32) { 7 }))
        assertEquals(
            UsernameRegistration.Rejection.INVALID_SIGNATURE,
            (UsernameRegistration.verify(modified, now) as UsernameRegistration.Verification.Rejected).reason
        )
        assertEquals(
            UsernameRegistration.Rejection.IDENTITY_MISMATCH,
            (UsernameRegistration.verify(record, now, "00".repeat(32)) as UsernameRegistration.Verification.Rejected).reason
        )
        assertFalse(runCatching {
            UsernameRegistration.sign(
                "rahim", address,
                identity.copy(publicKey = UsernameRegistration.generateIdentity().publicKey),
                now, now + 60_000
            )
        }.isSuccess)
    }

    @Test
    fun rejectsExpiredMalformedAndOverlongRecords() {
        val identity = UsernameRegistration.generateIdentity()
        val record = UsernameRegistration.sign("rahim", address, identity, now - 120_000, now - 1)
        assertEquals(
            UsernameRegistration.Rejection.EXPIRED,
            (UsernameRegistration.verify(record, now) as UsernameRegistration.Verification.Rejected).reason
        )
        assertNull(UsernameRegistration.decode("!!!"))
        assertEquals(
            UsernameRegistration.Rejection.MALFORMED_SIGNATURE,
            (UsernameRegistration.verify(record.copy(signature = "bad"), now - 120_000) as UsernameRegistration.Verification.Rejected).reason
        )
        assertFalse(runCatching {
            UsernameRegistration.sign("rahim", address, identity, now, now + UsernameRegistration.MAX_RECORD_LIFETIME_MS + 1)
        }.isSuccess)
        assertFalse(runCatching {
            UsernameRegistration.sign("rahim", "not-an-onion", identity, now, now + 60_000)
        }.isSuccess)
        assertEquals(
            UsernameRegistration.Rejection.ISSUED_IN_FUTURE,
            (UsernameRegistration.verify(
                UsernameRegistration.sign("rahim", address, identity, now + 10 * 60_000, now + 11 * 60_000), now
            ) as UsernameRegistration.Verification.Rejected).reason
        )
    }

    @Test
    fun reportsConflictingKeysInsteadOfTrustingFirstDirectoryAnswer() {
        val first = UsernameRegistration.sign("rahim", address, UsernameRegistration.generateIdentity(), now, now + 60_000)
        val second = UsernameRegistration.sign("rahim", address, UsernameRegistration.generateIdentity(), now, now + 60_000)

        val result = UsernameRegistration.resolve(listOf(first, second), "RAHIM", now)
        assertTrue(result is UsernameRegistration.Resolution.ConflictingClaims)
        assertEquals(2, (result as UsernameRegistration.Resolution.ConflictingClaims).fingerprints.size)
    }

    @Test
    fun ignoresInvalidDirectoryResponsesButReturnsNoValidRecord() {
        val identity = UsernameRegistration.generateIdentity()
        val valid = UsernameRegistration.sign("rahim", address, identity, now, now + 60_000)
        assertTrue(UsernameRegistration.resolve(listOf(valid), "rahim", now) is UsernameRegistration.Resolution.Verified)
        assertEquals(UsernameRegistration.Resolution.NotFound, UsernameRegistration.resolve(listOf(valid), "other", now))
        val malformed = valid.copy(signature = "bad")
        val rejected = UsernameRegistration.resolve(listOf(malformed), "rahim", now)
        assertTrue(rejected is UsernameRegistration.Resolution.NoValidRecords)
        assertEquals(1, (rejected as UsernameRegistration.Resolution.NoValidRecords).rejectionCounts[UsernameRegistration.Rejection.MALFORMED_SIGNATURE])
    }

    @Test
    fun rejectsUnknownVersionTrailingBytesAndOversizedDiscoveryPayloads() {
        val identity = UsernameRegistration.generateIdentity()
        val wire = UsernameRegistration.encode(
            UsernameRegistration.sign("rahim", address, identity, now, now + 60_000)
        )
        val decoded = Base64.getUrlDecoder().decode(wire)

        val unknownVersion = decoded.copyOf().apply { this[5] = 2 }
        val withTrailingByte = decoded + byteArrayOf(0)
        assertNull(UsernameRegistration.decode(Base64.getUrlEncoder().withoutPadding().encodeToString(unknownVersion)))
        assertNull(UsernameRegistration.decode(Base64.getUrlEncoder().withoutPadding().encodeToString(withTrailingByte)))
        assertNull(UsernameRegistration.decode("A".repeat(10_000)))
    }

    @Test
    fun repeatedClaimBySameIdentityIsStableAndDifferentUsernameIsNotFound() {
        val identity = UsernameRegistration.generateIdentity()
        val first = UsernameRegistration.sign("rahim", address, identity, now, now + 60_000)
        val refreshed = UsernameRegistration.sign("rahim", address, identity, now + 1_000, now + 61_000)

        val resolved = UsernameRegistration.resolve(listOf(first, refreshed), "RAHIM", now)
        assertTrue(resolved is UsernameRegistration.Resolution.Verified)
        assertEquals(refreshed, (resolved as UsernameRegistration.Resolution.Verified).record)
        assertEquals(UsernameRegistration.Resolution.NotFound, UsernameRegistration.resolve(listOf(first), "other", now))
    }
}
