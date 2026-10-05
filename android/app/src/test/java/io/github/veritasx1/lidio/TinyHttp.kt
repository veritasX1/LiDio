package io.github.veritasx1.lidio

import java.net.ServerSocket
import kotlin.concurrent.thread

/** A minimal HTTP server for tests (Android's unit-test classpath has no com.sun.net.httpserver): one request per
 *  connection, answers by path suffix, remembers what it was asked (address and headers). */
class TinyHttp(private val answer: (path: String) -> String?) {
    private val socket = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
    val port: Int get() = socket.localPort
    val seen = java.util.Collections.synchronizedList(mutableListOf<Pair<String, Map<String, String>>>())

    init {
        thread(isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                runCatching {
                    client.use { c ->
                        val input = c.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                        val target = input.readLine()?.split(" ")?.getOrNull(1) ?: return@use
                        val headers = mutableMapOf<String, String>()
                        while (true) { val line = input.readLine() ?: break; if (line.isEmpty()) break; line.split(": ", limit = 2).let { if (it.size == 2) headers[it[0]] = it[1] } }
                        headers.entries.firstOrNull { it.key.equals("Content-Length", true) }?.value?.toIntOrNull()?.let { n -> repeat(n) { input.read() } }
                        seen += String(target.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8) to headers
                        val body = answer(target.substringBefore('?'))
                        val bytes = body?.let { if (it.startsWith("base64:")) java.util.Base64.getDecoder().decode(it.removePrefix("base64:")) else it.toByteArray() } ?: "{}".toByteArray()
                        val out = c.getOutputStream()
                        out.write("HTTP/1.1 ${if (body == null) "404 Not Found" else "200 OK"}\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        out.write(bytes); out.flush()
                    }
                }
            }
        }
    }

    fun close() = socket.close()
}
