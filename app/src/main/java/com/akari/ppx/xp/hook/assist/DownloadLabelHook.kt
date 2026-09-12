package com.akari.ppx.xp.hook.assist

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.akari.ppx.utils.hookAfterMethod
import com.akari.ppx.utils.hookBeforeMethod
import com.akari.ppx.utils.findClass
import com.akari.ppx.utils.getStaticObjectFieldAs
import com.akari.ppx.xp.Init.cl
import com.akari.ppx.xp.ModuleEntryBridge
import com.akari.ppx.xp.hook.BaseHook

/** Only the save action is renamed; the original action and click listener remain intact. */
class DownloadLabelHook : BaseHook {
    override fun onHook() {
        val overlayLabelId = "com.sup.android.module.m_sharecontroller.R\$id".findClass(cl)
            .getStaticObjectFieldAs<Int>("platform_name")
        // The end-of-video overlay uses a separate label instead of OptionAction tags.
        TextView::class.java.hookBeforeMethod("setText", CharSequence::class.java, TextView.BufferType::class.java) { param ->
            if (param.args[0]?.toString() != "保存视频" || !ModuleEntryBridge.isFeatureInstalled("save_video")) return@hookBeforeMethod
            val view = param.thisObject as TextView
            if (view.id == overlayLabelId) param.args[0] = "去水印保存"
        }

        View::class.java.hookAfterMethod("setTag", Object::class.java) { param ->
            val action = param.args[0] as? Enum<*> ?: return@hookAfterMethod
            if (action.name != "ACTION_SAVE" ||
                !action.javaClass.name.startsWith("com.sup.android.i_sharecontroller.model.OptionAction")) return@hookAfterMethod
            val root = param.thisObject as? ViewGroup ?: return@hookAfterMethod
            fun relabel(view: View) {
                if (view is TextView) {
                    val enabled = when (view.text.toString()) {
                        "保存视频" -> ModuleEntryBridge.isFeatureInstalled("save_video")
                        "保存图片", "保存动图" -> ModuleEntryBridge.isFeatureInstalled("save_image")
                        else -> false
                    }
                    if (enabled) view.text = "去水印保存"
                }
                if (view is ViewGroup) for (i in 0 until view.childCount) relabel(view.getChildAt(i))
            }
            relabel(root)
        }
    }
}
