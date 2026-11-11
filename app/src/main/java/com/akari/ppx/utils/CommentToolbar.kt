package com.akari.ppx.utils

import android.app.Dialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.akari.ppx.xp.Init

/** Line icons share the native GIF anchor and identical spacing. */
internal object CommentToolbar {
    enum class Icon { SETTINGS, IMAGES, VIDEOS }

    fun onCreate(attach: (Dialog) -> Unit) {
        val callback = "com.sup.android.module.publish.view.NewInputCommentDialog\$tryPublish\$2".findClass(Init.cl)
        val type = callback.declaredFields.first { Dialog::class.java.isAssignableFrom(it.type) }.type
        type.hookAfterMethod("onCreate", Bundle::class.java) { param ->
            val dialog = param.thisObject as Dialog
            dialog.window?.decorView?.post { runCatching { attach(dialog) }.onFailure(Log::e) }
        }
    }

    fun attach(dialog: Dialog, tag: String, icon: Icon, slot: Int, description: String, action: () -> Unit) {
        val root = dialog.window?.decorView as? FrameLayout ?: return
        if (root.findViewWithTag<View>(tag) != null) return
        val ids = "com.sup.android.module.publish.R\$id".findClass(Init.cl)
        val gif = root.findViewById<ImageView>(ids.getStaticObjectFieldAs<Int>("iv_comment_gif")) ?: return
        val size = dp(dialog.context, 36)
        val button = LineIcon(dialog.context, icon).apply {
            this.tag = tag
            contentDescription = description
            isClickable = true; isFocusable = true
            background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), android.graphics.drawable.ColorDrawable(0x10000000))
                addState(intArrayOf(), android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            }
            setOnClickListener { action() }
        }
        root.addView(button, FrameLayout.LayoutParams(size, size))
        root.viewTreeObserver.addOnGlobalLayoutListener {
            button.visibility = if (gif.isShown) View.VISIBLE else View.GONE
            val origin = IntArray(2); val anchor = IntArray(2)
            root.getLocationInWindow(origin); gif.getLocationInWindow(anchor)
            val start = anchor[0] - origin[0] + gif.width + dp(dialog.context, 12)
            val stride = minOf(dp(dialog.context, 44), ((root.width - dp(dialog.context, 8) - size - start) / 2).coerceAtLeast(size))
            button.x = (start + slot * stride).toFloat()
            button.y = (anchor[1] - origin[1] + (gif.height - size) / 2).toFloat()
            gif.imageTintList?.let { button.color = it.getColorForState(gif.drawableState, it.defaultColor) }
        }
    }

    private class LineIcon(context: Context, private val icon: Icon) : View(context) {
        var color = if (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
            0xFFE0E0E0.toInt() else 0xFF494949.toInt()
            set(value) { if (field != value) { field = value; invalidate() } }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1.5f
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.save(); canvas.scale(width / 36f, height / 36f)
            paint.color = color
            when (icon) {
                Icon.SETTINGS -> {
                    val gear = Path()
                    for (i in 0 until 48) {
                        val angle = i * Math.PI / 24 - Math.PI / 2
                        val radius = if (i % 6 in 1..3) 11f else 8.7f
                        val x = 18f + kotlin.math.cos(angle).toFloat() * radius
                        val y = 18f + kotlin.math.sin(angle).toFloat() * radius
                        if (i == 0) gear.moveTo(x, y) else gear.lineTo(x, y)
                    }
                    gear.close(); canvas.drawPath(gear, paint); canvas.drawCircle(18f, 18f, 3.5f, paint)
                }
                Icon.IMAGES, Icon.VIDEOS -> {
                    canvas.drawRoundRect(9f, 9f, 29f, 28f, 4f, 4f, paint)
                    canvas.drawPath(Path().apply { moveTo(6f, 23f); lineTo(6f, 9f); quadTo(6f, 6f, 9f, 6f); lineTo(24f, 6f) }, paint)
                    if (icon == Icon.IMAGES) {
                        canvas.drawCircle(23f, 14f, 1.4f, paint)
                        canvas.drawPath(Path().apply { moveTo(10f, 24f); lineTo(16f, 18f); lineTo(20f, 22f); lineTo(23f, 20f); lineTo(28f, 25f) }, paint)
                    } else canvas.drawPath(Path().apply { moveTo(16f, 14f); lineTo(23f, 18.5f); lineTo(16f, 23f); close() }, paint)
                }
            }
            canvas.restore()
        }
    }

    private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density + 0.5f).toInt()
}
