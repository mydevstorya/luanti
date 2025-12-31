-- Luanti
-- Copyright (C) 2014 sapier
-- SPDX-License-Identifier: LGPL-2.1-or-later
-- VocoCraft Mobile UI - Online Servers Screen

local selected_server_idx = 0
local lan_servers = {}  -- LAN servers discovered via UDP broadcast (auto-scanned in C++)
local lan_refresh_active = false  -- Is auto-refresh polling active
local last_lan_count = 0  -- Track changes to trigger refresh

--------------------------------------------------------------------------------
-- Helper functions (from tab_online.lua)
--------------------------------------------------------------------------------

local function get_sorted_servers()
	local servers = {
		fav = {},
		public = {},
		incompatible = {}
	}

	local favs = serverlistmgr.get_favorites()
	local taken_favs = {}
	local result = menudata.search_result or serverlistmgr.servers
	for _, server in ipairs(result) do
		server.is_favorite = false
		for index, fav in ipairs(favs) do
			if server.address == fav.address and server.port == fav.port then
				taken_favs[index] = true
				server.is_favorite = true
				break
			end
		end
		server.is_compatible = is_server_protocol_compat(server.proto_min, server.proto_max)
		if server.is_favorite then
			table.insert(servers.fav, server)
		elseif server.is_compatible then
			table.insert(servers.public, server)
		else
			table.insert(servers.incompatible, server)
		end
	end

	if not menudata.search_result then
		for index, fav in ipairs(favs) do
			if not taken_favs[index] then
				table.insert(servers.fav, fav)
			end
		end
	end

	return servers
end

local function set_selected_server(server)
	if server == nil then
		core.settings:remove("address")
		core.settings:remove("remote_port")
		return
	end
	local address = server.address
	local port    = server.port
	gamedata.serverdescription = server.description

	if address and port then
		core.settings:set("address", address)
		core.settings:set("remote_port", port)
	end
end

local function find_selected_server()
	local address = core.settings:get("address")
	local port = tonumber(core.settings:get("remote_port"))
	for _, server in ipairs(serverlistmgr.servers) do
		if server.address == address and server.port == port then
			return server
		end
	end
	for _, server in ipairs(serverlistmgr.get_favorites()) do
		if server.address == address and server.port == port then
			return server
		end
	end
	-- Check LAN servers too
	for _, server in ipairs(lan_servers) do
		if server.address == address and server.port == port then
			return {
				address = server.address,
				port = server.port,
				name = server.name or (server.address .. ":" .. server.port),
				description = server.description or "",
				clients = server.clients,
				clients_max = server.clients_max,
				is_lan = true,
				is_compatible = true
			}
		end
	end
end

local function is_selected_fav(server)
	local address = core.settings:get("address")
	local port = tonumber(core.settings:get("remote_port"))

	for _, fav in ipairs(serverlistmgr.get_favorites()) do
		if address == fav.address and port == fav.port then
			return true
		end
	end
	return false
end

