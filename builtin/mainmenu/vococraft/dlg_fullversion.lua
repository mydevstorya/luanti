-- Vococraft
-- Full Version purchase dialog (one-time purchase via YooKassa)
-- SPDX-License-Identifier: LGPL-2.1-or-later

-- Beautiful mobile-first one-time purchase offer dialog

-- Product ID constant
local PRODUCT_ID = "vococraft_full_version"

-- Helper function to send full-version-window analytics (dialog open event)
local function send_window_analytics(event_name, source)
	local params = {}
	if source then
		params.source = source
	end
	local json_params = core.write_json(params)
	core.log("action", "[Vococraft Analytics] Sending full-version-window/" .. event_name .. ": " .. json_params)
	if core.send_analytics_event then
		local res = core.send_analytics_event("full-version-window", json_params)
		core.log("action", "[Vococraft Analytics] Result: " .. tostring(res))
	else
		core.log("warning", "[Vococraft Analytics] core.send_analytics_event is nil!")
	end
end

-- Helper function to send purchase analytics
-- Event: "purchase" with child params:
--   success -> {id}
--   failed -> {id, reason}
--   canceled -> {id, reason (optional)}
local function send_purchase_analytics(result_type, product_id, reason)
	local params = {}
	
	if result_type == "success" then
		params.success = { id = product_id }
	elseif result_type == "failed" then
		params.failed = { id = product_id, reason = reason or "unknown" }
	elseif result_type == "canceled" then
		params.canceled = { id = product_id }
		if reason then
			params.canceled.reason = reason
		end
	end
	
	local json_params = core.write_json(params)
	core.log("action", "[Vococraft Analytics] Sending purchase: " .. json_params)
	if core.send_analytics_event then
		local res = core.send_analytics_event("purchase", json_params)
		core.log("action", "[Vococraft Analytics] Result: " .. tostring(res))
	else
		core.log("warning", "[Vococraft Analytics] core.send_analytics_event is nil!")
	end
end

local function get_fullversion_formspec(data)
	-- Note: purchase result checking is now handled in fullversion_event_handler
	-- when "Refresh" event is received, to avoid race conditions
	
	-- Poll other async operations (restore, product info, state changes)
	vococraft_subscription.poll_async_results()
	
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
		"label[", padding + 1.2, ",0.85;", core.colorize(accent_gold, "Полная версия"), "]",
		"style_type[label;font_size=]",
		
		-- Features card
		"box[", padding, ",1.4;", content_w, ",2.5;", bg_card, "]",
		
		-- Features with plus icons
		"label[", padding + 0.4, ",1.85;", 
			core.colorize(accent_gold, "+ "),
			core.colorize(text_light, "Установка модов в 1 клик"), "]",
		
		"label[", padding + 0.4, ",2.4;", 
			core.colorize(accent_gold, "+ "),
			core.colorize(text_light, "Игра без рекламы навсегда"), "]",
		
		"label[", padding + 0.4, ",2.95;", 
			core.colorize(accent_gold, "+ "),
			core.colorize(text_light, "Эксклюзивный контент"), "]",
		
		"label[", padding + 0.4, ",3.5;", 
			core.colorize(accent_gold, "+ "),
			core.colorize(text_light, "Разовая покупка — навсегда"), "]",
		
		-- Pricing section
		"box[", padding, ",4.1;", content_w, ",0.7;", bg_card, "]",
		
		-- Offer (highlighted) - show price
		"box[", padding + 0.1, ",4.2;", content_w - 0.2, ",0.5;#ffc10733]",
	}
	
	-- Determine what price to show
	local offer_price = ""
	
	core.log("action", "[Vococraft Dialog] price=" .. tostring(info.price_formatted) ..
		", product_info_fetched=" .. tostring(info.product_info_fetched))
	
	-- Check if product info is loaded
	if not info.product_info_fetched then
		-- Product info still loading
		offer_price = "Загрузка..."
	else
		-- One-time purchase price
		offer_price = info.price_formatted ~= "" and info.price_formatted or "249 ₽"
	end
	
	table.insert(formspec, table.concat({
		"label[", padding + 0.3, ",4.47;",
			core.colorize(accent_gold, "Цена:  "),
			core.colorize(text_white, offer_price), "]",
		
		-- CTA Button - SOLID PURPLE with box behind
		"box[", padding, ",5.0;", content_w, ",1.05;#9c27b0]",
		"style[btn_subscribe;bgcolor=#9c27b0;border=false;textcolor=#ffffff]",
		"style[btn_subscribe:hovered;bgcolor=#ab47bc]",
		"style[btn_subscribe:pressed;bgcolor=#7b1fa2]",
		"button[", padding, ",5.0;", content_w, ",1.05;btn_subscribe;",
		"★ Купить полную версию ★]",
	}))
	
	return table.concat(formspec)
end


