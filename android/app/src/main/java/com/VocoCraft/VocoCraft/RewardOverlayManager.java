package com.VocoCraft.VocoCraft;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

/**
 * Optional rewarded-ad controls shown over local singleplayer gameplay.
 *
 * These controls are intentionally available to both free and Premium users:
 * forced ads are a monetization restriction, while rewarded ads are an
 * opt-in gameplay bonus.
 */
public final class RewardOverlayManager {
	private static final String TAG = "RewardOverlay";

	public static final int REWARD_HEALTH = 1;
	public static final int REWARD_FOOD = 2;
	public static final int REWARD_CASE = 3;
	public static final int ACTION_MENU = 10;
	public static final int ACTION_INVENTORY = 11;
	public static final int ACTION_PURCHASE = 12;

	// Four hearts equal eight engine HP/food points.
	private static final int LOW_RESOURCE_THRESHOLD = 8;
	private static final long FULL_ACCESS_INITIAL_ATTENTION_MS = 9000L;
	private static final long FULL_ACCESS_ATTENTION_INTERVAL_MS = 30000L;
	private static final Handler UI_HANDLER = new Handler(Looper.getMainLooper());

	private static Activity activity;
	private static ViewGroup root;
	private static LinearLayout container;
	private static LinearLayout bonusContainer;
	private static LinearLayout navigationColumn;
	private static LinearLayout fullAccessItem;
	private static View fullAccessGlow;
	private static TextView fullAccessLabel;
	private static LinearLayout healthItem;
	private static LinearLayout foodItem;
	private static View caseItem;
	private static AnimatorSet fullAccessAnimator;
	private static AnimatorSet healthAnimator;
	private static AnimatorSet foodAnimator;

	private static int currentHp;
	private static int currentMaxHp = 20;
	private static int currentHunger = -1;
	private static boolean singleplayer;
	private static boolean survivalMode;
	private static boolean gameplayActive;
	private static boolean healthImpressionSent;
	private static boolean foodImpressionSent;
	private static boolean fullAccessImpressionSent;
	private static boolean rewardFlowInProgress;
	private static boolean purchaseFlowInProgress;

	private static final Runnable FULL_ACCESS_ATTENTION_TICK = new Runnable() {
		@Override
		public void run() {
			if (fullAccessItem != null
					&& fullAccessItem.getVisibility() == View.VISIBLE
					&& gameplayActive && !YooKassaPay.hasPurchase()) {
				playFullAccessAttention();
				trackFullAccessEvent("attention");
			}
			if (activity != null) {
				UI_HANDLER.postDelayed(this, FULL_ACCESS_ATTENTION_INTERVAL_MS);
			}
		}
	};

	private RewardOverlayManager() {}

	public static void init(Activity hostActivity, ViewGroup hostRoot) {
		if (hostActivity == null || hostRoot == null) {
			return;
		}

		hostActivity.runOnUiThread(() -> {
			if (activity == hostActivity && container != null) {
				return;
			}

			destroyInternal();
			activity = hostActivity;
			root = hostRoot;
			buildOverlay();
			refreshVisibility();
			Log.d(TAG, "Reward overlay initialized");
		});
	}

	public static void updateState(int hp, int maxHp, int hunger,
			boolean isSingleplayer, boolean isSurvivalMode,
			boolean isGameplayActive) {
		currentHp = hp;
		currentMaxHp = Math.max(1, maxHp);
		currentHunger = hunger;
		singleplayer = isSingleplayer;
		survivalMode = isSurvivalMode;
		gameplayActive = isGameplayActive;

		Activity host = activity;
		if (host == null || host.isFinishing() || host.isDestroyed()) {
			return;
		}
		host.runOnUiThread(RewardOverlayManager::refreshVisibility);
	}

	public static void onAdAvailabilityChanged() {
		Activity host = activity;
		if (host != null && !host.isFinishing() && !host.isDestroyed()) {
			host.runOnUiThread(RewardOverlayManager::refreshVisibility);
		}
	}

	public static void onPurchaseStateChanged() {
		purchaseFlowInProgress = false;
		refreshVisibilityOnUiThread();
	}

	public static void onPurchaseDialogDismissed() {
		purchaseFlowInProgress = false;
		refreshVisibilityOnUiThread();
	}

