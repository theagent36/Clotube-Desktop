package com.clotube.desktop.media

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

object LocalStreamServer {
    private var server: HttpServer? = null
    var serverPort: Int = 0
        private set

    private val daemonThreadFactory = ThreadFactory { runnable ->
        Thread(runnable, "LocalStreamServer-Worker").apply {
            isDaemon = true
        }
    }

    @Synchronized
    fun start(): Int {
        if (server != null) return serverPort
        try {
            val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            s.executor = Executors.newCachedThreadPool(daemonThreadFactory)
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

    fun getStreamUrl(fileId: String, accessToken: String): String {
        val port = start()
        return "http://127.0.0.1:$port/stream?id=$fileId&token=$accessToken"
    }

    private fun handleStream(exchange: HttpExchange) {
        val query = exchange.requestURI.query ?: ""
        val params = query.split("&").associate {
            val parts = it.split("=", limit = 2)
            parts[0] to (parts.getOrNull(1) ?: "")
        }
        val fileId = params["id"]
        val token = params["token"]

        if (fileId.isNullOrBlank() || token.isNullOrBlank()) {
            exchange.sendResponseHeaders(400, -1)
            exchange.close()
            return
        }

        try {
            var currentUrl = "https://www.googleapis.com/drive/v3/files/$fileId?alt=media&supportsAllDrives=true&acknowledgeAbuse=true"
            var conn: HttpURLConnection
            var redirectCount = 0

            while (true) {
                @Suppress("DEPRECATION")
                conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = if (exchange.requestMethod.equals("HEAD", ignoreCase = true)) "GET" else exchange.requestMethod
                    if (currentUrl.contains("googleapis.com")) {
                        setRequestProperty("Authorization", "Bearer $token")
                    }
                    setRequestProperty("User-Agent", "Clotube-Windows/1.0")

                    exchange.requestHeaders.getFirst("Range")?.let { range ->
                        setRequestProperty("Range", range)
                    }
                    connectTimeout = 15000
                    readTimeout = 30000
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

            val responseCode = conn.responseCode

            conn.headerFields.forEach { (key, values) ->
                if (key != null && 
                    !key.equals("Transfer-Encoding", ignoreCase = true) && 
                    !key.equals("Server", ignoreCase = true) &&
                    !key.equals("Content-Length", ignoreCase = true)
                ) {
                    values.forEach { v -> exchange.responseHeaders.add(key, v) }
                }
            }
            exchange.responseHeaders.set("Accept-Ranges", "bytes")

            val contentLength = conn.contentLengthLong

            if (exchange.requestMethod.equals("HEAD", ignoreCase = true)) {
                exchange.sendResponseHeaders(if (responseCode in 200..299) 200 else responseCode, -1)
                exchange.close()
                conn.disconnect()
                return
            }

            exchange.sendResponseHeaders(responseCode, if (contentLength > 0) contentLength else 0)

            val inputStream: InputStream? = if (responseCode in 200..299) conn.inputStream else conn.errorStream
            inputStream?.use { input ->
                exchange.responseBody.use { output ->
                    input.copyTo(output)
                }
            } ?: exchange.responseBody.close()
        } catch (e: Exception) {
            try { exchange.close() } catch (_: Exception) {}
        }
    }
}
