-- Vococraft
-- Subscription management module with RuStore Pay SDK integration
-- SPDX-License-Identifier: LGPL-2.1-or-later

-- === SUBSCRIPTION STATE ===
-- This module manages subscription state for mod installation
-- On Android: Integrates with RuStore Pay SDK for real payments
-- On PC: Emulation mode - resets to false on restart
--
-- NOTE: core.after is NOT available in main menu Lua environment,
-- so we use lazy/synchronous checking instead of polling.

vococraft_subscription = {
	-- Current subscription state (false by default)
	is_subscribed = false,
	
	-- Expiration date (Unix timestamp, 0 if not subscribed)
	expiration_date = 0,
	
	-- Subscription prices (fetched from RuStore on Android, empty until loaded)
	monthly_price_formatted = "",    -- Main subscription price (from Product.amountLabel)
	trial_price_formatted = "",      -- Trial period price (usually "Бесплатно" if price=0)
	trial_days = 0,                  -- Trial duration in days (parsed from ISO 8601)
	promo_price_formatted = "",      -- Promo period price (if available)
	promo_days = 0,                  -- Promo duration in days
	
	-- Price loading state
	prices_loaded = false,
	product_info_fetched = false,
	
	-- Purchase flow state
	purchase_in_progress = false,
	last_error = "",
	
	-- Initialization flag
	initialized = false,
	
	-- Operation result constants (from Java)
	RESULT_NONE = 0,
	RESULT_SUCCESS = 1,
	RESULT_ERROR = 2,
	RESULT_CANCELLED = 3,
	RESULT_NO_INTERNET = 4,
	RESULT_NOT_AVAILABLE = 5,
}

--- Check if we are running on Android
---@return boolean
function vococraft_subscription.is_android()
	return PLATFORM == "Android"
end

--- Initialize subscription system (call on app start)
--- On Android: Restores purchases and fetches product info
function vococraft_subscription.init()
	if vococraft_subscription.initialized then
		return
	end
	vococraft_subscription.initialized = true
	
	core.log("action", "[Vococraft Subscription] Initializing...")
	
	if vococraft_subscription.is_android() then
		-- On Android, restore purchases from RuStore
		core.log("action", "[Vococraft Subscription] Android detected, restoring purchases...")
		
		-- First check cached state
		local has_sub = core.rustore_has_subscription()
		vococraft_subscription.is_subscribed = has_sub
		vococraft_subscription.expiration_date = core.rustore_get_expiration_date()
		
		core.log("action", "[Vococraft Subscription] Cached state: subscribed=" .. 
			tostring(has_sub) .. ", expires=" .. tostring(vococraft_subscription.expiration_date))
		
		-- Then restore from server in background (async, will update cache)
		core.rustore_restore_purchases()
		
		-- Fetch current prices from RuStore (async)
		core.rustore_fetch_product_info()
		
		-- Note: Prices will be loaded lazily when get_info() or update_product_info() is called
	else
		-- PC mode - no real prices available
		vococraft_subscription.prices_loaded = true
		core.log("action", "[Vococraft Subscription] PC mode, no prices available")
	end
end

--- Update product info from RuStore if available (call this periodically or before showing UI)
--- This is a synchronous check - does not block
---@return boolean True if product info is now available
function vococraft_subscription.update_product_info()
	if not vococraft_subscription.is_android() then
		return vococraft_subscription.prices_loaded
	end
	
	if vococraft_subscription.product_info_fetched then
		return true
	end
	
	if core.rustore_is_product_info_fetched() then
		-- Product info is ready, load all values
		vococraft_subscription.monthly_price_formatted = core.rustore_get_monthly_price()
		vococraft_subscription.trial_price_formatted = core.rustore_get_trial_price()
		vococraft_subscription.trial_days = core.rustore_get_trial_days()
		vococraft_subscription.promo_price_formatted = core.rustore_get_promo_price()
		vococraft_subscription.promo_days = core.rustore_get_promo_days()
		vococraft_subscription.product_info_fetched = true
		vococraft_subscription.prices_loaded = true
		
		core.log("action", "[Vococraft Subscription] Product info loaded from RuStore:")
		core.log("action", "  - Monthly price: " .. vococraft_subscription.monthly_price_formatted)
		core.log("action", "  - Trial: " .. vococraft_subscription.trial_days .. " days (" .. 
			vococraft_subscription.trial_price_formatted .. ")")
		if vococraft_subscription.promo_days > 0 then
			core.log("action", "  - Promo: " .. vococraft_subscription.promo_days .. " days (" .. 
				vococraft_subscription.promo_price_formatted .. ")")
		end
		return true
	end
	
	return false
end

