@file:Suppress("unused")

package com.akari.ppx.xp.hook.misc

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import com.akari.ppx.utils.hookAfterMethod
import com.akari.ppx.utils.hookBeforeMethod
import com.akari.ppx.xp.Init.mainActivityClass
import com.akari.ppx.xp.hook.SwitchHook
import java.util.Collections
import java.util.WeakHashMap

/**
 * 我的页条目在宿主里没有稳定的业务 id，只能按可见文案定位。
 * 文案节点只是行内文字，直接 GONE 会留下空白，所以按固定层数上溯到整行容器。
 */
private data class MyPageEntry(val text: String, val climb: Int, val fullWidth: Boolean = false)

private object MyPageEntryHider {
    private val entries = mutableSetOf<MyPageEntry>()
    private val watched = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    private var installed = false

    @Synchronized
    fun register(newEntries: List<MyPageEntry>) {
        entries += newEntries
        if (installed) return
        installed = true
        mainActivityClass?.apply {
            hookAfterMethod("onWindowFocusChanged", Boolean::class.java) { param ->
                if (param.args[0] == true) hideIn((param.thisObject as Activity).window.decorView)
            }
            hookBeforeMethod("onResume") { param ->
                hideIn((param.thisObject as Activity).window.decorView)
            }
        }
    }

    private fun hideIn(root: View) {
        if (root in watched) return
        watched += root
        var layoutDirty = true
        // Tab 切换和异步刷新都会重新布局；只标记，避免布局后直接 GONE 留下一帧空隙。
        root.viewTreeObserver.addOnGlobalLayoutListener(
            object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    layoutDirty = true
                }
            }
        )
        root.viewTreeObserver.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (!layoutDirty) return true
                    layoutDirty = false
                    // GONE 会请求下一次布局；取消本帧，等整行收缩完成再显示。
                    return !hideEntries(root)
                }
            }
        )
    }

    private fun hideEntries(root: View): Boolean {
        var changed = false
        entries.forEach { entry ->
            findText(root, entry.text)?.let {
                if (hideRow(root, it, entry)) changed = true
            }
        }
        return changed
    }

    private fun findText(view: View, text: String): View? {
        if (view is TextView && view.text?.toString() == text) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findText(view.getChildAt(index), text)?.let { return it }
            }
        }
        return null
    }

    private fun hideRow(root: View, view: View, entry: MyPageEntry): Boolean {
        // 整行目标要求节点本身横跨屏宽，避免误伤别处的同名标题（如浏览历史页标题）。
        if (entry.fullWidth) {
            if (view.width == 0) return false
            if (view.width < root.rootView.width * 0.8f) return false
        }
        var target: View = view
        repeat(entry.climb) {
            target = target.parent as? View ?: return false
        }
        if (target.visibility == View.GONE) return false
        target.visibility = View.GONE
        return true
    }
}

/** 我的页顶部「进入宠物乐园」横幅：上溯 2 层正好是整条 bgq，不能到 dlp。 */
class HideMinePetEntryHook : SwitchHook("hide_mine_pet_entry") {
    override fun onHook() = MyPageEntryHider.register(listOf(MyPageEntry("进入宠物乐园", 2)))
}

/** 我的页「历史记录」整行：该节点自身就是整行（宽 1080），上溯 0 层即可。 */
class HideMineHistoryEntryHook : SwitchHook("hide_mine_history_entry") {
    override fun onHook() = MyPageEntryHider.register(listOf(MyPageEntry("历史记录", 0, fullWidth = true)))
}

/** 我的页「福利中心」整段（含右侧金币文案与外层间距）。 */
class HideMineWelfareEntryHook : SwitchHook("hide_mine_welfare_entry") {
    override fun onHook() = MyPageEntryHider.register(listOf(MyPageEntry("福利中心", 3)))
}

/** 我的页「创作中心」整行。 */
class HideMineCreateCenterHook : SwitchHook("hide_mine_create_center") {
    override fun onHook() = MyPageEntryHider.register(listOf(MyPageEntry("创作中心", 2)))
}

/** 我的页「更多功能」整块（神评鉴定 / 设置）。 */
class HideMineMoreFunctionsHook : SwitchHook("hide_mine_more_functions") {
    override fun onHook() = MyPageEntryHider.register(listOf(MyPageEntry("更多功能", 1)))
}
