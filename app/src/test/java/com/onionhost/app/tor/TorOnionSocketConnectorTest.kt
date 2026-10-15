package com.onionhost.app.tor

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.net.ServerSocket
import java.util.concurrent.Executors

class TorOnionSocketConnectorTest {
    private val onion = TorManager.generateValidV3OnionAddress(ByteArray(32) { (it + 1).toByte() })

    @Test
    fun sendsOnionAsSocksDomainAndReturnsConnectedSocket() {
        ServerSocket(0).use { proxy ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val observed = executor.submit<String> {
                    proxy.accept().use { socket ->
                        val input = DataInputStream(socket.getInputStream())
                        val output = socket.getOutputStream()
                        assertArrayEquals(byteArrayOf(5, 1, 0), ByteArray(3).also(input::readFully))
                        output.write(byteArrayOf(5, 0))
                        output.flush()

                        val header = ByteArray(5).also(input::readFully)
                        assertArrayEquals(byteArrayOf(5, 1, 0, 3, 62), header)
                        val host = ByteArray(header[4].toInt() and 0xff).also(input::readFully)
                        assertEquals(onion, String(host, Charsets.US_ASCII))
                        val port = input.readUnsignedShort()
                        assertEquals(80, port)
                        output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 80))
                        output.flush()
                        String(host, Charsets.US_ASCII)
                    }
                }

                TorOnionSocketConnector(socksPort = proxy.localPort).connect(onion, 80).use { socket ->
                    assertTrue(socket.isConnected)
                }
                assertEquals(onion, observed.get())
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun rejectsNonOnionDestinationBeforeOpeningSocket() {
        assertThrows(IllegalArgumentException::class.java) {
            TorOnionSocketConnector().connect("example.com", 80)
        }
    }

    @Test
    fun doesNotReturnSocketWhenSocksRequestFails() {
        ServerSocket(0).use { proxy ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                executor.submit {
                    proxy.accept().use { socket ->
                        socket.getInputStream().readNBytes(3)
                        socket.getOutputStream().write(byteArrayOf(5, 0))
                        socket.getOutputStream().flush()
                        socket.getInputStream().readNBytes(5)
                        socket.getInputStream().readNBytes(62 + 2)
                        socket.getOutputStream().write(byteArrayOf(5, 4, 0, 1, 0, 0, 0, 0, 0, 0))
                        socket.getOutputStream().flush()
                    }
                }
                assertThrows(java.io.IOException::class.java) {
                    TorOnionSocketConnector(socksPort = proxy.localPort).connect(onion, 80)
                }
            } finally {
                executor.shutdownNow()
            }
        }
    }
}
