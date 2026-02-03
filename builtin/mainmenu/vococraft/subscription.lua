-- Vococraft
-- Purchase management module with YooKassa SDK integration
-- SPDX-License-Identifier: LGPL-2.1-or-later
--
-- NOTE: This file is named subscription.lua for backwards compatibility
-- but manages ONE-TIME PURCHASE (not subscription) for full version access.
-- The global is vococraft_subscription for backwards compat, but also
-- aliased as vococraft_purchase.
--
-- === PURCHASE STATE ===
-- This module manages one-time purchase state for full version access
-- On Android: Integrates with YooKassa SDK for real payments  
-- On PC: Emulation mode - resets to false on restart

vococraft_subscription = {
	-- Current purchase state (false by default)
	is_subscribed = false,  -- Kept as is_subscribed for backwards compatibility
	
	-- Purchase date (Unix timestamp, 0 if not purchased)
	purchase_date = 0,
	
	-- Product info (fetched from backend on Android)
	product_price_formatted = "Загрузка...",
	product_amount = "249",      -- Raw amount for purchase (e.g. "249")
	product_currency = "RUB",    -- Currency for purchase (e.g. "RUB")
	
	-- Device UUID for backend API
	device_uuid = "",
	
	-- Price loading state
	prices_loaded = false,
	product_info_fetched = false,
	
	-- Purchase flow state
	purchase_in_progress = false,
	last_error = "",
	
	-- Restore flow state
	restore_in_progress = false,
	restore_callback = nil,
	
	-- Async polling state
	poll_attempt = 0,
	
	-- Pending purchase result (set when operation completes, cleared when formspec reads it)
	pending_purchase_success = nil,
	pending_purchase_error = nil,
	
	-- Initialization flag
	initialized = false,
	
	-- Event listeners for purchase state changes
	listeners = {},
	
	-- Operation result constants (from Java)
	RESULT_NONE = 0,
	RESULT_SUCCESS = 1,
	RESULT_ERROR = 2,
	RESULT_CANCELLED = 3,
	RESULT_PENDING_CONFIRMATION = 4,
	RESULT_CONFIRMATION_NEEDED = 5,
}

--- Register a listener for purchase state changes
---@param listener function Callback function(event_type, data)
---@return number listener_id ID to use for unregistering
function vococraft_subscription.add_listener(listener)
	local id = #vococraft_subscription.listeners + 1
	vococraft_subscription.listeners[id] = listener
	core.log("action", "[Vococraft Purchase] Added listener #" .. id)
	return id
end

--- Unregister a listener
---@param listener_id number ID returned by add_listener
function vococraft_subscription.remove_listener(listener_id)
	if vococraft_subscription.listeners[listener_id] then
		vococraft_subscription.listeners[listener_id] = nil
		core.log("action", "[Vococraft Purchase] Removed listener #" .. listener_id)
	end
end

--- Notify all listeners of an event
---@param event_type string Event type
---@param data table|nil Optional event data
local function notify_listeners(event_type, data)
	core.log("action", "[Vococraft Purchase] Notifying listeners: " .. event_type)
	for id, listener in pairs(vococraft_subscription.listeners) do
		local ok, err = pcall(listener, event_type, data)
		if not ok then
			core.log("warning", "[Vococraft Purchase] Listener #" .. id .. " error: " .. tostring(err))
		end
	end
end

--- Check if we are running on Android
---@return boolean
function vococraft_subscription.is_android()
	return PLATFORM == "Android"
end

--- Get device UUID for backend API
---@return string
function vococraft_subscription.get_device_uuid()
	if vococraft_subscription.device_uuid ~= "" then
		return vococraft_subscription.device_uuid
	end
	
	if vococraft_subscription.is_android() then
		vococraft_subscription.device_uuid = core.yookassa_get_device_uuid()
	else
		-- PC emulation - generate random UUID
		vococraft_subscription.device_uuid = "pc-emulation-" .. os.time()
	end
	
	core.log("action", "[Vococraft Purchase] Device UUID: " .. 
		vococraft_subscription.device_uuid:sub(1, 8) .. "...")
	return vococraft_subscription.device_uuid
