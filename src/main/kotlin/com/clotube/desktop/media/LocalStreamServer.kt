package com.clotube.desktop.media

import com.clotube.desktop.auth.DesktopAuthManager
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.util.concurrent.Executors

object LocalStreamServer {
    private var server: HttpServer? = null
    var serverPort: Int = 0
        private set

    @Synchronized
    fun start(): Int {
        if (server != null) return serverPort
        try {
            val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            s.executor = Executors.newCachedThreadPool { runnable ->
                Thread(runnable, "LocalStreamServer-Worker").apply {
                    isDaemon = true
                }
            }
            s.createContext("/stream") { exchange ->
                handleStream(exchange)
            }
            s.start()
            server = s
            serverPort = s.address.port
            println("[LocalStreamServer] Listening on http://127.0.0.1:$serverPort")
            return serverPort
        } catch (e: Exception) {
            System.err.println("[LocalStreamServer] Failed to start: ${e.message}")
            return 0
        }
    }

    @Synchronized
    fun stop() {
        try {
            server?.stop(0)
            server = null
            serverPort = 0
            println("[LocalStreamServer] Stopped successfully.")
        } catch (e: Exception) {
            System.err.println("[LocalStreamServer] Error stopping server: ${e.message}")
        }
    }

    fun getStreamUrl(fileId: String, token: String): String {
        val port = start()
        return "http://127.0.0.1:$port/stream?id=$fileId&token=$token"
    }

    private fun openDriveConnection(
        fileId: String,
        token: String,
        rangeHeader: String?,
        requestMethod: String
    ): HttpURLConnection {
        var currentUrl = "https://www.googleapis.com/drive/v3/files/$fileId?alt=media&supportsAllDrives=true&acknowledgeAbuse=true"
        var conn: HttpURLConnection
        var redirectCount = 0

        val effectiveRange = if (!rangeHeader.isNullOrBlank()) rangeHeader else "bytes=0-"
        val effectiveMethod = if (requestMethod.equals("HEAD", ignoreCase = true)) "GET" else requestMethod

        while (true) {
            @Suppress("DEPRECATION")
            conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                this.requestMethod = effectiveMethod
                if (currentUrl.contains("googleapis.com")) {
                    setRequestProperty("Authorization", "Bearer $token")
                }
                setRequestProperty("User-Agent", "Clotube-Windows/1.0")
                setRequestProperty("Range", effectiveRange)
                setRequestProperty("Accept-Encoding", "identity") // Prevent gzip compression from corrupting byte ranges
                connectTimeout = 12000
                readTimeout = 20000
                instanceFollowRedirects = false
            }

            val status = conn.responseCode
            if (status in listOf(301, 302, 303, 307, 308) && redirectCount < 5) {
                val newLocation = conn.getHeaderField("Location")
                conn.disconnect()
                if (!newLocation.isNullOrBlank()) {
                    currentUrl = newLocation
                    redirectCount++
                    continue
                }
            }
            break
        }
        return conn
    }

    private fun handleStream(exchange: HttpExchange) {
        val query = exchange.requestURI.query ?: ""
        val params = query.split("&").associate {
            val parts = it.split("=", limit = 2)
            parts[0] to (parts.getOrNull(1) ?: "")
        }
        val fileId = params["id"]
        val tokenParam = params["token"] ?: ""

        if (fileId.isNullOrBlank()) {
            exchange.sendResponseHeaders(400, -1)
            exchange.close()
            return
        }

        val effectiveToken = try {
            DesktopAuthManager.getCredential()?.accessToken?.ifBlank { tokenParam } ?: tokenParam
        } catch (_: Exception) {
            tokenParam
        }
        val clientRange = exchange.requestHeaders.getFirst("Range")

        var conn: HttpURLConnection? = null
        var currentInput: InputStream? = null
        var output: OutputStream? = null

        try {
            conn = openDriveConnection(fileId, effectiveToken, clientRange, exchange.requestMethod)
            val responseCode = conn.responseCode

            // Always announce byte-range support to LibVLC
            exchange.responseHeaders.set("Accept-Ranges", "bytes")

            conn.getHeaderField("Content-Range")?.let { cr ->
                exchange.responseHeaders.set("Content-Range", cr)
            }
            conn.getHeaderField("Content-Type")?.let { ct ->
                exchange.responseHeaders.set("Content-Type", ct)
            }

            val contentLength = conn.contentLengthLong

            if (exchange.requestMethod.equals("HEAD", ignoreCase = true)) {
                exchange.sendResponseHeaders(if (responseCode in 200..299) 200 else responseCode, -1)
                exchange.close()
                conn.disconnect()
                return
            }

            val responseStatus = if (responseCode in 200..299 && !clientRange.isNullOrBlank()) 206 else responseCode
            exchange.sendResponseHeaders(responseStatus, if (contentLength > 0) contentLength else 0)

            output = exchange.responseBody
            currentInput = if (responseCode in 200..299) conn.inputStream else conn.errorStream

            val buffer = ByteArray(64 * 1024)
            var bytesWritten = 0L

            while (true) {
                if (contentLength > 0 && bytesWritten >= contentLength) break

                val read = try {
                    currentInput?.read(buffer) ?: -1
                } catch (e: Exception) {
                    -2 // Upstream read error
                }

                if (read == -1) {
                    break
                } else if (read > 0) {
                    try {
                        output.write(buffer, 0, read)
                        bytesWritten += read
                    } catch (_: Exception) {
                        // LibVLC disconnected (e.g. user scrubbed elsewhere). Cleanly abort.
                        break
                    }
                } else if (read == -2) {
                    // Try to reconnect if network dropped mid-stream
                    try { currentInput?.close() } catch (_: Exception) {}
                    val freshToken = try {
                        DesktopAuthManager.getCredential()?.accessToken ?: effectiveToken
                    } catch (_: Exception) {
                        effectiveToken
                    }
                    val startOffset = if (!clientRange.isNullOrBlank()) {
                        val start = clientRange.removePrefix("bytes=").substringBefore('-').toLongOrNull() ?: 0L
                        start + bytesWritten
                    } else {
                        bytesWritten
                    }
                    val resumeRange = "bytes=$startOffset-"

                    var reconnected = false
                    for (attempt in 1..20) {
                        try {
                            Thread.sleep(1500L)
                            val retryConn = openDriveConnection(fileId, freshToken, resumeRange, exchange.requestMethod)
                            if (retryConn.responseCode in 200..299) {
                                currentInput = retryConn.inputStream
                                conn = retryConn
                                reconnected = true
                                break
                            } else {
                                retryConn.disconnect()
                            }
                        } catch (_: Exception) {}
                    }
                    if (!reconnected) break
                }
            }
        } catch (e: Exception) {
            // Client closed connection during scrub
        } finally {
            try { currentInput?.close() } catch (_: Exception) {}
            try { output?.close() } catch (_: Exception) {}
            try { conn?.disconnect() } catch (_: Exception) {}
            try { exchange.close() } catch (_: Exception) {}
        }
    }
}
