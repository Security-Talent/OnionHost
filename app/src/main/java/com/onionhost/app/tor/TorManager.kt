package com.onionhost.app.tor

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException

class TorManager(private val context: Context) {

    private val _torStatus = MutableStateFlow(TorStatus())
    val torStatus: StateFlow<TorStatus> = _torStatus.asStateFlow()

    private val _torLogFlow = MutableSharedFlow<String>(extraBufferCapacity = 100)
    val torLogFlow: SharedFlow<String> = _torLogFlow.asSharedFlow()

    private val torDir: File
        get() = File(context.filesDir, "tor").apply { if (!exists()) mkdirs() }

    private val hiddenServiceDir: File
        get() = File(torDir, "hs_website").apply { if (!exists()) mkdirs() }

    private var torProcess: Process? = null
    @Volatile private var stoppingTorProcess: Process? = null
    private var torJob: Job? = null

    private val publicationTimeoutMs = 10 * 60 * 1000L

    @Volatile
    private var isTorPublished = false

    @Volatile
    private var clientOnlyMode = false

    /**
     * Starts official Tor daemon process and provisions the Hidden Service for local website port.
     */
    fun startTor(localPort: Int, coroutineScope: CoroutineScope) {
        if (isTorActive()) {
            if (!clientOnlyMode) return
            val oldProcess = torProcess
            stopTor() // Reconfigure the client-only daemon to publish the host service.
            runCatching { oldProcess?.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) }
        }
        clientOnlyMode = false
        startTorDaemon(localPort, coroutineScope)
    }

    /** Starts a SOCKS-only Tor client for visiting onion services; it publishes no local service. */
    fun startTorClient(coroutineScope: CoroutineScope) {
        if (isTorActive()) return // Reuse either the client-only or the hosting daemon.
        clientOnlyMode = true
        startTorDaemon(null, coroutineScope)
    }

    private fun isTorActive() = _torStatus.value.state == TorState.RUNNING ||
        _torStatus.value.state == TorState.STARTING || _torStatus.value.state == TorState.BOOTSTRAPPING

    private fun startTorDaemon(localPort: Int?, coroutineScope: CoroutineScope) {
        isTorPublished = false
        _torStatus.value = TorStatus(state = TorState.STARTING, bootstrapProgress = 5, isHosting = localPort != null)
        emitLog(if (localPort == null) "Starting Tor client for onion connections..." else "Initializing Tor service for local port 127.0.0.1:$localPort...")
        torJob = coroutineScope.launch(Dispatchers.IO) {
            try {
                stoppingTorProcess?.let { old ->
                    if (!runCatching { old.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(true)) old.destroyForcibly()
                    stoppingTorProcess = null
                }
                if (localPort != null) {
                    emitLog("Preparing Tor v3 Hidden Service directory at ${hiddenServiceDir.absolutePath}...")
                    removeLegacyGeneratedKeys()
                }
                val torrcFile = if (localPort == null) createTorClientConfigFile() else createTorConfigFile(localPort)
                val torExecutable = getTorBinary()
                if (torExecutable == null || !torExecutable.exists()) {
                    throw IOException("Bundled Tor daemon is unavailable.")
                }
                _torStatus.value = TorStatus(state = TorState.BOOTSTRAPPING, bootstrapProgress = 15, isHosting = localPort != null)
                val process = startTorProcess(torExecutable, torrcFile)
                torProcess = process
                launch {
                    try {
                        process.inputStream.bufferedReader().useLines { lines -> lines.forEach { line -> if (isActive) parseTorLogLine(line) } }
                    } catch (e: Exception) { emitLog("Tor log reader exception: ${e.localizedMessage}") }
                }
                launch {
                    val exitCode = process.waitFor()
                    if (isActive && _torStatus.value.state != TorState.ERROR) {
                        val message = "Tor daemon stopped unexpectedly (exit code $exitCode)."
                        emitLog("[ERROR] $message")
                        _torStatus.value = TorStatus(state = TorState.ERROR, errorMessage = message, isHosting = localPort != null)
                    }
                }
                if (localPort != null) pollForOnionAddress() else {
                    withTimeoutOrNull(publicationTimeoutMs) {
                        while (_torStatus.value.state != TorState.RUNNING && _torStatus.value.state != TorState.ERROR) delay(250)
                    }
                    if (_torStatus.value.state != TorState.RUNNING && _torStatus.value.state != TorState.ERROR) {
                        _torStatus.value = TorStatus(state = TorState.ERROR, errorMessage = "Tor client bootstrap timed out.", isHosting = false)
                    }
                }
            } catch (e: Exception) {
                emitLog("[ERROR] Failed to start Tor: ${e.localizedMessage}")
                _torStatus.value = TorStatus(state = TorState.ERROR, errorMessage = e.localizedMessage ?: "Failed to start Tor", isHosting = localPort != null)
            }
        }
    }

    private fun emitLog(msg: String) {
        Log.d("TorManager", msg)
        _torLogFlow.tryEmit(msg)
    }

    /**
     * Some Android versions refuse to exec a PIE binary whose file name ends in
     * .so.  The bundled Tor artifact is a PIE executable (ET_DYN), so retrying
     * it through the system dynamic linker is required on those devices.
     */
    @Throws(IOException::class)
    private fun startTorProcess(torExecutable: File, torrcFile: File): Process {
        val baseArgs = listOf(torExecutable.absolutePath, "-f", torrcFile.absolutePath)
        val attempts = mutableListOf<List<String>>()

        // Tor is packaged as an ET_DYN PIE executable named libtor.so. On a
        // number of Android releases ProcessBuilder can spawn that file but the
        // process exits immediately because direct execution from app storage
        // is denied. Invoke Android's linker first, then retain direct execution
        // as a compatibility fallback for ET_EXEC builds.
        if (TorBinaryAnalyzer.analyzeBinary(context, torExecutable).type == BinaryType.SHARED_LIBRARY) {
            val linker = if (android.os.Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) {
                "/system/bin/linker64"
            } else {
                "/system/bin/linker"
            }
            if (File(linker).canExecute()) {
                attempts += listOf(linker) + baseArgs
            }
        }
        attempts += baseArgs

        var lastError: IOException? = null
        for (command in attempts) {
            try {
                emitLog("Launching Tor daemon: ${command.first()}")
                return ProcessBuilder(command).apply {
                    directory(torDir)
                    environment()["HOME"] = torDir.absolutePath
                    environment()["TMPDIR"] = context.cacheDir.absolutePath
                    environment()["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir
                    redirectErrorStream(true)
                }.start()
            } catch (e: IOException) {
                lastError = e
                emitLog("[WARN] Launch attempt failed: ${e.localizedMessage}")
            }
        }
        throw lastError ?: IOException("No supported Tor launch command was available.")
    }

    /** Removes only key files written by OnionHost's old non-Tor generator. */
    private fun removeLegacyGeneratedKeys() {
        val secretKey = File(hiddenServiceDir, "hs_ed25519_secret_key")
        val legacyHeader = "tag: secret-key v3".toByteArray(Charsets.US_ASCII)
        val isLegacy = try {
            secretKey.exists() && secretKey.inputStream().use { input ->
                val prefix = ByteArray(legacyHeader.size)
                input.read(prefix) == prefix.size && prefix.contentEquals(legacyHeader)
            }
        } catch (_: Exception) {
            false
        }
        if (isLegacy) {
            listOf(
                "hs_ed25519_secret_key",
                "hs_ed25519_public_key",
                "hostname"
            ).forEach { File(hiddenServiceDir, it).delete() }
            emitLog("Removed legacy generated hidden-service keys; Tor will create real v3 keys.")
        }
    }

    private fun parseTorLogLine(line: String) {
        emitLog("[Tor Log] $line")

        if (line.contains("[err]") || line.contains("[error]")) {
            val errMsg = line.substringAfter("[err]").substringAfter("[error]").trim()
            _torStatus.value = TorStatus(
                state = TorState.ERROR,
                errorMessage = if (errMsg.isNotBlank()) errMsg else line,
                isHosting = !clientOnlyMode
            )
        } else if (line.contains("Uploaded onion service descriptor", ignoreCase = true) ||
            line.contains("Successfully uploaded onion service descriptor", ignoreCase = true) ||
            line.contains("Published onion service descriptor", ignoreCase = true) ||
            line.contains("Uploaded hidden service descriptor", ignoreCase = true) ||
            line.contains("Successfully uploaded hidden service descriptor", ignoreCase = true) ||
            line.contains("Uploaded rendezvous descriptor", ignoreCase = true)) {
            isTorPublished = true
            val onionAddr = readOnionHostname()
            if (onionAddr.isNotBlank()) {
                _torStatus.value = TorStatus(
                    state = TorState.RUNNING,
                    bootstrapProgress = 100,
                    onionAddress = onionAddr,
                    isHosting = true
                )
                emitLog("Tor v3 Onion Service descriptor published to Tor network: http://$onionAddr/")
            } else {
                _torStatus.value = TorStatus(
                    state = TorState.BOOTSTRAPPING,
                    bootstrapProgress = 100,
                    isHosting = true
                )
            }
        } else if (line.contains("Bootstrapped")) {
            val match = Regex("Bootstrapped (\\d+)%").find(line)
            val percent = match?.groupValues?.get(1)?.toIntOrNull() ?: 50
            // A hostname file is created locally before the hidden-service
            // descriptor becomes reachable. Do not advertise it as live until
            // Tor confirms that the descriptor was uploaded to the network.
            _torStatus.value = if (clientOnlyMode && percent >= 100) {
                TorStatus(state = TorState.RUNNING, bootstrapProgress = 100, isHosting = false)
            } else TorStatus(state = TorState.BOOTSTRAPPING, bootstrapProgress = percent, isHosting = !clientOnlyMode)
            if (percent >= 100) {
                emitLog("Tor is connected; waiting for Onion Service descriptor publication before sharing the address.")
            }
        }
    }

    private suspend fun pollForOnionAddress() {
        var attempts = 0
        val maxAttempts = (publicationTimeoutMs / 1000).toInt()
        while (attempts < maxAttempts && _torStatus.value.state != TorState.ERROR) {
            delay(1000)
            val hostname = readOnionHostname()
            if (hostname.isNotBlank() && isTorPublished) {
                _torStatus.value = TorStatus(
                    state = TorState.RUNNING,
                    bootstrapProgress = 100,
                    onionAddress = hostname,
                    isHosting = true
                )
                emitLog("Onion address verified and descriptor published on Tor network: http://$hostname/")
                return
            }
            attempts++
        }

        if (_torStatus.value.onionAddress.isBlank() || !isTorPublished) {
            if (_torStatus.value.state != TorState.ERROR) {
                _torStatus.value = TorStatus(
                    state = TorState.ERROR,
                    errorMessage = "Tor did not publish the onion service within 10 minutes. Check the Tor logs, network restrictions, and retry.",
                    isHosting = true
                )
                emitLog("[ERROR] Onion address publication timeout.")
            }
        }
    }

    fun stopTor() {
        emitLog("Stopping Tor service...")
        torJob?.cancel()
        torProcess?.let { process ->
            stoppingTorProcess = process
            process.destroy()
        }
        torProcess = null
        isTorPublished = false
        clientOnlyMode = false
        _torStatus.value = TorStatus(state = TorState.STOPPED, bootstrapProgress = 0)
    }

    fun stopTorClient() {
        if (clientOnlyMode) stopTor()
    }

    fun readOnionHostname(): String {
        val hostnameFile = File(hiddenServiceDir, "hostname")
        if (hostnameFile.exists()) {
            val text = hostnameFile.readText().trim()
            if (isValidTorV3Address(text)) {
                return text
            }
        }
        return ""
    }

    private fun createTorConfigFile(localPort: Int): File {
        torDir.apply {
            setReadable(true, true)
            setWritable(true, true)
            setExecutable(true, true)
        }
        hiddenServiceDir.apply {
            setReadable(true, true)
            setWritable(true, true)
            setExecutable(true, true)
        }

        val torrc = File(torDir, "torrc")
        val configContent = buildTorConfig(localPort, SOCKS_PORT)
        torrc.writeText(configContent)
        return torrc
    }

    private fun createTorClientConfigFile(): File {
        torDir.apply { setReadable(true, true); setWritable(true, true); setExecutable(true, true) }
        val torrc = File(torDir, "torrc")
        torrc.writeText("""
            DataDirectory ${torDir.absolutePath}
            SocksPort 127.0.0.1:$SOCKS_PORT OnionTrafficOnly SafeSocks
            ControlPort 0
            Log info stdout
            SafeLogging 1
        """.trimIndent())
        return torrc
    }

    internal fun buildTorConfig(localPort: Int, socksPort: Int): String {
        require(localPort in 1..65535)
        require(socksPort in 1..65535)
        return """
            DataDirectory ${torDir.absolutePath}
            HiddenServiceDir ${hiddenServiceDir.absolutePath}
            HiddenServicePort 80 127.0.0.1:$localPort
            HiddenServiceVersion 3
            # Outbound client access is local to OnionHost and restricted to onions.
            SocksPort 127.0.0.1:$socksPort OnionTrafficOnly SafeSocks
            ControlPort 0
            # Tor reports hidden-service descriptor uploads at INFO level.  Using
            # NOTICE here makes a successful publication indistinguishable from a
            # timeout to the app because the upload event is never received.
            Log info stdout
            SafeLogging 1
        """.trimIndent()
    }

    suspend fun getTorBinary(): File {
        return TorBinaryInstaller.getOrInstallTorBinary(context)
    }

    companion object {
        /** Loopback-only SOCKS endpoint consumed by [TorOnionSocketConnector]. */
        const val SOCKS_PORT = 9050

        /**
         * Generates a cryptographically valid Tor v3 Onion Address (rend-spec-v3).
         * Format: base32(pubkey[32] || checksum[2] || version[1=0x03]) + ".onion"
         */
        fun generateValidV3OnionAddress(pubKey: ByteArray): String {
            val prefix = ".onion checksum".toByteArray(Charsets.US_ASCII)
            val version = byteArrayOf(0x03)
            val checksumInput = prefix + pubKey + version

            val hash = Sha3.digest256(checksumInput)
            val checksum = hash.copyOfRange(0, 2)

            val rawBytes = pubKey + checksum + version
            return encodeBase32(rawBytes) + ".onion"
        }

        fun isValidTorV3Address(address: String): Boolean {
            val clean = address.trim().lowercase().removeSuffix(".onion")
            if (clean.length != 56) return false
            val bytes = decodeBase32(clean) ?: return false
            if (bytes.size != 35) return false
            if (bytes[34] != 0x03.toByte()) return false

            val pubKey = bytes.copyOfRange(0, 32)
            val checksum = bytes.copyOfRange(32, 34)
            val prefix = ".onion checksum".toByteArray(Charsets.US_ASCII)
            val version = byteArrayOf(0x03)
            val checksumInput = prefix + pubKey + version

            return try {
                val hash = Sha3.digest256(checksumInput)
                hash[0] == checksum[0] && hash[1] == checksum[1]
            } catch (e: Exception) {
                false
            }
        }

        private fun encodeBase32(data: ByteArray): String {
            val alphabet = "abcdefghijklmnopqrstuvwxyz234567"
            val sb = StringBuilder()
            var buffer = 0
            var bitsLeft = 0
            for (b in data) {
                buffer = (buffer shl 8) or (b.toInt() and 0xFF)
                bitsLeft += 8
                while (bitsLeft >= 5) {
                    val index = (buffer shr (bitsLeft - 5)) and 0x1F
                    sb.append(alphabet[index])
                    bitsLeft -= 5
                    buffer = buffer and ((1 shl bitsLeft) - 1)
                }
            }
            if (bitsLeft > 0) {
                val index = (buffer shl (5 - bitsLeft)) and 0x1F
                sb.append(alphabet[index])
            }
            return sb.toString()
        }

        private fun decodeBase32(input: String): ByteArray? {
            val alphabet = "abcdefghijklmnopqrstuvwxyz234567"
            var buffer = 0
            var bitsLeft = 0
            val result = mutableListOf<Byte>()
            for (c in input) {
                val valIdx = alphabet.indexOf(c)
                if (valIdx < 0) return null
                buffer = (buffer shl 5) or valIdx
                bitsLeft += 5
                if (bitsLeft >= 8) {
                    val byteVal = (buffer shr (bitsLeft - 8)) and 0xFF
                    result.add(byteVal.toByte())
                    bitsLeft -= 8
                    buffer = buffer and ((1 shl bitsLeft) - 1)
                }
            }
            return result.toByteArray()
        }
    }
}
