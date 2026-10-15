package com.onionhost.app.chat.discovery

import com.onionhost.app.tor.TorManager
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale

/**
 * Cryptographic building blocks for a future distributed username directory.
 * This class does not perform network discovery or prove control of an onion
 * service; callers must treat records as untrusted until that is implemented.
 */
object UsernameRegistration {
    const val MIN_USERNAME_LENGTH = 3
    const val MAX_USERNAME_LENGTH = 32
    const val MAX_RECORD_LIFETIME_MS = 30L * 24 * 60 * 60 * 1000
    const val MAX_CLOCK_SKEW_MS = 2L * 60 * 1000

    private const val WIRE_MAGIC = 0x4F485552 // "OHUR"
    private const val WIRE_VERSION = 1
    private const val MAX_WIRE_BYTES = 4096
    private val USERNAME_PATTERN = Regex("[a-z][a-z0-9_]{2,31}")
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()
    private val signatureDomain = "OnionHost username registration v1\u0000".toByteArray(Charsets.US_ASCII)

    data class IdentityKeyPair(val publicKey: ByteArray, val privateKeySeed: ByteArray)

    data class SignedRecord(
        val username: String,
        val onionAddress: String,
        val publicKey: String,
        val issuedAtMillis: Long,
        val expiresAtMillis: Long,
        val signature: String
    )

    sealed interface Verification {
        data class Verified(val record: SignedRecord, val fingerprint: String) : Verification
        data class Rejected(val reason: Rejection) : Verification
    }

    enum class Rejection {
        INVALID_USERNAME,
        INVALID_ONION_ADDRESS,
        MALFORMED_KEY,
        MALFORMED_SIGNATURE,
        INVALID_TIME_RANGE,
        EXPIRED,
        ISSUED_IN_FUTURE,
        IDENTITY_MISMATCH,
        INVALID_SIGNATURE
    }

    sealed interface Resolution {
        data object NotFound : Resolution
        data class Verified(val record: SignedRecord, val fingerprint: String) : Resolution
        data class ConflictingClaims(val username: String, val fingerprints: Set<String>) : Resolution
        data class NoValidRecords(val rejectionCounts: Map<Rejection, Int>) : Resolution
    }

    fun normalizeUsername(input: String): String? {
        val normalized = input.lowercase(Locale.ROOT)
        return normalized.takeIf {
            it.length in MIN_USERNAME_LENGTH..MAX_USERNAME_LENGTH &&
                USERNAME_PATTERN.matches(it) && it.all { ch -> ch.code in 0x21..0x7e }
        }
    }

    fun generateIdentity(random: SecureRandom = SecureRandom()): IdentityKeyPair {
        val generator = Ed25519KeyPairGenerator().apply {
            init(Ed25519KeyGenerationParameters(random))
        }
        val pair = generator.generateKeyPair()
        return IdentityKeyPair(
            publicKey = (pair.public as Ed25519PublicKeyParameters).encoded,
            privateKeySeed = (pair.private as Ed25519PrivateKeyParameters).encoded
        )
    }

    fun sign(
        username: String,
        onionAddress: String,
        identity: IdentityKeyPair,
        issuedAtMillis: Long,
        expiresAtMillis: Long
    ): SignedRecord {
        val cleanUsername = normalizeUsername(username)
            ?: throw IllegalArgumentException("Username must be 3-32 ASCII letters, digits, or underscores and start with a letter.")
        val cleanAddress = onionAddress.lowercase(Locale.ROOT)
        require(cleanAddress.endsWith(".onion") && TorManager.isValidTorV3Address(cleanAddress)) {
            "A valid Tor v3 onion address is required."
        }
        require(identity.privateKeySeed.size == 32 && identity.publicKey.size == 32) {
            "Ed25519 keys must be 32 bytes."
        }
        val derivedPublicKey = Ed25519PrivateKeyParameters(identity.privateKeySeed, 0).generatePublicKey().encoded
        require(derivedPublicKey.contentEquals(identity.publicKey)) { "Identity key pair does not match." }
        require(issuedAtMillis >= 0 && expiresAtMillis > issuedAtMillis &&
            expiresAtMillis - issuedAtMillis <= MAX_RECORD_LIFETIME_MS
        ) {
            "Registration expiry must be after issue time and no more than 30 days later."
        }

        val unsigned = SignedRecord(
            username = cleanUsername,
            onionAddress = cleanAddress,
            publicKey = encoder.encodeToString(identity.publicKey),
            issuedAtMillis = issuedAtMillis,
            expiresAtMillis = expiresAtMillis,
            signature = ""
        )
        val payload = signedBytes(unsigned)
        val signer = Ed25519Signer().apply {
            init(true, Ed25519PrivateKeyParameters(identity.privateKeySeed, 0))
            update(payload, 0, payload.size)
        }
        return unsigned.copy(signature = encoder.encodeToString(signer.generateSignature()))
    }