end

--- Initialize purchase system (call on app start)
function vococraft_subscription.init()
	if vococraft_subscription.initialized then
		return
	end
	vococraft_subscription.initialized = true
	
	core.log("action", "[Vococraft Purchase] Initializing...")
	
	if vococraft_subscription.is_android() then
		-- On Android, restore purchases from backend
		core.log("action", "[Vococraft Purchase] Android detected, restoring purchases...")
		
		-- First check cached state
		local has_purchase = core.yookassa_has_purchase()
		vococraft_subscription.is_subscribed = has_purchase
		
		core.log("action", "[Vococraft Purchase] Cached state: purchased=" .. tostring(has_purchase))
		
		-- Get device UUID
		vococraft_subscription.get_device_uuid()
		
		-- Then restore from server in background (async)
		vococraft_subscription.restore_in_progress = true
		core.yookassa_restore_purchases()
		
		-- Fetch current price from backend (async)
		core.yookassa_fetch_product_info()
		
		-- Start async polling for results
		vococraft_subscription.start_async_polling()
	else
		-- PC mode - use test price with word
		vococraft_subscription.product_price_formatted = "249 рублей"
		vococraft_subscription.product_amount = "249"
		vococraft_subscription.product_currency = "RUB"
		vococraft_subscription.prices_loaded = true
		vococraft_subscription.product_info_fetched = true
		core.log("action", "[Vococraft Purchase] PC mode, using test price")
	end
end

--- Start async polling for purchase restore results
function vococraft_subscription.start_async_polling()
	if not vococraft_subscription.is_android() then
		return
	end
	
	-- Only poll if we have pending async operations
	if not vococraft_subscription.restore_in_progress and 
	   vococraft_subscription.product_info_fetched then
		core.log("action", "[Vococraft Purchase] No pending async operations, stopping poll")
		return
	end
	
	core.log("action", "[Vococraft Purchase] Starting async poll...")
	
	core.handle_async(
		function(param)
			local start = os.clock()
			while os.clock() - start < 0.5 do end -- ~500ms delay
			return param
		end,
		{ attempt = (vococraft_subscription.poll_attempt or 0) + 1 },
		function(result)
			vococraft_subscription.poll_attempt = result.attempt
			
			-- Check for results
			vococraft_subscription.poll_async_results()
			
			core.log("action", "[Vococraft Purchase] Poll #" .. result.attempt .. 
				", restore_in_progress=" .. tostring(vococraft_subscription.restore_in_progress) ..
				", product_fetched=" .. tostring(vococraft_subscription.product_info_fetched) ..
				", purchased=" .. tostring(vococraft_subscription.is_subscribed))
			
			-- Continue polling if still have pending operations (max 20 attempts = 10 seconds)
			if result.attempt < 20 and 
			   (vococraft_subscription.restore_in_progress or 
			    not vococraft_subscription.product_info_fetched) then
				vococraft_subscription.start_async_polling()
			else
				core.log("action", "[Vococraft Purchase] Async polling finished")
			end
		end
	)
end

--- Update product info from YooKassa backend if available
---@return boolean True if product info is now available
function vococraft_subscription.update_product_info()
	if not vococraft_subscription.is_android() then
		return vococraft_subscription.prices_loaded
	end
	
	if vococraft_subscription.product_info_fetched then
		return true
	end
	
	if core.yookassa_is_product_info_fetched() then
		-- Product info is ready, load values
		local price = core.yookassa_get_product_price()
		if price ~= "" then
			vococraft_subscription.product_price_formatted = price
		end
		
		-- Load amount and currency for purchase
		local amount = core.yookassa_get_product_amount()
		if amount ~= "" then
			vococraft_subscription.product_amount = amount
		end
		
		local currency = core.yookassa_get_product_currency()
		if currency ~= "" then
			vococraft_subscription.product_currency = currency
		end
		
		vococraft_subscription.product_info_fetched = true
		vococraft_subscription.prices_loaded = true
		
		core.log("action", "[Vococraft Purchase] Product info loaded: " .. 
			vococraft_subscription.product_price_formatted .. " (" ..
			vococraft_subscription.product_amount .. " " ..
			vococraft_subscription.product_currency .. ")")
		
		-- Notify listeners that product info is now available
		notify_listeners("product_info_loaded", {
			price = vococraft_subscription.product_price_formatted,
			amount = vococraft_subscription.product_amount,
			currency = vococraft_subscription.product_currency,
		})
		
		return true
	end
	
	return false