--------------------------------------------------------------------------------
-- MOBILE UI FORMSPEC
--------------------------------------------------------------------------------
local function get_formspec(W, H, CONTENT_Y, CONTENT_H, tabdata)
	-- Initialize search
	if not tabdata.search_for then
		tabdata.search_for = ""
	end

	local fs = {}
	
	-- === LEFT: Server list ===
	local list_x = 0.3
	local list_w = 9.0
	local list_y = CONTENT_Y + 0.2
	
	-- Search bar
	local search_h = 0.7
	table.insert(fs, "field[" .. list_x .. "," .. list_y .. ";" .. (list_w - 2.0) .. "," .. search_h .. 
		";te_search;;" .. core.formspec_escape(tabdata.search_for) .. "]")
	table.insert(fs, "field_enter_after_edit[te_search;true]")
	
	-- Search and refresh buttons
	table.insert(fs, "style[btn_mp_search;bgcolor=#555555]")
	table.insert(fs, "button[" .. (list_x + list_w - 1.9) .. "," .. list_y .. ";0.9," .. search_h .. ";btn_mp_search;>]")
	table.insert(fs, "style[btn_mp_refresh;bgcolor=#555555]")
	table.insert(fs, "button[" .. (list_x + list_w - 0.9) .. "," .. list_y .. ";0.9," .. search_h .. ";btn_mp_refresh;R]")
	
	list_y = list_y + search_h + 0.15
	local list_h = CONTENT_H - search_h - 0.55

	-- Server table
	local servers = get_sorted_servers()
	local total_servers = #servers.fav + #servers.public + #servers.incompatible
	
	if total_servers == 0 and #serverlistmgr.servers == 0 then
		-- Loading or no servers
		table.insert(fs, "box[" .. list_x .. "," .. list_y .. ";" .. list_w .. "," .. list_h .. ";#1a1a1a]")
		local label_y = list_y + (list_h / 2) - 0.3
		table.insert(fs, "style[loading_label;border=false;bgcolor=#1a1a1a;font_size=*1.3]")
		table.insert(fs, "button[" .. list_x .. "," .. label_y .. ";" .. list_w .. ",0.6;loading_label;" .. 
			fgettext("Loading servers...") .. "]")
	else
		-- Build table columns for mobile (simpler than desktop)
		table.insert(fs, "tablecolumns[" ..
			"color,span=1;" ..
			"text,align=left,width=8;" ..  -- Server name
			"color,span=1;" ..
			"text,align=right,width=2]")  -- Player count
		
		tabdata.lookup = {}
		local rows = {}
		
		-- Favorites section
		if #servers.fav > 0 then
			rows[#rows + 1] = "#ffff00,★ " .. fgettext("Favorites") .. ",,"
			for _, server in ipairs(servers.fav) do
				tabdata.lookup[#rows + 1] = server
				local name = server.name or (server.address .. ":" .. server.port)
				local clients = server.clients and server.clients_max and 
					(server.clients .. "/" .. server.clients_max) or "?"
				local color = server.is_compatible and "#ffffff" or "#888888"
				rows[#rows + 1] = color .. "," .. core.formspec_escape(name:sub(1,35)) .. 
					"," .. color .. "," .. clients
			end
		end
		
		-- Local Servers section (LAN discovery via UDP broadcast - auto-scanned in C++)
		rows[#rows + 1] = "#55aaff,~ " .. fgettext("Local Servers") .. ",,"
		
		-- Get discovered LAN servers (C++ scans every 3 seconds in background)
		if core.get_lan_servers then
			lan_servers = core.get_lan_servers() or {}
		end
		
		if #lan_servers > 0 then
			for _, server in ipairs(lan_servers) do
				-- Add to lookup for selection
				tabdata.lookup[#rows + 1] = {
					address = server.address,
					port = server.port,
					name = server.name or (server.address .. ":" .. server.port),
					description = server.description or "",
					clients = server.clients,
					clients_max = server.clients_max,
					is_lan = true,
					is_compatible = true
				}
				local name = server.name or (server.address .. ":" .. server.port)
				local clients = (server.clients or "?") .. "/" .. (server.clients_max or "?")
				rows[#rows + 1] = "#55aaff," .. core.formspec_escape(name:sub(1,35)) .. 
					",#55aaff," .. clients
			end
		else
			rows[#rows + 1] = "#666666," .. fgettext("No LAN servers found") .. ",,"
		end
		
		-- Public servers section
		rows[#rows + 1] = "#4bdd42,● " .. fgettext("Public Servers") .. ",,"
		if #servers.public > 0 then
			for _, server in ipairs(servers.public) do
				tabdata.lookup[#rows + 1] = server
				local name = server.name or (server.address .. ":" .. server.port)
				local clients = server.clients and server.clients_max and 
					(server.clients .. "/" .. server.clients_max) or "?"
				rows[#rows + 1] = "#ffffff," .. core.formspec_escape(name:sub(1,35)) .. 
					",#ffffff," .. clients
			end
		else
			rows[#rows + 1] = "#666666," .. fgettext("No servers found. Try refreshing.") .. ",,"
		end
		
		--[[ VocoCraft: Incompatible servers hidden for cleaner mobile UI
		if #servers.incompatible > 0 then
			rows[#rows + 1] = "#888888,⚠ " .. fgettext("Incompatible") .. ",,"
			for _, server in ipairs(servers.incompatible) do
				tabdata.lookup[#rows + 1] = server
				local name = server.name or (server.address .. ":" .. server.port)
				local clients = server.clients and server.clients_max and 
					(server.clients .. "/" .. server.clients_max) or "?"
				rows[#rows + 1] = "#888888," .. core.formspec_escape(name:sub(1,35)) .. 
					",#888888," .. clients
			end
		end
		--]]
		
		-- Find selected row
		local selected_server = find_selected_server()
		local selected_row = 0
		if selected_server then
			for i, server in pairs(tabdata.lookup) do
				if selected_server.address == server.address and
						selected_server.port == server.port then
					selected_row = i
					break
				end
			end
		end
		
		table.insert(fs, "table[" .. list_x .. "," .. list_y .. ";" .. list_w .. "," .. list_h .. 
			";servers;" .. table.concat(rows, ",") .. ";" .. selected_row .. "]")
	end

	-- === RIGHT: Server details panel ===
	local panel_x = 9.5
	local panel_w = 6.2
	local panel_y = CONTENT_Y + 0.2
	local panel_h = CONTENT_H - 0.4

	table.insert(fs, "box[" .. panel_x .. "," .. panel_y .. ";" .. panel_w .. "," .. panel_h .. ";#2a2a2a]")

	local field_x = panel_x + 0.3
	local field_w = panel_w - 0.6

	local selected_server = find_selected_server()

	if selected_server then
		local y = panel_y + 0.4

		-- Favorite star button in top right corner
		local star_x = panel_x + panel_w - 0.9
		local star_y = panel_y + 0.25
		if is_selected_fav(selected_server) then
			table.insert(fs, "style[btn_toggle_fav;bgcolor=#aa8800;font_size=*1.4]")
			table.insert(fs, "button[" .. star_x .. "," .. star_y .. ";0.65,0.65;btn_toggle_fav;★]")
		else
			table.insert(fs, "style[btn_toggle_fav;bgcolor=#444444;font_size=*1.4]")
			table.insert(fs, "button[" .. star_x .. "," .. star_y .. ";0.65,0.65;btn_toggle_fav;☆]")
		end

		-- Server name (with space for star)
		table.insert(fs, "style_type[label;font_size=*1.2]")
		local server_name = selected_server.name or selected_server.address
		table.insert(fs, "label[" .. field_x .. "," .. y .. ";" .. 
			core.formspec_escape(server_name:sub(1, 22)) .. "]")
		y = y + 0.6

		-- Server info
		table.insert(fs, "style_type[label;font_size=*0.9]")
		table.insert(fs, "label[" .. field_x .. "," .. y .. ";" .. 
			core.colorize("#aaaaaa", selected_server.address .. ":" .. (selected_server.port or 30000)) .. "]")
		y = y + 0.5

		-- Players count
		if selected_server.clients and selected_server.clients_max then
			table.insert(fs, "label[" .. field_x .. "," .. y .. ";" .. 
				fgettext("Players") .. ": " .. selected_server.clients .. "/" .. selected_server.clients_max .. "]")
			y = y + 0.45
		end

		-- Description
		if selected_server.description and selected_server.description ~= "" then
			--y = y + 0.2
			table.insert(fs, "textarea[" .. field_x .. "," .. y .. ";" .. field_w .. ",1.8;;;" .. 
				core.formspec_escape(selected_server.description) .. "]")
		end

		-- === LOGIN FORM at bottom of panel ===
		local form_y = panel_y + panel_h - 3.5
		table.insert(fs, "style_type[label;font_size=*1.0]")
		
		-- Player name field
		table.insert(fs, "label[" .. field_x .. "," .. form_y .. ";" .. fgettext("Name") .. "]")
		table.insert(fs, "field[" .. field_x .. "," .. (form_y + 0.35) .. ";" .. field_w .. ",0.65;te_name;;" .. 
			core.formspec_escape(core.settings:get("name") or "") .. "]")
		form_y = form_y + 1.2
		
		-- Password field
		table.insert(fs, "label[" .. field_x .. "," .. form_y .. ";" .. fgettext("Password") .. "]")
		table.insert(fs, "pwdfield[" .. field_x .. "," .. (form_y + 0.35) .. ";" .. field_w .. ",0.65;te_pwd;]")
		form_y = form_y + 1.15

		-- Buttons row: Register and Login
		local btn_w = (field_w - 0.15) / 2
		
		-- Register button (if split login/register enabled)
		if core.settings:get_bool("enable_split_login_register") then
			table.insert(fs, "style[btn_register;bgcolor=#2196F3;font_size=*1.1]")
			table.insert(fs, "button[" .. field_x .. "," .. form_y .. ";" .. btn_w .. ",0.8;btn_register;" .. 
				fgettext("Register") .. "]")
			
			-- Login button
			table.insert(fs, "style[btn_join;bgcolor=#4CAF50;font_size=*1.1]")
			table.insert(fs, "button[" .. (field_x + btn_w + 0.15) .. "," .. form_y .. ";" .. btn_w .. ",0.8;btn_join;" .. 
				fgettext("Login") .. "]")
		else
			-- Only Login button (full width)
			table.insert(fs, "style[btn_join;bgcolor=#4CAF50;font_size=*1.2]")
			table.insert(fs, "button[" .. field_x .. "," .. form_y .. ";" .. field_w .. ",0.8;btn_join;" .. 
				fgettext("Login") .. "]")
		end

	else
		-- No server selected
		local label_y = panel_y + (panel_h / 2) - 0.3
		table.insert(fs, "style[select_server_label;border=false;bgcolor=#2a2a2a;font_size=*1.2]")
		table.insert(fs, "button[" .. panel_x .. "," .. label_y .. ";" .. panel_w .. ",0.6;select_server_label;" .. 
			fgettext("Select a server") .. "]")
	end

	return table.concat(fs)
end

--------------------------------------------------------------------------------
-- BUTTON HANDLER
--------------------------------------------------------------------------------
local function handle_buttons(fields, tabdata)
	-- Save player name
	if fields.te_name then
		gamedata.playername = fields.te_name
		core.settings:set("name", fields.te_name)
	end

	-- Server selection
	if fields.servers then
		local event = core.explode_table_event(fields.servers)
		local server = tabdata.lookup and tabdata.lookup[event.row]

		if server then
			if event.type == "DCL" then
				-- Double click - join server
				if not is_server_protocol_compat_or_error(server.proto_min, server.proto_max) then
					return true
				end

				gamedata.address    = server.address
				gamedata.port       = server.port
				gamedata.playername = fields.te_name or core.settings:get("name")
				gamedata.selected_world = 0
				gamedata.servername = server.name
				gamedata.serverdescription = server.description

				set_selected_server(server)
				
				-- Send analytics event for server connection
				if core.send_analytics_event then
					local params = "address=" .. server.address
					params = params .. ",name=" .. core.formspec_escape(server.name or "")
					core.send_analytics_event("connect_to_remote_server", params)
				end
				
				core.start()
				return true
			end
			if event.type == "CHG" then
				set_selected_server(server)
				return true
			end
		end
	end

	-- Join/Login button
	if fields.btn_join then
		local server = find_selected_server()
		if server then
			if not is_server_protocol_compat_or_error(server.proto_min, server.proto_max) then
				return true
			end

			gamedata.address    = server.address
			gamedata.port       = server.port
			gamedata.playername = fields.te_name or core.settings:get("name")
			gamedata.password   = fields.te_pwd or ""
			gamedata.selected_world = 0
			gamedata.servername = server.name
			gamedata.serverdescription = server.description
			
			-- Set login mode
			local enable_split = core.settings:get_bool("enable_split_login_register")
			gamedata.allow_login_or_register = enable_split and "login" or "any"

			set_selected_server(server)
			
			-- Don't add LAN servers to favorites automatically
			if not server.is_lan then
				serverlistmgr.add_favorite(server)
			end
			
			core.settings:set("address", gamedata.address)
			core.settings:set("remote_port", gamedata.port)
			
			-- Send analytics event for server connection
			if core.send_analytics_event then
				local params = "address=" .. server.address
				params = params .. ",name=" .. core.formspec_escape(server.name or "")
				core.send_analytics_event("connect_to_remote_server", params)
			end
			
			core.start()
		end
		return true
	end

	-- Register button
	if fields.btn_register then
		local server = find_selected_server()
		if server then
			if not is_server_protocol_compat_or_error(server.proto_min, server.proto_max) then
				return true
			end
			
			-- Open register dialog
			local dlg = create_register_dialog(server.address, server.port, server)
			dlg:set_parent(tabdata.parent)
			tabdata.parent:hide()
			dlg:show()
		end
		return true
	end

	-- Toggle favorite
	if fields.btn_toggle_fav then
		local server = find_selected_server()
		if server then
			if is_selected_fav(server) then
				serverlistmgr.delete_favorite(server)
			else
				serverlistmgr.add_favorite(server)
			end
		end
		return true
	end

	-- Search
	if fields.btn_mp_search or fields.key_enter_field == "te_search" then
		tabdata.search_for = fields.te_search or ""
		-- Simple search implementation
		if tabdata.search_for == "" then
			menudata.search_result = nil
		else
			local query = tabdata.search_for:lower()
			menudata.search_result = {}
			for _, server in ipairs(serverlistmgr.servers) do
				local name = (server.name or ""):lower()
				local desc = (server.description or ""):lower()
				if name:find(query, 1, true) or desc:find(query, 1, true) then
					table.insert(menudata.search_result, server)
				end
			end
		end
		return true
	end

	-- Refresh
	if fields.btn_mp_refresh then
		serverlistmgr.sync()
		return true
	end

	return false
end

--------------------------------------------------------------------------------
-- LAN servers auto-refresh (polls C++ every 2 seconds and updates UI if changed)
--------------------------------------------------------------------------------
local function start_lan_refresh()
	if lan_refresh_active then return end
	lan_refresh_active = true
	
	local function poll_lan_servers()
		if not lan_refresh_active then return end
		
		-- Get current LAN servers from C++
		local new_servers = {}
		if core.get_lan_servers then
			new_servers = core.get_lan_servers() or {}
		end
		
		-- Check if list changed (simple count + first server check)
		local changed = (#new_servers ~= last_lan_count)
		if not changed and #new_servers > 0 and #lan_servers > 0 then
			-- Also check if first server info changed (e.g. player count)
			local new_first = new_servers[1]
			local old_first = lan_servers[1]
			if new_first and old_first then
				if new_first.clients ~= old_first.clients or
				   new_first.address ~= old_first.address or
				   new_first.name ~= old_first.name then
					changed = true
				end
			end
		end
		
		if changed then
			lan_servers = new_servers
			last_lan_count = #new_servers
			-- Trigger UI refresh
			core.event_handler("Refresh")
		end
		
		-- Schedule next poll (2 seconds)
		if lan_refresh_active then
			core.handle_async(
				function() return true end,
				nil,
				function()
					-- Small delay then poll again
					poll_lan_servers()
				end
			)
		end
	end
	
	-- Start polling
	poll_lan_servers()
end

local function stop_lan_refresh()
	lan_refresh_active = false
end

--------------------------------------------------------------------------------
-- Tab change handler
--------------------------------------------------------------------------------
local function on_change(type)
	if type == "ENTER" then
		serverlistmgr.sync()
		-- Start auto-refresh for LAN servers
		start_lan_refresh()
	elseif type == "LEAVE" then
		-- Stop auto-refresh when leaving tab
		stop_lan_refresh()
	end
end

return {
	get_formspec = get_formspec,
	handle_buttons = handle_buttons,
	on_change = on_change,
}
