package com.akari.ppx.utils

import android.util.Log as AndroidLog

/**
 * Module logger.
 *
 * Every entry goes to logcat (`PPXPPX`) *and* to [RuntimeLog], which the
 * settings UI exposes as 运行日志. Keeping both means users can file a bug with
 * a screenshot of the in-app log, while developers still have adb logcat.
 */
object Log {
    private const val TAG = "PPXPPX"

    private fun log(level: Char, printed: (String, String) -> Int, obj: Any?) {
        val message = if (obj is Throwable) {
            AndroidLog.getStackTraceString(obj)
        } else {
            obj?.toString() ?: "null"
        }
        printed(TAG, message)
        runCatching { RuntimeLog.write(level, TAG, message) }
    }

    fun d(obj: Any?) = log('D', AndroidLog::d, obj)

    fun i(obj: Any?) = log('I', AndroidLog::i, obj)

    fun e(obj: Any?) = log('E', AndroidLog::e, obj)

    fun v(obj: Any?) = log('V', AndroidLog::v, obj)

    fun w(obj: Any?) = log('W', AndroidLog::w, obj)
}
