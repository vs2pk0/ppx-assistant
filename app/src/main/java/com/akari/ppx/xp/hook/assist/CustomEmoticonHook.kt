package com.akari.ppx.xp.hook.assist

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.BaseAdapter
import android.widget.AbsListView
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.akari.ppx.data.XPrefs
import com.akari.ppx.utils.CustomEmoticon
import com.akari.ppx.utils.EmoticonGroups
import com.akari.ppx.utils.MediaSheets.showSheet
import com.akari.ppx.utils.MediaSheets.sheetRow
import com.akari.ppx.utils.MediaSheets.cardDialog
import com.akari.ppx.utils.CommentToolbar
import com.akari.ppx.utils.StoragePaths
import com.akari.ppx.utils.callMethod
import com.akari.ppx.utils.callMethodAs
import com.akari.ppx.utils.callMethodOrNull
import com.akari.ppx.utils.callStaticMethod
import com.akari.ppx.utils.new
import com.akari.ppx.utils.check
import com.akari.ppx.utils.findClass
import com.akari.ppx.utils.getStaticObjectField
import com.akari.ppx.utils.getStaticObjectFieldAs
import com.akari.ppx.utils.hookAfterMethod
import com.akari.ppx.utils.hookBeforeMethod
import com.akari.ppx.utils.HookParam
import com.akari.ppx.utils.Log
import com.akari.ppx.xp.Init.cl
import com.akari.ppx.xp.hook.BaseHook
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Custom emoticon groups layered on the host's emoji panel:
 * - an extra tab icon at the panel bottom bar opens the group browser,
 * - long-pressing a panel cell (favorite/meme/sticker tabs) offers collecting
 *   to the host's default emoticons or to a module group,
 * - long-pressing "save to emoticon" on a comment with several images offers a
 *   one-tap batch collect into both destinations.
 *
 * Insertion reuses the host's own path: the panel insert callback captured
 * from EmojiPanelManager.showEmojiPanel is invoked with an EmoticonModel that
 * EmojiService.collectEmoticon stored (it downloads and dedupes by uri).
 */
class CustomEmoticonHook : BaseHook {
    private val main = Handler(Looper.getMainLooper())

    private val panelViewRef = AtomicReference<WeakReference<View>?>(null)
    private val insertCallback = AtomicReference<WeakReference<Any>?>(null)
    private val importingFavorites = AtomicBoolean(false)

    override fun onHook() {
        XPrefs<Boolean>("enable_custom_emoticon").check(true) {
            setupToolbarEntry()
            setupPanelEntry()
        }
        XPrefs<Boolean>("batch_collect_emoticon").check(true) { setupBatchCollect() }
    }

    private fun emojiService(): Any? =
        "com.bytedance.news.common.service.manager.ServiceManager".findClass(cl)
            .callStaticMethod("getService", "com.sup.android.superb.i_emoji.IEmojiService".findClass(cl))

    private fun toast(context: Context?, message: String) {
        val target = context ?: return
        main.post { Toast.makeText(target, message, Toast.LENGTH_SHORT).show() }
    }

    // region panel entry + long-press

    private fun setupPanelEntry() {
        val managerClass = "com.sup.android.module.publish.emoji.e".findClass(cl)
        val showMethod = managerClass.declaredMethods.firstOrNull { m ->
            m.parameterTypes.size == 5 && m.parameterTypes[1] == ViewGroup::class.java
                    && m.parameterTypes[2] == EditText::class.java
        }?.name ?: run { Log.i("CustomEmoticon panel manager method missing"); return }
        managerClass.hookAfterMethod(
            showMethod,
            java.lang.Boolean.TYPE,
            ViewGroup::class.java,
            EditText::class.java,
            Map::class.java,
            "kotlin.jvm.functions.Function1"
        ) { param ->
            insertCallback.set(WeakReference(param.args[4]))
            val container = param.args[1] as ViewGroup
            val panel = (0 until container.childCount)
                .map(container::getChildAt)
                .firstOrNull { it.javaClass.name == "com.sup.android.module.publish.emoji.d" }
            panel?.let {
                panelViewRef.set(WeakReference(it))
                main.post { wireCellLongPress(it) }
            }
        }
        // Pages render lazily per tab; rewire cells whenever the tab changes.
        val panelClass = "com.sup.android.module.publish.emoji.d".findClass(cl)
        val selectMethod = panelClass.declaredMethods.firstOrNull { m ->
            m.parameterTypes.size == 1 && m.parameterTypes[0] == Int::class.javaPrimitiveType
        }?.name
        selectMethod?.let { name ->
            panelClass.hookAfterMethod(name, Int::class.javaPrimitiveType) { param ->
                main.post { wireCellLongPress(param.thisObject as View) }
            }
        }
    }

    private fun setupToolbarEntry() {
        CommentToolbar.onCreate { dialog ->
            CommentToolbar.attach(dialog, "ppx_custom_emoticon", CommentToolbar.Icon.IMAGES, 1,
                "自定义表情") { showGroupsDialog(dialog.context) }
        }
    }

