package com.VocoCraft.VocoCraft;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * VocoCraft's opt-in rewarded case. The actual item IDs and weights mirror the
 * allow-list in the VoxeLibre vococraft_rewarded_ads mod.
 */
public final class CaseRewardManager {
	private static final String TAG = "CaseReward";
	private static final String PREFS = "vococraft_rewarded_case";
	private static final String KEY_COOLDOWN_UNTIL = "cooldown_until_ms";
	private static final long COOLDOWN_MS = 90_000L;
	private static final long ATTENTION_INTERVAL_MS = 30_000L;
	private static final int TOTAL_WEIGHT = 10_000;
	private static final int REEL_TARGET_INDEX = 36;
	private static final int REEL_ITEM_COUNT = 42;

	public static final class Prize {
		public final int index;
		public final String itemId;
		public final String title;
		public final int count;
		public final int weight;
		public final String tier;
		public final int tierColor;
		public final int iconRes;

		Prize(int index, String itemId, String title, int count, int weight,
				String tier, int tierColor, int iconRes) {
			this.index = index;
			this.itemId = itemId;
			this.title = title;
			this.count = count;
			this.weight = weight;
			this.tier = tier;
			this.tierColor = tierColor;
			this.iconRes = iconRes;
		}
	}

	private static final int COMMON = Color.parseColor("#8FA7BD");
	private static final int UNCOMMON = Color.parseColor("#38C978");
	private static final int RARE = Color.parseColor("#39A8FF");
	private static final int EPIC = Color.parseColor("#B46CFF");
	private static final int LEGENDARY = Color.parseColor("#FFB52E");

	// Exactly 10,000 basis points: percentages are stable, testable and easy
	// to audit. Prize indexes must remain in sync with the Lua allow-list.
	public static final Prize[] PRIZES = {
			new Prize(0, "mcl_tools:pick_stone", "Каменная кирка", 1, 1600,
					"Обычный", COMMON, R.drawable.vococraft_case_item_stone_pick),
			new Prize(1, "mcl_torches:torch", "Факелы ×24", 24, 1200,
					"Обычный", COMMON, R.drawable.vococraft_case_item_torches),
			new Prize(2, "mcl_core:coal_lump", "Уголь ×16", 16, 900,
					"Обычный", COMMON, R.drawable.vococraft_case_item_coal),
			new Prize(3, "mcl_mobitems:cooked_beef", "Стейк ×8", 8, 800,
					"Обычный", COMMON, R.drawable.vococraft_case_item_cooked_beef),
			new Prize(4, "mcl_core:iron_ingot", "Железо ×5", 5, 900,
					"Обычный", COMMON, R.drawable.vococraft_case_item_iron_ingot),
			new Prize(5, "mcl_tools:pick_iron", "Железная кирка", 1, 1200,
					"Обычный", COMMON, R.drawable.vococraft_case_item_iron_pick),
			new Prize(6, "mcl_bows:bow", "Лук", 1, 600,
					"Необычный", UNCOMMON, R.drawable.vococraft_case_item_bow),
			new Prize(7, "mcl_core:apple_gold", "Золотое яблоко", 1, 700,
					"Необычный", UNCOMMON, R.drawable.vococraft_case_item_golden_apple),
			new Prize(8, "mcl_potions:healing", "Зелье лечения", 1, 550,
					"Необычный", UNCOMMON, R.drawable.vococraft_case_item_potion_healing),
			new Prize(9, "mcl_potions:swiftness", "Зелье скорости", 1, 450,
					"Необычный", UNCOMMON, R.drawable.vococraft_case_item_potion_swiftness),
			new Prize(10, "mcl_potions:fire_resistance", "Огнестойкость", 1, 350,
					"Необычный", UNCOMMON, R.drawable.vococraft_case_item_potion_fire_resistance),
			new Prize(11, "mcl_core:diamond", "Алмазы ×2", 2, 300,
					"Редкий", RARE, R.drawable.vococraft_case_item_diamonds),
			new Prize(12, "mcl_tools:pick_diamond", "Алмазная кирка", 1, 200,
					"Редкий", RARE, R.drawable.vococraft_case_item_diamond_pick),
			new Prize(13, "mcl_armor:helmet_diamond", "Алмазный шлем", 1, 100,
					"Эпический", EPIC, R.drawable.vococraft_case_item_diamond_helmet),
			new Prize(14, "mcl_armor:leggings_diamond", "Алмазные поножи", 1, 70,
					"Эпический", EPIC, R.drawable.vococraft_case_item_diamond_leggings),
			new Prize(15, "mcl_armor:chestplate_diamond", "Алмазный нагрудник", 1, 50,
					"Эпический", EPIC, R.drawable.vococraft_case_item_diamond_chestplate),
			new Prize(16, "mcl_totems:totem", "Тотем бессмертия", 1, 30,
					"Легендарный", LEGENDARY, R.drawable.vococraft_case_item_totem),
	};

