/*
 * VocoCraft - Premium purchase dialog
 *
 * Beautiful native Android overlay dialog promoting the full version.
 * Can be triggered after interstitial ads, from main menu, or mod install.
 * Closes only via X button — not by touching outside.
 */

package com.VocoCraft.VocoCraft

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.Keep

/**
 * Premium purchase dialog with an attractive, polished design.
 * Closes only via X button — prevents accidental dismissal.
 */
@Keep
@Suppress("unused")
object PurchasePromptDialog {

    private const val TAG = "PurchasePromptDialog"
    private var currentDialog: Dialog? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentSource: String = "after_interstitial"
    private var isUnclosable: Boolean = false

    /**
     * Show purchase prompt dialog.
     * @param activity The activity to show the dialog on
     * @param source Analytics source tag (e.g. "after_interstitial", "main_menu", "mod_install")
     */
    @JvmStatic
    @JvmOverloads
    fun show(activity: Activity, source: String = "after_interstitial") {
        if (YooKassaPay.hasPurchase()) {
            Log.d(TAG, "User already purchased, skipping prompt")
            return
        }

        currentSource = source

        mainHandler.post {
            try {
                if (activity.isFinishing || activity.isDestroyed) {
                    Log.w(TAG, "Activity not available, skipping prompt")
                    return@post
                }
                isUnclosable = false
                showDialog(activity)
            } catch (e: Exception) {
                Log.e(TAG, "Error showing purchase prompt: ${e.message}")
            }
        }
    }

    /**
     * Show UNCLOSABLE purchase dialog (trial expired).
     * No close button, no "not now" — user must purchase to continue.
     */
    @JvmStatic
    fun showUnclosable(activity: Activity) {
        if (YooKassaPay.hasPurchase()) {
            Log.d(TAG, "User already purchased, skipping unclosable prompt")
            return
        }

        currentSource = "trial_expired"

        mainHandler.post {
            try {
                if (activity.isFinishing || activity.isDestroyed) {
                    Log.w(TAG, "Activity not available, skipping prompt")
                    return@post
                }
                isUnclosable = true
                showDialog(activity)
            } catch (e: Exception) {
                Log.e(TAG, "Error showing unclosable purchase prompt: ${e.message}")
            }
        }
    }

    @JvmStatic
    fun dismiss() {
        mainHandler.post {
            try {
                currentDialog?.dismiss()
                currentDialog = null
            } catch (e: Exception) {
                Log.e(TAG, "Error dismissing dialog: ${e.message}")
            }
        }
    }

    private fun showDialog(activity: Activity) {
        currentDialog?.dismiss()

        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        // Only close via X button — not by back press or outside touch
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)

        val contentView = buildDialogLayout(activity, dialog)
        dialog.setContentView(contentView)

        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(
                (activity.resources.displayMetrics.widthPixels * 0.92).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setGravity(Gravity.CENTER)
            // Dim behind the dialog for focus
            setDimAmount(0.7f)
        }

        dialog.setOnDismissListener {
            currentDialog = null
            Log.d(TAG, "Purchase prompt dismissed")
        }

        currentDialog = dialog
        dialog.show()

        try {
            val gameActivity = activity as? GameActivity
            gameActivity?.let {
                Analytics.sendAdEvent(it, "purchase_prompt", "shown", currentSource)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending analytics: ${e.message}")
        }

        Log.d(TAG, "Purchase prompt shown (source: $currentSource)")
    }

    // ──────────────────────────────────────────────────────────────
    //  Layout builder
    // ──────────────────────────────────────────────────────────────

    private fun buildDialogLayout(activity: Activity, dialog: Dialog): View {
        val d = activity.resources.displayMetrics.density
        val unclosable = isUnclosable

        // ── Color palette ──
        val bgDark       = Color.parseColor("#0f0f23")
        val bgCard       = Color.parseColor("#1a1a3e")
        val bgFeature    = Color.parseColor("#212150")
        val gold         = Color.parseColor("#FFD54F")
        val goldDark     = Color.parseColor("#FFC107")
        val white        = Color.parseColor("#FFFFFF")
        val textSoft     = Color.parseColor("#C5C5E0")
        val textMuted    = Color.parseColor("#8888AA")
        val gradStart    = Color.parseColor("#7C4DFF")
        val gradEnd      = Color.parseColor("#E040FB")
        val greenBright  = Color.parseColor("#69F0AE")
        val cyanAccent   = Color.parseColor("#40C4FF")

        // ── Root — deep dark with rounded corners ──
        val rootBg = GradientDrawable().apply {
            colors = intArrayOf(Color.parseColor("#12122a"), Color.parseColor("#0a0a1e"))
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
            cornerRadius = 24 * d
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = rootBg
            setPadding((18 * d).toInt(), (14 * d).toInt(), (18 * d).toInt(), (18 * d).toInt())
        }

        // ── Scrollable wrapper (for small screens) ──
        val scroll = ScrollView(activity).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            isVerticalScrollBarEnabled = false
        }