    private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density + 0.5f).toInt()

    /** Wires long-press save dialogs onto every favorite/meme/sticker grid cell. */
    private fun wireCellLongPress(panel: View) {
        val viewPager = findChildByClass(panel, "com.sup.android.module.publish.emoji.WrapContentViewPager") ?: return
        if (viewPager !is ViewGroup) return
        for (pageIndex in 0 until viewPager.childCount) {
            val page = viewPager.getChildAt(pageIndex)
            if (!page.javaClass.name.startsWith("com.sup.android.module.publish.emoji.view.")) continue
            runCatching {
                fun fieldUpTo(name: String) = generateSequence(page.javaClass as Class<*>?) { it.superclass }
                    .firstOrNull { it != null && runCatching { it.getDeclaredField(name) }.isSuccess }
                    ?.getDeclaredField(name)?.apply { isAccessible = true }
                val indexMap = fieldUpTo("c")?.get(page) as? Map<*, *> ?: return
                val position = fieldUpTo("d")?.get(page) as? Int ?: 0
                val drawableForIndex = fieldUpTo("h")?.get(page)
                indexMap.entries.forEach { entry ->
                    val index = (entry.key as? Number)?.toInt() ?: return@forEach
                    val cell = entry.value as? View ?: return@forEach
                    if (cell.tag == "ppx_long_press") return@forEach
                    cell.tag = "ppx_long_press"
                    cell.setOnLongClickListener {
                        val source = drawableForIndex?.callMethod("invoke", position, index)
                        if (source is String) showCellSaveDialog(cell.context, source)
                        true
                    }
                }
            }.onFailure(Log::e)
        }
    }

    private fun findChildByClass(root: View, className: String): View? {
        if (root.javaClass.name == className) return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findChildByClass(root.getChildAt(index), className)?.let { return it }
            }
        }
        return null
    }

    // endregion

    // region per-cell save dialog

    private fun showCellSaveDialog(context: Context, localPath: String) {
        val service = emojiService() ?: return
        val model = emoticonModels(service).firstOrNull { it.path == localPath }
        val uri = model?.uri
        val sheet = showSheet(context, "保存表情", "选择保存到默认表情或自定义分组")
        val savedToDefault = model != null
        sheet.body.addView(sheetRow(
            context,
            if (savedToDefault) "默认表情（已收藏）" else "收藏到默认表情",
            if (savedToDefault) "该表情已在默认表情中" else "保存到官方表情面板",
            accent = !savedToDefault
        ) {
            sheet.dismiss()
            if (!savedToDefault) collectLocal(service, context, localPath)
        })
        if (!uri.isNullOrBlank()) {
            val w = model.width.takeIf { it > 0 } ?: decodeBounds(localPath).first
            val h = model.height.takeIf { it > 0 } ?: decodeBounds(localPath).second
            sheet.body.addView(sheetRow(context, "加入自定义分组", "同时保存到自定义分组") {
                sheet.dismiss()
                showGroupPicker(context) { group ->
                    val cached = StoragePaths.cacheEmoticon(localPath, uri)
                    val gif = model.gif || localPath.lowercase().endsWith(".gif")
                    val added = EmoticonGroups.addEmoji(
                        group, CustomEmoticon(uri, w, h, gif, cached ?: localPath)
                    )
                    val persisted = EmoticonGroups.isPersisted(group, uri)
                    toast(
                        context,
                        when {
                            !persisted -> "保存失败，分组未写入"
                            added -> "已加入分组「$group」"
                            else -> "分组中已存在"
                        }
                    )
                    if (!savedToDefault) collectLocal(service, context, localPath)
                }
            })
        }
        sheet.body.addView(sheetRow(context, "取消", null) { sheet.dismiss() })
    }

    // mirrors EmoticonModel field access through one holder
    private class EmoticonRef(val path: String, val uri: String?, val width: Int, val height: Int, val gif: Boolean)

    private fun emoticonModels(service: Any): List<EmoticonRef> {
        val out = mutableListOf<EmoticonRef>()
        listOf(2, 4).forEach { type ->
            runCatching {
                (service.callMethod("getEmojiListByType", type) as? List<*>)?.forEach { item ->
                    val path = item?.callMethodOrNull("getLocalPath") as? String ?: return@forEach
                    out += EmoticonRef(
                        path = path,
                        uri = item.callMethodOrNull("getUri") as? String,
                        width = (item.callMethodOrNull("getWidth") as? Number)?.toInt() ?: 0,
                        height = (item.callMethodOrNull("getHeight") as? Number)?.toInt() ?: 0,
                        gif = (item.callMethodOrNull("getIsGif") as? Boolean)
                            ?: (item.callMethodOrNull("isGif") as? Boolean)
                            ?: path.lowercase().endsWith(".gif")
                    )
                }
            }.onFailure(Log::e)
        }
        return out
    }

    private fun decodeBounds(path: String): Pair<Int, Int> = runCatching {
        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(path, options)
        options.outWidth to options.outHeight
    }.getOrDefault(0 to 0)

    private fun singleCallback(context: Context, onResult: (Int) -> Unit): Any {
        val iface = "com.sup.android.superb.i_emoji.IEmojiService\$SingleCallBack".findClass(cl)
        return java.lang.reflect.Proxy.newProxyInstance(cl, arrayOf(iface)) { _, method, args ->
            if (method.name == "onFinish") {
                val result = (args?.getOrNull(0) as? Number)?.toInt() ?: 0
                main.post { onResult(result) }
            }
            null
        }
    }

    private fun collectLocal(service: Any, context: Context, localPath: String) {
        val (w, h) = decodeBounds(localPath)
        val gif = localPath.lowercase().endsWith(".gif")
        runCatching {
            service.callMethod(
                "collectLocalEmoticon", localPath, w, h, gif,
                singleCallback(context) { result ->
                    toast(context, if (result == 1) "已收藏到默认表情" else "收藏失败（代码 $result）")
                }
            )
        }.onFailure { Log.e(it); toast(context, "收藏失败") }
    }

    // endregion

    // region group browser

    /**
     * Tapping a saved emoticon shows it enlarged with send/cancel actions and
     * lets the user swipe through the rest of the group before inserting.
     */
    private fun showEmojiPreview(context: Context, emojis: List<CustomEmoticon>, startIndex: Int) {
        if (emojis.isEmpty()) return
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density + 0.5f).toInt()
        val metrics = context.resources.displayMetrics
        val maxSide = minOf((metrics.widthPixels * 0.72f).toInt(), dp(320))
        var index = startIndex.coerceIn(0, emojis.lastIndex)

        val dialog = Dialog(context)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(20), dp(18), dp(14))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(20).toFloat(); setColor(Color.WHITE)
            }
        }
        val image = emojiView(context)
        // Fixed square stage: switching between portrait/landscape entries used
        // to resize the dialog and shift the buttons under the user's finger.
        val stage = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(maxSide, maxSide)
        }
        stage.addView(image, FrameLayout.LayoutParams(maxSide, maxSide, Gravity.CENTER))
        container.addView(stage)
        val position = TextView(context).apply {
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(0xFF9A9A9A.toInt())
            setPadding(0, dp(8), 0, 0)
        }
        container.addView(position)
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(18), 0, 0)
        }
        fun button(label: String, filled: Boolean, block: () -> Unit) = TextView(context).apply {
            text = label
            textSize = 15f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(11), 0, dp(11))
            if (filled) {
                setTextColor(Color.WHITE)
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(20).toFloat(); setColor(0xFFED6559.toInt())
                }
            } else {
                setTextColor(0xFF666666.toInt())
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(20).toFloat(); setColor(0xFFF2F2F2.toInt())
                }
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { block() }
        }
        // Declared up-front so the nav buttons can close over the rebind function.
        lateinit var show: (Int) -> Unit
        val prev = button("‹", false) { show(index - 1) }
        val next = button("›", false) { show(index + 1) }
        prev.layoutParams = LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.WRAP_CONTENT)
        next.layoutParams = LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.WRAP_CONTENT)
        prev.textSize = 20f
        next.textSize = 20f
        actions.addView(prev)
        actions.addView(button("发送", true) {
            dialog.dismiss()
            insertEmoticon(context, emojis[index])
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(8); rightMargin = dp(8)
        })
        actions.addView(next)
        container.addView(actions)
        container.addView(TextView(context).apply {
            text = "取消"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(0xFF9A9A9A.toInt())
            setPadding(0, dp(14), 0, 0)
            setOnClickListener { dialog.dismiss() }
        })

        /** Re-binds the enlarged view; GIF entries keep animating on switch. */
        show = { target ->
            index = target.coerceIn(0, emojis.lastIndex)
            val emoji = emojis[index]
            val ratio = if (emoji.w > 0 && emoji.h > 0) emoji.w.toFloat() / emoji.h else 1f
            // The image keeps its own aspect ratio inside the fixed stage.
            val width = (if (ratio >= 1f) maxSide else (maxSide * ratio).toInt()).coerceAtLeast(dp(80))
            val height = (if (ratio >= 1f) (maxSide / ratio).toInt() else maxSide).coerceAtLeast(dp(80))
            image.layoutParams = FrameLayout.LayoutParams(width, height, Gravity.CENTER)
            loadEmojiImage(image, emoji, maxSide)
            position.text = if (emojis.size > 1) "${index + 1} / ${emojis.size}" else ""
            prev.alpha = if (index > 0) 1f else 0.35f
            next.alpha = if (index < emojis.lastIndex) 1f else 0.35f
        }
        show(index)

        // Horizontal fling over the picture also switches, matching galleries.
        image.setOnTouchListener(object : android.view.View.OnTouchListener {
            private var downX = 0f
            override fun onTouch(view: View, event: android.view.MotionEvent): Boolean {
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> downX = event.rawX
                    android.view.MotionEvent.ACTION_UP -> {
                        val dx = event.rawX - downX
                        if (dx > dp(48) && index > 0) show(index - 1)
                        else if (dx < -dp(48) && index < emojis.lastIndex) show(index + 1)
                    }
                }
                return true
            }
        })

        dialog.setContentView(container)
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setBackgroundDrawableResource(android.R.color.transparent)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.5f)
        }
        dialog.show()
    }

    private fun showGroupsDialog(context: Context) {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow((context as? Activity)?.window?.decorView?.windowToken, 0)
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density + 0.5f).toInt()
        val accent = 0xFFED6559.toInt()

        lateinit var render: () -> Unit
        var selected: String? = EmoticonGroups.load().firstOrNull()?.name
        val sheet = showSheet(
            context,
            "自定义表情",
            "长按分组可管理 · 长按表情可移动或移出",
            listOf("导入" to { importDialog(context) { render() } }, "导出" to { exportToClipboard(context) }),
            expanded = true
        )

        var chips: LinearLayout? = null
        var grid: GridView? = null
        val empty = TextView(context).apply {
            textSize = 13f; setTextColor(0xFF9A9A9A.toInt()); gravity = Gravity.CENTER
        }

        fun renderGrid(groupName: String?) {
            val target = grid ?: return
            val groups = EmoticonGroups.load()
            val group = groups.firstOrNull { it.name == groupName }
            empty.text = if (group == null) "还没有分组，点上方「＋ 添加分组」创建"
                else "分组还是空的，可导入收藏或长按表情加入"
            val emojis = group?.emojis?.toList().orEmpty()
            target.setOnItemClickListener { _, _, position, _ ->
                showEmojiPreview(context, emojis, position)
            }
            target.setOnItemLongClickListener { _, _, position, _ ->
                val name = group?.name ?: return@setOnItemLongClickListener false
                val emoji = emojis.getOrNull(position) ?: return@setOnItemLongClickListener false
                val menu = showSheet(context, "管理表情", "当前分组：$name")
                menu.body.addView(sheetRow(context, "移动到其他分组", "选择目标分组，也可以新建分组", accent = true) {
                    menu.dismiss()
                    showGroupPicker(context, movingFrom = name) { destination ->
                        if (EmoticonGroups.moveEmoji(name, destination, emoji.uri)) {
                            toast(context, "已移动到「$destination」")
                            selected = destination
                            render()
                        } else toast(context, "移动失败，表情或目标分组不可用，请检查存储目录")
                    }
                })
                menu.body.addView(sheetRow(context, "移出当前分组", "仅从「$name」中移出这张表情") {
                    EmoticonGroups.removeEmoji(name, emoji.uri)
                    menu.dismiss()
                    val groupsNow = EmoticonGroups.load()
                    selected = groupsNow.firstOrNull { it.name == name }?.name
                        ?: groupsNow.firstOrNull()?.name
                    render()
                })
                menu.body.addView(sheetRow(context, "取消", null) { menu.dismiss() })
                true
            }
            // The native GridView recycles cells, so importing hundreds of favorites
            // does not decode every image or start every GIF at once.
            target.adapter = object : BaseAdapter() {
                override fun getCount() = emojis.size
                override fun getItem(position: Int) = emojis[position]
                override fun getItemId(position: Int) = position.toLong()
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val emoji = emojis[position]
                    // GridView measures position 0 using a temporarily detached scrap
                    // cell. Keep its children intact and let the grid dispatch item clicks.
                    val cell = convertView as? EmoticonCell ?: EmoticonCell(context)
                    cell.bind(emoji, position)
                    return cell
                }
            }
            target.setSelection(0)
        }

        fun renderChips(active: String?) {
            val row = chips ?: return
            row.removeAllViews()
            fun chip(label: String, filled: Boolean, onClick: () -> Unit, onLong: (() -> Unit)? = null) {
                row.addView(TextView(context).apply {
                    text = label
                    textSize = 13f
                    setPadding(dp(16), dp(9), dp(16), dp(9))
                    gravity = Gravity.CENTER
                    if (filled) {
                        setTextColor(Color.WHITE)
                        background = android.graphics.drawable.GradientDrawable().apply {
                            cornerRadius = dp(18).toFloat(); setColor(accent)
                        }
                    } else {
                        setTextColor(0xFF4A4A4A.toInt())
                        background = android.graphics.drawable.GradientDrawable().apply {
                            cornerRadius = dp(18).toFloat(); setColor(0xFFF2F2F2.toInt())
                        }
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { rightMargin = dp(8) }
                    setOnClickListener { onClick() }
                    onLong?.let { listener -> setOnLongClickListener { listener(); true } }
                })
            }
            EmoticonGroups.load().forEach { group ->
                chip("${group.name} · ${group.emojis.size}", group.name == active, {
                    selected = group.name; render()
                }, {
                    val menu = showSheet(context, "分组：${group.name}", "选择要执行的操作")
                    menu.body.addView(sheetRow(context, "重命名分组", "修改分组名称") {
                        menu.dismiss()
                        renameDialog(context, group.name) { newName ->
                            EmoticonGroups.renameGroup(group.name, newName)
                            selected = newName; render()
                        }
                    })
                    menu.body.addView(sheetRow(context, "删除分组", "同时删除组内 ${group.emojis.size} 张表情", accent = true) {
                        EmoticonGroups.deleteGroup(group.name)
                        selected = EmoticonGroups.load().firstOrNull()?.name
                        menu.dismiss(); render()
                    })
                    menu.body.addView(sheetRow(context, "取消", null) { menu.dismiss() })
                })
            }
            chip("＋ 添加分组", false, {
                renameDialog(context, null) { name ->
                    EmoticonGroups.createGroup(name)
                    selected = name; render()
                }
            })
        }

        render = { renderChips(selected); renderGrid(selected) }

        sheet.body.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            fun action(label: String, block: () -> Unit) {
                addView(TextView(context).apply {
                    text = label; textSize = 13f; gravity = Gravity.CENTER
                    setTextColor(accent)
                    setPadding(dp(8), dp(12), dp(8), dp(12))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = dp(12).toFloat(); setColor(0x14ED6559)
                    }
                    setOnClickListener { block() }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(3), 0, dp(3), dp(12))
                })
            }
            action("导入全部收藏") {
                importFavorites(context) {
                    if (sheet.dialog.isShowing) {
                        selected = EmoticonGroups.load().firstOrNull { it.name == "收藏表情" }?.name ?: selected
                        render()
                    }
                }
            }
            action("全部删除表情") {
                confirmClearEmojis(context) { if (sheet.dialog.isShowing) render() }
            }
        })
        chips = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        sheet.body.addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
        })
        grid = GridView(context).apply {
            numColumns = 4
            horizontalSpacing = dp(4); verticalSpacing = dp(4)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false
            emptyView = empty
        }
        sheet.body.addView(FrameLayout(context).apply {
            addView(grid)
            addView(empty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply { topMargin = dp(10) }
        })
        render()
    }

    private inner class EmoticonCell(context: Context) : FrameLayout(context) {
        private val image = emojiView(context)
        private val badge = TextView(context).apply {
            text = "GIF"; textSize = 8.5f; gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(context, 5).toFloat(); setColor(0xCCED6559.toInt())
            }
        }
        private var bound: CustomEmoticon? = null

        init {
            layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 84))
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(context, 14).toFloat(); setColor(0xFFF7F7F7.toInt())
            }
            addView(image, FrameLayout.LayoutParams(dp(context, 76), dp(context, 76), Gravity.CENTER))
            addView(badge, FrameLayout.LayoutParams(
                dp(context, 26), dp(context, 14), Gravity.TOP or Gravity.END
            ).apply { topMargin = dp(context, 4); rightMargin = dp(context, 4) })
        }

        fun bind(emoji: CustomEmoticon, position: Int) {
            contentDescription = "表情 ${position + 1}"
            if (bound != emoji) {
                (image as? android.widget.ImageView)?.let {
                    (it.drawable as? android.graphics.drawable.Animatable)?.stop()
                    it.setImageDrawable(null)
                }
                loadEmojiImage(image, emoji, dp(context, 76))
                bound = emoji
            }
            badge.visibility = if (emoji.gif || emoji.local?.lowercase()?.endsWith(".gif") == true)
                View.VISIBLE else View.GONE
        }
    }

    private fun confirmClearEmojis(context: Context, onDone: () -> Unit) {
        if (importingFavorites.get()) {
            toast(context, "正在导入收藏，请稍后再试")
            return
        }
        val count = EmoticonGroups.load().sumOf { it.emojis.size }
        if (count == 0) {
            toast(context, "暂无可删除的自定义表情")
            return
        }
        val message = TextView(context).apply {
            text = "将删除所有分组中的 $count 张自定义表情，保留分组名称。\n\n官方收藏不受影响。删除后可通过之前导出的 JSON 恢复。"
            textSize = 14f; setTextColor(0xFF666666.toInt())
        }
        cardDialog(context, "删除全部自定义表情？", message, "全部删除") {
            if (EmoticonGroups.clearEmojis()) {
                toast(context, "已删除 $count 张自定义表情")
                onDone()
            } else toast(context, "删除失败，请检查存储目录")
        }
    }

    private fun importFavorites(context: Context, onDone: () -> Unit) {
        if (!importingFavorites.compareAndSet(false, true)) {
            toast(context, "正在导入收藏，请稍后再试")
            return
        }
        val service = runCatching { emojiService() }.onFailure(Log::e).getOrNull()
        if (service == null) {
            importingFavorites.set(false)
            toast(context, "表情服务未就绪，请稍后再试")
            return
        }
        val dialog = Dialog(context)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val status = TextView(context).apply {
            text = "正在读取收藏表情…"; textSize = 14f; setTextColor(0xFF666666.toInt())
            setPadding(0, dp(context, 16), 0, dp(context, 12))
        }
        val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            progressTintList = android.content.res.ColorStateList.valueOf(0xFFED6559.toInt())
        }
        val close = TextView(context).apply {
            text = "正在导入…"; textSize = 14f; gravity = Gravity.END
            setTextColor(0xFFED6559.toInt()); setPadding(0, dp(context, 18), 0, 0)
            isEnabled = false
            setOnClickListener { dialog.dismiss() }
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 24), dp(context, 22), dp(context, 24), dp(context, 20))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(context, 20).toFloat(); setColor(Color.WHITE)
            }
            addView(TextView(context).apply {
                text = "导入全部收藏"; textSize = 18f; setTextColor(0xFF1F1F1F.toInt())
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(status)
            addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 8)))
            addView(close)
        }
        dialog.setContentView(content)
        dialog.setCancelable(false)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
        dialog.window?.setLayout(context.resources.displayMetrics.widthPixels - dp(context, 48), ViewGroup.LayoutParams.WRAP_CONTENT)
        Thread({
            val result = runCatching {
                val models = (service.callMethod("getEmojiListByType", 2) as? List<*>)?.toList()
                    ?: error("收藏列表读取失败")
                val emojis = ArrayList<CustomEmoticon>(models.size)
                var skipped = 0
                var lastPercent = -1
                models.forEachIndexed { index, item ->
                    val emoji = runCatching { item?.let(::favoriteEmoticon) }.onFailure(Log::e).getOrNull()
                    if (emoji == null) skipped++ else emojis.add(emoji)
                    val done = index + 1
                    val percent = (done.toLong() * 100 / models.size).toInt()
                    if (percent != lastPercent || done == models.size) {
                        lastPercent = percent
                        main.post {
                            progress.isIndeterminate = false
                            progress.progress = percent
                            status.text = "正在导入 $done / ${models.size}（$percent%）"
                        }
                    }
                }
                main.post { status.text = "正在保存分组…" }
                val added = EmoticonGroups.addEmojis("收藏表情", emojis)
                check(added >= 0) { "分组保存失败，请检查存储目录" }
                Log.i("CustomEmoticon favorites import total=${models.size} added=$added skipped=$skipped")
                when {
                    models.isEmpty() -> "暂无已收藏的表情"
                    else -> "导入完成，新增 $added 张，已有 ${emojis.size - added} 张" +
                        if (skipped > 0) "，无法读取 $skipped 张" else ""
                }
            }.onFailure(Log::e)
            main.post {
                importingFavorites.set(false)
                val activity = context as? Activity
                if (activity?.isFinishing == true || activity?.isDestroyed == true) return@post
                progress.isIndeterminate = false
                progress.progress = if (result.isSuccess) 100 else progress.progress
                status.text = result.getOrElse { "导入失败：${it.message ?: "请稍后重试"}" }
                close.text = "完成"; close.isEnabled = true
                dialog.setCancelable(true)
                if (result.isSuccess) onDone()
            }
        }, "ppx-import-favorites").start()
    }

    /** Convert official favorites without invoking collection/download APIs again. */
    private fun favoriteEmoticon(model: Any): CustomEmoticon? {
        val rawPath = model.callMethodOrNull("getLocalPath") as? String
        val path = rawPath?.let { if (it.startsWith("file:")) Uri.parse(it).path else it }
        val uri = imageUri(model).takeIf { it.isNotBlank() }
            ?: path?.takeIf { java.io.File(it).isFile }?.let(::fileUri)
            ?: return null
        return CustomEmoticon(
            uri = uri,
            w = (model.callMethodOrNull("getWidth") as? Number)?.toInt() ?: 0,
            h = (model.callMethodOrNull("getHeight") as? Number)?.toInt() ?: 0,
            gif = (model.callMethodOrNull("getIsGif") as? Boolean)
                ?: (model.callMethodOrNull("isGif") as? Boolean)
                ?: (path?.lowercase()?.endsWith(".gif") == true),
            local = StoragePaths.cacheEmoticon(path, uri) ?: path
        )
    }

    private fun exportToClipboard(context: Context) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("皮友助手自定义表情", EmoticonGroups.export()))
        Toast.makeText(context, "分组 JSON 已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }

    /**
     * Finds the on-disk copy for a stored group entry. Saved entries keep the
     * source uri (for cross-device import), but the host already downloaded the
     * image, so the local file is preferred: it always renders and works
     * offline. Matching by file name also covers uris rewritten by the CDN.
     */
    private fun localPathFor(emoji: CustomEmoticon): String? {
        emoji.local?.takeIf { java.io.File(it).isFile }?.let { return it }
        // Older entries only stored the URI; look for a cached copy in the
        // shared emoticon folder, then fall back to the host's own download.
        runCatching {
            StoragePaths.emoticonDir().listFiles()?.forEach { file ->
                if (file.isFile && file.name.startsWith(cacheKey(emoji.uri))) return file.absolutePath
            }
        }
        val service = emojiService() ?: return null
        val target = emoji.uri
        val targetName = target.substringAfterLast('/').substringBefore('?')
        val models = runCatching {
            (service.callMethod("getEmojiListByType", 2) as? List<*>).orEmpty() +
                (service.callMethod("getEmojiListByType", 4) as? List<*>).orEmpty()
        }.getOrDefault(emptyList())
        models.forEach { item ->
            val path = item?.callMethodOrNull("getLocalPath") as? String ?: return@forEach
            if (!java.io.File(path).isFile) return@forEach
            val uri = item.callMethodOrNull("getUri") as? String
            val name = uri?.substringAfterLast('/')?.substringBefore('?')
            if (uri == target || (targetName.isNotBlank() && name == targetName)) {
                StoragePaths.cacheEmoticon(path, target)?.let { return it }
                return path
            }
        }
        return null
    }

    private fun cacheKey(uri: String): String = runCatching {
        val digest = java.security.MessageDigest.getInstance("SHA-1")
        digest.digest(uri.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
    }.getOrDefault(uri.hashCode().toString())

    private fun fileUri(path: String): String =
        if (path.startsWith("file:")) path else Uri.fromFile(java.io.File(path)).toString()

    /**
     * Builds the view used inside groups and the preview.
     *
     * Animated entries (GIF / animated WebP) cannot go through the host's
     * Fresco helper: it decodes frame 0 only, and even the controller path left
     * them frozen on 6.5.5. Android 16 exposes ImageDecoder + AnimatedImageDrawable
     * for both formats, so those files are rendered natively and stay animated
     * no matter how the host configured Fresco.
     */
    private fun emojiView(context: Context): View =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P)
            android.widget.ImageView(context).apply {
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(view: View) {
                        (drawable as? android.graphics.drawable.Animatable)?.start()
                    }
                    override fun onViewDetachedFromWindow(view: View) {
                        (drawable as? android.graphics.drawable.Animatable)?.stop()
                    }
                })
            }
        else "com.facebook.drawee.view.SimpleDraweeView".findClass(cl).new(context) as View

    /**
     * Renders a local emoticon file into [view], starting the frame animation
     * when the bytes are animated. Returns false when the file cannot be read,
     * so the caller can fall back to the host's Fresco loader.
     *
     * Android 16 can decode GIF and animated WebP through ImageDecoder, while
     * the host's Fresco helper only ever produced frame 0 for our saved files.
     */
    private fun loadNativeImage(view: View, path: String, size: Int): Boolean = runCatching {
        val image = view as? android.widget.ImageView ?: return false
        val file = java.io.File(path)
        if (!file.isFile || file.length() == 0L) return false
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            val uri = Uri.fromFile(file)
            val source = android.graphics.ImageDecoder.createSource(view.context.contentResolver, uri)
            val drawable = android.graphics.ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
                val longest = maxOf(info.size.width, info.size.height)
                if (longest > size) decoder.setTargetSize(
                    (info.size.width.toLong() * size / longest).toInt().coerceAtLeast(1),
                    (info.size.height.toLong() * size / longest).toInt().coerceAtLeast(1)
                )
            }
            image.setImageDrawable(drawable)
            (drawable as? android.graphics.drawable.Animatable)?.let {
                it.start()
                Log.i("CustomEmoticon image native-anim file=${file.name}")
            }
            return true
        }
        val bitmap = android.graphics.BitmapFactory.decodeFile(path) ?: return false
        image.setImageBitmap(bitmap)
        true
    }.onFailure { Log.e(it) }.getOrDefault(false)

    /** Loads an emoticon: local copy rendered natively, remote falls back to Fresco. */
    private fun loadEmojiImage(view: View, emoji: CustomEmoticon, size: Int) {
        val local = localPathFor(emoji)
        if (local != null && loadNativeImage(view, local, size)) return
        val source = local?.let(::fileUri) ?: normalizeRemote(emoji.uri)
        if (source.isBlank()) {
            Log.i("CustomEmoticon skip blank uri")
            return
        }
        // No readable local file (remote-only entry): the host helper needs a
        // SimpleDraweeView, so only this branch still depends on Fresco.
        val animated = emoji.gif ||
            local?.lowercase()?.endsWith(".gif") == true ||
            source.substringBefore('?').lowercase().endsWith(".gif")
        if (!animated) {
            val loaded = runCatching {
                val drawee = "com.facebook.drawee.view.SimpleDraweeView".findClass(cl)
                if (!drawee.isInstance(view)) return@runCatching false
                "com.bytedance.ies.xelement.video.base.utils.FrescoHelper".findClass(cl)
                    .callStaticMethod(
                        "bindImage", view, source, size, size, android.graphics.Bitmap.Config.ARGB_8888
                    )
                true
            }.onFailure(Log::e).getOrDefault(false)
            if (loaded) {
                Log.i("CustomEmoticon image source=${source.take(72)} local=false")
                return
            }
        }
        runCatching {
            require(view !is android.widget.ImageView) { "ImageView has no Fresco controller" }
            val requestBuilder = "com.facebook.imagepipeline.request.ImageRequestBuilder".findClass(cl)
                .callStaticMethod("newBuilderWithSource", Uri.parse(source))
            val request = requestBuilder.callMethod("build")
            val controllerBuilder = "com.facebook.drawee.backends.pipeline.Fresco".findClass(cl)
                .callStaticMethod("newDraweeControllerBuilder")
            controllerBuilder.callMethod("setImageRequest", request)
            runCatching {
                controllerBuilder.callMethod("setOldController", view.callMethod("getController"))
            }.onFailure(Log::e)
            if (animated) controllerBuilder.callMethod("setAutoPlayAnimations", true)
            view.callMethod("setController", controllerBuilder.callMethod("build"))
            Log.i("CustomEmoticon image fresco source=${source.take(72)} animated=$animated")
        }.onFailure(Log::e)
    }

    /** Bare CDN keys have no scheme; the host's CDN expects the ppx host. */
    private fun normalizeRemote(uri: String): String = when {
        uri.isBlank() -> ""
        uri.startsWith("http://") || uri.startsWith("https://") || uri.startsWith("file:") -> uri
        uri.startsWith("//") -> "https:$uri"
        uri.startsWith("/") -> fileUri(uri)
        else -> "https://p1-ppx.bytecdn.cn/$uri"
    }

    private fun renameDialog(context: Context, current: String?, onDone: (String) -> Unit) {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density + 0.5f).toInt()
        val input = EditText(context).apply {
            setText(current); hint = "输入分组名称"
            setTextColor(0xFF222222.toInt()); setHintTextColor(0xFF999999.toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(12).toFloat(); setColor(0xFFF5F5F5.toInt())
            }
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        cardDialog(context, if (current == null) "新建分组" else "重命名分组", input, "确定") {
            val name = input.text.toString().trim()
            if (name.isNotEmpty()) onDone(name)
        }
    }

    private fun importDialog(context: Context, onDone: () -> Unit) {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density + 0.5f).toInt()
        val input = EditText(context).apply {
            hint = "粘贴导出的分组 JSON"
            setTextColor(0xFF222222.toInt()); setHintTextColor(0xFF999999.toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(12).toFloat(); setColor(0xFFF5F5F5.toInt())
            }
            setPadding(dp(14), dp(12), dp(14), dp(12))
            minHeight = dp(120); gravity = Gravity.TOP
        }
        cardDialog(context, "导入自定义表情", input, "导入") {
            val added = EmoticonGroups.import(input.text.toString())
            Toast.makeText(context, if (added >= 0) "导入完成，新增 $added 个表情" else "JSON 解析失败", Toast.LENGTH_SHORT).show()
            if (added >= 0) onDone()
        }
    }

    private fun showGroupPicker(context: Context, movingFrom: String? = null, onPick: (String) -> Unit) {
        val sheet = showSheet(
            context,
            if (movingFrom == null) "加入自定义分组" else "移动到分组",
            if (movingFrom == null) "选择保存位置，表情会同时收藏为默认表情"
            else "从「$movingFrom」移出，目标分组已有的表情会自动去重"
        )
        EmoticonGroups.load().filter { it.name != movingFrom }.forEach { group ->
            sheet.body.addView(sheetRow(context, group.name, "已有 ${group.emojis.size} 张表情") {
                sheet.dismiss(); onPick(group.name)
            })
        }
        sheet.body.addView(sheetRow(context, "＋ 新建分组", if (movingFrom == null) "创建后立即保存到该分组" else "创建后立即移动到该分组", accent = true) {
            sheet.dismiss()
            renameDialog(context, null) { name ->
                EmoticonGroups.createGroup(name)
                onPick(name)
            }
        })
        sheet.body.addView(sheetRow(context, "取消", null) { sheet.dismiss() })
    }

    // region insertion

    private fun insertEmoticon(context: Context, emoji: CustomEmoticon) {
        val service = emojiService()
        if (service == null) { toast(context, "表情服务不可用"); return }
        val collected = findCollected(service, emoji.uri)
        if (collected != null) {
            invokeInsert(context, collected)
            return
        }
        val imageModel = "com.sup.android.base.model.ImageModel".findClass(cl).new()
        imageModel.callMethod("setUri", emoji.uri)
        imageModel.callMethod("setWidth", emoji.w)
        imageModel.callMethod("setHeight", emoji.h)
        imageModel.callMethod("setGif", emoji.gif)
        imageModel.callMethod(
            "setUrlList",
            arrayListOf("com.sup.android.base.model.ImageUrlModel".findClass(cl).new(emoji.uri))
        )
        val callback = singleCallback(context) { result ->
            val model = findCollected(service, emoji.uri)
            if (model != null) invokeInsert(context, model)
            else toast(context, "表情下载失败（代码 $result）")
        }
        runCatching {
            service.callMethod("collectEmoticon", imageModel, 0L, 0L, 0L, callback)
        }.onFailure { Log.e(it); toast(context, "表情服务调用失败") }
    }

    private fun findCollected(service: Any, uri: String): Any? =
        runCatching {
            (service.callMethod("getEmojiListByType", 2) as? List<*>)?.firstOrNull { item ->
                item?.callMethodOrNull("getUri") == uri
            }
        }.getOrNull()

    private fun invokeInsert(context: Context, model: Any) {
        val callback = insertCallback.get()?.get()
        if (callback == null) {
            toast(context, "请先打开评论输入框的表情面板")
            return
        }
        runCatching { callback.callMethod("invoke", model) }
            .onFailure { Log.e(it); toast(context, "插入失败") }
    }

    // endregion

    // region comment batch collect

    private val gridComments = java.util.WeakHashMap<View, Any>()

    private fun setupBatchCollect() {
        // Comment list and detail pages save through two different helpers.
        runCatching {
            val helperClass = "com.sup.android.m_comment.util.helper.c".findClass(cl)
            val saveMethod = helperClass.declaredMethods.firstOrNull { m ->
                m.parameterTypes.size == 3 && m.parameterTypes[0] == Activity::class.java
            }?.name ?: error("CommentHelper save method missing")
            helperClass.hookBeforeMethod(
                saveMethod,
                Activity::class.java,
                "com.sup.android.mi.feed.repo.bean.cell.AbsFeedCell",
                Int::class.javaPrimitiveType
            ) { param -> interceptBatch(param) }
        }.onFailure(Log::e)
        runCatching {
            "com.sup.android.detail.util.h".findClass(cl).declaredMethods.firstOrNull { m ->
                m.parameterTypes.size == 3 && m.parameterTypes[0] == Activity::class.java
                        && m.parameterTypes[1].name.endsWith("AbsFeedCell")
            }?.let { method ->
                method.hookBeforeMethod { param -> interceptBatch(param) }
            }
        }.onFailure(Log::e)
        // Long-pressing a comment image opens the batch dialog directly,
        // bypassing the host's report/add-to-emoticon bubble.
        runCatching {
            val gridClass = "com.sup.android.uikit.imagegrid.MultiImageGrid".findClass(cl)
            val holderClass = "com.sup.android.m_comment.docker.holder.e".findClass(cl)
            val bindMethod = holderClass.declaredMethods.firstOrNull { m ->
                m.parameterTypes.size == 2 && m.parameterTypes[0].name.endsWith("comment.Comment")
            } ?: error("grid bind method missing")
            val gridField = holderClass.declaredFields.firstOrNull {
                it.type.name.endsWith("MultiImageGrid")
            }?.apply { isAccessible = true } ?: error("grid field missing")
            Log.i("CustomEmoticon grid bind method=${bindMethod.name}")
            bindMethod.hookAfterMethod { param ->
                val grid = gridField.get(param.thisObject) as? View ?: return@hookAfterMethod
                (param.args[0] as? Any)?.let { gridComments[grid] = it }
            }
            gridClass.declaredMethods.firstOrNull { it.name == "setGridViewLongClick" }?.let { setLc ->
                Log.i("CustomEmoticon grid wrapper armed")
                setLc.hookBeforeMethod { param ->
                    if (!XPrefs<Boolean>("batch_collect_emoticon")) return@hookBeforeMethod
                    val grid = param.thisObject as View
                    val host = param.args[0]
                    val iface = "com.sup.android.uikit.imagegrid.MultiImageGrid\$b".findClass(cl)
                    param.args[0] = java.lang.reflect.Proxy.newProxyInstance(cl, arrayOf(iface)) { _, method, args ->
                        if (method.name == "onLongClick") {
                            val view = args?.getOrNull(1) as? View
                            val context = view?.context
                            val comment = gridComments[grid]
                            Log.i("CustomEmoticon grid long-click context=${context?.javaClass?.name} isActivity=${context is Activity} comment=${comment != null}")
                            if (context is Activity && comment != null) {
                                val images = runCatching {
                                    comment.callMethodAs<List<Any?>>("getImages")
                                }.getOrNull().orEmpty()
                                Log.i("CustomEmoticon images=${images.size}")
                                if (images.isNotEmpty()) {
                                    main.post {
                                        runCatching { showBatchDialog(context, images) }.onFailure(Log::e)
                                        Log.i("CustomEmoticon batch dialog posted")
                                    }
                                    return@newProxyInstance true
                                }
                            }
                        }
                        host?.callMethod(method.name, *(args ?: emptyArray<Any?>()))
                    }
                }
            }
        }.onFailure(Log::e)
    }

    private fun showBatchDialog(context: Activity, images: List<Any?>) {
        val valid = images.filterNotNull()
        val sheet = showSheet(
            context,
            "一键收藏表情（${valid.size} 张）",
            "将该评论里的全部表情保存到哪里？"
        )
        sheet.body.addView(sheetRow(context, "添加到官方表情", "收藏后可在表情面板直接使用") {
            sheet.dismiss()
            val service = emojiService() ?: return@sheetRow
            val success = java.util.concurrent.atomic.AtomicInteger()
            val total = valid.size
            val callback = singleCallback(context) { result ->
                if (result == 1 || result == 8) {
                    val done = success.incrementAndGet()
                    if (done == total) toast(context, "已收藏 $done 张表情")
                }
            }
            valid.forEach { image ->
                runCatching { service.callMethod("collectEmoticon", image, 0L, 0L, 0L, callback) }
                    .onFailure(Log::e)
            }
        })
        sheet.body.addView(sheetRow(context, "添加到自定义分组", "可新建分组并自动收藏到默认表情", accent = true) {
            sheet.dismiss()
            showGroupPicker(context) { group ->
                var added = 0
                val service = emojiService()
                val callback = service?.let {
                    singleCallback(context) { }
                }
                valid.forEach { image ->
                    val uri = imageUri(image)
                    if (uri.isBlank()) return@forEach
                    val w = (image.callMethodOrNull("getWidth") as? Number)?.toInt() ?: 0
                    val h = (image.callMethodOrNull("getHeight") as? Number)?.toInt() ?: 0
                    val rawLocal = (image.callMethodOrNull("getUri") as? String)?.takeIf {
                        it.startsWith("/") || it.startsWith("file:")
                    } ?: (image.callMethodOrNull("getLocalPath") as? String)
                    val local = StoragePaths.cacheEmoticon(rawLocal, uri) ?: rawLocal
                    val gif = (image.callMethodOrNull("isGif") as? Boolean) == true ||
                        (image.callMethodOrNull("getIsGif") as? Boolean) == true ||
                        rawLocal?.lowercase()?.endsWith(".gif") == true
                    if (EmoticonGroups.addEmoji(group, CustomEmoticon(uri, w, h, gif, local))) added++
                    if (service != null && callback != null) {
                        runCatching { service.callMethod("collectEmoticon", image, 0L, 0L, 0L, callback) }
                            .onFailure(Log::e)
                    }
                }
                val persisted = EmoticonGroups.isGroupPersisted(group)
                toast(
                    context,
                    if (persisted) "已加入分组「$group」$added 张" else "保存失败，分组未写入"
                )
            }
        })
        sheet.body.addView(sheetRow(context, "取消", null) { sheet.dismiss() })
    }

    private fun imageUri(image: Any): String =
        ((image.callMethodOrNull("getUri") as? String)
            ?: ((image.callMethodOrNull("getUrlList") as? List<*>)?.firstOrNull()
                ?.callMethodOrNull("getUrl") as? String)).orEmpty()

    private fun interceptBatch(param: com.akari.ppx.utils.HookParam) {
        val context = param.args[0] as Activity
        val cell = param.args[1] ?: return
        val util = "com.sup.android.mi.feed.repo.utils.b\$a".findClass(cl)
            .getStaticObjectField("a") ?: return
        val images = runCatching { util.callMethodAs<List<Any?>>("ad", cell) }.getOrNull().orEmpty()
        if (images.isEmpty()) return
        param.result = null
        showBatchDialog(context, images)
    }

    // endregion
}