	private static final SecureRandom RANDOM = new SecureRandom();
	private static final Handler HANDLER = new Handler(Looper.getMainLooper());

	private static Activity activity;
	private static ViewGroup root;
	private static LinearLayout button;
	private static FrameLayout buttonGlow;
	private static ImageView buttonIcon;
	private static TextView buttonBadge;
	private static TextView buttonLabel;
	private static Runnable clickAction;
	private static Runnable presentationFinished;
	private static boolean eligible;
	private static boolean hasInternet;
	private static boolean adReady;
	private static boolean flowInProgress;
	private static boolean impressionSent;
	private static Prize pendingPrize;
	private static FrameLayout dialog;
	private static SoundPool soundPool;
	private static int clickSound;
	private static int openSound;
	private static int tickSound;
	private static int winSound;
	private static boolean destroyed;

	private static final Runnable STATUS_TICK = new Runnable() {
		@Override
		public void run() {
			refreshButton();
			HANDLER.postDelayed(this, 500L);
		}
	};

	private static final Runnable ATTENTION_TICK = new Runnable() {
		@Override
		public void run() {
			if (isReady() && buttonGlow != null && button.getVisibility() == View.VISIBLE) {
				playAttentionAnimation();
				track("button", "attention", null);
			}
			HANDLER.postDelayed(this, ATTENTION_INTERVAL_MS);
		}
	};

	private CaseRewardManager() {}

	public static View createButton(Activity host, ViewGroup hostRoot,
			Runnable onClick, Runnable onPresentationFinished) {
		activity = host;
		root = hostRoot;
		clickAction = onClick;
		presentationFinished = onPresentationFinished;
		destroyed = false;
		initSounds();

		button = new LinearLayout(host);
		button.setOrientation(LinearLayout.VERTICAL);
		button.setGravity(Gravity.CENTER);
		button.setPadding(dp(1), dp(2), dp(1), dp(3));
		button.setLayoutParams(new LinearLayout.LayoutParams(
				dp(76), ViewGroup.LayoutParams.WRAP_CONTENT));
		button.setContentDescription("Открыть кейс за просмотр рекламы");
		button.setClickable(true);
		button.setFocusable(true);

		buttonGlow = new FrameLayout(host);
		buttonGlow.setLayoutParams(new LinearLayout.LayoutParams(dp(68), dp(68)));
		buttonGlow.setBackground(ovalGradient(
				Color.parseColor("#E527A9E8"),
				Color.parseColor("#F109234B"),
				Color.WHITE, 2));
		buttonGlow.setElevation(dp(8));

		FrameLayout inner = new FrameLayout(host);
		FrameLayout.LayoutParams innerParams =
				new FrameLayout.LayoutParams(dp(60), dp(60), Gravity.CENTER);
		GradientDrawable innerBackground = new GradientDrawable(
				GradientDrawable.Orientation.TOP_BOTTOM,
				new int[]{Color.parseColor("#F0253555"), Color.parseColor("#F2081020")});
		innerBackground.setShape(GradientDrawable.OVAL);
		innerBackground.setStroke(dp(1), Color.parseColor("#FF39D7FF"));
		inner.setBackground(innerBackground);
		buttonGlow.addView(inner, innerParams);

		buttonIcon = new ImageView(host);
		buttonIcon.setImageResource(R.drawable.vococraft_case_closed);
		buttonIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
		buttonIcon.setAdjustViewBounds(true);
		FrameLayout.LayoutParams iconParams =
				new FrameLayout.LayoutParams(dp(57), dp(57), Gravity.CENTER);
		inner.addView(buttonIcon, iconParams);

		buttonBadge = new TextView(host);
		buttonBadge.setText("▶ AD");
		buttonBadge.setTextColor(Color.WHITE);
		buttonBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8f);
		buttonBadge.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
		buttonBadge.setGravity(Gravity.CENTER);
		buttonBadge.setPadding(dp(5), dp(1), dp(5), dp(1));
		GradientDrawable badgeBackground = new GradientDrawable();
		badgeBackground.setColor(Color.parseColor("#E6000000"));
		badgeBackground.setCornerRadius(dp(9));
		badgeBackground.setStroke(dp(1), Color.parseColor("#FF54E6FF"));
		buttonBadge.setBackground(badgeBackground);
		FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(
				ViewGroup.LayoutParams.WRAP_CONTENT, dp(17),
				Gravity.TOP | Gravity.END);
		buttonGlow.addView(buttonBadge, badgeParams);

