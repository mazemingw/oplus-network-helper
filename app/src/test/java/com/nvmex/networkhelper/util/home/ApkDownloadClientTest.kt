package com.nvmex.networkhelper.util.home

import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.net.ServerSocket
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class ApkDownloadClientTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun withResponse(response: String, bodyDelay: Long = 0, test: (String) -> Unit) {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val worker = thread(isDaemon = true, name = "apk-test-server") {
                runCatching {
                    server.accept().use { socket ->
                        socket.soTimeout = 2000
                        val input = socket.getInputStream().bufferedReader()
                        while (!input.readLine().isNullOrEmpty()) { /* consume GET headers */ }
                        val split = response.indexOf("\r\n\r\n") + 4
                        socket.getOutputStream().write(response.substring(0, split).toByteArray())
                        socket.getOutputStream().flush()
                        if (bodyDelay > 0) Thread.sleep(bodyDelay)
                        socket.getOutputStream().write(response.substring(split).toByteArray())
                    }
                }
            }
            try { test("http://127.0.0.1:${server.localPort}/update.apk") }
            finally { server.close(); worker.join(1200) }
        }
    }

    @Test fun downloadsKnownLengthAndClosesResponse() {
        val file = temporary.newFile()
        val progress = mutableListOf<Int>()
        withResponse("HTTP/1.1 200 OK\r\nContent-Length: 5\r\nConnection: close\r\n\r\nHELLO") { url ->
            ApkDownloadClient().download(url, file, {}, { p, _ -> progress += p })
        }
        assertEquals("HELLO", file.readText())
        assertEquals(100, progress.last())
    }

    @Test fun acceptsUnknownLengthChunkedDownload() {
        val file = temporary.newFile()
        val progress = mutableListOf<Int>()
        withResponse("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n5\r\nHELLO\r\n0\r\n\r\n") { url ->
            ApkDownloadClient().download(url, file, {}, { p, _ -> progress += p })
        }
        assertEquals("HELLO", file.readText())
        assertTrue(progress.contains(-1))
        assertEquals(100, progress.last())
    }

    @Test fun rejectsHttpErrorEmptyAndTruncatedBodiesAndRemovesPartialFile() {
        for (response in listOf(
            "HTTP/1.1 500 Error\r\nContent-Length: 0\r\n\r\n",
            "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n",
            "HTTP/1.1 200 OK\r\nContent-Length: 12\r\nConnection: close\r\n\r\nHELLO"
        )) {
            val file = temporary.newFile()
            withResponse(response) { url ->
                assertThrows(IOException::class.java) { ApkDownloadClient().download(url, file, {}, { _, _ -> }) }
            }
            assertFalse(file.exists())
        }
    }

    @Test fun overallCallTimeoutInterruptsBodyDownload() {
        val file = temporary.newFile()
        val client = OkHttpClient.Builder().callTimeout(150, TimeUnit.MILLISECONDS).build()
        withResponse("HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nHELLO", bodyDelay = 600) { url ->
            assertThrows(IOException::class.java) { ApkDownloadClient(client).download(url, file, {}, { _, _ -> }) }
        }
        assertFalse(file.exists())
    }

    @Test fun cancellationRemovesPartialFile() {
        val file = temporary.newFile()
        withResponse("HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nHELLO") { url ->
            assertThrows(IOException::class.java) {
                ApkDownloadClient().download(url, file, { it.cancel() }, { _, _ -> })
            }
        }
        assertFalse(file.exists())
    }
}