end

--- Update purchase state from cache
---@return boolean state_changed
function vococraft_subscription.update_purchase_state()
	if not vococraft_subscription.is_android() then
		return false
	end
	
	-- Don't process results while purchase is in progress
	if vococraft_subscription.purchase_in_progress then
		return false
	end
	
	local state_changed = false
	
	-- Check if operation completed
	if not core.yookassa_is_operation_in_progress() then
		local result = core.yookassa_get_last_operation_result()
		if result == vococraft_subscription.RESULT_SUCCESS then
			-- Refresh purchase state from cache
			local old_purchased = vococraft_subscription.is_subscribed
			vococraft_subscription.is_subscribed = core.yookassa_has_purchase()
			
			-- Check if state actually changed
			if old_purchased ~= vococraft_subscription.is_subscribed then
				state_changed = true
				core.log("action", "[Vococraft Purchase] State changed: " .. 
					tostring(old_purchased) .. " -> " .. tostring(vococraft_subscription.is_subscribed))
				
				-- Notify listeners
				notify_listeners("state_changed", {
					is_purchased = vococraft_subscription.is_subscribed,
				})
			end
		end
		-- Clear the result so we don't process it again
		if result ~= vococraft_subscription.RESULT_NONE then
			core.yookassa_clear_operation_result()
		end
	end
	
	return state_changed
end

--- Check if user has purchased full version
---@return boolean
function vococraft_subscription.has_subscription()
	if vococraft_subscription.is_android() then
		-- Try to update state first
		vococraft_subscription.update_purchase_state()
		-- Return cached state
		return vococraft_subscription.is_subscribed
	else
		-- PC emulation mode
		return vococraft_subscription.is_subscribed
	end
end

--- Refresh purchase state from backend (Android only)
function vococraft_subscription.refresh()
	if not vococraft_subscription.is_android() then
		return
	end
	
	-- Update from cache
	vococraft_subscription.is_subscribed = core.yookassa_has_purchase()
	
	-- Try to update product info
	vococraft_subscription.update_product_info()
end

--- Purchase full version
--- On Android: Opens YooKassa purchase flow
--- On PC: Just sets the emulated state to true
---@param callback function|nil Optional callback(success: boolean, error: string|nil)
function vococraft_subscription.purchase(callback)
	if vococraft_subscription.purchase_in_progress then
		core.log("warning", "[Vococraft Purchase] Purchase already in progress")
		if callback then
			callback(false, "Покупка уже выполняется")
		end
		return
	end
	
	if vococraft_subscription.is_android() then
		-- Check if product info is loaded
		if not vococraft_subscription.product_info_fetched then
			core.log("warning", "[Vococraft Purchase] Product info not loaded yet")
			if callback then
				callback(false, "Информация о продукте ещё загружается")
			end
			return
		end
		
		-- Start YooKassa purchase flow
		core.log("action", "[Vococraft Purchase] Starting purchase flow with amount: " ..
			vococraft_subscription.product_amount .. " " .. vococraft_subscription.product_currency)
		vococraft_subscription.purchase_in_progress = true
		vococraft_subscription.purchase_callback = callback
		core.yookassa_clear_operation_result()
		
		-- Start tokenization with YooKassa SDK
		-- Parameters: amount, currency, title, description
		-- Use loaded product amount and currency from backend
		core.yookassa_start_purchase(
			vococraft_subscription.product_amount,
			vococraft_subscription.product_currency,
			"VocoCraft Полная версия",
			"Разблокировка всех функций без рекламы"
		)
		
		-- Note: Result will be checked in check_purchase_result() or update_purchase_state()
	else
		-- PC emulation mode - just enable purchase
		vococraft_subscription.is_subscribed = true
		vococraft_subscription.purchase_date = os.time()
		core.log("action", "[Vococraft] Purchase emulation: purchased (will reset on restart)")
		if callback then
			callback(true)
		end
	end
