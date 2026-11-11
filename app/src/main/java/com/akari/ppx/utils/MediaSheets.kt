package com.akari.ppx.utils

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Shared chrome for the custom image and video libraries. */
internal object MediaSheets {
    class Sheet(val dialog: Dialog, val body: LinearLayout) {
        fun dismiss() = dialog.dismiss()
    }

    /** Shared bottom sheet chrome: drag handle, title row and rounded top corners. */
    fun showSheet(
        context: Context,
        title: String,
        subtitle: String? = null,
        actions: List<Pair<String, () -> Unit>> = emptyList(),
        expanded: Boolean = false
    ): Sheet {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density + 0.5f).toInt()
        val accent = 0xFFED6559.toInt()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadii = floatArrayOf(
                    dp(24).toFloat(), dp(24).toFloat(), dp(24).toFloat(), dp(24).toFloat(),
                    0f, 0f, 0f, 0f
                )
                setColor(Color.WHITE)
            }
            setPadding(dp(20), dp(8), dp(20), dp(22))
        }
        lateinit var dialog: Dialog
        // The handle is a wide touch strip so a downward drag anywhere on the
        // header closes the sheet, matching native bottom-sheet behaviour.
        val handleArea = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(28)
            )
        }
        handleArea.addView(View(context).apply {
            layoutParams = FrameLayout.LayoutParams(dp(40), dp(4), Gravity.CENTER)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(2).toFloat(); setColor(0x33000000)
            }
        })
        root.addView(handleArea)
        val titleColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(14), 0, dp(2))
        }
        titleColumn.addView(TextView(context).apply {
            text = title; textSize = 19f; setTextColor(0xFF1F1F1F.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        if (!subtitle.isNullOrBlank()) titleColumn.addView(TextView(context).apply {
            text = subtitle; textSize = 12.5f; setTextColor(0xFF9A9A9A.toInt())
            setPadding(0, dp(5), 0, 0)
        })
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(titleColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.forEach { (label, block) ->
            header.addView(TextView(context).apply {
                text = label; textSize = 13f; gravity = Gravity.CENTER
                setTextColor(accent)
                setPadding(dp(12), dp(7), dp(12), dp(7))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat(); setColor(0x14ED6559)
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { leftMargin = dp(8) }
                setOnClickListener { block() }
            })
        }
        // Quick close: an explicit X in the header, independent of drag-to-dismiss.
        header.addView(TextView(context).apply {
            text = "✕"; textSize = 15f; gravity = Gravity.CENTER
            setTextColor(0xFF9A9A9A.toInt())
            setPadding(dp(10), dp(7), dp(10), dp(7))
            contentDescription = "关闭"
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0x0F000000)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(8) }
            setOnClickListener { dialog.dismiss() }
        })
        root.addView(header)
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        root.addView(body, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            if (expanded) 0 else ViewGroup.LayoutParams.WRAP_CONTENT,
            if (expanded) 1f else 0f
        ))
        dialog = Dialog(context).apply {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            setContentView(root)
            window?.apply {
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setGravity(Gravity.BOTTOM)
                setBackgroundDrawableResource(android.R.color.transparent)
                addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setDimAmount(0.45f)
            }
        }
        var startY = 0f
        handleArea.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    startY = event.rawY
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    root.translationY = (event.rawY - startY).coerceAtLeast(0f)
                    true
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    val dragged = event.rawY - startY
                    if (dragged > dp(56)) dialog.dismiss()
                    else root.animate().translationY(0f).setDuration(160).start()
                    true
                }
                else -> false
            }
        }
        dialog.show()
        if (expanded) {
            val bounds = android.graphics.Rect()
            (context as? Activity)?.window?.decorView?.getWindowVisibleDisplayFrame(bounds)
            val available = bounds.height().takeIf { it > 0 }
                ?: context.resources.displayMetrics.heightPixels
            dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (available * 0.82f).toInt())
        }
        return Sheet(dialog, body)
    }

    /** One selectable row inside a sheet. */
    fun sheetRow(
        context: Context,
        title: String,
        description: String?,
        accent: Boolean = false,
        onClick: () -> Unit
    ): View {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density + 0.5f).toInt()
        val accentColor = 0xFFED6559.toInt()
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(13), dp(14), dp(13))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(if (accent) 0x14ED6559 else 0xFFF6F6F6.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            isClickable = true; isFocusable = true
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(context).apply {
                    text = title; textSize = 15f
                    setTextColor(if (accent) accentColor else 0xFF262626.toInt())
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
                if (!description.isNullOrBlank()) addView(TextView(context).apply {
                    text = description; textSize = 12f; setTextColor(0xFF9A9A9A.toInt())
                    setPadding(0, dp(3), 0, 0)
                })
            })
            addView(TextView(context).apply {
                text = "›"; textSize = 20f
                setTextColor(if (accent) accentColor else 0xFFC4C4C4.toInt())
                setPadding(dp(8), 0, 0, 0)
            })
            setOnClickListener { onClick() }
        }
    }

    /** Centered rounded-card dialog matching the group browser style. */
    fun cardDialog(context: Context, title: String, content: View, positive: String, onPositive: () -> Unit) {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density + 0.5f).toInt()
        val dialog = Dialog(context)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(14))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(20).toFloat(); setColor(Color.WHITE)
            }
        }
        root.addView(TextView(context).apply {
            text = title; textSize = 17f; setTextColor(0xFF1F1F1F.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(content, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, dp(14), 0, dp(6)) })
        root.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END
            fun action(label: String, filled: Boolean, block: () -> Unit) {
                addView(TextView(context).apply {
                    text = label
                    setPadding(dp(18), dp(8), dp(18), dp(8))
                    gravity = Gravity.CENTER
                    if (filled) {
                        setTextColor(Color.WHITE)
                        background = android.graphics.drawable.GradientDrawable().apply {
                            cornerRadius = dp(18).toFloat(); setColor(0xFFED6559.toInt())
                        }
                    } else {
                        setTextColor(0xFF666666.toInt())
                        background = android.graphics.drawable.GradientDrawable().apply {
                            cornerRadius = dp(18).toFloat(); setColor(0x14000000)
                        }
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { leftMargin = dp(8) }
                    setOnClickListener { block() }
                })
            }
            action("取消", false) { dialog.dismiss() }
            action(positive, true) {
                dialog.dismiss()
                onPositive()
            }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        dialog.setContentView(FrameLayout(context).apply {
            setBackgroundColor(0x66000000)
            addView(root, FrameLayout.LayoutParams(
                context.resources.displayMetrics.widthPixels - dp(48),
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER
            ))
        })
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawableResource(android.R.color.transparent)
        }
        dialog.show()
    }

}
