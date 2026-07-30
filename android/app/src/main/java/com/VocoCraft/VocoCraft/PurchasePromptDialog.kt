/*
 * VocoCraft - Premium purchase dialog
 *
 * Beautiful native Android overlay dialog promoting the full version.
 * Can be triggered after interstitial ads, from main menu, or mod install.
 * Closes only via X button — not by touching outside.
 */

package com.VocoCraft.VocoCraft

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
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
import android.text.style.StrikethroughSpan
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.Keep
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

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
    private var shimmerAnimator: ValueAnimator? = null

    private const val SKIN_WIDTH = 1870f
    private const val SKIN_HEIGHT = 841f

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
                stopVisualAnimations()
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

    private fun stopVisualAnimations() {
        shimmerAnimator?.cancel()
        shimmerAnimator = null
    }

    private fun showDialog(activity: Activity) {
        stopCountdownTimer()
        stopVisualAnimations()
        currentDialog?.dismiss()

        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        // Only close via X button — not by back press or outside touch
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)

        val contentView = buildDialogLayoutV2(activity, dialog)
        dialog.setContentView(contentView)

        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            decorView.setPadding(0, 0, 0, 0)
            setGravity(Gravity.CENTER)
            setDimAmount(0.82f)
        }

        dialog.setOnDismissListener {
            stopCountdownTimer()
            stopVisualAnimations()
            currentDialog = null
            RewardOverlayManager.onPurchaseDialogDismissed()
            sendPurchaseWindowAnalytics(activity, "dismissed")
            Log.d(TAG, "Purchase prompt dismissed")
        }

        currentDialog = dialog
        dialog.show()
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        sendPurchaseWindowAnalytics(activity, "shown")

        Log.d(TAG, "Purchase prompt shown (source: $currentSource)")
    }

    // ──────────────────────────────────────────────────────────────
    //  Layout builder
    // ──────────────────────────────────────────────────────────────

    private fun buildDialogLayoutV2(activity: Activity, dialog: Dialog): View {
        val metrics = activity.resources.displayMetrics
        val skinAspect = SKIN_WIDTH / SKIN_HEIGHT
        val canvasWidth = min(
            metrics.widthPixels,
            (metrics.heightPixels * skinAspect).roundToInt()
        )
        val canvasHeight = (canvasWidth / skinAspect).roundToInt()
        val scale = canvasWidth / SKIN_WIDTH
        val isSpecialOffer = YooKassaPay.isSpecialOfferAvailable()

        val root = FrameLayout(activity).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            isFocusable = true
        }

        val stage = FrameLayout(activity).apply {
            clipChildren = false
            clipToPadding = false
            pivotX = canvasWidth / 2f
            pivotY = canvasHeight / 2f
        }
        root.addView(
            stage,
            FrameLayout.LayoutParams(canvasWidth, canvasHeight, Gravity.CENTER)
        )

        val skin = ImageView(activity).apply {
            setImageResource(
                if (isSpecialOffer) {
                    R.drawable.vococraft_purchase_skin_v2
                } else {
                    R.drawable.vococraft_purchase_skin_regular_v2
                }
            )
            scaleType = ImageView.ScaleType.FIT_XY
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        stage.addView(
            skin,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        // The generated skin already contains the complete scene and controls.
        // This lightweight layer makes the dust and portal atmosphere feel alive.
        stage.addView(
            MagicParticleView(activity, scale),
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        fun params(x: Float, y: Float, width: Float, height: Float) =
            FrameLayout.LayoutParams(
                (width * scale).roundToInt(),
                (height * scale).roundToInt()
            ).apply {
                leftMargin = (x * scale).roundToInt()
                topMargin = (y * scale).roundToInt()
            }

        val animatedTexts = mutableListOf<TextView>()

        fun addText(
            value: String,
            x: Float,
            y: Float,
            width: Float,
            height: Float,
            baseSizePx: Float,
            color: Int,
            bold: Boolean = false,
            maxLines: Int = 1,
            gradient: IntArray? = null,
            shadowColor: Int = Color.TRANSPARENT,
            shadowRadius: Float = 0f
        ): TextView {
            val view = if (gradient != null) {
                GradientTextView(activity, gradient)
            } else {
                TextView(activity)
            }.apply {
                text = value
                setTextColor(color)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, baseSizePx * scale)
                gravity = Gravity.CENTER
                includeFontPadding = false
                setLines(maxLines)
                this.maxLines = maxLines
                if (bold) setTypeface(typeface, Typeface.BOLD)
                if (shadowRadius > 0f) {
                    setShadowLayer(shadowRadius * scale, 0f, 2f * scale, shadowColor)
                }
                letterSpacing = if (baseSizePx >= 35f) 0.015f else 0f
                layoutParams = params(x, y, width, height)
            }
            stage.addView(view)
            animatedTexts.add(view)
            return view
        }

        val title = addText(
            "VocoCraft — Полный доступ",
            510f, 102f, 940f, 82f,
            48f, Color.WHITE, bold = true,
            gradient = intArrayOf(
                Color.parseColor("#FFFFD76A"),
                Color.parseColor("#FFFFFFFF"),
                Color.parseColor("#FFBEBBFF")
            ),
            shadowColor = Color.parseColor("#99275EFF"),
            shadowRadius = 8f
        )
        addText(
            "Открой весь мир без ограничений",
            676f, 181f, 760f, 50f,
            27f, Color.parseColor("#FFD9DCEB")
        )

        val featureColor = Color.parseColor("#FFF5F7FF")
        val featureShadow = Color.parseColor("#CC050814")
        addText("На весь экран", 742f, 361f, 266f, 58f, 22f, featureColor,
            bold = true, shadowColor = featureShadow, shadowRadius = 3f)
        addText("Без принудительной\nрекламы", 1025f, 354f, 272f, 72f, 18f, featureColor,
            bold = true, maxLines = 2, shadowColor = featureShadow, shadowRadius = 3f)
        addText("1000+ модов и карт", 1314f, 355f, 270f, 68f, 21f, featureColor,
            bold = true, maxLines = 2, shadowColor = featureShadow, shadowRadius = 3f)
        addText("Игра без интернета", 742f, 552f, 266f, 66f, 21f, featureColor,
            bold = true, maxLines = 2, shadowColor = featureShadow, shadowRadius = 3f)
        addText("Без ограничений", 1025f, 552f, 272f, 66f, 21f, featureColor,
            bold = true, maxLines = 2, shadowColor = featureShadow, shadowRadius = 3f)
        addText("Все функции открыты", 1314f, 552f, 270f, 66f, 20f, featureColor,
            bold = true, maxLines = 2, shadowColor = featureShadow, shadowRadius = 3f)

        val offerLabel = if (isSpecialOffer) {
            addText(
                "Спецпредложение",
                252f, 651f, 382f, 43f,
                23f, Color.parseColor("#FFD7D3ED")
            )
        } else {
            null
        }
        val countdownTextView = if (isSpecialOffer) {
            addText(
                "",
                247f, 688f, 392f, 75f,
                48f, Color.WHITE, bold = true,
                gradient = intArrayOf(
                    Color.parseColor("#FFF5C7FF"),
                    Color.parseColor("#FFC06CFF"),
                    Color.parseColor("#FF8E75FF")
                ),
                shadowColor = Color.parseColor("#AA721CFF"),
                shadowRadius = 9f
            )
        } else {
            null
        }

        val priceX = if (isSpecialOffer) 640f else 248f
        val priceWidth = if (isSpecialOffer) 338f else 730f

        addText(
            "Навсегда за",
            priceX + 6f, 650f, priceWidth - 12f, 44f,
            23f, Color.parseColor("#FFFFD86B"), bold = true
        )
        val priceValue = addText(
            getFormattedPrice(),
            priceX, 686f, priceWidth, 82f,
            58f, Color.WHITE, bold = true,
            gradient = intArrayOf(
                Color.parseColor("#FFFFF2A7"),
                Color.parseColor("#FFFFC24F"),
                Color.parseColor("#FFFF8A35")
            ),
            shadowColor = Color.parseColor("#CCFF9A1A"),
            shadowRadius = 10f
        )

        val buyText = addText(
            "Получить полный доступ",
            994f, 656f, 638f, 110f,
            38f, Color.WHITE, bold = true,
            shadowColor = Color.parseColor("#CC3420A8"),
            shadowRadius = 8f
        )

        // Independent glossy sweeps over the already rendered cards, price and CTA.
        val shimmerViews = mutableListOf<ShimmerView>()
        fun addShimmer(
            x: Float,
            y: Float,
            width: Float,
            height: Float,
            alpha: Float
        ) {
            val shimmer = ShimmerView(activity, scale).apply {
                this.alpha = alpha
                isClickable = false
                isFocusable = false
            }
            stage.addView(shimmer, params(x, y, width, height))
            shimmerViews.add(shimmer)
        }
        addShimmer(738f, 238f, 274f, 186f, 0.18f)
        addShimmer(1020f, 238f, 282f, 186f, 0.18f)
        addShimmer(1310f, 238f, 276f, 186f, 0.18f)
        addShimmer(738f, 435f, 274f, 185f, 0.18f)
        addShimmer(1020f, 435f, 282f, 185f, 0.18f)
        addShimmer(1310f, 435f, 276f, 185f, 0.18f)
        addShimmer(
            if (isSpecialOffer) 637f else 240f,
            642f,
            if (isSpecialOffer) 342f else 740f,
            146f,
            0.42f
        )
        addShimmer(984f, 642f, 660f, 146f, 0.62f)

        shimmerAnimator?.cancel()
        shimmerAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3_400L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            startDelay = 650L
            addUpdateListener { animator ->
                val fraction = animator.animatedValue as Float
                shimmerViews.forEachIndexed { index, shimmer ->
                    shimmer.shimmerOffset = (fraction + index * 0.13f) % 1f
                    shimmer.invalidate()
                }
                val pricePulse = 0.96f + 0.04f *
                    kotlin.math.sin(fraction * Math.PI * 2.0).toFloat()
                priceValue.scaleX = pricePulse
                priceValue.scaleY = pricePulse
            }
            start()
        }

        // Rare, short attention motion: calm most of the time, noticeable once per cycle.
        if (offerLabel != null && countdownTextView != null) {
            val attentionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 9_000L
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { animator ->
                    val fraction = animator.animatedFraction
                    val active = if (fraction < 0.105f) fraction / 0.105f else 1f
                    val envelope = if (fraction < 0.105f) 1f - active else 0f
                    val wiggle = kotlin.math.sin(active * Math.PI * 8.0).toFloat() *
                        6f * scale * envelope
                    offerLabel.translationX = wiggle
                    countdownTextView.translationX = wiggle
                }
            }
            attentionAnimator.start()
            stage.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit
                override fun onViewDetachedFromWindow(v: View) {
                    attentionAnimator.cancel()
                }
            })
        }

        var dismissing = false
        fun dismissAnimated(after: (() -> Unit)? = null) {
            if (dismissing) return
            dismissing = true
            stage.animate()
                .alpha(0f)
                .scaleX(0.965f)
                .scaleY(0.965f)
                .translationY(14f * scale)
                .setDuration(190L)
                .withEndAction {
                    dialog.dismiss()
                    after?.invoke()
                }
                .start()
        }

        fun addPressTarget(
            x: Float,
            y: Float,
            width: Float,
            height: Float,
            radius: Float,
            description: String,
            onClick: () -> Unit
        ) {
            val hit = FrameLayout(activity).apply {
                isClickable = true
                isFocusable = true
                contentDescription = description
            }
            val pressed = View(activity).apply {
                alpha = 0f
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#38FFFFFF"))
                    cornerRadius = radius * scale
                }
            }
            hit.addView(
                pressed,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            hit.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> pressed.animate().alpha(1f).setDuration(70L).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        pressed.animate().alpha(0f).setDuration(150L).start()
                }
                false
            }
            hit.setOnClickListener { onClick() }
            stage.addView(hit, params(x, y, width, height))
        }

        addPressTarget(
            1646f, 34f, 104f, 92f, 22f,
            "Закрыть окно покупки"
        ) {
            sendPurchaseWindowAnalytics(activity, "close_clicked")
            dismissAnimated()
        }
        addPressTarget(
            982f, 638f, 668f, 156f, 24f,
            "Получить полный доступ"
        ) {
            Log.d(TAG, "Buy button clicked")
            sendPurchaseWindowAnalytics(activity, "buy_clicked")
            dismissAnimated {
                startPurchase(activity)
            }
        }

        if (isSpecialOffer) {
            countdownTextView?.let { startCountdownTimer(it) }
        }

        // Entrance: cinematic scale/fade, then staggered native copy.
        stage.alpha = 0f
        stage.scaleX = 0.925f
        stage.scaleY = 0.925f
        stage.translationY = 24f * scale
        animatedTexts.forEach {
            it.alpha = 0f
            it.translationY = 8f * scale
        }
        stage.post {
            AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(stage, View.ALPHA, 0f, 1f),
                    ObjectAnimator.ofFloat(stage, View.SCALE_X, 0.925f, 1f),
                    ObjectAnimator.ofFloat(stage, View.SCALE_Y, 0.925f, 1f),
                    ObjectAnimator.ofFloat(stage, View.TRANSLATION_Y, 24f * scale, 0f)
                )
                duration = 560L
                interpolator = OvershootInterpolator(0.68f)
                start()
            }
            animatedTexts.forEachIndexed { index, textView ->
                textView.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay(120L + index * 24L)
                    .setDuration(330L)
                    .start()
            }
            title.animate()
                .scaleX(1.018f)
                .scaleY(1.018f)
                .setStartDelay(620L)
                .setDuration(700L)
                .withEndAction {
                    title.animate().scaleX(1f).scaleY(1f).setDuration(500L).start()
                }
                .start()
            buyText.animate()
                .scaleX(1.025f)
                .scaleY(1.025f)
                .setStartDelay(1_050L)
                .setDuration(420L)
                .withEndAction {
                    buyText.animate().scaleX(1f).scaleY(1f).setDuration(360L).start()
                }
                .start()
        }

        return root
    }

    @Suppress("unused")
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
            Tile("🔕", "Без принудительной\nрекламы", Color.parseColor("#FF9800")),
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
                "Разблокировка всех функций без принудительной рекламы"
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