end

--- Check purchase result (call this periodically while purchase_in_progress is true)
---@return boolean|nil success, string|nil error_message
function vococraft_subscription.check_purchase_result()
	-- Check if we have a stored pending result
	if vococraft_subscription.pending_purchase_success then
		vococraft_subscription.pending_purchase_success = nil
		return true, nil
	end
	if vococraft_subscription.pending_purchase_error then
		local err = vococraft_subscription.pending_purchase_error
		vococraft_subscription.pending_purchase_error = nil
		return false, err
	end
	
	if not vococraft_subscription.purchase_in_progress then
		return nil
	end
	
	if not vococraft_subscription.is_android() then
		return nil
	end
	
	local in_progress = core.yookassa_is_operation_in_progress()
	
	if in_progress then
		return nil -- Still waiting
	end
	
	-- Operation complete
	vococraft_subscription.purchase_in_progress = false
	local result = core.yookassa_get_last_operation_result()
	local error_msg = core.yookassa_get_last_error()
	core.yookassa_clear_operation_result()
	
	local callback = vococraft_subscription.purchase_callback
	vococraft_subscription.purchase_callback = nil
	
	if result == vococraft_subscription.RESULT_SUCCESS then
		-- Purchase successful!
		vococraft_subscription.pending_purchase_success = true
		vococraft_subscription.is_subscribed = core.yookassa_has_purchase()
		core.log("action", "[Vococraft Purchase] Purchase successful!")
		
		-- Notify listeners about successful purchase
		notify_listeners("purchase_complete", {
			success = true,
			is_purchased = vococraft_subscription.is_subscribed,
		})
		
		if callback then
			callback(true)
		end
		return true, nil
	elseif result == vococraft_subscription.RESULT_CANCELLED then
		core.log("action", "[Vococraft Purchase] Purchase cancelled by user")
		if callback then
			callback(false, "Покупка отменена")
		end
		return false, "Покупка отменена"
	elseif result == vococraft_subscription.RESULT_CONFIRMATION_NEEDED then
		-- Need to start confirmation (3DS/SBP/SberPay)
		core.log("action", "[Vococraft Purchase] Confirmation needed, starting...")
		local confirm_url = core.yookassa_get_pending_confirmation_url()
		local payment_method = core.yookassa_get_pending_payment_method_type()
		if confirm_url ~= "" then
			vococraft_subscription.purchase_in_progress = true
			core.yookassa_start_confirmation(confirm_url, payment_method)
		end
		return nil -- Still in progress
	else
		core.log("warning", "[Vococraft Purchase] Purchase failed: " .. error_msg)
		local msg = error_msg ~= "" and error_msg or "Ошибка покупки"
		vococraft_subscription.pending_purchase_error = msg
		if callback then
			callback(false, msg)
		end
		return false, msg
	end
end

--- Restore purchases (for Android - restore previous purchases from backend)
---@param callback function|nil Optional callback(success: boolean, found_purchase: boolean)
function vococraft_subscription.restore(callback)
	if vococraft_subscription.is_android() then
		core.log("action", "[Vococraft Purchase] Restoring purchases...")
		vococraft_subscription.restore_callback = callback
		vococraft_subscription.restore_in_progress = true
		core.yookassa_clear_operation_result()
		core.yookassa_restore_purchases()
	else
		-- PC doesn't support restore
		if callback then
			callback(false, false)
		end
	end
end

