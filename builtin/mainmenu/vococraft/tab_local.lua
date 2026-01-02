-- Luanti
-- Copyright (C) 2014 sapier
-- SPDX-License-Identifier: LGPL-2.1-or-later
-- VocoCraft Mobile UI - Play Screen

local mobile_online = dofile(core.get_mainmenu_path() .. DIR_DELIM .. "vococraft" .. DIR_DELIM .. "tab_mobile_online.lua")

-- === VOCOCRAFT: Subscribe to subscription events to update UI ===
if vococraft_subscription then
	vococraft_subscription.add_listener(function(event_type, data)
		core.log("action", "[Vococraft tab_local] Received event: " .. event_type)
		if event_type == "state_changed" or event_type == "restore_complete" or event_type == "purchase_complete" then
			-- Subscription state changed, update main menu UI
			core.log("action", "[Vococraft tab_local] Updating UI due to subscription change")
			ui.update()
		end
	end)
end
-- === END VOCOCRAFT ===

local current_game, singleplayer_refresh_gamebar
local valid_disabled_settings = {
	["enable_damage"]=false,
	["creative_mode"]=false,
	["enable_server"]=false,
}

-- Current sub-tab: "worlds" or "servers"
local current_subtab = "worlds"
local selected_world_idx = 1

function current_game()
	local gameid = core.settings:get("menu_last_game")
	local game = gameid and pkgmgr.find_by_gameid(gameid)
	if not game and #pkgmgr.games > 0 then
		local picked_game
		if pkgmgr.games[1].id == "devtest" and #pkgmgr.games > 1 then
			picked_game = 2
		else
			picked_game = 1
		end
		game = pkgmgr.games[picked_game]
		gameid = game.id
		core.settings:set("menu_last_game", gameid)
	end
	return game
end

function apply_game(game)
	core.settings:set("menu_last_game", game.id)
	menudata.worldlist:set_filtercriteria(game.id)
	mm_game_theme.set_game(game)

	local index = filterlist.get_current_index(menudata.worldlist,
		tonumber(core.settings:get("mainmenu_last_selected_world")))
	if not index or index < 1 then
		local selected = core.get_textlist_index("sp_worlds")
		if selected ~= nil and selected < #menudata.worldlist:get_list() then
			index = selected
		else
			index = #menudata.worldlist:get_list()
		end
	end
	menu_worldmt_legacy(index)
end

function singleplayer_refresh_gamebar()
	local old_bar = ui.find_by_name("game_button_bar")
	if old_bar ~= nil then
		old_bar:delete()
	end
	return false
end

local function get_disabled_settings(game)
	if not game then
		return {}
	end
	local gameconfig = Settings(game.path .. "/game.conf")
	local disabled_settings = {}
	if gameconfig then
		local disabled_settings_str = (gameconfig:get("disabled_settings") or ""):split()
		for _, value in pairs(disabled_settings_str) do
			local state = false
			value = value:trim()
			if string.sub(value, 1, 1) == "!" then
				state = true
				value = string.sub(value, 2)
			end
			if valid_disabled_settings[value] then
				disabled_settings[value] = state
			end
		end
	end
	return disabled_settings
end