/**
 * Native copy stays sharp at every resolution while inheriting the generated
 * skin's gold-to-lavender and gold-to-orange material treatment.
 */
private class GradientTextView(
    context: android.content.Context,
    private val gradientColors: IntArray
) : TextView(context) {
    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (width <= 0) return
        paint.shader = LinearGradient(
            0f, 0f, width.toFloat(), 0f,
            gradientColors, null, Shader.TileMode.CLAMP
        )
        invalidate()
    }
}

/**
 * Subtle animated dust and mineral motes. The effect is intentionally sparse:
 * it adds depth to the generated cavern without reducing copy readability.
 */
private class MagicParticleView(
    context: android.content.Context,
    private val uiScale: Float
) : View(context) {
    private data class Particle(
        val x: Float,
        val y: Float,
        val size: Float,
        val speed: Float,
        val phase: Float,
        val drift: Float,
        val color: Int,
        val opacity: Float
    )

    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val random = Random(0x564F434F)
    private val colors = intArrayOf(
        Color.parseColor("#FFFFC84A"),
        Color.parseColor("#FF4DE8FF"),
        Color.parseColor("#FF9A6CFF"),
        Color.parseColor("#FFFFFFFF")
    )
    private val particles = List(28) {
        Particle(
            x = random.nextFloat(),
            y = random.nextFloat(),
            size = 1.4f + random.nextFloat() * 3.2f,
            speed = 0.10f + random.nextFloat() * 0.24f,
            phase = random.nextFloat(),
            drift = (random.nextFloat() - 0.5f) * 0.035f,
            color = colors[random.nextInt(colors.size)],
            opacity = 0.20f + random.nextFloat() * 0.40f
        )
    }
    private var progress = 0f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 12_000L
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.RESTART
        interpolator = LinearInterpolator()
        addUpdateListener {
            progress = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        isFocusable = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!animator.isStarted) animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        particles.forEach { particle ->
            val cycle = (progress * particle.speed + particle.phase) % 1f
            val x = (
                particle.x + particle.drift *
                    kotlin.math.sin((cycle + particle.phase) * Math.PI * 2.0).toFloat()
                ) * width
            val y = (particle.y - cycle + 1f) % 1f * height
            val fade = kotlin.math.sin(cycle * Math.PI).toFloat().coerceAtLeast(0f)
            particlePaint.color = particle.color
            particlePaint.alpha = (255f * particle.opacity * fade).roundToInt()

            val radius = particle.size * uiScale
            canvas.save()
            canvas.rotate(45f, x, y)
            canvas.drawRoundRect(
                x - radius,
                y - radius,
                x + radius,
                y + radius,
                radius * 0.22f,
                radius * 0.22f,
                particlePaint
            )
            canvas.restore()
        }
    }
}
