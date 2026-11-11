package com.akari.ppx.xp.hook.assist

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.PathShape
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import com.akari.ppx.data.CommentGod
import com.akari.ppx.utils.callMethodOrNull
import com.akari.ppx.utils.getStaticObjectFieldAs
import com.akari.ppx.utils.hookAfterMethod
import com.akari.ppx.xp.hook.SwitchHook
import kotlin.math.roundToInt

@Suppress("unused")
class CommentGodButtonHook : SwitchHook(CommentGod.BUTTON) {
    override fun onHook() {
        if (!supportsCommentActions("CommentGodButton")) return
        val commentIds = hostClass("com.sup.android.m_comment.R\$id")
        val likeId = commentIds.getStaticObjectFieldAs<Int>("detail_comment_like_layout")
        val countId = commentIds.getStaticObjectFieldAs<Int>("detail_comment_like_tv")
        val nativeId = commentIds.getStaticObjectFieldAs<Int>("ll_comment_god_auth_container")
        val details = hostClass("com.sup.superb.dockerbase.misc.DockerContext")
        val dockerRow = hostClass("com.sup.android.m_comment.docker.a.a\$a")
        for (name in listOf("d", "i")) {
            val holder = hostClass("com.sup.android.m_comment.docker.holder.$name")
            val getView = holder.getDeclaredMethod("b")
            holder.getDeclaredMethod("a", dockerRow, details).hookAfterMethod { param ->
                if (param.throwable != null) return@hookAfterMethod
                val root = getView.invoke(param.thisObject) as? View ?: return@hookAfterMethod
                val row = CommentGodAction.row(param.args[0].callMethodOrNull("getCellData"))
                val nativeButton = root.findViewById<View>(nativeId)
                val showExtra = nativeButton?.visibility != View.VISIBLE
                bind(root, likeId, countId, row.takeIf { showExtra }, bottom = false)
            }
        }

        val bottom = hostClass("com.sup.android.detail.util.viewcontroller.a")
        val getBottom = bottom.getDeclaredMethod("c")
        val cell = hostClass("com.sup.android.mi.feed.repo.bean.cell.AbsFeedCell")
        val bottomIds = hostClass("com.sup.android.detail.R\$id")
        val bottomLikeId = bottomIds.getStaticObjectFieldAs<Int>("detail_bottom_like_layout")
        val bottomCountId = bottomIds.getStaticObjectFieldAs<Int>("detail_bottom_like_num_tv")
        bottom.getDeclaredMethod("a", cell, Boolean::class.java).hookAfterMethod { param ->
            if (param.throwable != null) return@hookAfterMethod
            val root = getBottom.invoke(param.thisObject) as? View ?: return@hookAfterMethod
            bind(root, bottomLikeId, bottomCountId, CommentGodAction.row(param.args[0]), bottom = true)
        }
    }

    private fun bind(root: View, likeId: Int, countId: Int, row: CommentGodAction.Row?, bottom: Boolean) {
        val like = root.findViewById<View>(likeId) ?: return
        val actions = like.parent as? LinearLayout ?: return
        if (actions.orientation != LinearLayout.HORIZONTAL) return
        val existing = actions.findViewWithTag<SendButton>(BUTTON_TAG)
        if (row == null) {
            existing?.row = null
            existing?.visibility = View.GONE
            existing?.setOnClickListener(null)
            return
        }
        val count = like.findViewById<TextView>(countId)
        val button = existing ?: SendButton(actions.context).apply {
            tag = BUTTON_TAG
            val inline = !bottom && (like as? LinearLayout)?.orientation == LinearLayout.HORIZONTAL
            gravity = if (bottom) Gravity.TOP or Gravity.CENTER_HORIZONTAL else Gravity.CENTER
            setSingleLine(true)
            if (count != null) {
                setTextSize(TypedValue.COMPLEX_UNIT_PX, count.textSize)
                includeFontPadding = count.includeFontPadding
            } else {
                textSize = 10f
            }
            val icon = createIcon(dp(if (bottom) 24 else if (inline) 20 else 22))
            if (inline) {
                compoundDrawablePadding = dp(2)
                setCompoundDrawablesRelative(null, null, icon, null)
            } else {
                compoundDrawablePadding = dp(1)
                setCompoundDrawablesRelative(null, icon, null, null)
            }
            setPadding(dp(3), if (bottom) dp(4) else 0, dp(3), 0)
            val ripple = TypedValue()
            if (context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)) {
                setBackgroundResource(ripple.resourceId)
            }
            isFocusable = true
            val width = like.layoutParams.width.takeIf { bottom && it > 0 } ?: WRAP_CONTENT
            val height = if (bottom) MATCH_PARENT else WRAP_CONTENT
            actions.addView(this, actions.indexOfChild(like), LinearLayout.LayoutParams(width, height).apply {
                gravity = Gravity.CENTER_VERTICAL
            })
        }
        count?.let { button.setTextColor(it.textColors) }
        button.compoundDrawableTintList = button.textColors
        button.row = row
        button.visibility = View.VISIBLE
        updateLabel(button, row)
        val click = View.OnClickListener {
            CommentGodAction.request(button, row, automatic = false) { updateLabel(button, row) }
        }
        button.setOnClickListener(click)
    }

    private fun updateLabel(button: SendButton, row: CommentGodAction.Row) {
        // A response can arrive after a holder has been recycled for a different row.
        if (button.row?.comment !== row.comment) return
        button.text = when (CommentGodAction.status(row)) {
            2 -> "已送神"
            3 -> "已送满"
            else -> "送神"
        }
    }

    // This view uses the host's theme, which is not guaranteed to be AppCompat.
    @SuppressLint("AppCompatCustomView")
    private class SendButton(context: Context) : TextView(context) {
        var row: CommentGodAction.Row? = null

        fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
    }

    private companion object {
        const val BUTTON_TAG = "ppx_comment_god_button"

        private val sparkle = Path().apply {
            moveTo(10f, 5.5f)
            quadTo(11.1f, 11.9f, 17.5f, 13f)
            quadTo(11.1f, 14.1f, 10f, 20.5f)
            quadTo(8.9f, 14.1f, 2.5f, 13f)
            quadTo(8.9f, 11.9f, 10f, 5.5f)
            close()
            moveTo(18.5f, 3.5f)
            lineTo(18.5f, 9.5f)
            moveTo(15.5f, 6.5f)
            lineTo(21.5f, 6.5f)
        }

        fun createIcon(size: Int): ShapeDrawable = ShapeDrawable(PathShape(sparkle, 24f, 24f)).apply {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.7f
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
            setBounds(0, 0, size, size)
        }
    }
}