--------------------------------------------------------------------------------
-- MOBILE UI FORMSPEC
--------------------------------------------------------------------------------
local function get_formspec(tabview, name, tabdata)
	-- === VOCOCRAFT: Poll for async subscription results ===
	if vococraft_subscription then
		vococraft_subscription.poll_async_results()
	end
	-- === END VOCOCRAFT ===
	
	local W = 16
	
	-- === VOCOCRAFT: Increase height if premium button is shown ===
	local H = 9.5
	local PREMIUM_BTN_H = 0
	if vococraft_subscription and not vococraft_subscription.has_subscription() then
		PREMIUM_BTN_H = 1.0
		H = 10.5  -- Increase total height to fit premium button
	end
	-- === END VOCOCRAFT ===

	local HEADER_H = 0.7
	local TAB_H = 0.9
	local CONTENT_Y = HEADER_H + TAB_H
	local CONTENT_H = H - CONTENT_Y - PREMIUM_BTN_H

	local fs = {}

	table.insert(fs, "formspec_version[6]")
	table.insert(fs, string.format("size[%f,%f]", W, H))
	table.insert(fs, "bgcolor[#313131;both]")
	table.insert(fs, "box[0,0;" .. W .. "," .. H .. ";#313131]")

	-- ============ HEADER with Settings & About buttons ============
	table.insert(fs, "box[0,0;" .. W .. "," .. HEADER_H .. ";#1e1e1e]")
	
	-- Settings button (left side of header)
	local hdr_btn_w = 1.8
	local hdr_btn_h = 0.5
	local hdr_btn_y = (HEADER_H - hdr_btn_h) / 2
	table.insert(fs, "style[btn_settings;bgcolor=#444444;font_size=*0.9]")
	table.insert(fs, "button[0.2," .. hdr_btn_y .. ";" .. hdr_btn_w .. "," .. hdr_btn_h .. ";btn_settings;" .. fgettext("Settings") .. "]")
	
	-- About button (right side of header)
	table.insert(fs, "style[btn_about;bgcolor=#444444;font_size=*0.9]")
	table.insert(fs, "button[" .. (W - hdr_btn_w - 0.2) .. "," .. hdr_btn_y .. ";" .. hdr_btn_w .. "," .. hdr_btn_h .. ";btn_about;" .. fgettext("About") .. "]")

	-- ============ TAB BAR ============
	local tab_y = HEADER_H
	local tab_w = W / 2

	table.insert(fs, "box[0," .. tab_y .. ";" .. W .. "," .. TAB_H .. ";#2a2a2a]")

	local world_count = menudata.worldlist and #menudata.worldlist:get_list() or 0

	if current_subtab == "worlds" then
		table.insert(fs, "box[0," .. tab_y .. ";" .. tab_w .. "," .. TAB_H .. ";#3d3d3d]")
		table.insert(fs, "box[0.5," .. (tab_y + TAB_H - 0.06) .. ";" .. (tab_w - 1) .. ",0.05;#4CAF50]")
	end
	table.insert(fs, "style_type[button;font_size=*1.0]")
	table.insert(fs, "button[0," .. tab_y .. ";" .. tab_w .. "," .. TAB_H .. ";subtab_worlds;" .. fgettext("Worlds") .. " (" .. world_count .. ")]")

	if current_subtab == "servers" then
		table.insert(fs, "box[" .. tab_w .. "," .. tab_y .. ";" .. tab_w .. "," .. TAB_H .. ";#3d3d3d]")
		table.insert(fs, "box[" .. (tab_w + 0.5) .. "," .. (tab_y + TAB_H - 0.06) .. ";" .. (tab_w - 1) .. ",0.05;#4CAF50]")
	end
	table.insert(fs, "button[" .. tab_w .. "," .. tab_y .. ";" .. tab_w .. "," .. TAB_H .. ";subtab_servers;" .. fgettext("Servers") .. "]")

	-- ============ CONTENT AREA ============
	table.insert(fs, "box[0," .. CONTENT_Y .. ";" .. W .. "," .. CONTENT_H .. ";#3a3a3a]")

	if current_subtab == "worlds" then
		-- === LEFT: World list using textlist ===
		local list_x = 0.3
		local list_w = 9.0
		local list_y = CONTENT_Y + 0.2
		local list_h = CONTENT_H - 0.4

		if world_count == 0 then
			table.insert(fs, "box[" .. list_x .. "," .. list_y .. ";" .. list_w .. "," .. list_h .. ";#1a1a1a]")
			-- Use button_exit with no action for centered text display
			local label_text = fgettext("No worlds yet!")
			local label_y = list_y + (list_h / 2) - 0.3
			table.insert(fs, "style[no_worlds_label;border=false;bgcolor=#1a1a1a;font_size=*1.5]")
			table.insert(fs, "button[" .. list_x .. "," .. label_y .. ";" .. list_w .. ",0.6;no_worlds_label;" .. label_text .. "]")
		else
			local index = core.get_textlist_index("sp_worlds") or 
				filterlist.get_current_index(menudata.worldlist,
					tonumber(core.settings:get("mainmenu_last_selected_world"))) or 1
			
			selected_world_idx = index
			
			-- MOBILE: Large text in world list for easy touch
			table.insert(fs, "style[sp_worlds;font_size=*1.6]")
			table.insert(fs, "textlist[" .. list_x .. "," .. list_y .. ";" .. list_w .. "," .. list_h .. ";sp_worlds;" ..
				menu_render_worldlist() .. ";" .. index .. "]")
		end

		-- === RIGHT: Selected world panel ===
		local panel_x = 9.5
		local panel_w = 6.2
		local panel_y = CONTENT_Y + 0.2
		local panel_h = CONTENT_H - 0.4

		table.insert(fs, "box[" .. panel_x .. "," .. panel_y .. ";" .. panel_w .. "," .. panel_h .. ";#2a2a2a]")

		local list = menudata.worldlist:get_list()
		local world = list and list[selected_world_idx]

		-- Constants for layout
		local field_x = panel_x + 0.3
		local field_w = panel_w - 0.6
		
		-- Fixed button positions from bottom of panel
		local btn_bottom = panel_y + panel_h
		local del_edit_y = btn_bottom - 0.65
		local create_y = del_edit_y - 0.7
		local play_y = create_y - 0.85

		if world then
			local server_on = core.settings:get_bool("enable_server")
			local y = panel_y + 0.4

			-- World name header
			table.insert(fs, "style_type[label;font_size=*1.3]")
			table.insert(fs, "label[" .. field_x .. "," .. y .. ";" .. core.formspec_escape(world.name) .. "]")
			y = y + 0.7

			-- Game settings
			local game_obj = pkgmgr.find_by_gameid(world.gameid)
			local disabled_settings = get_disabled_settings(game_obj)

			-- MOBILE: Custom large checkboxes using styled buttons
			local cb_size = 0.56  -- checkbox button size
			local cb_spacing = 0.75  -- vertical spacing between checkboxes

			-- Creative Mode
			if disabled_settings["creative_mode"] == nil then
				local creative_on = core.settings:get_bool("creative_mode")
				local cb_text = creative_on and "✓" or ""
				table.insert(fs, "style[cb_creative_mode;bgcolor=" .. (creative_on and "#4CAF50" or "#555555") .. ";font_size=*1.0]")
				table.insert(fs, "button[" .. field_x .. "," .. y .. ";" .. cb_size .. "," .. cb_size .. ";cb_creative_mode;" .. cb_text .. "]")
				table.insert(fs, "style_type[label;font_size=*1.3]")
				table.insert(fs, "label[" .. (field_x + cb_size + 0.15) .. "," .. (y + cb_size/2) .. ";" .. fgettext("Creative Mode") .. "]")
				y = y + cb_spacing
			end

			-- Enable Damage (default: true)
			if disabled_settings["enable_damage"] == nil then
				local damage_on = core.settings:get_bool("enable_damage", true)
				local cb_text = damage_on and "✓" or ""
				table.insert(fs, "style[cb_enable_damage;bgcolor=" .. (damage_on and "#4CAF50" or "#555555") .. ";font_size=*1.0]")
				table.insert(fs, "button[" .. field_x .. "," .. y .. ";" .. cb_size .. "," .. cb_size .. ";cb_enable_damage;" .. cb_text .. "]")
				table.insert(fs, "label[" .. (field_x + cb_size + 0.15) .. "," .. (y + cb_size/2) .. ";" .. fgettext("Enable Damage") .. "]")
				y = y + cb_spacing
			end

			-- Host Server
			if disabled_settings["enable_server"] == nil then
				local cb_text = server_on and "✓" or ""
				table.insert(fs, "style[cb_server;bgcolor=" .. (server_on and "#4CAF50" or "#555555") .. ";font_size=*1.0]")
				table.insert(fs, "button[" .. field_x .. "," .. y .. ";" .. cb_size .. "," .. cb_size .. ";cb_server;" .. cb_text .. "]")
				table.insert(fs, "label[" .. (field_x + cb_size + 0.15) .. "," .. (y + cb_size/2) .. ";" .. fgettext("Host Server") .. "]")
				table.insert(fs, "style_type[label;font_size=*1.0]")
				
				-- Server configuration (only when enabled) - compact layout
				if server_on then
					y = y + 1.2
					
					-- Server Name field (larger height for mobile)
					table.insert(fs, "field[" .. field_x .. "," .. y .. ";" .. field_w .. ",1.0;te_playername;" .. 
						fgettext("Server Name") .. ";" .. core.formspec_escape(core.settings:get("name") or "Server") .. "]")
					y = y + 1.3
					
					-- Warning about LAN requirement (yellow text)
					table.insert(fs, "style_type[label;font_size=*0.85]")
					table.insert(fs, "label[" .. field_x .. "," .. y .. ";" .. 
						core.colorize("#FFD700", fgettext("LAN only (same Wi-Fi)")) .. "]")
					table.insert(fs, "style_type[label;font_size=*1.0]")
				else
					y = y + 0.65
				end
			end

			-- === BUTTONS - MOBILE FRIENDLY SIZES ===
			
			-- PLAY / HOST button - BIG GREEN (easy to tap)
			local btn_label = server_on and fgettext("HOST GAME") or fgettext("PLAY")
			table.insert(fs, "style[play;bgcolor=#4CAF50;font_size=*1.4]")
			table.insert(fs, "button[" .. field_x .. "," .. play_y .. ";" .. field_w .. ",0.8;play;" .. btn_label .. "]")

			-- Create New World - BLUE (good size for mobile)
			table.insert(fs, "style[world_create;bgcolor=#2196F3;font_size=*1.1]")
			table.insert(fs, "button[" .. field_x .. "," .. create_y .. ";" .. field_w .. ",0.65;world_create;" .. fgettext("Create World") .. "]")

			-- Delete and Mods buttons side by side
			local small_btn_w = (field_w - 0.15) / 2
			table.insert(fs, "style[world_delete;bgcolor=#555555;font_size=*1.0]")
			table.insert(fs, "button[" .. field_x .. "," .. del_edit_y .. ";" .. small_btn_w .. ",0.55;world_delete;" .. fgettext("Delete") .. "]")
			table.insert(fs, "style[world_details;bgcolor=#555555;font_size=*1.0]")
			table.insert(fs, "button[" .. (field_x + small_btn_w + 0.15) .. "," .. del_edit_y .. ";" .. small_btn_w .. ",0.55;world_details;" .. fgettext("Mods") .. "]")

		else
			-- No world selected - centered text using styled button
			local label_y = panel_y + (panel_h / 2) - 0.3
			table.insert(fs, "style[select_world_label;border=false;bgcolor=#2a2a2a;font_size=*1.2]")
			table.insert(fs, "button[" .. panel_x .. "," .. label_y .. ";" .. panel_w .. ",0.6;select_world_label;" .. fgettext("Select a world") .. "]")
			
			-- Create button even when no world selected - BIG for mobile
			table.insert(fs, "style[world_create;bgcolor=#4CAF50;font_size=*1.2]")
			table.insert(fs, "button[" .. field_x .. "," .. create_y .. ";" .. field_w .. ",0.8;world_create;" .. fgettext("Create New World") .. "]")
		end

	elseif current_subtab == "servers" then
		-- Server browser from mobile_online module
		local servers_fs = mobile_online.get_formspec(W, H, CONTENT_Y, CONTENT_H, tabdata)
		table.insert(fs, servers_fs)
	end

	-- === VOCOCRAFT: Show "Unlock Premium" button if no subscription ===
	if vococraft_subscription and not vococraft_subscription.has_subscription() then
		local sub_btn_y = H - 0.85
		local sub_btn_h = 0.7
		local sub_btn_x = 0.3
		local sub_btn_w = W - 0.6
		
		-- Background box for button area
		table.insert(fs, "box[0," .. (H - 0.95) .. ";" .. W .. ",0.95;#1e1e1e]")
		
		-- Purple premium button at bottom - bright and noticeable
		table.insert(fs, "style[btn_unlock_premium;bgcolor=#9c27b0;border=true;font_size=*1.2;textcolor=#ffffff]")
		table.insert(fs, "style[btn_unlock_premium:hovered;bgcolor=#ba68c8]")
		table.insert(fs, "style[btn_unlock_premium:pressed;bgcolor=#7b1fa2]")
		table.insert(fs, "button[" .. sub_btn_x .. "," .. sub_btn_y .. ";" .. sub_btn_w .. "," .. sub_btn_h .. ";btn_unlock_premium;★ " .. fgettext("Unlock Premium - play with mods and no ads") .. " ★]")
	end
	-- === END VOCOCRAFT ===

	return table.concat(fs), true
