package com.akari.ppx.utils

import android.app.Dialog
import android.app.ProgressDialog
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.akari.ppx.utils.MediaSheets.cardDialog
import com.akari.ppx.utils.MediaSheets.sheetRow
import com.akari.ppx.utils.MediaSheets.showSheet
import com.akari.ppx.xp.Init
import java.io.File
import java.lang.reflect.Modifier
import kotlin.concurrent.thread

/** Offline, grouped videos use the host chooser callback to attach to an unsent comment. */
internal object CustomVideos {
    private val main = Handler(Looper.getMainLooper())

    fun save(context: Context, cell: Any) {
        val source = CleanVideoSource.from(cell) ?: error("当前内容没有可保存的视频")
        pickGroup(context) { group -> safely(context) { CustomVideoSaver.save(context, source, group) } }
    }

    private fun safely(context: Context, action: () -> Unit) {
        runCatching(action).onFailure { Log.e(it); context.showToast(it.message ?: "操作失败，请重试") }
    }

    private fun rename(context: Context, current: String? = null, action: (String) -> Unit) {
        val input = EditText(context).apply { hint = "输入视频分组名称"; setText(current); setSingleLine(true) }
        cardDialog(context, if (current == null) "新建视频分组" else "重命名视频分组", input, "确定") {
            val name = input.text.toString().trim()
            if (name.isBlank()) context.showToast("分组名称不能为空") else safely(context) { action(name) }
        }
    }

    private fun pickGroup(context: Context, exclude: String? = null, action: (String) -> Unit) {
        val groups = VideoGroups.load()
        val sheet = showSheet(context, if (exclude == null) "保存到自定义视频" else "移动到视频分组",
            if (exclude == null) "下载无水印视频，保存时显示进度" else "目标分组已有的视频会自动去重")
        val scroll = ScrollView(context)
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        sheet.body.addView(scroll, LinearLayout.LayoutParams(-1, -2).apply {
            height = minOf(dp(context, (groups.size + 2) * 74), (context.resources.displayMetrics.heightPixels * .55f).toInt())
        })
        groups.filter { it.name != exclude }.forEach { group ->
            list.addView(sheetRow(context, group.name, "已有 ${group.videos.size} 个视频") { sheet.dismiss(); action(group.name) })
        }
        list.addView(sheetRow(context, "＋ 新建分组", "创建后保存到该分组", true) {
            sheet.dismiss()
            rename(context) { name ->
                if (VideoGroups.create(name)) action(name) else context.showToast("已存在同名分组")
            }
        })
        list.addView(sheetRow(context, "取消", null) { sheet.dismiss() })
    }