		buttonLabel = new TextView(host);
		buttonLabel.setTextColor(Color.WHITE);
		buttonLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
		buttonLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
		buttonLabel.setGravity(Gravity.CENTER);
		buttonLabel.setSingleLine(true);
		buttonLabel.setShadowLayer(5f, 0f, 1f, Color.parseColor("#FF16CFFF"));
		LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, dp(20));
		labelParams.topMargin = dp(1);

		button.addView(buttonGlow);
		button.addView(buttonLabel, labelParams);
		button.setOnClickListener(view -> {
			playSound(clickSound, 0.72f, 0.88f);
			if (clickAction != null) {
				clickAction.run();
			}
		});

		HANDLER.removeCallbacks(STATUS_TICK);
		HANDLER.removeCallbacks(ATTENTION_TICK);
		HANDLER.post(STATUS_TICK);
		HANDLER.postDelayed(ATTENTION_TICK, ATTENTION_INTERVAL_MS);
		refreshButton();
		return button;
	}

	public static void updateContext(boolean isEligible, boolean internet,
			boolean rewardedReady, boolean inProgress) {
		eligible = isEligible;
		hasInternet = internet;
		adReady = rewardedReady;
		flowInProgress = inProgress;
		refreshButton();
	}

	public static boolean isReady() {
		return eligible && hasInternet && adReady && !flowInProgress
				&& getCooldownRemainingMs() <= 0L;
	}

	public static String getUnavailableReason() {
		if (!eligible) {
			return "ineligible";
		}
		if (getCooldownRemainingMs() > 0L) {
			return "cooldown";
		}
		if (!hasInternet) {
			return "offline";
		}
		if (!adReady) {
			return "ad_loading";
		}
		if (flowInProgress) {
			return "flow_in_progress";
		}
		return "ready";
	}

	/**
	 * Select the reward only after the SDK has emitted onRewarded().
	 */
	public static int preparePrize() {
		int roll = RANDOM.nextInt(TOTAL_WEIGHT);
		int cursor = 0;
		for (Prize prize : PRIZES) {
			cursor += prize.weight;
			if (roll < cursor) {
				pendingPrize = prize;
				trackPrize("selected", prize);
				showPendingDialog();
				return prize.index;
			}
		}
		pendingPrize = PRIZES[0];
		trackPrize("selected_fallback", pendingPrize);
		showPendingDialog();
		return pendingPrize.index;
	}

	public static void onPrizeGranted(int prizeIndex) {
		Prize prize = getPrize(prizeIndex);
		if (prize == null || pendingPrize == null || pendingPrize.index != prizeIndex) {
			onPrizeRejected(prizeIndex);
			return;
		}
		setCooldown();
		trackPrize("granted", prize);
		if (dialog == null) {
			showPendingDialog();
		}
		startOpeningAnimation(prize);
	}

	public static void onPrizeRejected(int prizeIndex) {
		Prize prize = getPrize(prizeIndex);
		if (prize != null) {
			trackPrize("rejected", prize);
		} else {
			track("prize", "rejected_unknown", "index_" + prizeIndex);
		}
		pendingPrize = null;
		removeDialog();
		finishPresentation();
	}

	public static Prize getPrize(int index) {
		return index >= 0 && index < PRIZES.length ? PRIZES[index] : null;
	}

	public static long getCooldownRemainingMs() {
		Activity host = activity;
		if (host == null) {
			return 0L;
		}
		long until = host.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
				.getLong(KEY_COOLDOWN_UNTIL, 0L);
		return Math.max(0L, until - System.currentTimeMillis());
	}

	public static void destroy() {
		destroyed = true;
		HANDLER.removeCallbacks(STATUS_TICK);
		HANDLER.removeCallbacks(ATTENTION_TICK);
		removeDialog();
		if (soundPool != null) {
			soundPool.release();
			soundPool = null;
		}
		activity = null;
		root = null;
		button = null;
		buttonGlow = null;
		buttonIcon = null;
		buttonBadge = null;
		buttonLabel = null;
		clickAction = null;
		presentationFinished = null;
		pendingPrize = null;
		impressionSent = false;
	}

	private static void refreshButton() {
		if (destroyed || button == null || buttonLabel == null) {
			return;
		}
		button.setVisibility(eligible ? View.VISIBLE : View.GONE);
		if (!eligible) {
			impressionSent = false;
			return;
		}
		if (!impressionSent) {
			track("button", "shown", null);
			impressionSent = true;
		}

		long remaining = getCooldownRemainingMs();
		boolean ready = isReady();
		button.setEnabled(true);
		button.setClickable(true);
		buttonGlow.setAlpha(ready ? 1f : 0.52f);
		if (ready) {
			buttonIcon.clearColorFilter();
		} else {
			buttonIcon.setColorFilter(Color.argb(150, 120, 130, 145));
		}
		buttonBadge.setVisibility(ready ? View.VISIBLE : View.GONE);

		if (remaining > 0L) {
			long totalSeconds = (remaining + 999L) / 1000L;
			buttonLabel.setText(String.format(Locale.US, "%02d:%02d",
					totalSeconds / 60L, totalSeconds % 60L));
		} else if (!hasInternet) {
			buttonLabel.setText("НЕТ СЕТИ");
		} else if (!adReady) {
			buttonLabel.setText("ЗАГРУЗКА");
		} else if (flowInProgress) {
			buttonLabel.setText("ОТКРЫВАЕМ");
		} else {
			buttonLabel.setText("КЕЙС");
		}
	}

	private static void setCooldown() {
		Activity host = activity;
		if (host == null) {
			return;
		}
		SharedPreferences preferences =
				host.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
		preferences.edit()
				.putLong(KEY_COOLDOWN_UNTIL, System.currentTimeMillis() + COOLDOWN_MS)
				.apply();
		refreshButton();
	}

	private static void showPendingDialog() {
		if (activity == null || root == null || pendingPrize == null) {
			return;
		}
		removeDialog();
		track("opening", "dialog_shown", null);

		dialog = new FrameLayout(activity);
		dialog.setClickable(true);
		dialog.setFocusable(true);
		dialog.setBackgroundColor(Color.parseColor("#D9080C18"));
		dialog.setElevation(dp(30));

		LinearLayout panel = new LinearLayout(activity);
		panel.setOrientation(LinearLayout.VERTICAL);
		panel.setGravity(Gravity.CENTER_HORIZONTAL);
		panel.setClipChildren(false);
		panel.setPadding(dp(14), dp(8), dp(14), dp(8));
		panel.setBackground(panelBackground());

		DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
		int panelWidth = Math.min(dp(620), metrics.widthPixels - dp(32));
		int panelHeight = Math.min(dp(266), metrics.heightPixels - dp(24));
		FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
				panelWidth, panelHeight, Gravity.CENTER);
		dialog.addView(panel, panelParams);

		TextView title = text("КЕЙС VOCOCRAFT", 18f, Color.WHITE, true);
		title.setShadowLayer(8f, 0f, 2f, Color.parseColor("#FF15CFFF"));
		panel.addView(title, new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, dp(30)));

		TextView subtitle = text("Награда получена — открываем безопасно на паузе", 10f,
				Color.parseColor("#FFB7C9DE"), false);
		panel.addView(subtitle, new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, dp(18)));

		FrameLayout stage = new FrameLayout(activity);
		stage.setClipChildren(false);
		stage.setBackground(stageBackground());
		LinearLayout.LayoutParams stageParams = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
		stageParams.topMargin = dp(3);
		panel.addView(stage, stageParams);

		ImageView closed = new ImageView(activity);
		closed.setId(View.generateViewId());
		closed.setImageResource(R.drawable.vococraft_case_closed);
		closed.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
		stage.addView(closed, new FrameLayout.LayoutParams(
				dp(122), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));

		ImageView opened = new ImageView(activity);
		opened.setId(View.generateViewId());
		opened.setImageResource(R.drawable.vococraft_case_open);
		opened.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
		opened.setAlpha(0f);
		stage.addView(opened, new FrameLayout.LayoutParams(
				dp(128), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));

		FrameLayout reel = new FrameLayout(activity);
		reel.setId(View.generateViewId());
		reel.setClipChildren(true);
		reel.setVisibility(View.INVISIBLE);
		stage.addView(reel, new FrameLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.MATCH_PARENT));

		TextView result = text("Проверяем награду…", 13f,
				Color.parseColor("#FFD8E8F7"), true);
		result.setId(View.generateViewId());
		LinearLayout.LayoutParams resultParams = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, dp(34));
		resultParams.topMargin = dp(3);
		panel.addView(result, resultParams);

		TextView claim = text("ПОДОЖДИТЕ…", 13f, Color.WHITE, true);
		claim.setId(View.generateViewId());
		claim.setEnabled(false);
		claim.setAlpha(0.48f);
		claim.setGravity(Gravity.CENTER);
		claim.setBackground(actionBackground(Color.parseColor("#FF197D99")));
		LinearLayout.LayoutParams claimParams = new LinearLayout.LayoutParams(
				Math.min(dp(250), panelWidth - dp(50)), dp(38));
		claimParams.topMargin = dp(2);
		panel.addView(claim, claimParams);

		dialog.setTag(new DialogViews(closed, opened, stage, reel, result, claim));
		root.addView(dialog, new ViewGroup.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.MATCH_PARENT));
	}

	private static void startOpeningAnimation(Prize prize) {
		if (dialog == null || !(dialog.getTag() instanceof DialogViews)) {
			return;
		}
		DialogViews views = (DialogViews) dialog.getTag();
		track("opening", "started", null);
		playSound(openSound, 0.9f, 0.9f);

		ObjectAnimator shake = ObjectAnimator.ofFloat(
				views.closed, View.ROTATION, 0f, -4f, 4f, -3f, 3f, 0f);
		ObjectAnimator pulseX = ObjectAnimator.ofFloat(
				views.closed, View.SCALE_X, 1f, 1.08f, 0.96f, 1.02f);
		ObjectAnimator pulseY = ObjectAnimator.ofFloat(
				views.closed, View.SCALE_Y, 1f, 1.08f, 0.96f, 1.02f);
		AnimatorSet charge = new AnimatorSet();
		charge.playTogether(shake, pulseX, pulseY);
		charge.setDuration(650L);
		charge.addListener(new AnimatorListenerAdapter() {
			@Override
			public void onAnimationEnd(Animator animation) {
				views.opened.setAlpha(0f);
				views.opened.setScaleX(0.86f);
				views.opened.setScaleY(0.86f);
				views.opened.animate().alpha(1f).scaleX(1f).scaleY(1f)
						.setDuration(420L).start();
				views.closed.animate().alpha(0f).scaleX(1.18f).scaleY(1.18f)
						.setDuration(300L)
						.withEndAction(() -> {
							views.closed.setVisibility(View.INVISIBLE);
							HANDLER.postDelayed(() -> startReel(views, prize), 360L);
						}).start();
			}
		});
		charge.start();
	}

	private static void startReel(DialogViews views, Prize winningPrize) {
		if (dialog == null || views.reel == null) {
			return;
		}
		views.opened.animate().alpha(0f).setDuration(220L)
				.withEndAction(() -> views.opened.setVisibility(View.INVISIBLE)).start();
		views.reel.setVisibility(View.VISIBLE);
		views.result.setText("Лента наград запущена…");

		LinearLayout strip = new LinearLayout(activity);
		strip.setOrientation(LinearLayout.HORIZONTAL);
		strip.setGravity(Gravity.CENTER_VERTICAL);
		final int slotWidth = dp(72);
		for (int i = 0; i < REEL_ITEM_COUNT; i++) {
			Prize prize = i == REEL_TARGET_INDEX
					? winningPrize : PRIZES[RANDOM.nextInt(PRIZES.length)];
			LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
					slotWidth - dp(4), ViewGroup.LayoutParams.MATCH_PARENT);
			cardParams.leftMargin = dp(2);
			cardParams.rightMargin = dp(2);
			strip.addView(createPrizeCard(prize), cardParams);
		}
		views.reel.addView(strip, new FrameLayout.LayoutParams(
				slotWidth * REEL_ITEM_COUNT,
				ViewGroup.LayoutParams.MATCH_PARENT));

		TextView markerTop = text("▼", 14f, LEGENDARY, true);
		markerTop.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
		markerTop.setIncludeFontPadding(false);
		FrameLayout.LayoutParams markerTopParams = new FrameLayout.LayoutParams(
				dp(28), dp(28), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
		markerTopParams.topMargin = -dp(14);
		views.stage.addView(markerTop, markerTopParams);
		TextView markerBottom = text("▲", 14f, LEGENDARY, true);
		markerBottom.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
		markerBottom.setIncludeFontPadding(false);
		FrameLayout.LayoutParams markerBottomParams = new FrameLayout.LayoutParams(
				dp(28), dp(28), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
		markerBottomParams.bottomMargin = -dp(14);
		views.stage.addView(markerBottom, markerBottomParams);

		views.reel.post(() -> {
			float startX = views.reel.getWidth() / 2f - slotWidth / 2f;
			float endX = views.reel.getWidth() / 2f
					- (REEL_TARGET_INDEX * slotWidth + slotWidth / 2f);
			strip.setTranslationX(startX);
			final int[] lastIndex = {-1};
			ValueAnimator animator = ValueAnimator.ofFloat(startX, endX);
			animator.setDuration(5_200L);
			animator.setInterpolator(new DecelerateInterpolator(2.15f));
			animator.addUpdateListener(valueAnimator -> {
				float x = (float) valueAnimator.getAnimatedValue();
				strip.setTranslationX(x);
				int centered = Math.max(0, Math.min(REEL_ITEM_COUNT - 1,
						Math.round((views.reel.getWidth() / 2f - x
								- slotWidth / 2f) / slotWidth)));
				if (lastIndex[0] < 0) {
					lastIndex[0] = centered;
				} else {
					float progress = valueAnimator.getAnimatedFraction();
					while (lastIndex[0] != centered) {
						lastIndex[0] += centered > lastIndex[0] ? 1 : -1;
						playSound(tickSound, 0.38f, 0.88f + progress * 0.34f);
					}
				}
			});
			animator.addListener(new AnimatorListenerAdapter() {
				@Override
				public void onAnimationEnd(Animator animation) {
					showResult(views, winningPrize);
				}
			});
			animator.start();
		});
	}

	private static View createPrizeCard(Prize prize) {
		FrameLayout card = new FrameLayout(activity);
		card.setPadding(dp(5), dp(7), dp(5), dp(7));
		GradientDrawable background = new GradientDrawable(
				GradientDrawable.Orientation.TOP_BOTTOM,
				new int[]{withAlpha(prize.tierColor, 118), Color.parseColor("#E50A1020")});
		background.setCornerRadius(dp(9));
		card.setBackground(background);

		ImageView icon = new ImageView(activity);
		icon.setImageResource(prize.iconRes);
		if (icon.getDrawable() instanceof BitmapDrawable) {
			BitmapDrawable bitmap = (BitmapDrawable) icon.getDrawable();
			bitmap.setAntiAlias(false);
			bitmap.setFilterBitmap(false);
			bitmap.setDither(false);
		}
		icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
		icon.setAdjustViewBounds(true);
		icon.setFilterTouchesWhenObscured(true);
		FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(
				dp(48), dp(48), Gravity.CENTER);
		card.addView(icon, iconParams);

		if (prize.count > 1) {
			TextView count = text("×" + prize.count, 9f, Color.WHITE, true);
			count.setText("x" + prize.count);
			count.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f);
			count.setGravity(Gravity.CENTER);
			count.setPadding(dp(4), 0, dp(4), 0);
			count.setBackground(quantityBadgeBackground(prize.tierColor));
			FrameLayout.LayoutParams countParams = new FrameLayout.LayoutParams(
					dp(34), dp(20),
					Gravity.BOTTOM | Gravity.END);
			countParams.rightMargin = dp(3);
			countParams.bottomMargin = dp(3);
			card.addView(count, countParams);
		}
		return card;
	}

	private static void showResult(DialogViews views, Prize prize) {
		playSound(winSound, 0.82f, prize.tierColor == LEGENDARY ? 1.12f : 0.98f);
		String chance = String.format(Locale.US, "%.2f", prize.weight / 100.0)
				.replace('.', ',');
		views.result.setText(prize.tier + " • " + prize.title + " • шанс " + chance + "%");
		views.result.setTextColor(prize.tierColor);
		views.result.setShadowLayer(7f, 0f, 1f, prize.tierColor);
		views.claim.setText("ЗАБРАТЬ");
		views.claim.setEnabled(true);
		views.claim.setAlpha(1f);
		views.claim.setBackground(actionBackground(prize.tierColor));
		views.claim.setOnClickListener(view -> {
			playSound(clickSound, 0.76f, 0.92f);
			trackPrize("claimed", prize);
			pendingPrize = null;
			removeDialog();
			finishPresentation();
		});
		trackPrize("animation_completed", prize);
	}

	private static void finishPresentation() {
		flowInProgress = false;
		refreshButton();
		if (presentationFinished != null) {
			presentationFinished.run();
		}
	}

	private static void removeDialog() {
		if (dialog != null) {
			ViewGroup parent = (ViewGroup) dialog.getParent();
			if (parent != null) {
				parent.removeView(dialog);
			}
			dialog = null;
		}
	}

	private static void playAttentionAnimation() {
		if (buttonGlow == null) {
			return;
		}
		ObjectAnimator rotate = ObjectAnimator.ofFloat(
				buttonGlow, View.ROTATION, 0f, -7f, 7f, -5f, 5f, 0f);
		ObjectAnimator scaleX = ObjectAnimator.ofFloat(
				buttonGlow, View.SCALE_X, 1f, 1.1f, 1f);
		ObjectAnimator scaleY = ObjectAnimator.ofFloat(
				buttonGlow, View.SCALE_Y, 1f, 1.1f, 1f);
		AnimatorSet animator = new AnimatorSet();
		animator.playTogether(rotate, scaleX, scaleY);
		animator.setDuration(780L);
		animator.start();
	}

	private static void initSounds() {
		if (activity == null || soundPool != null) {
			return;
		}
		AudioAttributes attributes = new AudioAttributes.Builder()
				.setUsage(AudioAttributes.USAGE_GAME)
				.setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
				.build();
		soundPool = new SoundPool.Builder()
				.setMaxStreams(5)
				.setAudioAttributes(attributes)
				.build();
		clickSound = soundPool.load(activity, R.raw.vococraft_case_click, 1);
		openSound = soundPool.load(activity, R.raw.vococraft_case_open, 1);
		tickSound = soundPool.load(activity, R.raw.vococraft_case_tick, 1);
		winSound = soundPool.load(activity, R.raw.vococraft_case_win, 1);
	}

	private static void playSound(int sound, float volume, float rate) {
		if (soundPool != null && sound != 0) {
			soundPool.play(sound, volume, volume, 1, 0,
					Math.max(0.5f, Math.min(2f, rate)));
		}
	}

	private static void track(String category, String action, String detail) {
		try {
			JSONObject actionNode = new JSONObject();
			actionNode.put(action, 1);
			JSONObject categoryNode = new JSONObject();
			categoryNode.put(category, actionNode);
			JSONObject context = new JSONObject();
			context.put("premium", YooKassaPay.hasPurchase());
			context.put("ad_ready", adReady);
			context.put("internet", hasInternet);
			context.put("cooldown_ms", getCooldownRemainingMs());
			if (detail != null) {
				context.put("detail", detail);
			}
			JSONObject params = new JSONObject();
			params.put("rewarded_case", categoryNode);
			params.put("context", context);
			Analytics.sendEventWithParams("rewarded_case", params.toString());
			Log.d(TAG, category + "/" + action);
		} catch (Exception error) {
			Log.e(TAG, "Analytics failed: " + error.getMessage());
		}
	}

	private static void trackPrize(String action, Prize prize) {
		try {
			JSONObject data = new JSONObject();
			data.put("index", prize.index);
			data.put("item_id", prize.itemId);
			data.put("count", prize.count);
			data.put("tier", prize.tier);
			data.put("weight_bp", prize.weight);
			data.put("chance_percent", prize.weight / 100.0);
			JSONObject actionNode = new JSONObject();
			actionNode.put(action, data);
			JSONObject prizeNode = new JSONObject();
			prizeNode.put("prize", actionNode);
			JSONObject context = new JSONObject();
			context.put("premium", YooKassaPay.hasPurchase());
			context.put("cooldown_ms", getCooldownRemainingMs());
			JSONObject params = new JSONObject();
			params.put("rewarded_case", prizeNode);
			params.put("context", context);
			Analytics.sendEventWithParams("rewarded_case", params.toString());
			Log.d(TAG, "prize/" + action + "/" + prize.itemId);
		} catch (Exception error) {
			Log.e(TAG, "Prize analytics failed: " + error.getMessage());
		}
	}

	private static TextView text(String value, float sizeSp, int color, boolean bold) {
		TextView view = new TextView(activity);
		view.setText(value);
		view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
		view.setTextColor(color);
		view.setGravity(Gravity.CENTER);
		if (bold) {
			view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
		}
		return view;
	}

	private static GradientDrawable ovalGradient(int start, int end, int stroke, int strokeDp) {
		GradientDrawable drawable = new GradientDrawable(
				GradientDrawable.Orientation.TL_BR, new int[]{start, end});
		drawable.setShape(GradientDrawable.OVAL);
		drawable.setStroke(dp(strokeDp), stroke);
		return drawable;
	}

	private static GradientDrawable panelBackground() {
		GradientDrawable drawable = new GradientDrawable(
				GradientDrawable.Orientation.TL_BR,
				new int[]{Color.parseColor("#FA172A4A"), Color.parseColor("#FC080E1C")});
		drawable.setCornerRadius(dp(18));
		drawable.setStroke(dp(2), Color.parseColor("#FF35CFF4"));
		return drawable;
	}

	private static GradientDrawable stageBackground() {
		GradientDrawable drawable = new GradientDrawable(
				GradientDrawable.Orientation.LEFT_RIGHT,
				new int[]{Color.parseColor("#F4070B16"), Color.parseColor("#F4162540"),
						Color.parseColor("#F4070B16")});
		drawable.setCornerRadius(dp(11));
		return drawable;
	}

	private static GradientDrawable actionBackground(int color) {
		GradientDrawable drawable = new GradientDrawable(
				GradientDrawable.Orientation.TL_BR,
				new int[]{withAlpha(color, 245), Color.parseColor("#EE0A1830")});
		drawable.setCornerRadius(dp(12));
		drawable.setStroke(dp(1), Color.WHITE);
		return drawable;
	}

	private static GradientDrawable quantityBadgeBackground(int tierColor) {
		GradientDrawable drawable = new GradientDrawable(
				GradientDrawable.Orientation.LEFT_RIGHT,
				new int[]{Color.parseColor("#F20A1020"), withAlpha(tierColor, 235)});
		drawable.setCornerRadius(dp(10));
		return drawable;
	}

	private static int withAlpha(int color, int alpha) {
		return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
	}

	private static int dp(int value) {
		if (activity == null) {
			return value;
		}
		return Math.round(TypedValue.applyDimension(
				TypedValue.COMPLEX_UNIT_DIP, value,
				activity.getResources().getDisplayMetrics()));
	}

	private static final class DialogViews {
		final ImageView closed;
		final ImageView opened;
		final FrameLayout stage;
		final FrameLayout reel;
		final TextView result;
		final TextView claim;

		DialogViews(ImageView closed, ImageView opened, FrameLayout stage,
				FrameLayout reel,
				TextView result, TextView claim) {
			this.closed = closed;
			this.opened = opened;
			this.stage = stage;
			this.reel = reel;
			this.result = result;
			this.claim = claim;
		}
	}
}
