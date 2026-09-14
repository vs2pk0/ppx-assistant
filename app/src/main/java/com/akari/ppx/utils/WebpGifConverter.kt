package com.akari.ppx.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import com.squareup.gifencoder.GifEncoder
import com.squareup.gifencoder.ImageOptions
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/** Decode WebP frame payloads with Android and stream composited frames to the GIF encoder. */
object WebpGifConverter {
    fun convert(source: File, target: File) {
        RandomAccessFile(source, "r").use { input ->
            fun uint(bytes: Int): Int {
                var value = 0
                repeat(bytes) { value = value or (input.readUnsignedByte() shl (it * 8)) }
                return value
            }
            fun fourcc(): String = ByteArray(4).also { input.readFully(it) }.toString(Charsets.US_ASCII)
            check(fourcc() == "RIFF")
            val end = uint(4).toLong() + 8
            check(end == input.length() && fourcc() == "WEBP") { "WebP 文件不完整" }
            var width = 0
            var height = 0
            var background = Color.TRANSPARENT
            var loops = 0
            var frames = 0
            var surface: Bitmap? = null
            var flattened: Bitmap? = null
            try {
                target.outputStream().buffered().use { output ->
                    var encoder: GifEncoder? = null
                    while (input.filePointer < end) {
                        check(end - input.filePointer >= 8)
                        val type = fourcc()
                        val size = uint(4)
                        val start = input.filePointer
                        check(size >= 0 && start + size + (size and 1) <= end)
                        when (type) {
                            "VP8X" -> {
                                check(size == 10 && surface == null)
                                check(uint(1) and 2 != 0) { "WebP 不是动图" }
                                input.skipBytes(3)
                                width = uint(3) + 1
                                height = uint(3) + 1
                                check(width.toLong() * height <= 4_000_000) { "动图尺寸过大，无法转换" }
                            }
                            "ANIM" -> {
                                check(size == 6 && surface == null)
                                background = uint(4)
                                loops = uint(2)
                            }
                            "ANMF" -> {
                                check(size >= 16 && width > 0 && height > 0)
                                val x = uint(3) * 2
                                val y = uint(3) * 2
                                val w = uint(3) + 1
                                val h = uint(3) + 1
                                val duration = uint(3)
                                val flags = uint(1)
                                check(x.toLong() + w <= width && y.toLong() + h <= height)
                                check(++frames <= 1000 && size <= 32 * 1024 * 1024) { "动图过大，无法转换" }
                                if (surface == null) {
                                    surface = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                    surface!!.eraseColor(background)
                                    flattened = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                    encoder = GifEncoder(output, width, height, if (loops == 0) 0 else (loops - 1).coerceAtLeast(1))
                                }
                                // Each ANMF payload contains standalone ALPH/VP8 or VP8L chunks.
                                val payload = ByteArray(size - 16 + 30)
                                "RIFF".toByteArray().copyInto(payload)
                                val riffSize = payload.size - 8
                                repeat(4) { payload[4 + it] = (riffSize ushr (it * 8)).toByte() }
                                "WEBP".toByteArray().copyInto(payload, 8)
                                "VP8X".toByteArray().copyInto(payload, 12)
                                payload[16] = 10
                                payload[20] = 16
                                repeat(3) {
                                    payload[24 + it] = ((w - 1) ushr (it * 8)).toByte()
                                    payload[27 + it] = ((h - 1) ushr (it * 8)).toByte()
                                }
                                input.readFully(payload, 30, size - 16)
                                val frame = BitmapFactory.decodeByteArray(payload, 0, payload.size)
                                    ?: error("WebP 动画帧解码失败")
                                try {
                                    check(frame.width == w && frame.height == h)
                                    val canvas = Canvas(surface!!)
                                    if (flags and 2 != 0) {
                                        canvas.save()
                                        canvas.clipRect(x, y, x + w, y + h)
                                        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                                        canvas.restore()
                                    }
                                    canvas.drawBitmap(frame, x.toFloat(), y.toFloat(), null)
                                    // GIF has no partial alpha; flatten transparency onto a white matte.
                                    flattened!!.eraseColor(Color.WHITE)
                                    Canvas(flattened!!).drawBitmap(surface!!, 0f, 0f, null)
                                    val pixels = IntArray(width * height)
                                    flattened!!.getPixels(pixels, 0, width, 0, 0, width, height)
                                    // Bound the palette before encoding to avoid costly per-frame color searches.
                                    for (i in pixels.indices) {
                                        val rgb = pixels[i]
                                        val r = ((rgb ushr 16 and 255) * 7 + 127) / 255 * 255 / 7
                                        val g = ((rgb ushr 8 and 255) * 7 + 127) / 255 * 255 / 7
                                        val b = ((rgb and 255) * 3 + 127) / 255 * 255 / 3
                                        pixels[i] = (r shl 16) or (g shl 8) or b
                                    }
                                    encoder!!.addImage(pixels, width, ImageOptions().setDelay(duration.coerceAtLeast(10).toLong(), TimeUnit.MILLISECONDS))
                                    if (flags and 1 != 0) {
                                        val paint = Paint().apply { color = background; xfermode = android.graphics.PorterDuffXfermode(PorterDuff.Mode.SRC) }
                                        canvas.drawRect(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat(), paint)
                                    }
                                } finally { frame.recycle() }
                            }
                        }
                        input.seek(start + size + (size and 1))
                    }
                    check(frames > 1) { "未取得完整动画帧" }
                    encoder!!.finishEncoding()
                }
            } finally {
                surface?.recycle()
                flattened?.recycle()
            }
        }
    }
}
