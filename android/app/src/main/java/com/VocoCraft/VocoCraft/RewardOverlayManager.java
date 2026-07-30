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
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
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
	public static final int ACTION_MENU = 10;
	public static final int ACTION_INVENTORY = 11;

	// Four hearts equal eight engine HP/food points.
	private static final int LOW_RESOURCE_THRESHOLD = 8;

	private static Activity activity;
	private static ViewGroup root;
	private static LinearLayout container;
	private static LinearLayout bonusContainer;
	private static LinearLayout navigationColumn;
	private static LinearLayout healthItem;
	private static LinearLayout foodItem;
	private static AnimatorSet healthAnimator;
	private static AnimatorSet foodAnimator;

	private static int currentHp;
	private static int currentMaxHp = 20;
	private static int currentHunger = -1;
	private static boolean singleplayer;
	private static boolean gameplayActive;
	private static boolean healthImpressionSent;
	private static boolean foodImpressionSent;
	private static boolean rewardFlowInProgress;

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
			boolean isSingleplayer, boolean isGameplayActive) {
		currentHp = hp;
		currentMaxHp = Math.max(1, maxHp);
		currentHunger = hunger;
		singleplayer = isSingleplayer;
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

	public static void onRewardFlowFinished(int rewardType, boolean earned) {
		rewardFlowInProgress = false;
		trackEvent(rewardType, "reward", earned ? "earned" : "not_earned", null);
		refreshVisibilityOnUiThread();
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
		bonusContainer.setOrientation(LinearLayout.HORIZONTAL);
		bonusContainer.setGravity(Gravity.TOP | Gravity.END);
		bonusContainer.setClipChildren(false);
		bonusContainer.setClipToPadding(false);

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
		container.setVisibility(View.GONE);
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

	private static void handleRewardClick(int rewardType) {
		if (rewardFlowInProgress || !YandexAds.isRewardedReady() || !isInternetAvailable()) {
			trackEvent(rewardType, "button", "unavailable", null);
			YandexAds.ensureRewardedLoaded();
			refreshVisibility();
			return;
		}

		rewardFlowInProgress = true;
		trackEvent(rewardType, "button", "clicked", "amount_100");
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
		boolean showControls = gameplayActive && !rewardFlowInProgress;
		boolean eligibleContext = singleplayer && showControls;

		if (eligibleContext && hasInternet && (lowHealth || lowFood)
				&& !YandexAds.isRewardedReady()) {
			YandexAds.ensureRewardedLoaded();
		}

		boolean adReady = YandexAds.isRewardedReady();
		boolean showHealth = eligibleContext && hasInternet && adReady && lowHealth;
		boolean showFood = eligibleContext && hasInternet && adReady && lowFood;

		setItemVisible(healthItem, healthAnimator, showHealth, REWARD_HEALTH);
		setItemVisible(foodItem, foodAnimator, showFood, REWARD_FOOD);
		bonusContainer.setVisibility(showHealth || showFood ? View.VISIBLE : View.GONE);
		navigationColumn.setVisibility(showControls ? View.VISIBLE : View.GONE);
		container.setVisibility(showControls ? View.VISIBLE : View.GONE);
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

	public static void trackAdEvent(int rewardType, String action, String detail) {
		trackEvent(rewardType, "ad", action, detail);
	}

	private static void trackEvent(int rewardType, String category,
			String action, String detail) {
		try {
			String rewardName = rewardType == REWARD_HEALTH ? "health" : "food";

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
			context.put("premium", YooKassaPay.hasPurchase());
			context.put("hp", currentHp);
			context.put("hp_max", currentMaxHp);
			context.put("hunger", currentHunger);
			if (detail != null) {
				context.put("detail", detail);
			}

			JSONObject params = new JSONObject();
			params.put("rewarded_bonus", rewardNode);
			params.put("context", context);
			Analytics.sendEventWithParams("rewarded_bonus", params.toString());
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
		healthAnimator = null;
		foodAnimator = null;
		bonusContainer = null;
		navigationColumn = null;
		healthItem = null;
		foodItem = null;
		container = null;
		root = null;
		activity = null;
		healthImpressionSent = false;
		foodImpressionSent = false;
		rewardFlowInProgress = false;
	}
}