        val innerColumn = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // ═══════════════════════════════════════════
        //  CLOSE BUTTON  (top-right) — hidden when unclosable
        // ═══════════════════════════════════════════
        if (!unclosable) {
            val closeFrame = FrameLayout(activity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            val closeBtnBg = GradientDrawable().apply {
                setColor(Color.parseColor("#2a2a4a"))
                cornerRadius = 20 * d
            }

            val closeBtn = TextView(activity).apply {
                text = "✕"
                setTextColor(Color.parseColor("#9999BB"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                background = closeBtnBg
                val size = (32 * d).toInt()
                val lp = FrameLayout.LayoutParams(size, size).apply {
                    gravity = Gravity.END
                }
                layoutParams = lp
                setOnClickListener { dialog.dismiss() }
            }
            closeFrame.addView(closeBtn)
            innerColumn.addView(closeFrame)
        }

        // ═══════════════════════════════════════════
        //  HEADER — title + subtitle
        // ═══════════════════════════════════════════
        val headerContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (2 * d).toInt()
                bottomMargin = (10 * d).toInt()
            }
        }

        // "VocoCraft — Полный доступ"
        val titleText = TextView(activity).apply {
            text = "VocoCraft — Полный доступ"
            setTextColor(gold)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            // Subtle gold shadow
            setShadowLayer(8f, 0f, 0f, Color.parseColor("#80FFD54F"))
        }
        headerContainer.addView(titleText)

        // Subtitle
        val subtitleText = TextView(activity).apply {
            text = if (unclosable)
                "Бесплатное время закончилось.\nОплатите, чтобы продолжить играть"
            else
                "Разблокируй лучший игровой опыт"
            setTextColor(if (unclosable) Color.parseColor("#FF8A80") else textSoft)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4 * d).toInt() }
        }
        headerContainer.addView(subtitleText)
        innerColumn.addView(headerContainer)

        // ═══════════════════════════════════════════
        //  FEATURES — compact tile grid (3 × 2)
        // ═══════════════════════════════════════════

        data class Tile(val icon: String, val label: String, val accent: Int)

     val tiles = listOf(
             Tile("📺", "Игра\nна весь экран", Color.parseColor("#FF5252")),
             Tile("🔕", "Без\nрекламы", Color.parseColor("#FF9800")),
             Tile("🧩", "1000+ модов\nи карт", cyanAccent),
             Tile("📶", "Играй\nбез интернета", greenBright),
             Tile("🛡️", "Без\nограничений", Color.parseColor("#FFEB3B")),
             Tile("🔓", "Все функции\nоткрыты", Color.parseColor("#B388FF"))
        )

        val tileGap = (6 * d).toInt()

        // Wrap tiles in a centered container to limit width
        val tilesContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            // Horizontal inset to make tiles narrower
            setPadding((16 * d).toInt(), 0, (16 * d).toInt(), 0)
        }

        for (rowStart in intArrayOf(0, 3)) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = if (rowStart == 0) tileGap else (12 * d).toInt()
                }
            }

            for (i in rowStart until rowStart + 3) {
                val tile = tiles[i]

                // Tile background: subtle accent gradient top → dark
                val tileBg = GradientDrawable().apply {
                    colors = intArrayOf(
                        Color.argb(25, Color.red(tile.accent), Color.green(tile.accent), Color.blue(tile.accent)),
                        bgFeature
                    )
                    orientation = GradientDrawable.Orientation.TOP_BOTTOM
                    cornerRadius = 12 * d
                    setStroke(
                        (1 * d).toInt(),
                        Color.argb(70, Color.red(tile.accent), Color.green(tile.accent), Color.blue(tile.accent))
                    )
                }

                val tileView = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    background = tileBg
                    setPadding((4 * d).toInt(), (6 * d).toInt(), (4 * d).toInt(), (6 * d).toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                    ).apply {
                        if (i < rowStart + 2) rightMargin = tileGap
                    }
                    // Make tile square: set height = width after layout
                    viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                        override fun onGlobalLayout() {
                            viewTreeObserver.removeOnGlobalLayoutListener(this)
                            val lp = layoutParams
                            lp.height = measuredWidth
                            layoutParams = lp
                        }
                    })
                }

                val iconView = TextView(activity).apply {
                    text = tile.icon
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = (2 * d).toInt() }
                }
                tileView.addView(iconView)

                val labelView = TextView(activity).apply {
                    text = tile.label
                    setTextColor(white)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setTypeface(null, Typeface.BOLD)
                    gravity = Gravity.CENTER
                    maxLines = 2
                    setLineSpacing(1 * d, 1f)
                }
                tileView.addView(labelView)

                row.addView(tileView)
            }

            tilesContainer.addView(row)
        }

        innerColumn.addView(tilesContainer)

        // ═══════════════════════════════════════════
        //  PRICE BADGE
        // ═══════════════════════════════════════════
        val priceText = getFormattedPrice()

        val priceBadgeContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (12 * d).toInt() }
        }

        val priceBadgeBg = GradientDrawable().apply {
            setColor(Color.parseColor("#1e1e42"))
            cornerRadius = 12 * d
            setStroke((1 * d).toInt(), goldDark)
        }

        val priceBadge = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = priceBadgeBg
            setPadding(
                (20 * d).toInt(), (12 * d).toInt(),
                (20 * d).toInt(), (12 * d).toInt()
            )
        }

        val priceLabel = TextView(activity).apply {
            text = "Всего "
            setTextColor(textSoft)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }
        priceBadge.addView(priceLabel)

        val priceValue = TextView(activity).apply {
            text = priceText
            setTextColor(gold)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTypeface(null, Typeface.BOLD)
            setShadowLayer(4f, 0f, 0f, Color.parseColor("#80FFD54F"))
        }
        priceBadge.addView(priceValue)

        val priceOnce = TextView(activity).apply {
            text = "  навсегда"
            setTextColor(textSoft)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }
        priceBadge.addView(priceOnce)

        priceBadgeContainer.addView(priceBadge)
        innerColumn.addView(priceBadgeContainer)

        // ═══════════════════════════════════════════
        //  BUY BUTTON — gradient purple→pink, no stars
        // ═══════════════════════════════════════════
        val buyBtnBg = GradientDrawable().apply {
            colors = intArrayOf(gradStart, gradEnd)
            orientation = GradientDrawable.Orientation.LEFT_RIGHT
            cornerRadius = 14 * d
        }

        val buyButton = TextView(activity).apply {
            text = "Получить полный доступ"
            setTextColor(white)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = buyBtnBg
            setPadding(
                (20 * d).toInt(), (18 * d).toInt(),
                (20 * d).toInt(), (18 * d).toInt()
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            // Glow shadow
            setShadowLayer(12f, 0f, 4f, Color.parseColor("#667C4DFF"))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                Log.d(TAG, "Buy button clicked")
                try {
                    val gameActivity = activity as? GameActivity
                    gameActivity?.let {
                        Analytics.sendAdEvent(it, "purchase_prompt", "buy_clicked", currentSource)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending analytics: ${e.message}")
                }
                startPurchase(activity)
                // In unclosable mode, don't dismiss — dialog stays until purchase completes
                // User will return to the dialog if they cancel payment
                if (!unclosable) {
                    dialog.dismiss()
                }
            }
        }
        innerColumn.addView(buyButton)

        // ═══════════════════════════════════════════
        //  Unclosable mode: reassuring message
        // ═══════════════════════════════════════════
        if (unclosable) {
            // In unclosable mode, show a reassuring message instead
            val keepWorldText = TextView(activity).apply {
                text = "\uD83C\uDFE0 Твой мир и постройки сохранены и ждут тебя!"
                setTextColor(Color.parseColor("#69F0AE"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (14 * d).toInt() }
            }
            innerColumn.addView(keepWorldText)
        }

        scroll.addView(innerColumn)
        root.addView(scroll)
        return root
    }

    private fun getFormattedPrice(): String {
        return try {
            val price = YooKassaPay.getProductPrice()
            if (price.isNotEmpty()) price else "249 ₽"
        } catch (e: Exception) {
            "249 ₽"
        }
    }

    private fun startPurchase(activity: Activity) {
        try {
            val amount = YooKassaPay.getProductAmount().ifEmpty { "249" }
            val currency = YooKassaPay.getProductCurrency().ifEmpty { "RUB" }

            YooKassaPay.clearOperationResult()
            YooKassaPay.startPurchase(
                amount,
                currency,
                "VocoCraft Полная версия",
                "Разблокировка всех функций без рекламы"
            )
            Log.d(TAG, "Purchase started from prompt")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting purchase: ${e.message}")
        }
    }
}