local function handle_fullversion_buttons(this, fields)
	-- Check for completed async purchase (called when formspec updates)
	if this.data.purchase_success then
		core.log("action", "[Vococraft] Purchase completed successfully (async)")
		
		-- Send success analytics
		send_purchase_analytics("success", PRODUCT_ID)
		
		current_fullversion_dialog = nil  -- Clear reference
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
		-- Send failed analytics
		send_purchase_analytics("failed", PRODUCT_ID, this.data.purchase_error)
		
		gamedata.errormessage = fgettext_ne("Purchase error") .. ": " .. this.data.purchase_error
		this.data.purchase_error = nil
		ui.update()
		return true
	end
	
	if fields.btn_close or fields.quit then
		-- Reset purchase state if user closes dialog during purchase
		vococraft_subscription.purchase_in_progress = false
		current_fullversion_dialog = nil  -- Clear reference
		this:delete()
		return true
	end
	
	if fields.btn_subscribe then
		-- Check if purchase already in progress
		local info = vococraft_subscription.get_info()
		if info.purchase_in_progress then
			return true -- Ignore if already purchasing
		end
		
		-- Start purchase (async, result will be checked in get_fullversion_formspec)
		vococraft_subscription.purchase()
		return true
	end
	
	return false
end


-- Store reference to dialog for use in event handler
-- (user_eventhandler is called with only event, not self)
local current_fullversion_dialog = nil

local function fullversion_event_handler(event)
	local self = current_fullversion_dialog
	if not self then
		return false
	end
	
	if event == "MenuQuit" then
		current_fullversion_dialog = nil  -- Clear reference
		self:delete()
		return true
	end
	
	-- Handle Refresh event - check if purchase completed
	if event == "Refresh" then
		-- Check purchase result
		local success, error_msg = vococraft_subscription.check_purchase_result()
		if success ~= nil then
			if success then
				core.log("action", "[Vococraft Dialog] Purchase success detected in event handler, closing dialog")
				self.data.purchase_success = true
				
				-- Send success analytics
				send_purchase_analytics("success", PRODUCT_ID)
				
				current_fullversion_dialog = nil  -- Clear reference before delete
				self:delete()
				
				-- If there's a pending package to install, proceed with it
				if self.data.pending_package and self.data.pending_parent then
					local package = self.data.pending_package
					if self.data.original_install_func then
						self.data.original_install_func(self.data.pending_parent, package)
					end
				end
				return true
			else
				-- Purchase failed or cancelled
				if error_msg then
					if error_msg == "Покупка отменена" then
						-- Send canceled analytics
						send_purchase_analytics("canceled", PRODUCT_ID)
					else
						-- Send failed analytics
						send_purchase_analytics("failed", PRODUCT_ID, error_msg)
						self.data.purchase_error = error_msg
					end
				end
				-- Don't close dialog on error/cancel - let user try again
			end
		end
		return false
	end
	
	return false
end


--- Create full version purchase dialog
---@param pending_package table|nil Package that user tried to install
---@param pending_parent table|nil Parent dialog to return to after purchase
---@param original_install_func function|nil Original install function to call after purchase
---@return table dialog
function create_fullversion_dialog(pending_package, pending_parent, original_install_func)
	local dlg = dialog_create("fullversion_dialog",
		get_fullversion_formspec,
		handle_fullversion_buttons,
		fullversion_event_handler)
	
	-- Store reference for event handler (user_eventhandler doesn't receive self)
	current_fullversion_dialog = dlg
	
	dlg.data.pending_package = pending_package
	dlg.data.pending_parent = pending_parent
	dlg.data.original_install_func = original_install_func
	
	-- Listen for purchase events to refresh UI
	local listener_id
	listener_id = vococraft_subscription.add_listener(function(event, data)
		if event == "product_info_loaded" then
			core.log("action", "[Vococraft Dialog] Product info loaded, refreshing UI")
			ui.update()
		elseif event == "purchase_complete" then
			-- Purchase completed - refresh UI immediately to close dialog
			core.log("action", "[Vococraft Dialog] Purchase complete event received, refreshing UI")
			ui.update()
		elseif event == "state_changed" then
			-- Purchase state changed - refresh UI
			core.log("action", "[Vococraft Dialog] State changed event received, refreshing UI")
			ui.update()
		end
	end)
	
	-- Store listener_id so we can clean it up if needed
	dlg.data.purchase_listener_id = listener_id
	
	return dlg
end


--- Show full version purchase dialog with proper parent handling
---@param parent table Parent dialog
---@param pending_package table|nil Package that user tried to install
---@param original_install_func function|nil Original install function to call after purchase
function show_fullversion_dialog(parent, pending_package, original_install_func)
	-- Analytics: dialog opened
	local source = pending_package and "mod_install" or "menu"
	send_window_analytics("open", source)
	
	local dlg = create_fullversion_dialog(pending_package, parent, original_install_func)
	dlg:set_parent(parent)
	parent:hide()
	dlg:show()
end

-- Backwards compatibility alias
show_subscription_dialog = show_fullversion_dialog
create_subscription_dialog = create_fullversion_dialog


core.log("action", "[Vococraft] Full version dialog module loaded (YooKassa)")
