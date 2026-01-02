-- Vococraft
-- Premium subscription dialog
-- SPDX-License-Identifier: LGPL-2.1-or-later

-- Beautiful mobile-first subscription offer dialog

local function get_subscription_formspec(data)
	-- Poll all async operations (restore, product info, state changes)
	vococraft_subscription.poll_async_results()
	
	-- Check if purchase operation completed (for async operations)
	-- Always check - even if purchase_in_progress is false, we might have a pending result
	local success, error_msg = vococraft_subscription.check_purchase_result()
	if success ~= nil then
		-- Operation completed
		if success then
			-- Purchase successful! Close this dialog
			data.purchase_success = true
			-- Analytics: successful purchase
			if core.send_analytics_event then
				core.send_analytics_event("subscription_purchase_success", "")
			end
		else
			-- Purchase failed or cancelled
			if error_msg and error_msg ~= "Покупка отменена" then
				data.purchase_error = error_msg
				-- Analytics: failed purchase with reason
				if core.send_analytics_event then
					local params = core.write_json({reason = error_msg})
					core.send_analytics_event("subscription_purchase_failed", params)
				end
			else
				-- Analytics: cancelled purchase
				if core.send_analytics_event then
					core.send_analytics_event("subscription_purchase_cancelled", "")
				end
			end
		end
	end
	
	-- Fixed dimensions for consistent look
	local w = 10
	local h = 6.8
	
	local info = vococraft_subscription.get_info()
	
	-- Premium color palette
	local bg_main = "#1a1a2e"
	local bg_card = "#252547"
	local accent_gold = "#ffc107"
	local text_white = "#ffffff"
	local text_light = "#e8e8e8"
	local text_muted = "#9e9e9e"
	local green_check = "#4caf50"
	
	-- Layout
	local padding = 0.5
	local content_w = w - padding * 2
	
	local formspec = {
		"formspec_version[6]",
		"size[", w, ",", h, "]",
		"position[0.5,0.5]",
		"no_prepend[]",
		
		-- Solid dark background
		"bgcolor[", bg_main, ";true]",
		"box[0,0;", w, ",", h, ";", bg_main, "]",
		
		-- Close button
		"style[btn_close;border=false;bgcolor=#ffffff11;textcolor=#888888]",
		"style[btn_close:hovered;bgcolor=#ffffff33;textcolor=#ffffff]",
		"button[", w - 0.7, ",0.15;0.55,0.55;btn_close;X]",
		
		-- Big star top-left
		"style_type[label;font_size=*3]",
		"label[", padding + 0.1, ",0.7;", core.colorize(accent_gold, "★"), "]",
		"style_type[label;font_size=]",
		
		-- PREMIUM text centered vertically with star
		"style_type[label;font_size=*1.5]",
		"label[", padding + 1.2, ",0.85;", core.colorize(accent_gold, "Расширенная версия"), "]",
		"style_type[label;font_size=]",
		
		-- Features card
		"box[", padding, ",1.4;", content_w, ",2.5;", bg_card, "]",
		
		-- Features with plus icons
		"label[", padding + 0.4, ",1.85;", 
			core.colorize(accent_gold, "+ "),
			core.colorize(text_light, "Установка модов в 1 клик"), "]",
		
		"label[", padding + 0.4, ",2.4;", 
			core.colorize(accent_gold, "+ "),
			core.colorize(text_light, "Игра без рекламы"), "]",
		
		"label[", padding + 0.4, ",2.95;", 
			core.colorize(accent_gold, "+ "),
			core.colorize(text_light, "Эксклюзивный контент"), "]",
		
		"label[", padding + 0.4, ",3.5;", 
			core.colorize(accent_gold, "+ "),
			core.colorize(text_light, "Больше места без баннера"), "]",
		
		-- Pricing section
		"box[", padding, ",4.1;", content_w, ",0.7;", bg_card, "]",
		
		-- Offer (highlighted) - show trial, promo or main price
		"box[", padding + 0.1, ",4.2;", content_w - 0.2, ",0.5;#ffc10733]",
	}
	
	-- Determine what price/period to show
	local offer_price = ""
	local offer_period = ""
	
	core.log("action", "[Vococraft Dialog] has_trial=" .. tostring(info.has_trial) ..
		", trial_days=" .. tostring(info.trial_days) ..
		", has_promo=" .. tostring(info.has_promo) ..
		", promo_days=" .. tostring(info.promo_days) ..
		", promo_price=" .. tostring(info.promo_price_formatted) ..
		", monthly_price=" .. tostring(info.monthly_price_formatted) ..
		", product_info_fetched=" .. tostring(info.product_info_fetched))
	
	-- Check if product info is loaded
	if not info.product_info_fetched then
		-- Product info still loading
		offer_price = "Загрузка..."
		offer_period = ""
	elseif info.has_trial and info.trial_days > 0 then
		-- Free trial period
		offer_price = info.trial_price_formatted ~= "" and info.trial_price_formatted or "Бесплатно"
		offer_period = "/" .. info.trial_days .. " дн. пробный период"
	elseif info.has_promo and info.promo_days > 0 then
		-- Discounted intro period
		offer_price = info.promo_price_formatted
		offer_period = "/" .. info.promo_days .. " дн. стартовый период"
	else
		-- Main price - show fallback if empty
		offer_price = info.monthly_price_formatted ~= "" and info.monthly_price_formatted or "—"
		offer_period = "/месяц"
	end
	
	table.insert(formspec, table.concat({
		"label[", padding + 0.3, ",4.47;",
			core.colorize(accent_gold, "Начни за:  "),
			core.colorize(text_white, offer_price),
			core.colorize(text_muted, offer_period), "]",
		
		-- CTA Button - SOLID PURPLE with box behind
		"box[", padding, ",5.0;", content_w, ",1.05;#9c27b0]",
		"style[btn_subscribe;bgcolor=#9c27b0;border=false;textcolor=#ffffff]",
		"style[btn_subscribe:hovered;bgcolor=#ab47bc]",
		"style[btn_subscribe:pressed;bgcolor=#7b1fa2]",
		"button[", padding, ",5.0;", content_w, ",1.05;btn_subscribe;",
			"★ Получить доступ ★]",
	}))
	
	return table.concat(formspec)