--- Update subscription state from RuStore cache (call periodically)
function vococraft_subscription.update_subscription_state()
	if not vococraft_subscription.is_android() then
		return
	end
	
	-- Check if operation completed
	if not core.rustore_is_operation_in_progress() then
		local result = core.rustore_get_last_operation_result()
		if result == vococraft_subscription.RESULT_SUCCESS then
			-- Refresh subscription state from cache
			vococraft_subscription.is_subscribed = core.rustore_has_subscription()
			vococraft_subscription.expiration_date = core.rustore_get_expiration_date()
		end
		-- Clear the result so we don't process it again
		if result ~= vococraft_subscription.RESULT_NONE then
			core.rustore_clear_operation_result()
		end
	end
end

--- Check if user has active subscription
--- Uses cached state, which is updated from RuStore
---@return boolean
function vococraft_subscription.has_subscription()
	if vococraft_subscription.is_android() then
		-- Try to update state first
		vococraft_subscription.update_subscription_state()
		-- Return cached state
		return vococraft_subscription.is_subscribed
	else
		-- PC emulation mode
		return vococraft_subscription.is_subscribed
	end
end

--- Refresh subscription state from RuStore (Android only)
--- Call this when returning to main menu or after time passes
function vococraft_subscription.refresh()
	if not vococraft_subscription.is_android() then
		return
	end
	
	-- Update from cache
	vococraft_subscription.is_subscribed = core.rustore_has_subscription()
	vococraft_subscription.expiration_date = core.rustore_get_expiration_date()
	
	-- Check if cache has expired subscription
	local exp = vococraft_subscription.expiration_date
	local now = os.time()
	
	if vococraft_subscription.is_subscribed and exp > 0 and exp < now then
		-- Subscription has expired
		core.log("action", "[Vococraft Subscription] Subscription expired, clearing")
		vococraft_subscription.is_subscribed = false
		vococraft_subscription.expiration_date = 0
	end
	
	-- Try to update product info
	vococraft_subscription.update_product_info()
end

--- Purchase subscription
--- On Android: Opens RuStore purchase flow
--- On PC: Just sets the emulated state to true
---@param callback function|nil Optional callback(success: boolean, error: string|nil)
function vococraft_subscription.purchase(callback)
	if vococraft_subscription.purchase_in_progress then
		core.log("warning", "[Vococraft Subscription] Purchase already in progress")
		if callback then
			callback(false, "Покупка уже выполняется")
		end
		return
	end
	
	if vococraft_subscription.is_android() then
		-- Start RuStore purchase flow
		core.log("action", "[Vococraft Subscription] Starting purchase flow...")
		vococraft_subscription.purchase_in_progress = true
		vococraft_subscription.purchase_callback = callback
		core.rustore_clear_operation_result()
		core.rustore_purchase_subscription()
		
		-- Note: Result will be checked in check_purchase_result() or update_subscription_state()
		-- UI should call check_purchase_result() periodically while purchase_in_progress is true
	else
		-- PC emulation mode - just enable subscription
		vococraft_subscription.is_subscribed = true
		vococraft_subscription.expiration_date = os.time() + (30 * 24 * 60 * 60) -- 30 days
		core.log("action", "[Vococraft] Subscription emulation: purchased (will reset on restart)")
		if callback then
			callback(true)
		end
	end
end

--- Check purchase result (call this periodically while purchase_in_progress is true)
--- Returns nil if still in progress, true/false when complete
---@return boolean|nil success, string|nil error_message
function vococraft_subscription.check_purchase_result()
	if not vococraft_subscription.purchase_in_progress then
		return nil
	end
	
	if not vococraft_subscription.is_android() then
		return nil
	end
	
	local in_progress = core.rustore_is_operation_in_progress()
	
	if in_progress then
		return nil -- Still waiting
	end
	
	-- Operation complete
	vococraft_subscription.purchase_in_progress = false
	local result = core.rustore_get_last_operation_result()
	local error_msg = core.rustore_get_last_error()
	core.rustore_clear_operation_result()
	
	local callback = vococraft_subscription.purchase_callback
	vococraft_subscription.purchase_callback = nil
	
	if result == vococraft_subscription.RESULT_SUCCESS then
		-- Purchase successful!
		vococraft_subscription.is_subscribed = core.rustore_has_subscription()
		vococraft_subscription.expiration_date = core.rustore_get_expiration_date()
		core.log("action", "[Vococraft Subscription] Purchase successful!")
		if callback then
			callback(true)
		end
		return true, nil
	elseif result == vococraft_subscription.RESULT_CANCELLED then
		core.log("action", "[Vococraft Subscription] Purchase cancelled by user")
		if callback then
			callback(false, "Покупка отменена")
		end
		return false, "Покупка отменена"
	elseif result == vococraft_subscription.RESULT_NOT_AVAILABLE then
		core.log("warning", "[Vococraft Subscription] Purchase not available: " .. error_msg)
		local msg = error_msg ~= "" and error_msg or "Покупки недоступны"
		if callback then
			callback(false, msg)
		end
		return false, msg
	else
		core.log("warning", "[Vococraft Subscription] Purchase failed: " .. error_msg)
		local msg = error_msg ~= "" and error_msg or "Ошибка покупки"
		if callback then
			callback(false, msg)
		end
		return false, msg
	end
