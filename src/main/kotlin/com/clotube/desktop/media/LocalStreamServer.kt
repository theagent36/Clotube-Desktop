package com.clotube.desktop.media

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.InputStream
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
            s.executor = Executors.newCachedThreadPool()
            s.createContext("/stream") { exchange ->
                handleStream(exchange)
            }
            s.start()
            server = s
            serverPort = s.address.port
            println("LocalStreamServer listening on http://127.0.0.1:$serverPort")
            return serverPort
        } catch (e: Exception) {
            System.err.println("Failed to start LocalStreamServer: ${e.message}")
            return 0
        }
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

            // Manually follow Google Drive redirects while preserving authentication
            while (true) {
                @Suppress("DEPRECATION")
                conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = exchange.requestMethod
                    if (currentUrl.contains("googleapis.com")) {
                        setRequestProperty("Authorization", "Bearer $token")
                    }
                    setRequestProperty("User-Agent", "Clotube-Windows/1.0")

                    // Forward Range headers from LibVLC for seekability
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

            // Forward response headers back to LibVLC
            conn.headerFields.forEach { (key, values) ->
                if (key != null && !key.equals("Transfer-Encoding", ignoreCase = true) && !key.equals("Server", ignoreCase = true)) {
                    values.forEach { v -> exchange.responseHeaders.add(key, v) }
                }
            }

            val contentLength = conn.contentLengthLong
            exchange.sendResponseHeaders(responseCode, if (contentLength > 0) contentLength else 0)

            val inputStream: InputStream? = if (responseCode in 200..299) conn.inputStream else conn.errorStream
            inputStream?.use { input ->
                exchange.responseBody.use { output ->
                    input.copyTo(output)
                }
            } ?: exchange.responseBody.close()
        } catch (e: Exception) {
            try { exchange.close() } catch (ignored: Exception) {}
        }
    }
}