	public static int onRewardFlowFinished(int rewardType, boolean earned) {
		trackEvent(rewardType, "reward", earned ? "earned" : "not_earned", null);
		if (rewardType == REWARD_CASE && earned) {
			return CaseRewardManager.preparePrize();
		}
		rewardFlowInProgress = false;
		refreshVisibilityOnUiThread();
		return -1;
	}

	public static void onNativeRewardGranted(int rewardType) {
		trackEvent(rewardType, "reward", "granted", "amount_100");
		Activity host = activity;
		if (host == null) {
			return;
		}
		host.runOnUiThread(() -> Toast.makeText(host,
				rewardType == REWARD_HEALTH
						? "Здоровье полностью восстановлено"
						: "Сытость полностью восстановлена",
				Toast.LENGTH_SHORT).show());
	}

	public static void onNativeRewardRejected(int rewardType) {
		rewardFlowInProgress = false;
		trackEvent(rewardType, "reward", "rejected", "state_changed");
		refreshVisibilityOnUiThread();
	}

	public static void onNativeCasePrizeGranted(int prizeIndex) {
		Activity host = activity;
		if (host != null) {
			host.runOnUiThread(() -> CaseRewardManager.onPrizeGranted(prizeIndex));
		}
	}

	public static void onNativeCasePrizeRejected(int prizeIndex) {
		rewardFlowInProgress = false;
		Activity host = activity;
		if (host != null) {
			host.runOnUiThread(() -> CaseRewardManager.onPrizeRejected(prizeIndex));
		}
		refreshVisibilityOnUiThread();
	}

	public static void destroy() {
		Activity host = activity;
		if (host != null) {
			host.runOnUiThread(RewardOverlayManager::destroyInternal);
		} else {
			destroyInternal();
		}
	}

