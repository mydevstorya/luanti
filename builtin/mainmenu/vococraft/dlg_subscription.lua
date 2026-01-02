-- Vococraft
-- Premium subscription dialog
-- SPDX-License-Identifier: LGPL-2.1-or-later

-- Beautiful mobile-first subscription offer dialog

local function get_subscription_formspec(data)
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
		
		-- Pricing section - only trial
		"box[", padding, ",4.1;", content_w, ",0.7;", bg_card, "]",
		
		-- Trial offer (highlighted)
		"box[", padding + 0.1, ",4.2;", content_w - 0.2, ",0.5;#ffc10733]",
		"label[", padding + 0.3, ",4.47;",
			core.colorize(accent_gold, "Начни за:  "),
			core.colorize(text_white, info.trial_price_formatted .. ""),
			core.colorize(text_muted, "/пробная неделя"), "]",
		
		-- CTA Button - SOLID PURPLE with box behind
		"box[", padding, ",5.0;", content_w, ",1.05;#9c27b0]",
		"style[btn_subscribe;bgcolor=#9c27b0;border=false;textcolor=#ffffff]",
		"style[btn_subscribe:hovered;bgcolor=#ab47bc]",
		"style[btn_subscribe:pressed;bgcolor=#7b1fa2]",
		"button[", padding, ",5.0;", content_w, ",1.05;btn_subscribe;",
			"★ Получить доступ ★]",
	}
	
	return table.concat(formspec)
end


local function handle_subscription_buttons(this, fields)
	if fields.btn_close or fields.quit then
		this:delete()
		return true
	end
	
	if fields.btn_subscribe then
		-- Check if purchase already in progress
		local info = vococraft_subscription.get_info()
		if info.purchase_in_progress then
			return true -- Ignore if already purchasing
		end
		
		-- Attempt to purchase subscription
		vococraft_subscription.purchase(function(success, error_msg)
			if success then
				core.log("action", "[Vococraft] Subscription purchased successfully")
				this:delete()
				
				-- If there's a pending package to install, proceed with it
				if this.data.pending_package and this.data.pending_parent then
					-- Retry the installation now that we have subscription
					local package = this.data.pending_package
					local parent = this.data.pending_parent
					
					-- Use the original install function
					if this.data.original_install_func then
						this.data.original_install_func(parent, package)
					end
				end
			else
				if error_msg and error_msg ~= "Покупка отменена" then
					-- Show error only if it's not a user cancellation
					gamedata.errormessage = fgettext_ne("Subscription purchase error") .. ": " .. error_msg
				end
			end
			ui.update()
		end)
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
	
	return dlg
end


--- Show subscription dialog with proper parent handling
---@param parent table Parent dialog
---@param pending_package table|nil Package that user tried to install
---@param original_install_func function|nil Original install function to call after purchase
function show_subscription_dialog(parent, pending_package, original_install_func)
	local dlg = create_subscription_dialog(pending_package, parent, original_install_func)
	dlg:set_parent(parent)
	parent:hide()
	dlg:show()
end


core.log("action", "[Vococraft] Subscription dialog module loaded")