end

--- Restore subscription (for Android - restore previous purchases)
---@param callback function|nil Optional callback(success: boolean, found_subscription: boolean)
function vococraft_subscription.restore(callback)
	if vococraft_subscription.is_android() then
		core.log("action", "[Vococraft Subscription] Restoring purchases...")
		vococraft_subscription.restore_callback = callback
		vococraft_subscription.restore_in_progress = true
		core.rustore_clear_operation_result()
		core.rustore_restore_purchases()
		
		-- Result will be checked in check_restore_result()
	else
		-- PC doesn't support restore
		if callback then
			callback(false, false)
		end
	end
end

--- Check restore result (call this periodically while restore_in_progress is true)
---@return boolean|nil complete, boolean|nil success, boolean|nil found_subscription
function vococraft_subscription.check_restore_result()
	if not vococraft_subscription.restore_in_progress then
		return nil
	end
	
	if not vococraft_subscription.is_android() then
		return nil
	end
	
	local in_progress = core.rustore_is_operation_in_progress()
	
	if in_progress then
		return nil -- Still waiting
	end
	
	-- Operation complete
	vococraft_subscription.restore_in_progress = false
	local result = core.rustore_get_last_operation_result()
	core.rustore_clear_operation_result()
	
	local callback = vococraft_subscription.restore_callback
	vococraft_subscription.restore_callback = nil
	
	if result == vococraft_subscription.RESULT_SUCCESS then
		-- Refresh state from cache
		local was_subscribed = vococraft_subscription.is_subscribed
		vococraft_subscription.is_subscribed = core.rustore_has_subscription()
		vococraft_subscription.expiration_date = core.rustore_get_expiration_date()
		
		local found = vococraft_subscription.is_subscribed and not was_subscribed
		core.log("action", "[Vococraft Subscription] Restore complete, found=" .. 
			tostring(found) .. ", subscribed=" .. tostring(vococraft_subscription.is_subscribed))
		
		if callback then
			callback(true, found)
		end
		return true, true, found
	else
		local error_msg = core.rustore_get_last_error()
		core.log("warning", "[Vococraft Subscription] Restore failed: " .. error_msg)
		if callback then
			callback(false, false)
		end
		return true, false, false
	end
end

--- Get subscription info for display
---@return table
function vococraft_subscription.get_info()
	-- Try to update product info before returning
	vococraft_subscription.update_product_info()
	-- Also update subscription state
	vococraft_subscription.update_subscription_state()
	
	return {
		-- Prices from RuStore
		monthly_price_formatted = vococraft_subscription.monthly_price_formatted,
		trial_price_formatted = vococraft_subscription.trial_price_formatted,
		trial_days = vococraft_subscription.trial_days,
		promo_price_formatted = vococraft_subscription.promo_price_formatted,
		promo_days = vococraft_subscription.promo_days,
		
		-- Subscription state
		is_subscribed = vococraft_subscription.has_subscription(),
		expiration_date = vococraft_subscription.expiration_date,
		
		-- Status flags
		is_android = vococraft_subscription.is_android(),
		prices_loaded = vococraft_subscription.prices_loaded,
		product_info_fetched = vococraft_subscription.product_info_fetched,
		purchase_in_progress = vococraft_subscription.purchase_in_progress,
		
		-- Helper for UI: has trial period?
		has_trial = vococraft_subscription.trial_days > 0,
		-- Helper for UI: has promo period?
		has_promo = vococraft_subscription.promo_days > 0,
	}
end

--- Get formatted expiration date string
---@return string|nil
function vococraft_subscription.get_expiration_string()
	if not vococraft_subscription.is_subscribed or vococraft_subscription.expiration_date == 0 then
		return nil
	end
	
	return os.date("%d.%m.%Y", vococraft_subscription.expiration_date)
end

--- Clear subscription cache (for testing/debugging)
function vococraft_subscription.clear_cache()
	if vococraft_subscription.is_android() then
		core.rustore_clear_cache()
	end
	vococraft_subscription.is_subscribed = false
	vococraft_subscription.expiration_date = 0
	core.log("action", "[Vococraft Subscription] Cache cleared")
end

-- Initialize on load
core.log("action", "[Vococraft] Subscription module loaded")