    fun show(composer: Dialog) = safely(composer.context) {
        val context = composer.context
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(composer.window?.decorView?.windowToken, 0)
        var selected = VideoGroups.load().firstOrNull()?.name
        lateinit var render: () -> Unit
        val sheet = showSheet(context, "自定义视频", "点击预览 · 长按视频移动或移出 · 长按分组管理", expanded = true)
        val chips = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        sheet.body.addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(chips) })
        val grid = GridView(context).apply {
            numColumns = 3; horizontalSpacing = dp(context, 8); verticalSpacing = dp(context, 8)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
        }
        val empty = TextView(context).apply { gravity = Gravity.CENTER; textSize = 14f; setTextColor(0xFF999999.toInt()) }
        val stage = FrameLayout(context)
        stage.addView(grid, FrameLayout.LayoutParams(-1, -1)); stage.addView(empty, FrameLayout.LayoutParams(-1, -1))
        sheet.body.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(context, 12) })
        render = {
            val groups = VideoGroups.load()
            if (groups.none { it.name == selected }) selected = groups.firstOrNull()?.name
            chips.removeAllViews()
            fun chip(text: String, active: Boolean, click: () -> Unit, hold: (() -> Unit)? = null) {
                chips.addView(TextView(context).apply {
                    this.text = text; textSize = 13f; gravity = Gravity.CENTER
                    setPadding(dp(context, 16), dp(context, 9), dp(context, 16), dp(context, 9))
                    setTextColor(if (active) Color.WHITE else 0xFF494949.toInt())
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = dp(context, 18).toFloat(); setColor(if (active) 0xFFED6559.toInt() else 0xFFF2F2F2.toInt())
                    }
                    layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(context, 8) }
                    setOnClickListener { safely(context, click) }
                    hold?.let { action -> setOnLongClickListener { safely(context, action); true } }
                })
            }
            groups.forEach { group -> chip("${group.name} · ${group.videos.size}", group.name == selected, {
                selected = group.name; render()
            }, {
                val menu = showSheet(context, "视频分组：${group.name}")
                menu.body.addView(sheetRow(context, "重命名分组", null) {
                    menu.dismiss(); rename(context, group.name) { name ->
                        if (VideoGroups.rename(group.name, name)) { selected = name; render() }
                        else context.showToast("已存在同名分组")
                    }
                })
                menu.body.addView(sheetRow(context, "删除分组", "移除分组及其中的视频记录") {
                    menu.dismiss()
                    cardDialog(context, "删除视频分组？", TextView(context).apply { text = "删除「${group.name}」及 ${group.videos.size} 个视频记录？" }, "删除") {
                        safely(context) { VideoGroups.removeGroup(group.name); render() }
                    }
                })
            }) }
            chip("＋ 添加分组", false, { rename(context) { name ->
                if (VideoGroups.create(name)) { selected = name; render() } else context.showToast("已存在同名分组")
            } })
            val videos = groups.firstOrNull { it.name == selected }?.videos?.toList().orEmpty()
            empty.visibility = if (videos.isEmpty()) View.VISIBLE else View.GONE
            empty.text = "还没有视频\n\n在视频的分享菜单中选择\n「保存到自定义视频」"
            grid.adapter = object : BaseAdapter() {
                override fun getCount() = videos.size
                override fun getItem(position: Int) = videos[position]
                override fun getItemId(position: Int) = position.toLong()
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                    (convertView as? VideoCell ?: VideoCell(context)).apply { bind(videos[position], position) }
            }
            grid.setOnItemClickListener { _, _, position, _ -> preview(composer, videos[position]) { sheet.dismiss() } }
            grid.setOnItemLongClickListener { _, _, position, _ ->
                val group = selected
                if (group != null) {
                    val video = videos[position]
                    val menu = showSheet(context, "管理视频", video.title)
                    menu.body.addView(sheetRow(context, "移动到其他分组", null, true) {
                        menu.dismiss(); pickGroup(context, group) { destination -> safely(context) {
                            if (VideoGroups.move(group, destination, video.id)) { selected = destination; render() }
                        } }
                    })
                    menu.body.addView(sheetRow(context, "移出当前分组", "已加入评论的视频不受影响") {
                        menu.dismiss(); safely(context) { VideoGroups.remove(group, video.id); render() }
                    })
                }
                true
            }
        }
        render()
    }

    private class VideoCell(context: Context) : FrameLayout(context) {
        private val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        private val label = TextView(context).apply {
            textSize = 11f; setTextColor(Color.WHITE); setBackgroundColor(0x99000000.toInt())
            setPadding(8, 4, 8, 4); maxLines = 2
        }
        private var bound: String? = null
        init {
            layoutParams = AbsListView.LayoutParams(-1, dp(context, 130)); descendantFocusability = FOCUS_BLOCK_DESCENDANTS
            setBackgroundColor(0xFFF2F2F2.toInt())
            addView(image, FrameLayout.LayoutParams(-1, -1)); addView(label, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        }
        fun bind(video: CustomVideo, position: Int) {
            contentDescription = "自定义视频 ${position + 1}"
            label.text = "▶ ${video.duration / 60000}:${(video.duration / 1000 % 60).toString().padStart(2, '0')}\n${video.title}"
            if (bound != video.cover) {
                bound = video.cover
                image.setImageBitmap(BitmapFactory.decodeFile(video.cover))
            }
        }
    }

    private fun preview(composer: Dialog, video: CustomVideo, onInserted: () -> Unit) = safely(composer.context) {
        val context = composer.context
        check(File(video.path).isFile) { "视频文件已丢失，请重新保存" }
        val player = VideoView(context)
        val loading = ProgressBar(context)
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val stage = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
        stage.addView(player, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        stage.addView(loading, FrameLayout.LayoutParams(dp(context, 36), dp(context, 36), Gravity.CENTER))
        content.addView(stage, LinearLayout.LayoutParams(-1, (context.resources.displayMetrics.heightPixels * .4f).toInt()))
        val dialog = android.app.AlertDialog.Builder(android.view.ContextThemeWrapper(context,
            android.R.style.Theme_Material_Light_Dialog_Alert)).setTitle(video.title).setView(content)
            .setPositiveButton("加入评论", null).setNegativeButton("关闭", null).create()
        dialog.setOnDismissListener { player.stopPlayback() }
        player.setOnPreparedListener { loading.visibility = View.GONE; player.start() }
        player.setOnErrorListener { _, _, _ -> loading.visibility = View.GONE; context.showToast("视频播放失败，请重新保存"); true }
        player.setMediaController(MediaController(context).apply { setAnchorView(stage) })
        dialog.show()
        player.setVideoPath(video.path)
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            safely(context) {
                val root = composer.window?.decorView ?: error("评论输入框已关闭")
                val ids = "com.sup.android.module.publish.R\$id".findClass(Init.cl)
                val nativeVideo = root.findViewById<View>(ids.getStaticObjectFieldAs<Int>("iv_comment_video"))
                check(nativeVideo?.isShown == true && nativeVideo.isEnabled) { "请先移除评论中的图片，再添加视频" }
                insert(composer, video) { dialog.dismiss(); onInserted() }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun insert(composer: Dialog, video: CustomVideo, onInserted: () -> Unit) {
        val context = composer.context
        val loading = ProgressDialog.show(context, "准备视频", "正在加入评论…", true, false)
        thread(name = "piyou-insert-video") {
            val result = runCatching { ForwardPublisher.chooser(File(video.path), true, File(video.cover)) }
            main.post {
                runCatching { loading.dismiss() }
                if (!composer.isShowing || context.mediaActivity()?.isDestroyed == true) return@post
                result.onSuccess { model -> safely(context) {
                    val callback = generateSequence(composer.javaClass as Class<*>?) { it.superclass }
                        .flatMap { it.declaredFields.asSequence() }
                        .filter { !Modifier.isStatic(it.modifiers) && it.type.methods.any { method -> method.name == "onChooseFinished" && method.parameterCount == 4 } }
                        .firstOrNull()?.apply { isAccessible = true }?.get(composer) ?: error("当前版本的评论附件接口不可用")
                    callback.callMethod("onChooseFinished", arrayListOf(model), null, null, null)
                    onInserted(); context.showToast("已加入评论，请确认后发布")
                    Log.i("CustomVideo inserted into unsent comment")
                } }.onFailure { Log.e(it); context.showToast("添加失败：${it.message ?: "请重试"}") }
            }
        }
    }

    private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density + .5f).toInt()
}
