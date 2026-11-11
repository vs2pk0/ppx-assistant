@file:Suppress("unused")

package com.akari.ppx.xp.hook.misc

import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.akari.ppx.data.Const.APP_NAME
import com.akari.ppx.data.Const.TAB_SCHEMA
import com.akari.ppx.data.XPrefs
import com.akari.ppx.ui.MainActivity
import com.akari.ppx.utils.Log
import com.akari.ppx.utils.callMethod
import com.akari.ppx.utils.callMethodOrNullAs
import com.akari.ppx.utils.callStaticMethodOrNull
import com.akari.ppx.utils.findClass
import com.akari.ppx.utils.getObjectFieldOrNull
import com.akari.ppx.utils.getObjectFieldOrNullAs
import com.akari.ppx.utils.hookAfterMethod
import com.akari.ppx.utils.new
import com.akari.ppx.utils.replaceMethod
import com.akari.ppx.xp.Init.cl
import com.akari.ppx.xp.Init.myTabList
import com.akari.ppx.xp.Init.myTabListClass
import com.akari.ppx.xp.Init.myTabView
import com.akari.ppx.xp.Init.myTabViewClass
import com.akari.ppx.xp.hook.BaseHook

class InnerOpenHook : BaseHook {
    override fun onHook() {
        runCatching {
            val tabListClass = myTabListClass ?: return@runCatching
            val tabListMethod = myTabList()
            val myTabItemClass = "com.sup.android.m_mine.bean.MyTabItem".findClass(cl)
            Log.i("InnerOpenHook tabList=${tabListClass.name}#$tabListMethod")

            tabListClass.hookAfterMethod(tabListMethod) { param ->
                val items = param.result as? ArrayList<Any?> ?: return@hookAfterMethod
                val beforeSize = items.size
                if (items.any { it?.callMethodOrNullAs<String>("getTabSchema") == TAB_SCHEMA }) {
                    return@hookAfterMethod
                }
                items.add(myTabItemClass.new().apply {
                    callMethod("setSchemaNeedLogin", false)
                    callMethod("setTabName", APP_NAME)
                    callMethod("setTabSchema", TAB_SCHEMA)
                    callMethod("setExtra", "{\"icon_list\":null,\"alert\":false}")
                    callMethod("setType", 4)
                })
                Log.i("InnerOpenHook entry inserted size=$beforeSize->${items.size}")
            }
        }.onFailure(Log::e)

        runCatching {
            val tabViewClass = myTabViewClass ?: return@runCatching
            val tabViewMethod = myTabView()
            val viewFieldName = tabViewClass.declaredFields.firstOrNull { it.type == View::class.java }?.name
            Log.i("InnerOpenHook tabView=${tabViewClass.name}#$tabViewMethod")

            if (XPrefs<Boolean>("show_mine_settings_entry")) {
                // 宿主的 type=4 共用同一行，必须独立创建 ViewHolder，不能再塞一个列表项。
                val bindMethod = tabViewClass.declaredMethods.single {
                    it.parameterTypes.size == 2 &&
                        it.parameterTypes[0].name == "com.sup.android.m_mine.bean.MyTabItem"
                }
                bindMethod.hookAfterMethod { param ->
                    if (param.args[0]?.callMethodOrNullAs<String>("getTabSchema") == TAB_SCHEMA) {
                        runCatching {
                            val row = viewFieldName?.let {
                                param.thisObject.getObjectFieldOrNullAs<View>(it)
                            } ?: return@runCatching
                            val parent = row.parent as? ViewGroup ?: return@runCatching
                            val tag = "ppx.mine.settings.entry"
                            if (parent.findViewWithTag<View>(tag) != null) return@runCatching
                            val layoutId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                row.sourceLayoutResId
                            } else {
                                row.resources.getIdentifier("mine_original_privilege", "layout", row.context.packageName)
                            }
                            check(layoutId != 0) { "Cannot resolve mine settings row layout" }
                            val settingsRow = LayoutInflater.from(row.context).inflate(layoutId, parent, false)
                            val holder = tabViewClass.new(settingsRow)
                            val item = param.args[0]!!.javaClass.new().apply {
                                callMethod("setSchemaNeedLogin", false)
                                callMethod("setTabName", "设置")
                                callMethod("setTabSchema", "//user/setting?tag=tag_setting_fragment")
                                callMethod("setType", 4)
                            }
                            holder.callMethod(bindMethod.name, item, param.args[1])
                            settingsRow.id = View.generateViewId()
                            settingsRow.tag = tag
                            parent.addView(settingsRow, parent.indexOfChild(row) + 1)
                            Log.i("InnerOpenHook settings row inserted layout=$layoutId")
                        }.onFailure(Log::e)
                    }
                }
            }

            "${tabViewClass.name}\$1".replaceMethod(cl, "doClick", View::class.java) { param ->
                val tabView = param.thisObject.getObjectFieldOrNull("b")
                    ?: return@replaceMethod param.invokeOriginalMethod()
                val tab = tabViewClass.callStaticMethodOrNull(tabViewMethod, tabView)
                if (tab?.callMethodOrNullAs<String>("getTabSchema") != TAB_SCHEMA) {
                    return@replaceMethod param.invokeOriginalMethod()
                }

                val context = viewFieldName?.let { field ->
                    tabView.getObjectFieldOrNullAs<View>(field)?.context
                } ?: return@replaceMethod param.invokeOriginalMethod()

                Log.i("InnerOpenHook open settings")
                MainActivity(context)
                null
            }
        }.onFailure(Log::e)
    }
}
