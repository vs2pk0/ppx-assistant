package com.akari.ppx.utils

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Resume transient failures, but never expose an incomplete file as a saved video. */
internal object VideoDownload {
    fun save(url: String, file: File, canceled: AtomicBoolean,
             active: AtomicReference<HttpURLConnection?>, progress: (Long, Long, Int) -> Unit) {
        fun checkCanceled() {
            if (canceled.get() || Thread.currentThread().isInterrupted) throw InterruptedIOException("已取消保存")
        }
        var validator: String? = null
        for (attempt in 1..3) {
            checkCanceled()
            val connection = URL(url).openConnection() as HttpURLConnection
            active.set(connection)
            try {
                checkCanceled()
                val offset = file.length()
                connection.connectTimeout = 15000; connection.readTimeout = 30000
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/123.0 Mobile Safari/537.36")
                if (offset > 0) {
                    connection.setRequestProperty("Range", "bytes=$offset-")
                    validator?.let { connection.setRequestProperty("If-Range", it) }
                }
                val status = connection.responseCode
                if (status >= 500) throw IOException("下载服务暂时不可用（$status）")
                check(status == 200 || status == 206) { "下载失败（$status）" }
                val range = connection.getHeaderField("Content-Range")?.let {
                    Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(it)?.destructured
                }
                if (status == 206 && (range == null || range.component1().toLong() != offset))
                    throw IOException("视频续传位置不一致，请重试")
                var total = if (status == 206) offset else 0L
                val length = if (status == 206) range!!.component3().toLong() else connection.contentLengthLong
                validator = connection.getHeaderField("ETag") ?: connection.getHeaderField("Last-Modified")
                progress(total, length, attempt)
                connection.inputStream.use { input -> FileOutputStream(file, status == 206).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        checkCanceled()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count); total += count
                        progress(total, length, attempt)
                    }
                    if (total <= 0 || (length >= 0 && total != length)) throw IOException("视频下载不完整，请重试")
                    output.fd.sync()
                } }
                checkCanceled()
                return
            } catch (error: IOException) {
                checkCanceled()
                if (attempt == 3) throw error
            } finally {
                active.compareAndSet(connection, null)
                connection.disconnect()
            }
        }
    }
}