end


local function handle_subscription_buttons(this, fields)
	-- Check for completed async purchase (called when formspec updates)
	if this.data.purchase_success then
		core.log("action", "[Vococraft] Subscription purchased successfully (async)")
		this:delete()
		
		-- If there's a pending package to install, proceed with it
		if this.data.pending_package and this.data.pending_parent then
			local package = this.data.pending_package
			if this.data.original_install_func then
				this.data.original_install_func(this.data.pending_parent, package)
			end
		end
		return true
	end
	
	if this.data.purchase_error then
		gamedata.errormessage = fgettext_ne("Subscription purchase error") .. ": " .. this.data.purchase_error
		this.data.purchase_error = nil
		ui.update()
		return true
	end
	
	if fields.btn_close or fields.quit then
		-- Reset purchase state if user closes dialog during purchase
		vococraft_subscription.purchase_in_progress = false
		this:delete()
		return true
	end
	
	if fields.btn_subscribe then
		-- Check if purchase already in progress
		local info = vococraft_subscription.get_info()
		if info.purchase_in_progress then
			return true -- Ignore if already purchasing
		end
		
		-- Start purchase (async, result will be checked in get_subscription_formspec)
		vococraft_subscription.purchase()
		return true
	end
	
	return false
end


local function subscription_event_handler(self, event)
	if event == "MenuQuit" then
		self:delete()
		return true
	end
	return false
end


--- Create subscription dialog
---@param pending_package table|nil Package that user tried to install
---@param pending_parent table|nil Parent dialog to return to after purchase
---@param original_install_func function|nil Original install function to call after purchase
---@return table dialog
function create_subscription_dialog(pending_package, pending_parent, original_install_func)
	local dlg = dialog_create("subscription_dialog",
		get_subscription_formspec,
		handle_subscription_buttons,
		subscription_event_handler)
	
	dlg.data.pending_package = pending_package
	dlg.data.pending_parent = pending_parent
	dlg.data.original_install_func = original_install_func
	
	-- Listen for product info loaded event to refresh UI
	local listener_id
	listener_id = vococraft_subscription.add_listener(function(event, data)
		if event == "product_info_loaded" then
			core.log("action", "[Vococraft Dialog] Product info loaded, refreshing UI")
			ui.update()
			-- Remove listener after product info is loaded
			-- (listener_id will be used next time dialog is opened if needed)
		end
	end)
	
	-- Store listener_id so we can clean it up if needed
	dlg.data.subscription_listener_id = listener_id
	
	return dlg
end


--- Show subscription dialog with proper parent handling
---@param parent table Parent dialog
---@param pending_package table|nil Package that user tried to install
---@param original_install_func function|nil Original install function to call after purchase
function show_subscription_dialog(parent, pending_package, original_install_func)
	-- Analytics: dialog opened
	if core.send_analytics_event then
		local source = pending_package and "mod_install" or "menu"
		local params = core.write_json({source = source})
		core.send_analytics_event("subscription_dialog_opened", params)
	end
	
	local dlg = create_subscription_dialog(pending_package, parent, original_install_func)
	dlg:set_parent(parent)
	parent:hide()
	dlg:show()
end


core.log("action", "[Vococraft] Subscription dialog module loaded")
