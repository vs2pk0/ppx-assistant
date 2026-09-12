package com.akari.ppx.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Switch
import com.akari.ppx.data.Prefs

/** Explicit activity entry works even when Android hides the module's provider from the host. */
class CommentSettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val themed = android.view.ContextThemeWrapper(this, android.R.style.Theme_Material_Light_Dialog_Alert)
        val content = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        val colorRow = TextView(themed).apply {
            textSize = 17f
            setTextColor(android.graphics.Color.rgb(51, 51, 51))
            setPadding(0, dp(18), 0, dp(18))
        }
        fun refresh() {
            val name = when (Prefs.get("comment_text_color", "none")) {
                "blue" -> "蓝色"; "red" -> "红色"; else -> "不处理"
            }
            colorRow.text = "评论文字颜色：$name　›"
        }
        refresh()
        colorRow.setOnClickListener {
            val values = arrayOf("none", "blue", "red")
            AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle("评论文字颜色")
                .setSingleChoiceItems(arrayOf("不处理", "蓝色", "红色（兼容客户端可见）"),
                    values.indexOf(Prefs.get("comment_text_color", "none"))) { picker, index ->
                    Prefs.setBlocking("comment_text_color", values[index])
                    refresh()
                    picker.dismiss()
                }.setNegativeButton("取消", null).show()
        }
        content.addView(colorRow)
        listOf("unlock_illegal_words" to "评论解锁敏感词", "unlock_video_comment_limit" to "楼中楼支持视频")
            .forEach { (key, title) ->
                content.addView(Switch(themed).apply {
                    text = title; textSize = 16f
                    setPadding(0, dp(14), 0, dp(14))
                    isChecked = Prefs.get(key, false) == true
                    setOnCheckedChangeListener { _, checked -> Prefs.setBlocking(key, checked) }
                })
            }
        content.addView(TextView(themed).apply {
            text = "颜色对接下来发布的普通文本立即生效；已有 @、时间标记会保留。视频开关在重新打开评论框后生效。"
            textSize = 12f; setPadding(0, dp(12), 0, dp(8))
        })
        AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("助手评论设置").setView(content).setPositiveButton("完成", null)
            .create().apply { setOnDismissListener { finish() }; show() }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}
