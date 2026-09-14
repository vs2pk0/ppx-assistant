package com.akari.ppx.xp.hook.assist

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.akari.ppx.utils.*
import com.akari.ppx.xp.Init
import com.akari.ppx.xp.ModuleEntryBridge
import com.akari.ppx.xp.hook.BaseHook
import java.util.WeakHashMap

/** Keep the original share actions intact and handle copy before the host's PI callback. */
class ShareActionsHook : BaseHook {
    private val cells = WeakHashMap<Dialog, Any>()

    override fun onHook() {
        // Text-only replies otherwise expose just report/copy and have no share entry.
        runCatching {
            val actions = "com.sup.android.m_comment.util.helper.a".findClass(Init.cl)
            val companion = "com.sup.android.m_comment.util.helper.j".findClass(Init.cl).getStaticObjectField("b")
            val share = companion.callMethod("d") as Int
            actions.hookAfterMethod("a", Init.absFeedCellClass, Boolean::class.java, Boolean::class.java) { param ->
                if (param.args[0]?.javaClass?.simpleName == "ReplyFeedCell") {
                    val options = param.result as? List<*> ?: return@hookAfterMethod
                    if (share !in options) param.result = ArrayList(options).apply { add(share) }
                }
            }
            // The host ignores dealShareClick for text-only ReplyFeedCell. Route that
            // exact action ourselves, keeping its original copy/report actions intact.
            actions.declaredMethods.filter { it.name == "b" && it.parameterTypes.size == 6 &&
                it.parameterTypes[1] == Init.absFeedCellClass && it.parameterTypes.last() == View::class.java
            }.forEach { method ->
                method.hookBeforeMethod { param ->
                    val cell = param.args[1] ?: return@hookBeforeMethod
                    if (cell.javaClass.simpleName != "ReplyFeedCell") return@hookBeforeMethod
                    param.result = null
                    val dialog = param.args[4] as Dialog
                    android.app.AlertDialog.Builder(dialog.context)
                        .setTitle("楼中楼分享")
                        .setItems(arrayOf("转发到发帖页")) { _, _ ->
                            runCatching { ForwardPublisher.start(dialog.context, ForwardContent.from(cell)) }
                                .onFailure { Log.e(it); dialog.context.showToast(it.message ?: "暂时无法转发") }
                        }.setNegativeButton("取消", null).show()
                    dialog.dismiss()
                }
            }
        }.onFailure(Log::e)
        // A forwarded editor must not load an unrelated existing draft into its media list.
        ForwardPublisher.ACTIVITY.findClass(Init.cl).hookBeforeMethod("loadDraft", Boolean::class.java) { param ->
            val activity = param.thisObject as android.app.Activity
            if (activity.intent.getBooleanExtra(ForwardPublisher.MARKER, false)) param.result = null
        }
        "com.sup.android.module.publish.view.PublishDraft\$a".findClass(Init.cl)
            .hookBeforeMethod("a", Context::class.java) { param ->
                val activity = param.args[0] as? android.app.Activity
                if (activity?.intent?.getBooleanExtra(ForwardPublisher.MARKER, false) == true) param.result = null
            }
        val panel = Init.shareViewClass ?: return
        Log.i("ShareActions panel=${panel.name}")
        panel.hookAfterAllConstructors { param ->
            val dialog = param.thisObject as? Dialog ?: return@hookAfterAllConstructors
            Log.i("ShareActions args=${param.args.map { it?.javaClass?.name }}")
            val cell = param.args.lastOrNull()?.takeIf { Init.absFeedCellClass?.isInstance(it) == true }
                ?: capturedCell(param.args.getOrNull(3))
                ?: return@hookAfterAllConstructors
            cells[dialog] = cell
            attachForward(dialog, cell)
        }
        panel.declaredMethods.filter { it.parameterTypes.contentEquals(arrayOf(View::class.java)) &&
            it.returnType == Void.TYPE }.forEach { method ->
            method.hookBeforeMethod { param ->
                val action = (param.args[0] as View).tag as? Enum<*>
                if (action?.name != "ACTION_PI" || !ModuleEntryBridge.isFeatureInstalled("copy_item")) return@hookBeforeMethod
                // Suppress the host callback even when the selected cell has no text.
                param.result = null
                val dialog = param.thisObject as Dialog
                runCatching {
                    val text = ForwardContent.text(cells[dialog])
                    if (text.isNullOrEmpty()) dialog.context.showToast("当前内容没有可复制的文字")
                    else {
                        (dialog.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText("皮皮虾", text))
                        dialog.context.showToast("复制成功")
                    }
                }.onFailure { Log.e(it); dialog.context.showToast("复制失败，请重新打开分享菜单") }
                dialog.dismiss()
            }
        }
    }

    private fun capturedCell(callback: Any?): Any? {
        val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
        val found = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
        fun visit(value: Any?, depth: Int) {
            if (value == null || depth > 4 || visited.size > 100 || !visited.add(value)) return
            if (Init.absFeedCellClass?.isInstance(value) == true) { found.add(value); return }
            if (!value.javaClass.name.startsWith("com.sup.")) return
            value.javaClass.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }.forEach { field ->
                runCatching { field.isAccessible = true; visit(field.get(value), depth + 1) }
            }
        }
        visit(callback, 0)
        Log.i("ShareActions captured cells=${found.map { it.javaClass.simpleName }}")
        return found.singleOrNull()
    }

    private fun attachForward(dialog: Dialog, cell: Any) {
        val root = dialog.window?.decorView ?: return
        val ids = "com.sup.android.module.m_sharecontroller.R\$id".findClass(Init.cl)
        val functions = root.findViewById<View>(ids.getStaticObjectFieldAs<Int>("share_dialog_optimize_functions")) ?: return
        val parent = functions.parent as? LinearLayout ?: run {
            Log.i("ShareActions parent=${functions.parent?.javaClass?.name}"); return
        }
        if (parent.findViewWithTag<View>("ppx_forward") != null) return
        // Inflate and bind the host's own item so typography, icon size and night mode match.
        val layout = "com.sup.android.module.m_sharecontroller.R\$layout".findClass(Init.cl)
        val button = android.view.LayoutInflater.from(dialog.context).inflate(
            layout.getStaticObjectFieldAs<Int>("share_optimize_dialog_functions_collect_item"), parent, false)
        val holder = "com.sup.android.m_sharecontroller.adapter.SharePlatformFunctionsViewHolder"
            .findClass(Init.cl).new(button)!!
        val action = "com.sup.android.i_sharecontroller.model.OptionAction\$OptionActionType"
            .findClass(Init.cl).getStaticObjectField("ACTION_GO_ANCESTOR")
        holder.callMethod("a", 0, 2, action)
        button.findViewById<TextView>(ids.getStaticObjectFieldAs<Int>("dialog_functions_item_content")).text = "转发"
        button.apply {
            tag = "ppx_forward"
            contentDescription = "转发到发帖页"
            isClickable = true; isFocusable = true
            setOnClickListener {
                runCatching { ForwardPublisher.start(dialog.context, ForwardContent.from(cell)) }
                    .onSuccess { dialog.dismiss() }
                    .onFailure { Log.e(it); dialog.context.showToast(it.message ?: "暂时无法转发") }
            }
        }
        val index = parent.indexOfChild(functions)
        val originalParams = functions.layoutParams
        parent.removeView(functions)
        val row = LinearLayout(dialog.context).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button)
        row.addView(functions, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        parent.addView(row, index, originalParams)
        Log.i("ShareActions attached cell=${cell.javaClass.simpleName}")
    }
}