    fun verify(record: SignedRecord, nowMillis: Long, expectedFingerprint: String? = null): Verification {
        if (normalizeUsername(record.username) != record.username) return Verification.Rejected(Rejection.INVALID_USERNAME)
        if (record.onionAddress != record.onionAddress.lowercase(Locale.ROOT) ||
            !record.onionAddress.endsWith(".onion") || !TorManager.isValidTorV3Address(record.onionAddress)
        ) {
            return Verification.Rejected(Rejection.INVALID_ONION_ADDRESS)
        }
        val publicBytes = decodeCanonical(record.publicKey)
            ?.takeIf { it.size == 32 }
            ?: return Verification.Rejected(Rejection.MALFORMED_KEY)
        val signatureBytes = decodeCanonical(record.signature)
            ?.takeIf { it.size == 64 }
            ?: return Verification.Rejected(Rejection.MALFORMED_SIGNATURE)
        if (record.issuedAtMillis < 0 || record.expiresAtMillis <= record.issuedAtMillis ||
            record.expiresAtMillis - record.issuedAtMillis > MAX_RECORD_LIFETIME_MS
        ) return Verification.Rejected(Rejection.INVALID_TIME_RANGE)
        if (record.issuedAtMillis > nowMillis + MAX_CLOCK_SKEW_MS) return Verification.Rejected(Rejection.ISSUED_IN_FUTURE)
        if (record.expiresAtMillis <= nowMillis) return Verification.Rejected(Rejection.EXPIRED)

        val fingerprint = fingerprint(publicBytes)
        if (expectedFingerprint != null && !fingerprint.equals(expectedFingerprint, ignoreCase = true)) {
            return Verification.Rejected(Rejection.IDENTITY_MISMATCH)
        }

        return try {
            val verifier = Ed25519Signer().apply {
                init(false, Ed25519PublicKeyParameters(publicBytes, 0))
            }
            val payload = signedBytes(record)
            verifier.update(payload, 0, payload.size)
            if (verifier.verifySignature(signatureBytes)) Verification.Verified(record, fingerprint)
            else Verification.Rejected(Rejection.INVALID_SIGNATURE)
        } catch (_: Exception) {
            Verification.Rejected(Rejection.INVALID_SIGNATURE)
        }
    }

    /**
     * Resolves a set of untrusted directory answers. Different signing keys
     * claiming the same normalized username are reported as a conflict rather
     * than selecting a node's preferred answer.
     */
    fun resolve(records: List<SignedRecord>, username: String, nowMillis: Long): Resolution {
        val normalized = normalizeUsername(username) ?: return Resolution.NotFound
        if (records.isEmpty()) return Resolution.NotFound
        val checked = records.map { it to verify(it, nowMillis) }
        val verified = checked.mapNotNull { (_, result) -> result as? Verification.Verified }
            .filter { it.record.username == normalized }
        if (verified.isEmpty()) {
            val rejections = checked.mapNotNull { (record, result) ->
                if (normalizeUsername(record.username) == normalized) (result as? Verification.Rejected)?.reason else null
            }.groupingBy { it }.eachCount()
            if (rejections.isEmpty()) return Resolution.NotFound
            return Resolution.NoValidRecords(rejections)
        }
        val byIdentity = verified.groupBy { it.fingerprint }
        if (byIdentity.size > 1) {
            return Resolution.ConflictingClaims(normalized, byIdentity.keys)
        }
        val latest = verified.maxBy { it.record.issuedAtMillis }
        return Resolution.Verified(latest.record, latest.fingerprint)
    }

    /** Encodes a bounded, versioned wire record suitable for untrusted responses. */
    fun encode(record: SignedRecord): String {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeInt(WIRE_MAGIC)
            data.writeShort(WIRE_VERSION)
            data.writeUTF(record.username)
            data.writeUTF(record.onionAddress)
            data.writeUTF(record.publicKey)
            data.writeLong(record.issuedAtMillis)
            data.writeLong(record.expiresAtMillis)
            data.writeUTF(record.signature)
        }
        require(output.size() <= MAX_WIRE_BYTES) { "Registration record is too large." }
        return encoder.encodeToString(output.toByteArray())
    }

    fun decode(encoded: String): SignedRecord? {
        if (encoded.length > MAX_WIRE_BYTES * 2 || encoded.isBlank()) return null
        val bytes = decodeCanonical(encoded)?.takeIf { it.size <= MAX_WIRE_BYTES } ?: return null
        return try {
            DataInputStream(ByteArrayInputStream(bytes)).use { data ->
                if (data.readInt() != WIRE_MAGIC || data.readUnsignedShort() != WIRE_VERSION) return null
                val record = SignedRecord(
                    username = data.readUTF(),
                    onionAddress = data.readUTF(),
                    publicKey = data.readUTF(),
                    issuedAtMillis = data.readLong(),
                    expiresAtMillis = data.readLong(),
                    signature = data.readUTF()
                )
                if (data.available() != 0) null else record
            }
        } catch (_: Exception) {
            null
        }
    }

    fun fingerprint(publicKey: ByteArray): String {
        require(publicKey.size == 32) { "Ed25519 public keys must be 32 bytes." }
        val digest = SHA256Digest()
        val output = ByteArray(digest.digestSize)
        digest.update(publicKey, 0, publicKey.size)
        digest.doFinal(output, 0)
        return output.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun signedBytes(record: SignedRecord): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.write(signatureDomain)
            data.writeUTF(record.username)
            data.writeUTF(record.onionAddress)
            data.writeUTF(record.publicKey)
            data.writeLong(record.issuedAtMillis)
            data.writeLong(record.expiresAtMillis)
        }
        return output.toByteArray()
    }

    private fun decodeCanonical(value: String): ByteArray? = try {
        decoder.decode(value).takeIf { encoder.encodeToString(it) == value }
    } catch (_: IllegalArgumentException) {
        null
    }
}
