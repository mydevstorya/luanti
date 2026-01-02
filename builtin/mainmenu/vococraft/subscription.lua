-- Vococraft
-- Subscription management module
-- SPDX-License-Identifier: LGPL-2.1-or-later

-- === SUBSCRIPTION STATE ===
-- This module manages subscription state for mod installation
-- On Android: Will integrate with RuStore in-app purchases
-- On PC: Emulation mode - resets to false on restart

vococraft_subscription = {
	-- Current subscription state (false by default, resets on restart for PC)
	is_subscribed = false,
	
	-- Subscription prices (default values, will be updated from RuStore on Android)
	-- These are fallback prices shown before RuStore prices are loaded
	monthly_price = 249,
	monthly_price_formatted = "249 Руб",
	trial_price = 99,
	trial_price_formatted = "99 Руб",
	trial_days = 7,
	
	-- Price loading state
	prices_loaded = false,
}

--- Check if we are running on Android
---@return boolean
function vococraft_subscription.is_android()
	return PLATFORM == "Android"
end

--- Check if user has active subscription
--- On Android: Will call RuStore API
--- On PC: Returns emulated state
---@return boolean
function vococraft_subscription.has_subscription()
	if vococraft_subscription.is_android() then
		-- TODO: Implement RuStore subscription check
		-- core.rustore_check_subscription() -- будущий API вызов
		return vococraft_subscription.is_subscribed
	else
		-- PC emulation mode
		return vococraft_subscription.is_subscribed
	end
end

--- Load prices from RuStore (Android only)
--- On PC: Uses default prices
---@param callback function|nil Optional callback(success: boolean)
function vococraft_subscription.load_prices(callback)
	if vococraft_subscription.is_android() then
		-- TODO: Implement RuStore price fetching
		-- core.rustore_get_subscription_info(function(info)
		--     if info then
		--         vococraft_subscription.monthly_price = info.monthly_price
		--         vococraft_subscription.monthly_price_formatted = info.monthly_price_formatted
		--         vococraft_subscription.trial_price = info.trial_price
		--         vococraft_subscription.trial_price_formatted = info.trial_price_formatted
		--         vococraft_subscription.prices_loaded = true
		--         if callback then callback(true) end
		--     else
		--         if callback then callback(false) end
		--     end
		-- end)
		
		-- For now, use default prices
		vococraft_subscription.prices_loaded = true
		if callback then
			callback(true)
		end
	else
		-- PC uses default prices
		vococraft_subscription.prices_loaded = true
		if callback then
			callback(true)
		end
	end
end

--- Purchase subscription
--- On Android: Will open RuStore purchase flow
--- On PC: Just sets the emulated state to true
---@param callback function|nil Optional callback(success: boolean)
function vococraft_subscription.purchase(callback)
	if vococraft_subscription.is_android() then
		-- TODO: Implement RuStore purchase flow
		-- core.rustore_purchase_subscription(function(success)
		--     if success then
		--         vococraft_subscription.is_subscribed = true
		--     end
		--     if callback then callback(success) end
		-- end)
		
		-- For now, simulate successful purchase
		vococraft_subscription.is_subscribed = true
		if callback then
			callback(true)
		end
	else
		-- PC emulation mode - just enable subscription
		vococraft_subscription.is_subscribed = true
		core.log("action", "[Vococraft] Subscription emulation: purchased (will reset on restart)")
		if callback then
			callback(true)
		end
	end
end

--- Restore subscription (for Android - restore previous purchases)
---@param callback function|nil Optional callback(success: boolean)
function vococraft_subscription.restore(callback)
	if vococraft_subscription.is_android() then
		-- TODO: Implement RuStore restore purchases
		-- core.rustore_restore_purchases(function(success)
		--     if success then
		--         vococraft_subscription.is_subscribed = true
		--     end
		--     if callback then callback(success) end
		-- end)
		if callback then
			callback(false)
		end
	else
		-- PC doesn't support restore
		if callback then
			callback(false)
		end
	end
end

--- Get subscription info for display
---@return table
function vococraft_subscription.get_info()
	return {
		monthly_price = vococraft_subscription.monthly_price,
		monthly_price_formatted = vococraft_subscription.monthly_price_formatted,
		trial_price = vococraft_subscription.trial_price,
		trial_price_formatted = vococraft_subscription.trial_price_formatted,
		trial_days = vococraft_subscription.trial_days,
		is_subscribed = vococraft_subscription.has_subscription(),
		is_android = vococraft_subscription.is_android(),
		prices_loaded = vococraft_subscription.prices_loaded,
	}
end

core.log("action", "[Vococraft] Subscription module loaded")
