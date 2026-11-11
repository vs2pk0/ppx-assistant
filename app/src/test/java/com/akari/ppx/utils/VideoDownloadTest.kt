package com.akari.ppx.utils

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.InterruptedIOException
import java.net.InetSocketAddress
import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class VideoDownloadTest {
    private fun fixture(block: (HttpServer, File, String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val file = File.createTempFile("ppx-video-", ".partial")
        try { block(server, file, "http://127.0.0.1:${server.address.port}/video") }
        finally { server.stop(0); file.delete() }
    }

    @Test fun resumesAnInterruptedDownloadAndValidatesTheFullLength() = fixture { server, file, url ->
        val calls = AtomicInteger()
        val bytes = ByteArray(10000) { (it % 251).toByte() }
        server.createContext("/video") { exchange ->
            try {
                if (calls.incrementAndGet() == 1) {
                    exchange.responseHeaders.set("ETag", "test-video")
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.write(bytes, 0, 3000)
                } else {
                    assertEquals("bytes=3000-", exchange.requestHeaders.getFirst("Range"))
                    assertEquals("test-video", exchange.requestHeaders.getFirst("If-Range"))
                    exchange.responseHeaders.set("Content-Range", "bytes 3000-9999/10000")
                    exchange.sendResponseHeaders(206, 7000)
                    exchange.responseBody.write(bytes, 3000, 7000)
                }
            } finally { exchange.close() }
        }
        server.start()
        val active = AtomicReference<HttpURLConnection?>()
        VideoDownload.save(url, file, AtomicBoolean(), active) { _, _, _ -> }
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(2, calls.get()); assertNull(active.get())
    }

    @Test fun serverIgnoringRangeRestartsInsteadOfAppendingDuplicateBytes() = fixture { server, file, url ->
        file.writeText("partial")
        server.createContext("/video") { exchange ->
            exchange.sendResponseHeaders(200, 5)
            exchange.responseBody.use { it.write("video".toByteArray()) }; exchange.close()
        }
        server.start()
        VideoDownload.save(url, file, AtomicBoolean(), AtomicReference()) { _, _, _ -> }
        assertEquals("video", file.readText())
    }

    @Test fun cancellationClosesConnectionAndDoesNotRetry() = fixture { server, file, url ->
        val calls = AtomicInteger()
        server.createContext("/video") { exchange ->
            calls.incrementAndGet(); exchange.sendResponseHeaders(200, 5)
            exchange.responseBody.use { it.write("video".toByteArray()) }; exchange.close()
        }
        server.start()
        val canceled = AtomicBoolean(); val active = AtomicReference<HttpURLConnection?>()
        try {
            VideoDownload.save(url, file, canceled, active) { _, _, _ -> canceled.set(true) }
            fail("Canceled download must fail")
        } catch (_: InterruptedIOException) { }
        assertEquals(1, calls.get()); assertNull(active.get())
    }

    @Test fun repeatedNetworkFailuresStopAfterThreeAttempts() = fixture { server, file, url ->
        val calls = AtomicInteger()
        server.createContext("/video") { exchange ->
            calls.incrementAndGet(); exchange.sendResponseHeaders(503, -1); exchange.close()
        }
        server.start()
        try {
            VideoDownload.save(url, file, AtomicBoolean(), AtomicReference()) { _, _, _ -> }
            fail("Unavailable download must fail")
        } catch (_: java.io.IOException) { }
        assertEquals(3, calls.get()); assertEquals(0, file.length())
    }
}
