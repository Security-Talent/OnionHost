package com.onionhost.app.tor

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/** Opens a TCP stream to a v3 onion service through the app's local Tor SOCKS5 listener. */
class TorOnionSocketConnector(
    private val socksHost: String = "127.0.0.1",
    private val socksPort: Int = TorManager.SOCKS_PORT,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000
) {
    init {
        require(socksHost == "127.0.0.1" || socksHost == "::1") { "Tor SOCKS must be loopback-only." }
        require(socksPort in 1..65535)
        require(connectTimeoutMs > 0 && readTimeoutMs > 0)
    }

    /**
     * Returns a connected socket only after Tor accepts the onion stream.
     * The caller owns and must close the returned socket. This method performs
     * blocking I/O and must be called from an IO dispatcher.
     */
    @Throws(IOException::class)
    fun connect(onionAddress: String, targetPort: Int): Socket {
        val hostname = onionAddress.trim().lowercase()
        require(TorManager.isValidTorV3Address(hostname)) { "A valid v3 onion address is required." }
        require(targetPort in 1..65535) { "Invalid target port." }

        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(socksHost, socksPort), connectTimeoutMs)
            socket.soTimeout = readTimeoutMs
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            // SOCKS5, one method, no authentication. The hostname stays a domain
            // name in the SOCKS request; it is never resolved by Android DNS.
            output.write(byteArrayOf(0x05, 0x01, 0x00))
            output.flush()
            val methodReply = readExactly(input, 2)
            if (methodReply[0] != 0x05.toByte() || methodReply[1] != 0x00.toByte()) {
                throw IOException("Tor SOCKS5 proxy did not accept no-auth negotiation.")
            }

            val hostBytes = hostname.toByteArray(Charsets.US_ASCII)
            if (hostBytes.size > 255) throw IOException("Onion hostname is too long.")
            output.write(byteArrayOf(0x05, 0x01, 0x00, 0x03, hostBytes.size.toByte()))
            output.write(hostBytes)
            output.write(byteArrayOf((targetPort ushr 8).toByte(), targetPort.toByte()))
            output.flush()

            val reply = readExactly(input, 4)
            if (reply[0] != 0x05.toByte()) throw IOException("Invalid Tor SOCKS5 response.")
            if (reply[1] != 0x00.toByte()) throw IOException("Tor could not connect to the onion service (SOCKS status ${reply[1].toInt() and 0xff}).")
            when (reply[3].toInt() and 0xff) {
                0x01 -> readExactly(input, 4)
                0x04 -> readExactly(input, 16)
                0x03 -> readExactly(input, readExactly(input, 1)[0].toInt() and 0xff)
                else -> throw IOException("Invalid Tor SOCKS5 address type.")
            }
            readExactly(input, 2) // bound port
            return socket
        } catch (error: Exception) {
            try { socket.close() } catch (_: Exception) { }
            if (error is IOException) throw error
            throw IOException("Unable to establish onion-only Tor connection.", error)
        }
    }

    private fun readExactly(input: java.io.InputStream, size: Int): ByteArray {
        val result = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = input.read(result, offset, size - offset)
            if (count < 0) throw IOException("Tor SOCKS5 proxy closed the connection unexpectedly.")
            offset += count
        }
        return result
    }
}
