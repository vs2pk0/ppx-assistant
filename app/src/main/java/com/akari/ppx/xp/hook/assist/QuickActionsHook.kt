package com.akari.ppx.xp.hook.assist

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.akari.ppx.utils.*
import com.akari.ppx.xp.Init.cl
import com.akari.ppx.xp.hook.BaseHook

/** Native controls use the host's current dialog and gallery item, including swiped pages. */
class QuickActionsHook : BaseHook {
    override fun onHook() {
        val callback = "com.sup.android.module.publish.view.NewInputCommentDialog\$tryPublish\$2".findClass(cl)
        val dialogClass = callback.declaredFields.first { Dialog::class.java.isAssignableFrom(it.type) }.type
        dialogClass.hookAfterMethod("onCreate", Bundle::class.java) { param ->
            val dialog = param.thisObject as Dialog
            dialog.window?.decorView?.post { runCatching { attachComposer(dialog) }.onFailure(Log::e) }
        }
        "com.sup.android.m_gallery.NewGalleryActivity".findClass(cl)
            .hookAfterMethod("onResume") { param ->
                val activity = param.thisObject as Activity
                val root = activity.window.decorView as? FrameLayout ?: return@hookAfterMethod
                if (root.findViewWithTag<View>("ppx_gallery_save") != null) return@hookAfterMethod
                val button = button(activity, "存", "保存当前图片或 GIF 到相册").apply {
                    tag = "ppx_gallery_save"
                    setOnClickListener {
                        runCatching { GallerySaver.save(activity, this) }
                            .onFailure { Log.e(it); activity.showToast("保存失败，请重试") }
                    }
                }
                root.addView(button, FrameLayout.LayoutParams(dp(activity, 44), dp(activity, 44),
                    Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = dp(activity, 16) })
            }
    }

    private fun attachComposer(dialog: Dialog) {
        val root = dialog.window?.decorView as? FrameLayout ?: return
        if (root.findViewWithTag<View>("ppx_comment_settings") != null) return
        val ids = "com.sup.android.module.publish.R\$id".findClass(cl)
        val gif = root.findViewById<View>(ids.getStaticObjectFieldAs<Int>("iv_comment_gif")) ?: return
        val button = button(dialog.context, "虾", "助手评论设置").apply {
            tag = "ppx_comment_settings"
            setOnClickListener { showSettings(dialog) }
        }
        // Anchor in the window so the host's obfuscated ConstraintLayout is left intact.
        root.addView(button, FrameLayout.LayoutParams(dp(dialog.context, 36), dp(dialog.context, 36)))
        root.viewTreeObserver.addOnGlobalLayoutListener {
            button.visibility = if (gif.isShown) View.VISIBLE else View.GONE
            val origin = IntArray(2); val anchor = IntArray(2)
            root.getLocationInWindow(origin); gif.getLocationInWindow(anchor)
            button.x = (anchor[0] - origin[0] + gif.width + dp(dialog.context, 12))
                .coerceAtMost(root.width - button.width - dp(dialog.context, 12)).toFloat()
            button.y = (anchor[1] - origin[1] + (gif.height - button.height) / 2).toFloat()
        }
    }

    private fun showSettings(composer: Dialog) {
        (composer.context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(composer.window?.decorView?.windowToken, 0)
        val intent = android.content.Intent().setClassName("com.akari.ppx", "com.akari.ppx.ui.CommentSettingsActivity")
        runCatching { composer.context.startActivity(intent) }
            .onFailure { Log.e(it); composer.context.showToast("无法打开助手评论设置") }
    }

    private fun button(context: Context, label: String, description: String) = TextView(context).apply {
        text = label; textSize = 19f; gravity = Gravity.CENTER
        contentDescription = description; setTextColor(Color.WHITE)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.rgb(187, 49, 95)) }
        elevation = dp(context, 3).toFloat()
        isClickable = true; isFocusable = true
    }

    private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density + 0.5f).toInt()
}
