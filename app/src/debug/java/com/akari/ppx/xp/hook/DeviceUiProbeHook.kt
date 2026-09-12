package com.akari.ppx.xp.hook

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import com.akari.ppx.data.XPrefs
import com.akari.ppx.utils.*
import com.akari.ppx.xp.Init
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Debug-only test channel in the host's PRIVATE cache; commands require adb root. */
class DeviceUiProbeHook : BaseHook {
    private val handler = Handler(Looper.getMainLooper())
    private var started = false
    private var lastCommand = ""

    override fun onHook() {
        "com.sup.android.mi.feed.repo.bean.comment.Comment".findClass(Init.cl).hookAfterMethod("getText") { param ->
            val text = param.result as? String
            if (text?.contains("PPX-RED-0912") == true) Log.i("DEVICE_COMMENT_RAW $text")
        }

        if (XPrefs<Boolean>("__probe_ui")) {
            "com.ss.ttvideoengine.TTVideoEngine".hookAfterMethod(Init.cl, "setPlaybackParams", "com.ss.ttm.player.PlaybackParams") {
                Log.i("DEVICE_PLAY_SPEED ${it.args[0].callMethodOrNull("getSpeed")}")
            }
            android.media.SoundPool::class.java.hookAfterMethod("play", Int::class.java, Float::class.java,
                Float::class.java, Int::class.java, Int::class.java, Float::class.java) {
                Log.i("DEVICE_SOUND_PLAY stream=${it.result}")
            }
        }

        Activity::class.java.hookAfterMethod("onResume") {
            if (!started && XPrefs<Boolean>("__probe_ui")) {
                started = true
                handler.post(object : Runnable {
                    override fun run() {
                        runCatching { poll() }.onFailure(Log::e)
                        handler.postDelayed(this, 500)
                    }
                })
            }
        }
    }

    private fun poll() {
        val file = File(Init.ctx.cacheDir, "ppx-ui-command.json")
        if (!file.isFile) return
        val command = file.readText()
        if (command == lastCommand) return
        val json = runCatching { JSONObject(command) }.getOrNull() ?: return
        lastCommand = command
        val output = JSONObject().put("request", json.optString("request"))
        runCatching {
            val views = mutableListOf<View>()
            fun visit(view: View) {
                if (!view.isShown) return
                views += view
                if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
            }
            val wm = Class.forName("android.view.WindowManagerGlobal")
                .callStaticMethod("getInstance")!!
            (wm.getObjectField("mViews") as List<*>).filterIsInstance<View>()
                .lastOrNull { it.isShown && it.width > 0 && it.height > 0 && it.getGlobalVisibleRect(Rect()) }?.let(::visit)
            if (json.optString("action") == "clipboard") {
                val clipboard = Init.ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                output.put("clipboard", clipboard.primaryClip?.getItemAt(0)?.text ?: "")
            }
            if (json.optString("action") == "sender") {
                val ownUser = "com.sup.android.module.usercenter.UserCenterService".findClass(Init.cl)
                    .callStaticMethod("getInstance").callMethod("getMyUserInfo")
                val ownId = ownUser.getLongField("id")
                check(ownId > 0)
                val probe = DeviceProbeHook()
                val helperClass = "com.sup.android.m_danmaku.widget.j".findClass(Init.cl)
                val helper = probe.uninitialized(helperClass)
                val context = views.firstOrNull()?.context ?: Init.ctx
                val danmakuView = "com.sup.android.m_danmaku.widget.k".findClass(Init.cl).new(context)
                helper.setObjectField("b", danmakuView)
                val danmaku = probe.uninitialized("com.sup.android.m_danmaku.danmaku.model.q".findClass(Init.cl)).setLongField("V", ownId)
                output.put("senderRoute", helper.callMethod("b", danmaku, 0f, 0f))
            }
            if (json.optString("action") == "commentColor") {
                val callback = "com.sup.android.module.publish.view.NewInputCommentDialog\$tryPublish\$2".findClass(Init.cl)
                val dialogClass = callback.declaredFields.first { android.app.Dialog::class.java.isAssignableFrom(it.type) }.type
                output.put("commentColor", dialogClass.callStaticMethod("e", "PPX live color probe"))
            }
            if (json.optString("action") == "sound") {
                val manager = "com.sup.android.manager.b".findClass(Init.cl)
                manager.getStaticObjectField("b").callMethod("a", "short_click", 0)
                output.put("soundStream", manager.getStaticObjectField("e"))
            }
            if (json.optString("action") == "self") {
                val user = "com.sup.android.module.usercenter.UserCenterService".findClass(Init.cl)
                    .callStaticMethod("getInstance").callMethod("getMyUserInfo")
                val fields = JSONObject()
                listOf("name", "description", "likeCount", "followersCount", "followingCount", "point").forEach {
                    fields.put(it, user.getObjectFieldOrNull(it))
                }
                fields.put("punishment", user.getObjectFieldOrNullAs<List<*>>("punishmentList")?.firstOrNull()?.getObjectFieldOrNull("shortDesc"))
                output.put("self", fields)
            }
            if (json.optString("action") == "media") {
                val extractor = android.media.MediaExtractor()
                try {
                    extractor.setDataSource(json.getString("path"))
                    output.put("media", JSONArray((0 until extractor.trackCount).map { extractor.getTrackFormat(it).toString() }))
                } finally { extractor.release() }
            }
            json.optString("action").takeIf { it.isNotBlank() && it !in setOf("dump", "clipboard", "media", "self", "sound", "sender", "commentColor") }?.let { action ->
                val selected = views.filter { view ->
                    (!json.has("id") || resourceName(view) == json.getString("id")) &&
                        (!json.has("text") || (view as? TextView)?.text?.toString() == json.getString("text")) &&
                        (action != "input" || view is EditText)
                }
                check(selected.size == 1) { "Expected one view; found ${selected.size}" }
                var view = selected.single()
                if (action == "input") {
                    (view as EditText).setText(json.getString("value"))
                    view.setSelection(view.text.length)
                } else {
                    while (!view.isClickable && view.parent is View) view = view.parent as View
                    check(view.performClick()) { "View did not handle click" }
                }
                output.put("action", "success")
            }
            val tree = JSONArray()
            views.forEach { view ->
                val rect = Rect()
                if (view.getGlobalVisibleRect(rect)) tree.put(JSONObject().apply {
                    put("id", resourceName(view))
                    put("text", (view as? TextView)?.text ?: "")
                    val rich = (view as? TextView)?.text as? android.text.Spanned
                    if (rich != null) put("foregroundSpans", JSONArray(rich.getSpans(0, rich.length,
                        android.text.style.ForegroundColorSpan::class.java).map { it.foregroundColor }))
                    put("desc", view.contentDescription ?: "")
                    put("class", view.javaClass.name)
                    put("bounds", rect.toShortString())
                    put("clickable", view.isClickable)
                })
            }
            output.put("views", tree)
        }.onFailure { output.put("error", it.stackTraceToString()) }
        File(Init.ctx.cacheDir, "ppx-ui-result.json").writeText(output.toString())
    }

    private fun resourceName(view: View): String =
        runCatching { view.resources.getResourceEntryName(view.id) }.getOrDefault("")
}
