@file:Suppress("unused")

package com.akari.ppx.xp.hook.assist

import android.view.View
import android.widget.LinearLayout
import com.akari.ppx.utils.*
import com.akari.ppx.xp.Init.cl
import com.akari.ppx.xp.hook.SwitchHook

class FooterHook : SwitchHook("use_feed_footer_new_style") {
    override fun onHook() {
        val legacy = runCatching {
            "com.sup.superb.m_feedui_common.util.FeedCommonSettingsHelper\$useFeedFooterNewStyle\$2".findClass(cl)
        }.getOrNull()
        if (legacy != null) {
            legacy.hookAfterMethod("invoke") { it.result = true }
            return
        }
        // 6.2.0 removed the experimental switch. Reorder the existing controls,
        // preserving their listeners, spacers and layout parameters.
        val ids = "com.sup.superb.feedui.R\$id".findClass(cl)
        val rootId = ids.getStaticObjectFieldAs<Int>("feedui_ll_cell_part_footer")
        val order = listOf("share", "comment", "like", "diss").map {
            ids.getStaticObjectFieldAs<Int>("feedui_ll_cell_part_footer_$it")
        }
        View::class.java.hookAfterMethod("onAttachedToWindow") { param ->
            val root = param.thisObject as? LinearLayout ?: return@hookAfterMethod
            if (root.id != rootId) return@hookAfterMethod
            reorder(root, order)
        }
    }

    internal fun reorder(root: LinearLayout, order: List<Int>) {
        val children = (0 until root.childCount).map(root::getChildAt)
        val controls = order.map { id ->
            var view = root.findViewById<View>(id) ?: return
            while (view.parent !== root) view = view.parent as? View ?: return
            view
        }
        if (controls.distinct().size != controls.size) return
        val slots = children.indices.filter { children[it] in controls }
        if (slots.map { children[it] } == controls) return
        // Remove only controls; leave decorative views in their existing slots.
        controls.forEach(root::removeView)
        slots.zip(controls).forEach { (index, view) -> root.addView(view, index) }
    }
}
