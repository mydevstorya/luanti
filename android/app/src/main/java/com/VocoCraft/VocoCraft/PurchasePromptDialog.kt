/*
 * VocoCraft - Premium purchase dialog
 *
 * Beautiful native Android overlay dialog promoting the full version.
 * Can be triggered after interstitial ads, from main menu, or mod install.
 * Closes only via X button — not by touching outside.
 */

package com.VocoCraft.VocoCraft

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
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
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.animation.LinearInterpolator
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
    private var countdownRunnable: Runnable? = null

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
                showDialog(activity)
            } catch (e: Exception) {
                Log.e(TAG, "Error showing purchase prompt: ${e.message}")
            }
        }
    }

    @JvmStatic
    fun dismiss() {
        mainHandler.post {
            try {
                stopCountdownTimer()
                currentDialog?.dismiss()
                currentDialog = null
            } catch (e: Exception) {
                Log.e(TAG, "Error dismissing dialog: ${e.message}")
            }
        }
    }

    private fun stopCountdownTimer() {
        countdownRunnable?.let { mainHandler.removeCallbacks(it) }
        countdownRunnable = null
    }

    private fun showDialog(activity: Activity) {
        stopCountdownTimer()
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
            val maxH = (activity.resources.displayMetrics.heightPixels * 0.95).toInt()
            setLayout(
                (activity.resources.displayMetrics.widthPixels * 0.92).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            decorView.post {
                if (decorView.height > maxH) {
                    setLayout(
                        (activity.resources.displayMetrics.widthPixels * 0.92).toInt(),
                        maxH
                    )
                }
            }
            setGravity(Gravity.CENTER)
            // Dim behind the dialog for focus
            setDimAmount(0.7f)
        }

        dialog.setOnDismissListener {
            stopCountdownTimer()
            currentDialog = null
            sendPurchaseWindowAnalytics(activity, "dismissed")
            Log.d(TAG, "Purchase prompt dismissed")
        }

        currentDialog = dialog
        dialog.show()

        sendPurchaseWindowAnalytics(activity, "shown")

        Log.d(TAG, "Purchase prompt shown (source: $currentSource)")
    }

    // ──────────────────────────────────────────────────────────────
    //  Layout builder
    // ──────────────────────────────────────────────────────────────

    private fun buildDialogLayout(activity: Activity, dialog: Dialog): View {
        val d = activity.resources.displayMetrics.density

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
            setPadding((14 * d).toInt(), (10 * d).toInt(), (14 * d).toInt(), (12 * d).toInt())
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
        //  HEADER ROW — title (center) + close button (top-right)
        // ═══════════════════════════════════════════
        val headerRow = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // "VocoCraft — Полный доступ"
        val titleText = TextView(activity).apply {
            text = "VocoCraft — Полный доступ"
            setTextColor(gold)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setShadowLayer(8f, 0f, 0f, Color.parseColor("#80FFD54F"))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER }
            // Pad right so title doesn't overlap close button
            setPadding((36 * d).toInt(), 0, (36 * d).toInt(), 0)
        }
        headerRow.addView(titleText)

        // Close button (top-right corner)
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
            layoutParams = FrameLayout.LayoutParams(size, size).apply {
                gravity = Gravity.END or Gravity.TOP
            }
            setOnClickListener { dialog.dismiss() }
        }
        headerRow.addView(closeBtn)

        innerColumn.addView(headerRow)

        // Subtitle
        val subtitleText = TextView(activity).apply {
            text = "Разблокируй лучший игровой опыт"
            setTextColor(textSoft)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (2 * d).toInt()
                bottomMargin = (10 * d).toInt()
            }
        }
        innerColumn.addView(subtitleText)

        // ═══════════════════════════════════════════
        //  FEATURES — 3 per row, emoji left + text right inside each tile
        // ═══════════════════════════════════════════

        data class Tile(val icon: String, val label: String, val accent: Int)

        val tiles = listOf(
            Tile("📺", "Игра\nна весь экран", Color.parseColor("#FF5252")),
            Tile("🔕", "Без\nрекламы", Color.parseColor("#FF9800")),
            Tile("🧩", "1000+\nмодов и карт", cyanAccent),
            Tile("📶", "Играй\nбез интернета", greenBright),
            Tile("🛡️", "Без\nограничений", Color.parseColor("#FFEB3B")),
            Tile("🔓", "Все функции\nоткрыты", Color.parseColor("#B388FF"))
        )

        val tileGap = (4 * d).toInt()

        // Container for feature rows — inset sides to make tiles compact
        val screenW = activity.resources.displayMetrics.widthPixels
        val tilesInset = (screenW * 0.12f).toInt()
        val tilesContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(tilesInset, 0, tilesInset, 0)
        }

        // Build 2 rows, 3 tiles per row
        for (rowStart in intArrayOf(0, 3)) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = if (rowStart == 0) tileGap else (8 * d).toInt()
                }
            }

            for (i in rowStart until rowStart + 3) {
                val tile = tiles[i]

                val tileBg = GradientDrawable().apply {
                    colors = intArrayOf(
                        Color.argb(50, Color.red(tile.accent), Color.green(tile.accent), Color.blue(tile.accent)),
                        Color.argb(12, Color.red(tile.accent), Color.green(tile.accent), Color.blue(tile.accent))
                    )
                    orientation = GradientDrawable.Orientation.TOP_BOTTOM
                    cornerRadius = 10 * d
                    setStroke(
                        (1 * d).toInt(),
                        Color.argb(100, Color.red(tile.accent), Color.green(tile.accent), Color.blue(tile.accent))
                    )
                }

                // Tile: horizontal layout — big emoji left, 2-line text right
                val tileView = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = tileBg
                    setPadding((4 * d).toInt(), (8 * d).toInt(), (4 * d).toInt(), (8 * d).toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                    ).apply {
                        if (i < rowStart + 2) rightMargin = tileGap
                    }
                }

                val iconView = TextView(activity).apply {
                    text = tile.icon
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { rightMargin = (2 * d).toInt() }
                }
                tileView.addView(iconView)

                val labelView = TextView(activity).apply {
                    text = tile.label
                    setTextColor(Color.parseColor("#F0F0FF"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setTypeface(null, Typeface.BOLD)
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    maxLines = 2
                    setLineSpacing(1 * d, 1f)
                    layoutParams = LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                    )
                }
                tileView.addView(labelView)

                row.addView(tileView)
            }

            tilesContainer.addView(row)
        }

        innerColumn.addView(tilesContainer)

        // ═══════════════════════════════════════════
        //  SPECIAL OFFER COUNTDOWN (if active)
        // ═══════════════════════════════════════════
        val isSpecialOffer = YooKassaPay.isSpecialOfferAvailable()
        var countdownTextView: TextView? = null

        if (isSpecialOffer) {
            // "Успей приобрести!" header
            val offerHeader = TextView(activity).apply {
                text = "\uD83D\uDD25 Успей приобрести!"
                setTextColor(Color.parseColor("#FF6D00"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (4 * d).toInt() }
            }
            innerColumn.addView(offerHeader)

            // Countdown timer text
            countdownTextView = TextView(activity).apply {
                setTextColor(Color.parseColor("#FFAB40"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (10 * d).toInt() }
            }
            innerColumn.addView(countdownTextView)
        }

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
            setStroke((1 * d).toInt(), if (isSpecialOffer) Color.parseColor("#FF6D00") else goldDark)
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

        // If special offer AND special price fetched, show old price struck through + new price
        val hasSpecialPrice = isSpecialOffer && YooKassaPay.isSpecialOfferPriceFetched()

        if (hasSpecialPrice) {
            val regularPrice = getRegularPrice()
            val oldPriceView = TextView(activity).apply {
                val span = SpannableString(regularPrice)
                span.setSpan(StrikethroughSpan(), 0, regularPrice.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                text = span
                setTextColor(Color.parseColor("#80FFFFFF"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setPadding(0, 0, (8 * d).toInt(), 0)
            }
            priceBadge.addView(oldPriceView)
        } else {
            // "Всего за" label before price
            val priceLabel = TextView(activity).apply {
                text = "Всего за "
                setTextColor(textSoft)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            }
            priceBadge.addView(priceLabel)
        }

        val priceValue = TextView(activity).apply {
            text = priceText
            setTextColor(gold)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTypeface(null, Typeface.BOLD)
            setShadowLayer(4f, 0f, 0f, Color.parseColor("#80FFD54F"))
        }
        priceBadge.addView(priceValue)

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

        // Wrap buy button in FrameLayout for shimmer overlay
        val buyButtonFrame = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val buyButton = TextView(activity).apply {
            text = "Получить полный доступ"
            setTextColor(white)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = buyBtnBg
            setPadding(
                (20 * d).toInt(), (14 * d).toInt(),
                (20 * d).toInt(), (14 * d).toInt()
            )
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            // Glow shadow
            setShadowLayer(12f, 0f, 4f, Color.parseColor("#667C4DFF"))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                Log.d(TAG, "Buy button clicked")
                sendPurchaseWindowAnalytics(activity, "buy_clicked")
                startPurchase(activity)
                dialog.dismiss()
            }
        }
        buyButtonFrame.addView(buyButton)

        // Shimmer overlay: sweeping white highlight
        val density = d
        val shimmerView = ShimmerView(activity, density)
        shimmerView.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        shimmerView.isClickable = false
        shimmerView.isFocusable = false
        buyButtonFrame.addView(shimmerView)

        // Animate shimmer sweep
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2200
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            startDelay = 800
            addUpdateListener { anim ->
                shimmerView.shimmerOffset = anim.animatedValue as Float
                shimmerView.invalidate()
            }
        }
        animator.start()

        innerColumn.addView(buyButtonFrame)

        scroll.addView(innerColumn)
        root.addView(scroll)

        // Start countdown timer if special offer active
        if (isSpecialOffer && countdownTextView != null) {
            startCountdownTimer(countdownTextView)
        }

        return root
    }

    private fun startCountdownTimer(timerView: TextView) {
        stopCountdownTimer()
        val runnable = object : Runnable {
            override fun run() {
                val remaining = YooKassaPay.getSpecialOfferRemainingSeconds()
                if (remaining <= 0) {
                    timerView.text = "00:00:00"
                    return
                }
                val h = remaining / 3600
                val m = (remaining % 3600) / 60
                val s = remaining % 60
                timerView.text = String.format("%02d:%02d:%02d", h, m, s)
                mainHandler.postDelayed(this, 1000)
            }
        }
        countdownRunnable = runnable
        runnable.run()
    }

    /**
     * Get the price to display: special offer price if active, else regular.
     */
    private fun getFormattedPrice(): String {
        return try {
            // Use special offer price only if within window AND price fetched from backend
            if (YooKassaPay.isSpecialOfferAvailable() && YooKassaPay.isSpecialOfferPriceFetched()) {
                val price = YooKassaPay.getSpecialOfferPrice()
                if (price.isNotEmpty()) return price
            }
            val price = YooKassaPay.getProductPrice()
            if (price.isNotEmpty()) price else "249 ₽"
        } catch (e: Exception) {
            "249 ₽"
        }
    }

    /**
     * Get the regular (non-discounted) price for strikethrough display.
     */
    private fun getRegularPrice(): String {
        return try {
            val price = YooKassaPay.getProductPrice()
            if (price.isNotEmpty()) price else "249 ₽"
        } catch (e: Exception) {
            "249 ₽"
        }
    }

    private fun startPurchase(activity: Activity) {
        try {
            val isSpecial = YooKassaPay.isSpecialOfferAvailable() && YooKassaPay.isSpecialOfferPriceFetched()
            val amount: String
            val currency: String

            if (isSpecial) {
                amount = YooKassaPay.getSpecialOfferAmount().ifEmpty { YooKassaPay.getProductAmount().ifEmpty { "249" } }
                currency = YooKassaPay.getSpecialOfferCurrency().ifEmpty { "RUB" }
            } else {
                amount = YooKassaPay.getProductAmount().ifEmpty { "249" }
                currency = YooKassaPay.getProductCurrency().ifEmpty { "RUB" }
            }

            YooKassaPay.clearOperationResult()
            YooKassaPay.startPurchase(
                amount,
                currency,
                "VocoCraft Полная версия",
                "Разблокировка всех функций без рекламы"
            )
            Log.d(TAG, "Purchase started from prompt (special=$isSpecial, amount=$amount)")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting purchase: ${e.message}")
        }
    }

    /**
     * Send structured analytics event for purchase window.
     * Event name: "purchase_window"
     * Params: action, source, offer_type, price, currency
     */
    private fun sendPurchaseWindowAnalytics(activity: Activity, action: String) {
        try {
            val isSpecial = YooKassaPay.isSpecialOfferAvailable()
            val offerType = if (isSpecial) "special_offer" else "regular"
            val price = if (isSpecial) {
                YooKassaPay.getSpecialOfferAmount().ifEmpty { YooKassaPay.getProductAmount() }
            } else {
                YooKassaPay.getProductAmount()
            }
            val currency = if (isSpecial) {
                YooKassaPay.getSpecialOfferCurrency()
            } else {
                YooKassaPay.getProductCurrency()
            }

            val params = org.json.JSONObject().apply {
                put("action", action)
                put("source", currentSource)
                put("offer_type", offerType)
                put("price", price)
                put("currency", currency)
            }

            Analytics.sendEventWithParams("purchase_window", params.toString())
            Log.d(TAG, "Analytics: purchase_window/$action ($offerType, source=$currentSource)")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending purchase_window analytics: ${e.message}")
        }
    }
}

/**
 * Custom View that draws a sweeping shimmer highlight across a rounded rect.
 */
private class ShimmerView(context: android.content.Context, private val density: Float) : View(context) {
    private val shimmerPaint = Paint()
    var shimmerOffset = -1f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val sw = width * 0.4f
        val x = shimmerOffset * (width + sw) - sw
        shimmerPaint.shader = LinearGradient(
            x, 0f, x + sw, 0f,
            intArrayOf(
                Color.TRANSPARENT,
                Color.argb(55, 255, 255, 255),
                Color.argb(85, 255, 255, 255),
                Color.argb(55, 255, 255, 255),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.3f, 0.5f, 0.7f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(
            0f, 0f, width.toFloat(), height.toFloat(),
            14 * density, 14 * density, shimmerPaint
        )
    }
}