	private static void buildOverlay() {
		if (activity == null || root == null) {
			return;
		}

		container = new LinearLayout(activity);
		container.setOrientation(LinearLayout.HORIZONTAL);
		container.setGravity(Gravity.TOP | Gravity.END);
		container.setClipChildren(false);
		container.setClipToPadding(false);

		bonusContainer = new LinearLayout(activity);
		bonusContainer.setOrientation(LinearLayout.VERTICAL);
		bonusContainer.setGravity(Gravity.TOP | Gravity.END);
		bonusContainer.setClipChildren(false);
		bonusContainer.setClipToPadding(false);

		fullAccessItem = createFullAccessItem();
		healthItem = createRewardItem(
				REWARD_HEALTH,
				R.drawable.vococraft_reward_heart,
				"+100 HP",
				Color.parseColor("#FF3158"),
				Color.parseColor("#7D1631"));
		foodItem = createRewardItem(
				REWARD_FOOD,
				R.drawable.vococraft_reward_food,
				"+100 ЕДА",
				Color.parseColor("#FF9F1C"),
				Color.parseColor("#7A3513"));

		bonusContainer.addView(fullAccessItem);
		caseItem = CaseRewardManager.createButton(
				activity,
				root,
				() -> handleRewardClick(REWARD_CASE),
				() -> {
					rewardFlowInProgress = false;
					refreshVisibilityOnUiThread();
				});
		bonusContainer.addView(caseItem);
		bonusContainer.addView(healthItem);
		bonusContainer.addView(foodItem);

		navigationColumn = new LinearLayout(activity);
		navigationColumn.setOrientation(LinearLayout.VERTICAL);
		navigationColumn.setGravity(Gravity.CENTER_HORIZONTAL);
		LinearLayout.LayoutParams navigationParams = new LinearLayout.LayoutParams(
				dp(50), ViewGroup.LayoutParams.WRAP_CONTENT);
		navigationParams.leftMargin = dp(4);
		navigationColumn.setLayoutParams(navigationParams);

		View menuButton = createNavigationButton(
				ACTION_MENU,
				R.drawable.vococraft_overlay_menu,
				"Открыть игровое меню",
				Color.parseColor("#54C7FF"));
		View inventoryButton = createNavigationButton(
				ACTION_INVENTORY,
				R.drawable.vococraft_overlay_inventory,
				"Открыть инвентарь",
				Color.parseColor("#FFB347"));
		LinearLayout.LayoutParams inventoryParams = new LinearLayout.LayoutParams(
				dp(46), dp(46));
		inventoryParams.topMargin = dp(6);
		navigationColumn.addView(menuButton);
		navigationColumn.addView(inventoryButton, inventoryParams);

		container.addView(bonusContainer);
		container.addView(navigationColumn);

		int top = dp(8);
		int right = dp(8);
		if (root instanceof RelativeLayout) {
			RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(
					ViewGroup.LayoutParams.WRAP_CONTENT,
					ViewGroup.LayoutParams.WRAP_CONTENT);
			params.addRule(RelativeLayout.ALIGN_PARENT_TOP);
			params.addRule(RelativeLayout.ALIGN_PARENT_END);
			params.topMargin = top;
			params.rightMargin = right;
			root.addView(container, params);
		} else {
			FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
					ViewGroup.LayoutParams.WRAP_CONTENT,
					ViewGroup.LayoutParams.WRAP_CONTENT,
					Gravity.TOP | Gravity.END);
			params.topMargin = top;
			params.rightMargin = right;
			root.addView(container, params);
		}
		container.setOnApplyWindowInsetsListener((view, insets) -> {
			applySafeAreaInsets(insets);
			return insets;
		});
		container.requestApplyInsets();
		container.setVisibility(View.GONE);
		UI_HANDLER.removeCallbacks(FULL_ACCESS_ATTENTION_TICK);
		UI_HANDLER.postDelayed(
				FULL_ACCESS_ATTENTION_TICK, FULL_ACCESS_INITIAL_ATTENTION_MS);
	}

	@SuppressWarnings("deprecation")
	private static void applySafeAreaInsets(WindowInsets insets) {
		if (container == null || insets == null) {
			return;
		}
		// In landscape, gesture/three-button navigation can occupy the right
		// edge even while immersive mode visually hides it. Stable insets keep
		// every overlay control inside the actually clickable display area.
		int safeRight = Math.max(
				insets.getSystemWindowInsetRight(),
				insets.getStableInsetRight());
		ViewGroup.LayoutParams rawParams = container.getLayoutParams();
		if (rawParams instanceof ViewGroup.MarginLayoutParams) {
			ViewGroup.MarginLayoutParams params =
					(ViewGroup.MarginLayoutParams) rawParams;
			int desiredRight = dp(8) + safeRight;
			if (params.rightMargin != desiredRight) {
				params.rightMargin = desiredRight;
				container.setLayoutParams(params);
			}
		}
	}

	private static LinearLayout createFullAccessItem() {
		LinearLayout item = new LinearLayout(activity);
		item.setOrientation(LinearLayout.VERTICAL);
		item.setGravity(Gravity.CENTER);
		item.setPadding(dp(1), dp(2), dp(1), dp(3));
		item.setLayoutParams(new LinearLayout.LayoutParams(
				dp(92), ViewGroup.LayoutParams.WRAP_CONTENT));
		item.setContentDescription("Купить полный доступ VocoCraft");
		item.setClickable(true);
		item.setFocusable(true);

		FrameLayout glow = new FrameLayout(activity);
		glow.setLayoutParams(new LinearLayout.LayoutParams(dp(72), dp(72)));
		GradientDrawable glowBackground = new GradientDrawable(
				GradientDrawable.Orientation.TL_BR,
				new int[]{
						Color.parseColor("#F2FFB52E"),
						Color.parseColor("#F56C24D9"),
						Color.parseColor("#F0097ACB")
				});
		glowBackground.setShape(GradientDrawable.OVAL);
		glowBackground.setStroke(dp(2), Color.WHITE);
		glow.setBackground(glowBackground);
		glow.setElevation(dp(9));

		FrameLayout inner = new FrameLayout(activity);
		FrameLayout.LayoutParams innerParams = new FrameLayout.LayoutParams(
				dp(64), dp(64), Gravity.CENTER);
		GradientDrawable innerBackground = new GradientDrawable(
				GradientDrawable.Orientation.TOP_BOTTOM,
				new int[]{
						Color.parseColor("#F21A2748"),
						Color.parseColor("#F2080E20")
				});
		innerBackground.setShape(GradientDrawable.OVAL);
		innerBackground.setStroke(dp(1), Color.parseColor("#FFFFC94D"));
		inner.setBackground(innerBackground);
		glow.addView(inner, innerParams);

		ImageView icon = new ImageView(activity);
		icon.setImageResource(R.drawable.vococraft_full_access);
		icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
		FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(
				dp(61), dp(61), Gravity.CENTER);
		inner.addView(icon, iconParams);

		TextView vipBadge = new TextView(activity);
		vipBadge.setText("VIP");
		vipBadge.setTextColor(Color.parseColor("#FF251000"));
		vipBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8f);
		vipBadge.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
		vipBadge.setGravity(Gravity.CENTER);
		vipBadge.setPadding(dp(6), dp(1), dp(6), dp(1));
		GradientDrawable badgeBackground = new GradientDrawable(
				GradientDrawable.Orientation.LEFT_RIGHT,
				new int[]{
						Color.parseColor("#FFFFE98A"),
						Color.parseColor("#FFFFA51F")
				});
		badgeBackground.setCornerRadius(dp(9));
		badgeBackground.setStroke(dp(1), Color.WHITE);
		vipBadge.setBackground(badgeBackground);
		FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(
				ViewGroup.LayoutParams.WRAP_CONTENT, dp(17),
				Gravity.TOP | Gravity.END);
		badgeParams.topMargin = dp(1);
		glow.addView(vipBadge, badgeParams);

		TextView label = new TextView(activity);
		label.setText("Полный доступ");
		label.setTextColor(Color.WHITE);
		label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
		label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
		label.setGravity(Gravity.CENTER);
		label.setSingleLine(true);
		label.setShadowLayer(6f, 0f, 1f, Color.parseColor("#FFFFB52E"));
		LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, dp(22));
		labelParams.topMargin = dp(1);

		item.addView(glow);
		item.addView(label, labelParams);
		item.setOnClickListener(view -> handleFullAccessClick());
		fullAccessGlow = glow;
		fullAccessLabel = label;
		return item;
	}

	private static LinearLayout createRewardItem(int rewardType, int iconRes,
			String rewardText, int accent, int accentDark) {
		LinearLayout item = new LinearLayout(activity);
		item.setOrientation(LinearLayout.VERTICAL);
		item.setGravity(Gravity.CENTER);
		item.setPadding(dp(1), dp(2), dp(1), dp(3));
		item.setLayoutParams(new LinearLayout.LayoutParams(
				dp(80), ViewGroup.LayoutParams.WRAP_CONTENT));
		item.setContentDescription(rewardType == REWARD_HEALTH
				? "Посмотреть рекламу и восстановить здоровье"
				: "Посмотреть рекламу и восстановить сытость");
		item.setClickable(true);
		item.setFocusable(true);

		FrameLayout glow = new FrameLayout(activity);
		LinearLayout.LayoutParams glowParams = new LinearLayout.LayoutParams(dp(68), dp(68));
		glow.setLayoutParams(glowParams);
		GradientDrawable glowBackground = new GradientDrawable(
				GradientDrawable.Orientation.TL_BR,
				new int[]{withAlpha(accent, 190), withAlpha(accentDark, 220)});
		glowBackground.setShape(GradientDrawable.OVAL);
		glowBackground.setStroke(dp(2), Color.WHITE);
		glow.setBackground(glowBackground);
		glow.setElevation(dp(8));

		FrameLayout inner = new FrameLayout(activity);
		FrameLayout.LayoutParams innerParams = new FrameLayout.LayoutParams(
				dp(60), dp(60), Gravity.CENTER);
		GradientDrawable innerBackground = new GradientDrawable(
				GradientDrawable.Orientation.TOP_BOTTOM,
				new int[]{Color.parseColor("#E62A2D46"), Color.parseColor("#F20B1020")});
		innerBackground.setShape(GradientDrawable.OVAL);
		innerBackground.setStroke(dp(1), withAlpha(accent, 230));
		inner.setBackground(innerBackground);
		glow.addView(inner, innerParams);

		ImageView icon = new ImageView(activity);
		icon.setImageResource(iconRes);
		icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
		FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(
				dp(48), dp(48), Gravity.CENTER);
		inner.addView(icon, iconParams);

		TextView adBadge = new TextView(activity);
		adBadge.setText("▶ AD");
		adBadge.setTextColor(Color.WHITE);
		adBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8f);
		adBadge.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
		adBadge.setGravity(Gravity.CENTER);
		adBadge.setPadding(dp(5), dp(1), dp(5), dp(1));
		GradientDrawable badgeBackground = new GradientDrawable();
		badgeBackground.setColor(Color.parseColor("#E6000000"));
		badgeBackground.setCornerRadius(dp(9));
		badgeBackground.setStroke(dp(1), withAlpha(accent, 255));
		adBadge.setBackground(badgeBackground);
		FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(
				ViewGroup.LayoutParams.WRAP_CONTENT, dp(17),
				Gravity.TOP | Gravity.END);
		badgeParams.topMargin = dp(1);
		badgeParams.rightMargin = dp(0);
		glow.addView(adBadge, badgeParams);

		TextView label = new TextView(activity);
		label.setText(rewardText);
		label.setTextColor(Color.WHITE);
		label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
		label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
		label.setGravity(Gravity.CENTER);
		label.setShadowLayer(5f, 0f, 1f, accent);
		LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, dp(20));
		labelParams.topMargin = dp(1);

		item.addView(glow);
		item.addView(label, labelParams);
		item.setOnClickListener(view -> handleRewardClick(rewardType));

		if (rewardType == REWARD_HEALTH) {
			healthAnimator = createHeartbeatAnimator(glow);
		} else {
			foodAnimator = createFoodAnimator(glow);
		}
		return item;
	}

	private static View createNavigationButton(int action, int iconRes,
			String accessibilityText, int accent) {
		FrameLayout button = new FrameLayout(activity);
		button.setLayoutParams(new LinearLayout.LayoutParams(dp(46), dp(46)));
		button.setContentDescription(accessibilityText);
		button.setClickable(true);
		button.setFocusable(true);
		button.setElevation(dp(7));

		GradientDrawable background = new GradientDrawable(
				GradientDrawable.Orientation.TL_BR,
				new int[]{
						Color.parseColor("#ED20283D"),
						Color.parseColor("#F20A1020")
				});
		background.setShape(GradientDrawable.OVAL);
		background.setStroke(dp(2), withAlpha(accent, 240));
		button.setBackground(background);

		ImageView icon = new ImageView(activity);
		icon.setImageResource(iconRes);
		icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
		FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(
				dp(35), dp(35), Gravity.CENTER);
		button.addView(icon, iconParams);
		button.setOnClickListener(view -> handleNavigationClick(action));
		return button;
	}

	private static void handleNavigationClick(int action) {
		if (!gameplayActive || rewardFlowInProgress) {
			return;
		}
		if (container != null) {
			container.setVisibility(View.GONE);
		}
		if (activity instanceof GameActivity) {
			((GameActivity) activity).onGameplayOverlayAction(action);
		}
	}

	private static void handleFullAccessClick() {
		if (!gameplayActive || purchaseFlowInProgress
				|| YooKassaPay.hasPurchase()) {
			return;
		}
		purchaseFlowInProgress = true;
		trackFullAccessEvent("clicked");
		if (container != null) {
			container.setVisibility(View.GONE);
		}
		if (activity instanceof GameActivity) {
			// Native opens the real pause menu first and only then calls back
			// into Java to show the purchase dialog. Keeping both operations
			// on the game loop prevents a slow frame from leaving the world
			// running behind the full-screen offer.
			((GameActivity) activity).onGameplayOverlayAction(ACTION_PURCHASE);
		}
	}

	private static void handleRewardClick(int rewardType) {
		boolean caseUnavailable = rewardType == REWARD_CASE
				&& !CaseRewardManager.isReady();
		if (rewardFlowInProgress || caseUnavailable
				|| !YandexAds.isRewardedReady() || !isInternetAvailable()) {
			String detail = rewardType == REWARD_CASE
					? CaseRewardManager.getUnavailableReason() : null;
			trackEvent(rewardType, "button", "unavailable", detail);
			YandexAds.ensureRewardedLoaded();
			refreshVisibility();
			return;
		}

		rewardFlowInProgress = true;
		trackEvent(rewardType, "button", "clicked",
				rewardType == REWARD_CASE ? "case_open" : "amount_100");
		if (container != null) {
			container.setVisibility(View.GONE);
		}
		if (activity instanceof GameActivity) {
			((GameActivity) activity).onRewardOverlayClicked(rewardType);
		}
	}

	private static void refreshVisibilityOnUiThread() {
		Activity host = activity;
		if (host != null && !host.isFinishing() && !host.isDestroyed()) {
			host.runOnUiThread(RewardOverlayManager::refreshVisibility);
		}
	}

	private static void refreshVisibility() {
		if (container == null || healthItem == null || foodItem == null) {
			return;
		}

		boolean hasInternet = isInternetAvailable();
		boolean lowHealth = currentHp > 0
				&& currentHp < LOW_RESOURCE_THRESHOLD
				&& currentHp < currentMaxHp;
		boolean lowFood = currentHunger >= 0
				&& currentHunger < LOW_RESOURCE_THRESHOLD;
		boolean showControls = gameplayActive
				&& !rewardFlowInProgress && !purchaseFlowInProgress;
		boolean eligibleContext = singleplayer && survivalMode && showControls;
		boolean showFullAccess = showControls && !YooKassaPay.hasPurchase();

		if (eligibleContext && hasInternet
				&& !YandexAds.isRewardedReady()) {
			YandexAds.ensureRewardedLoaded();
		}

		boolean adReady = YandexAds.isRewardedReady();
		boolean showHealth = eligibleContext && hasInternet && adReady && lowHealth;
		boolean showFood = eligibleContext && hasInternet && adReady && lowFood;

		setItemVisible(healthItem, healthAnimator, showHealth, REWARD_HEALTH);
		setItemVisible(foodItem, foodAnimator, showFood, REWARD_FOOD);
		boolean fullAccessWasVisible =
				fullAccessItem.getVisibility() == View.VISIBLE;
		fullAccessItem.setVisibility(showFullAccess ? View.VISIBLE : View.GONE);
		if (showFullAccess && !fullAccessWasVisible
				&& !fullAccessImpressionSent) {
			fullAccessImpressionSent = true;
			trackFullAccessEvent("shown");
		}
		if (!showFullAccess && fullAccessAnimator != null) {
			fullAccessAnimator.cancel();
		}
		CaseRewardManager.updateContext(
				eligibleContext, hasInternet, adReady, rewardFlowInProgress);
		bonusContainer.setVisibility(
				showFullAccess || showHealth || showFood || eligibleContext
						? View.VISIBLE : View.GONE);
		navigationColumn.setVisibility(showControls ? View.VISIBLE : View.GONE);
		container.setVisibility(showControls ? View.VISIBLE : View.GONE);
		updateAdaptiveOrientation();
	}

	private static void updateAdaptiveOrientation() {
		if (bonusContainer == null || root == null || root.getHeight() <= 0) {
			return;
		}
		int visibleItems = 0;
		for (int index = 0; index < bonusContainer.getChildCount(); index++) {
			if (bonusContainer.getChildAt(index).getVisibility() == View.VISIBLE) {
				visibleItems++;
			}
		}
		boolean useVertical = visibleItems <= 1
				|| visibleItems * dp(94) + dp(16) <= root.getHeight();
		int orientation = useVertical
				? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL;
		if (bonusContainer.getOrientation() != orientation) {
			bonusContainer.setOrientation(orientation);
		}
		for (int index = 0; index < bonusContainer.getChildCount(); index++) {
			View child = bonusContainer.getChildAt(index);
			LinearLayout.LayoutParams params =
					(LinearLayout.LayoutParams) child.getLayoutParams();
			params.width = dp(92);
			params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
			params.leftMargin = useVertical ? 0 : dp(2);
			params.topMargin = useVertical ? dp(2) : 0;
			child.setLayoutParams(params);
		}
	}

	private static void setItemVisible(View item, AnimatorSet animator,
			boolean visible, int rewardType) {
		boolean wasVisible = item.getVisibility() == View.VISIBLE;
		item.setVisibility(visible ? View.VISIBLE : View.GONE);
		if (visible && !wasVisible) {
			if (animator != null && !animator.isStarted()) {
				animator.start();
			}
			boolean sent = rewardType == REWARD_HEALTH
					? healthImpressionSent : foodImpressionSent;
			if (!sent) {
				trackEvent(rewardType, "button", "shown", "amount_100");
				if (rewardType == REWARD_HEALTH) {
					healthImpressionSent = true;
				} else {
					foodImpressionSent = true;
				}
			}
		} else if (!visible && wasVisible) {
			if (animator != null) {
				animator.cancel();
			}
			if (rewardType == REWARD_HEALTH) {
				healthImpressionSent = false;
			} else {
				foodImpressionSent = false;
			}
		}
	}

	private static AnimatorSet createHeartbeatAnimator(View target) {
		ObjectAnimator scaleX = ObjectAnimator.ofFloat(
				target, View.SCALE_X, 1f, 1.12f, 0.98f, 1.08f, 1f);
		ObjectAnimator scaleY = ObjectAnimator.ofFloat(
				target, View.SCALE_Y, 1f, 1.12f, 0.98f, 1.08f, 1f);
		scaleX.setRepeatCount(ValueAnimator.INFINITE);
		scaleY.setRepeatCount(ValueAnimator.INFINITE);
		scaleX.setDuration(1050);
		scaleY.setDuration(1050);
		AnimatorSet set = new AnimatorSet();
		set.playTogether(scaleX, scaleY);
		set.setInterpolator(new AccelerateDecelerateInterpolator());
		return set;
	}

	private static AnimatorSet createFoodAnimator(View target) {
		ObjectAnimator bob = ObjectAnimator.ofFloat(
				target, View.TRANSLATION_Y, 0f, -dp(4), 0f);
		ObjectAnimator rotate = ObjectAnimator.ofFloat(
				target, View.ROTATION, -2.5f, 2.5f, -2.5f);
		ObjectAnimator scaleX = ObjectAnimator.ofFloat(
				target, View.SCALE_X, 1f, 1.06f, 1f);
		ObjectAnimator scaleY = ObjectAnimator.ofFloat(
				target, View.SCALE_Y, 1f, 1.06f, 1f);
		for (ObjectAnimator animator : new ObjectAnimator[]{bob, rotate, scaleX, scaleY}) {
			animator.setRepeatCount(ValueAnimator.INFINITE);
			animator.setDuration(1500);
		}
		AnimatorSet set = new AnimatorSet();
		set.playTogether(bob, rotate, scaleX, scaleY);
		set.setInterpolator(new AccelerateDecelerateInterpolator());
		return set;
	}

	private static void playFullAccessAttention() {
		if (fullAccessGlow == null
				|| fullAccessAnimator != null && fullAccessAnimator.isRunning()) {
			return;
		}
		ObjectAnimator rotate = ObjectAnimator.ofFloat(
				fullAccessGlow, View.ROTATION,
				0f, -5f, 5f, -3f, 3f, 0f);
		ObjectAnimator scaleX = ObjectAnimator.ofFloat(
				fullAccessGlow, View.SCALE_X,
				1f, 1.10f, 0.98f, 1.06f, 1f);
		ObjectAnimator scaleY = ObjectAnimator.ofFloat(
				fullAccessGlow, View.SCALE_Y,
				1f, 1.10f, 0.98f, 1.06f, 1f);
		ObjectAnimator labelPulse = ObjectAnimator.ofFloat(
				fullAccessLabel, View.ALPHA, 1f, 0.62f, 1f);
		fullAccessAnimator = new AnimatorSet();
		fullAccessAnimator.playTogether(rotate, scaleX, scaleY, labelPulse);
		fullAccessAnimator.setDuration(920L);
		fullAccessAnimator.setInterpolator(
				new AccelerateDecelerateInterpolator());
		fullAccessAnimator.start();
	}

	private static void trackFullAccessEvent(String action) {
		try {
			JSONObject actionNode = new JSONObject();
			actionNode.put(action, 1);
			JSONObject buttonNode = new JSONObject();
			buttonNode.put("button", actionNode);
			JSONObject eventNode = new JSONObject();
			eventNode.put("full_access", buttonNode);

			JSONObject context = new JSONObject();
			context.put("premium", YooKassaPay.hasPurchase());
			context.put("singleplayer", singleplayer);
			context.put("survival", survivalMode);
			context.put("source", "game_overlay");

			JSONObject params = new JSONObject();
			params.put("purchase_overlay", eventNode);
			params.put("context", context);
			Analytics.sendEventWithParams(
					"purchase_overlay", params.toString());
			Log.d(TAG, "full_access/button/" + action);
		} catch (Exception e) {
			Log.e(TAG, "Failed to send full access analytics: "
					+ e.getMessage());
		}
	}

	public static void trackAdEvent(int rewardType, String action, String detail) {
		trackEvent(rewardType, "ad", action, detail);
	}

	private static void trackEvent(int rewardType, String category,
			String action, String detail) {
		try {
			String rewardName = rewardType == REWARD_HEALTH ? "health"
					: rewardType == REWARD_FOOD ? "food" : "case";

			// The nested object produces a readable hierarchy in AppMetrica:
			// rewarded_bonus -> health|food -> button|ad|reward -> action.
			JSONObject actionNode = new JSONObject();
			actionNode.put(action, 1);
			JSONObject categoryNode = new JSONObject();
			categoryNode.put(category, actionNode);
			JSONObject rewardNode = new JSONObject();
			rewardNode.put(rewardName, categoryNode);

			JSONObject context = new JSONObject();
			context.put("singleplayer", singleplayer);
			context.put("survival", survivalMode);
			context.put("premium", YooKassaPay.hasPurchase());
			context.put("hp", currentHp);
			context.put("hp_max", currentMaxHp);
			context.put("hunger", currentHunger);
			if (detail != null) {
				context.put("detail", detail);
			}

			JSONObject params = new JSONObject();
			String eventName = rewardType == REWARD_CASE
					? "rewarded_case" : "rewarded_bonus";
			params.put(eventName, rewardNode);
			params.put("context", context);
			Analytics.sendEventWithParams(eventName, params.toString());
			Log.d(TAG, rewardName + "/" + category + "/" + action);
		} catch (Exception e) {
			Log.e(TAG, "Failed to send analytics: " + e.getMessage());
		}
	}

	private static boolean isInternetAvailable() {
		Activity host = activity;
		if (host == null) {
			return false;
		}
		try {
			ConnectivityManager manager =
					(ConnectivityManager) host.getSystemService(Context.CONNECTIVITY_SERVICE);
			if (manager == null) {
				return false;
			}
			Network network = manager.getActiveNetwork();
			NetworkCapabilities capabilities =
					network == null ? null : manager.getNetworkCapabilities(network);
			return capabilities != null
					&& capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
					&& capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
		} catch (Exception e) {
			Log.w(TAG, "Network check failed: " + e.getMessage());
			return false;
		}
	}

	private static int withAlpha(int color, int alpha) {
		return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
	}

	private static int dp(int value) {
		if (activity == null) {
			return value;
		}
		return Math.round(value * activity.getResources().getDisplayMetrics().density);
	}

	private static void destroyInternal() {
		UI_HANDLER.removeCallbacks(FULL_ACCESS_ATTENTION_TICK);
		CaseRewardManager.destroy();
		if (fullAccessAnimator != null) {
			fullAccessAnimator.cancel();
		}
		if (healthAnimator != null) {
			healthAnimator.cancel();
		}
		if (foodAnimator != null) {
			foodAnimator.cancel();
		}
		if (root != null && container != null) {
			try {
				root.removeView(container);
			} catch (Exception ignored) {
			}
		}
		fullAccessAnimator = null;
		healthAnimator = null;
		foodAnimator = null;
		bonusContainer = null;
		navigationColumn = null;
		fullAccessItem = null;
		fullAccessGlow = null;
		fullAccessLabel = null;
		healthItem = null;
		foodItem = null;
		caseItem = null;
		container = null;
		root = null;
		activity = null;
		healthImpressionSent = false;
		foodImpressionSent = false;
		fullAccessImpressionSent = false;
		survivalMode = false;
		rewardFlowInProgress = false;
		purchaseFlowInProgress = false;
	}
}