end

--------------------------------------------------------------------------------
-- BUTTON HANDLER
--------------------------------------------------------------------------------
local function main_button_handler(this, fields, name, tabdata)
	assert(name == "local")

	-- === VOCOCRAFT: Handle unlock premium button ===
	if fields.btn_unlock_premium then
		local dlg = create_subscription_dialog(nil, nil, nil)
		dlg:set_parent(this)
		this:hide()
		dlg:show()
		return true
	end
	-- === END VOCOCRAFT ===

	-- Settings button handler
	if fields.btn_settings then
		local dlg = create_settings_dlg()
		dlg:set_parent(this)
		this:hide()
		dlg:show()
		return true
	end

	-- About button handler
	if fields.btn_about then
		local maintab = ui.find_by_name("maintab")
		if maintab then
			maintab:set_tab("about")
		end
		return true
	end

	if fields.subtab_worlds then
		current_subtab = "worlds"
		-- Stop servers tab auto-refresh when switching to worlds
		if mobile_online.on_change then
			mobile_online.on_change("LEAVE")
		end
		return true
	end
	if fields.subtab_servers then
		current_subtab = "servers"
		-- Start servers tab auto-refresh
		if mobile_online.on_change then
			mobile_online.on_change("ENTER")
		end
		return true
	end

	-- Handle server browser buttons when on servers tab
	if current_subtab == "servers" then
		tabdata.parent = this
		if mobile_online.handle_buttons(fields, tabdata) then
			return true
		end
	end

	if fields.btn_back then
		return true
	end

	-- Creative mode toggle (image_button acts as toggle)
	if fields["cb_creative_mode"] then
		local current = core.settings:get_bool("creative_mode")
		local new_value = not current
		core.settings:set_bool("creative_mode", new_value)
		local selected = core.get_textlist_index("sp_worlds") or selected_world_idx
		menu_worldmt(selected, "creative_mode", tostring(new_value))
		return true
	end

	-- Damage toggle (image_button acts as toggle)
	if fields["cb_enable_damage"] then
		local current = core.settings:get_bool("enable_damage")
		local new_value = not current
		core.settings:set_bool("enable_damage", new_value)
		local selected = core.get_textlist_index("sp_worlds") or selected_world_idx
		menu_worldmt(selected, "enable_damage", tostring(new_value))
		return true
	end

	-- Host Server toggle (image_button acts as toggle)
	if fields["cb_server"] then
		local current = core.settings:get_bool("enable_server")
		core.settings:set_bool("enable_server", not current)
		return true
	end

	-- Update server name from field
	if fields["te_playername"] then
		core.settings:set("name", fields["te_playername"])
	end

	if fields.game_open_cdb then
		local maintab = ui.find_by_name("maintab")
		local dlg = create_contentdb_dlg("game")
		dlg:set_parent(maintab)
		maintab:hide()
		dlg:show()
		return true
	end

	if this.dlg_create_world_closed_at == nil then
		this.dlg_create_world_closed_at = 0
	end

	local world_doubleclick = false

	if fields["sp_worlds"] ~= nil then
		local event = core.explode_textlist_event(fields["sp_worlds"])
		local selected = core.get_textlist_index("sp_worlds")
		menu_worldmt_legacy(selected)
		selected_world_idx = selected

		if event.type == "DCL" then
			world_doubleclick = true
		end

		if event.type == "CHG" and selected ~= nil then
			core.settings:set("mainmenu_last_selected_world",
				menudata.worldlist:get_raw_index(selected))
			return true
		end
	end

	if menu_handle_key_up_down(fields, "sp_worlds", "mainmenu_last_selected_world") then
		return true
	end

	if fields["play"] ~= nil or world_doubleclick or fields["key_enter"] then
		local enter_key_duration = core.get_us_time() - this.dlg_create_world_closed_at
		if world_doubleclick and enter_key_duration <= 200000 then
			this.dlg_create_world_closed_at = 0
			return true
		end

		local selected = core.get_textlist_index("sp_worlds") or selected_world_idx
		gamedata.selected_world = menudata.worldlist:get_raw_index(selected)

		if selected == nil or gamedata.selected_world == 0 then
			return true
		end

		local world = menudata.worldlist:get_raw_element(gamedata.selected_world)
		local game_obj
		if world then
			game_obj = pkgmgr.find_by_gameid(world.gameid)
			core.settings:set("menu_last_game", game_obj.id)
		end

		local disabled_settings = get_disabled_settings(game_obj)
		for k, _ in pairs(valid_disabled_settings) do
			local v = disabled_settings[k]
			if v ~= nil then
				if k == "enable_server" and v == true then
					error("Setting 'enable_server' cannot be force-enabled!")
				end
				core.settings:set_bool(k, disabled_settings[k])
			end
		end

		-- Server mode configuration
		if core.settings:get_bool("enable_server") then
			gamedata.playername = core.settings:get("name") or "Player"
			gamedata.password   = ""
			gamedata.port       = core.settings:get("port") or "30000"
			gamedata.address    = ""
			
			gamedata.singleplayer = false
		else
			gamedata.singleplayer = true
		end

		-- Send analytics events for game start (Android only)
		if core.send_analytics_event then
			-- Local server connection event
			core.send_analytics_event("connect_to_local_server", "")
			
			-- Game started event with details
			local params = ""
			if game_obj then
				params = "game=" .. game_obj.id
			end
			params = params .. ",creative=" .. tostring(core.settings:get_bool("creative_mode"))
			params = params .. ",damage=" .. tostring(core.settings:get_bool("enable_damage"))
			params = params .. ",host=" .. tostring(core.settings:get_bool("enable_server"))
			core.log("info", "[Analytics] Sending game_started event: " .. params)
			core.send_analytics_event("game_started", params)
		end

		core.start()
		return true
	end

	if fields["world_details"] ~= nil then
		local selected = core.get_textlist_index("sp_worlds") or selected_world_idx
		if selected ~= nil then
			local configdialog = create_configure_world_dlg(
				menudata.worldlist:get_raw_index(selected))
			if configdialog ~= nil then
				configdialog:set_parent(this)
				this:hide()
				configdialog:show()
			end
		end
		return true
	end

	if fields["world_create"] ~= nil then
		this.dlg_create_world_closed_at = 0
		local create_world_dlg = create_create_world_dlg()
		create_world_dlg:set_parent(this)
		this:hide()
		create_world_dlg:show()
		return true
	end

	if fields["world_delete"] ~= nil then
		local selected = core.get_textlist_index("sp_worlds") or selected_world_idx
		if selected ~= nil and selected <= menudata.worldlist:size() then
			local world = menudata.worldlist:get_list()[selected]
			if world ~= nil and world.name ~= nil and world.name ~= "" then
				local index = menudata.worldlist:get_raw_index(selected)
				local delete_world_dlg = create_delete_world_dlg(world.name, index)
				delete_world_dlg:set_parent(this)
				this:hide()
				delete_world_dlg:show()
			end
		end
		return true
	end
end

local function on_change(type)
	if type == "ENTER" then
		-- Set enable_damage to true by default
		core.settings:set_bool("enable_damage", true)
		
		local game = current_game()
		if game then
			apply_game(game)
		else
			mm_game_theme.set_engine()
		end
	elseif type == "LEAVE" then
		menudata.worldlist:set_filtercriteria(nil)
		local gamebar = ui.find_by_name("game_button_bar")
		if gamebar then
			gamebar:hide()
		end
	end
end

return {
	name = "local",
	caption = fgettext("Play"),
	cbf_formspec = get_formspec,
	cbf_button_handler = main_button_handler,
	on_change = on_change
}