--- Check restore result (call this periodically while restore_in_progress is true)
---@return boolean|nil complete, boolean|nil success, boolean|nil found_purchase
function vococraft_subscription.check_restore_result()
	if not vococraft_subscription.restore_in_progress then
		return nil
	end
	
	if not vococraft_subscription.is_android() then
		return nil
	end
	
	local in_progress = core.yookassa_is_operation_in_progress()
	
	if in_progress then
		return nil -- Still waiting
	end
	
	-- Operation complete
	vococraft_subscription.restore_in_progress = false
	local result = core.yookassa_get_last_operation_result()
	core.yookassa_clear_operation_result()
	
	local callback = vococraft_subscription.restore_callback
	vococraft_subscription.restore_callback = nil
	
	if result == vococraft_subscription.RESULT_SUCCESS then
		-- Refresh state from cache
		local was_purchased = vococraft_subscription.is_subscribed
		vococraft_subscription.is_subscribed = core.yookassa_has_purchase()
		
		local found = vococraft_subscription.is_subscribed and not was_purchased
		core.log("action", "[Vococraft Purchase] Restore complete: was_purchased=" .. 
			tostring(was_purchased) .. ", now_purchased=" .. tostring(vococraft_subscription.is_subscribed) ..
			", found=" .. tostring(found))
		
		-- Notify listeners
		notify_listeners("restore_complete", {
			success = true,
			found_purchase = found,
			is_purchased = vococraft_subscription.is_subscribed,
		})
		
		notify_listeners("state_changed", {
			is_purchased = vococraft_subscription.is_subscribed,
		})
		
		if callback then
			callback(true, found)
		end
		return true, true, found
	else
		local error_msg = core.yookassa_get_last_error()
		core.log("warning", "[Vococraft Purchase] Restore failed: " .. error_msg)
		if callback then
			callback(false, false)
		end
		return true, false, false
	end
end

--- Get purchase info for display
---@return table
function vococraft_subscription.get_info()
	-- Try to update product info before returning
	vococraft_subscription.update_product_info()
	-- Also update purchase state
	vococraft_subscription.update_purchase_state()
	
	return {
		-- Price from backend
		price_formatted = vococraft_subscription.product_price_formatted,
		-- For backwards compatibility (subscription dialog uses these)
		monthly_price_formatted = vococraft_subscription.product_price_formatted,
		trial_price_formatted = "",
		trial_days = 0,
		promo_price_formatted = "",
		promo_days = 0,
		
		-- Purchase state
		is_subscribed = vococraft_subscription.has_subscription(),
		is_purchased = vococraft_subscription.has_subscription(),
		
		-- Status flags
		is_android = vococraft_subscription.is_android(),
		prices_loaded = vococraft_subscription.prices_loaded,
		product_info_fetched = vococraft_subscription.product_info_fetched,
		purchase_in_progress = vococraft_subscription.purchase_in_progress,
		
		-- No trial/promo for one-time purchase
		has_trial = false,
		has_promo = false,
	}
end

--- Clear purchase cache (for testing/debugging)
function vococraft_subscription.clear_cache()
	if vococraft_subscription.is_android() then
		core.yookassa_clear_cache()
	end
	vococraft_subscription.is_subscribed = false
	vococraft_subscription.purchase_date = 0
	core.log("action", "[Vococraft Purchase] Cache cleared")
end

--- Poll async results and update state.
---@return boolean True if any async operation completed
function vococraft_subscription.poll_async_results()
	if not vococraft_subscription.is_android() then
		return false
	end
	
	local any_completed = false
	
	-- Check for restore completion
	if vococraft_subscription.restore_in_progress then
		local complete, success, found = vococraft_subscription.check_restore_result()
		if complete then
			any_completed = true
		end
	end
	
	-- Also check generic state updates
	local state_changed = vococraft_subscription.update_purchase_state()
	if state_changed then
		any_completed = true
	end
	
	-- Check for product info
	vococraft_subscription.update_product_info()
	
	return any_completed
end

-- Alias for semantic correctness (this is a purchase, not subscription)
vococraft_purchase = vococraft_subscription

-- Initialize on load
core.log("action", "[Vococraft] Purchase module loaded (YooKassa)")
