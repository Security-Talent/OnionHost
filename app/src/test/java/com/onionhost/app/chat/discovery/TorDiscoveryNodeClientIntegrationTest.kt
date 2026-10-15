package com.onionhost.app.chat.discovery

import com.onionhost.app.tor.TorManager
import com.onionhost.app.tor.TorOnionSocketConnector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.ServerSocket
import java.util.concurrent.Executors

/** Exercises the real discovery client and SOCKS adapter against a local protocol peer. */
class TorDiscoveryNodeClientIntegrationTest {
    @Test fun queriesConfiguredOnionUsingTorDomainSocksAndVerifiesSignedResponse() {
        val now = System.currentTimeMillis()
        val recordOnion = TorManager.generateValidV3OnionAddress(ByteArray(32) { (it + 1).toByte() })
        val nodeAddress = TorManager.generateValidV3OnionAddress(ByteArray(32) { (it + 30).toByte() })
        val privateKey = org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(ByteArray(32) { 9 }, 0)
        val identity = UsernameRegistration.IdentityKeyPair(privateKey.generatePublicKey().encoded, privateKey.encoded)
        val record = UsernameRegistration.sign("rahim", recordOnion, identity, now - 1_000, now + 60_000)
        val encoded = UsernameRegistration.encode(record)

        ServerSocket(0).use { proxy ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val server = executor.submit<String> {
                    proxy.accept().use { socket ->
                        val input = DataInputStream(socket.getInputStream())
                        val output = socket.getOutputStream()
                        assertEquals(listOf(5, 1, 0), input.readNBytes(3).map { it.toInt() and 0xff })
                        output.write(byteArrayOf(5, 0)); output.flush()
                        val requestHeader = input.readNBytes(5)
                        assertEquals(3, requestHeader[3].toInt())
                        val domain = String(input.readNBytes(requestHeader[4].toInt() and 0xff), Charsets.US_ASCII)
                        assertEquals(nodeAddress, domain)
                        assertEquals(80, input.readUnsignedShort())
                        output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 80)); output.flush()

                        val http = ByteArrayOutputStream()
                        var matched = 0
                        while (matched < 4) {
                            val b = input.read()
                            if (b < 0) error("Client closed before HTTP request completed")
                            http.write(b)
                            matched = when {
                                matched == 0 && b == 13 -> 1
                                matched == 1 && b == 10 -> 2
                                matched == 2 && b == 13 -> 3
                                matched == 3 && b == 10 -> 4
                                b == 13 -> 1
                                else -> 0
                            }
                        }
                        val request = http.toString(Charsets.US_ASCII.name())
                        assertTrue(request.startsWith("GET /.well-known/onionhost/users/rahim HTTP/1.0"))
                        val body = "$encoded\n".toByteArray(Charsets.US_ASCII)
                        output.write("HTTP/1.0 200 OK\r\nContent-Type: text/plain\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray(Charsets.US_ASCII))
                        output.write(body); output.flush()
                        request
                    }
                }

                val client = TorDiscoveryNodeClient(TorOnionSocketConnector(socksPort = proxy.localPort))
                val repository = UsernameDiscoveryRepository(client)
                val result = kotlinx.coroutines.runBlocking {
                    repository.lookup("rahim", nodeAddress, now)
                }
                assertTrue(result is UsernameLookupResult.Found)
                assertEquals(record.username, (result as UsernameLookupResult.Found).record.username)
                assertTrue(server.get().contains("Host: $nodeAddress"))
            } finally {
                executor.shutdownNow()
            }
        }
    }
}
