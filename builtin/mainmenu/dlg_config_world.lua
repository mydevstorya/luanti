-- Luanti
-- Copyright (C) 2013 sapier
-- SPDX-License-Identifier: LGPL-2.1-or-later

--------------------------------------------------------------------------------

local enabled_all = false

-- Render mod list for textlist (only external mods)
-- Returns: formatted string for textlist, filtered_to_original index map, external_mods list
local function render_modlist_textlist(all_mods, with_error)
	local retval = {}
	local filtered_to_original = {}
	local external_mods = {}
	
	for i, mod in ipairs(all_mods) do
		-- Skip game content - only show external mods
		if not mod.is_game_content and mod.type ~= "game" then
			table.insert(external_mods, mod)
			table.insert(filtered_to_original, i)
			
			local color = mt_color_grey
			local prefix = ""
			local has_error = with_error and with_error[mod.virtual_path or mod.path]
			
			if has_error then
				if has_error.type == "error" then
					color = mt_color_red
				else
					color = mt_color_orange
				end
			elseif mod.is_modpack then
				-- Check if modpack is entirely enabled
				local entirely_enabled = true
				for _, m in ipairs(all_mods) do
					if m.modpack == mod.name and not m.enabled then
						entirely_enabled = false
						break
					end
				end
				if entirely_enabled then
					color = mt_color_dark_green
					prefix = "☑ "
				else
					prefix = "☐ "
				end
			elseif mod.enabled then
				color = mt_color_green
				prefix = "☑ "
			else
				prefix = "☐ "
			end
			
			-- Indent for modpack contents
			local indent = ""
			if mod.modpack ~= nil then
				indent = "   "
			end
			
			local display_name = mod.list_title or mod.list_name or mod.title or mod.name
			retval[#retval + 1] = color .. indent .. prefix .. core.formspec_escape(display_name)
		end
	end
	
	return table.concat(retval, ","), filtered_to_original, external_mods
end

local function modname_valid(name)
	return not name:find("[^a-z0-9_]")
end

local function init_data(data)
	data.list = filterlist.create(
		pkgmgr.preparemodlist,
		pkgmgr.comparemod,
		function(element, uid)
			if element.name == uid then
				return true
			end
		end,
		function(element, criteria)
			if criteria.hide_game and
					element.is_game_content then
				return false
			end

			if criteria.hide_modpackcontents and
					element.modpack ~= nil then
				return false
			end
			return true
		end,
		{
			worldpath = data.worldspec.path,
			gameid = data.worldspec.gameid
		})

	if data.selected_mod > data.list:size() then
		data.selected_mod = 0
	end

	data.list:set_filtercriteria({
		hide_game = data.hide_gamemods,
		hide_modpackcontents = data.hide_modpackcontents
	})
	-- Sorting is already done by pgkmgr.get_mods
end


-- Returns errors errors and a list of all enabled mods (inc. game and world mods)
--
-- `with_errors` is a table from mod virtual path to `{ type = "error" | "warning" }`.
-- `enabled_mods_by_name` is a table from mod virtual path to `true`.
--
-- @param world_path Path to the world
-- @param all_mods List of mods, with `enabled` property.
-- @returns with_errors, enabled_mods_by_name
local function check_mod_configuration(world_path, all_mods)
	-- Build up lookup tables for enabled mods and all mods by vpath
	local enabled_mod_paths = {}
	local all_mods_by_vpath = {}
	for _, mod in ipairs(all_mods)  do
		if mod.type == "mod" then
			all_mods_by_vpath[mod.virtual_path] = mod
		end
		if mod.enabled then
			enabled_mod_paths[mod.virtual_path] = mod.path
		end
	end

	-- Use the engine's mod configuration code to resolve dependencies and return any errors
	local config_status = core.check_mod_configuration(world_path, enabled_mod_paths)

	-- Build the list of enabled mod virtual paths
	local enabled_mods_by_name = {}
	for _, mod in ipairs(config_status.satisfied_mods) do
		assert(mod.virtual_path ~= "")
		enabled_mods_by_name[mod.name] = all_mods_by_vpath[mod.virtual_path] or mod
	end
	for _, mod in ipairs(config_status.unsatisfied_mods) do
		assert(mod.virtual_path ~= "")
		enabled_mods_by_name[mod.name] = all_mods_by_vpath[mod.virtual_path] or mod
	end

	-- Build the table of errors
	local with_error = {}
	for _, mod in ipairs(config_status.unsatisfied_mods) do
		local error = { type = "warning" }
		with_error[mod.virtual_path] = error

		for _, depname in ipairs(mod.unsatisfied_depends) do
			if not enabled_mods_by_name[depname] then
				error.type = "error"
				break
			end
		end
	end

	return with_error, enabled_mods_by_name
end

local function get_formspec(data)
	if not data.list then
		init_data(data)
	end

	local all_mods = data.list:get_list()
	local with_error, enabled_mods_by_name = check_mod_configuration(data.worldspec.path, all_mods)

	local mod = all_mods[data.selected_mod] or {name = ""}

	local retval =
		"size[12,7.5,true]" ..
		"label[0.5,0;" .. fgettext("World:") .. "]" ..
		"label[1.75,0;" .. core.formspec_escape(data.worldspec.name) .. "]"

	-- Only show mod info if it's an external mod (not game content)
	if mod.name ~= "" and not mod.is_game_content and mod.type ~= "game" then
		if mod.is_modpack then
			-- Show modpack description
			local info = core.formspec_escape(
				core.get_content_info(mod.path).description)
			if info == "" then
				info = fgettext("No modpack description provided.")
			end
			retval = retval ..
				"label[0,0.7;" .. fgettext("Modpack:") .. "]" ..
				"label[1.5,0.7;" .. core.formspec_escape(mod.name) .. "]" ..
				"textarea[0.25,1.25;5.25,5.5;;" .. info .. ";]"
		else
			-- Show mod dependencies
			local hard_deps, soft_deps = pkgmgr.get_dependencies(mod.path)

			-- Add error messages to dep lists
			if mod.enabled then
				for i, dep_name in ipairs(hard_deps) do
					local dep = enabled_mods_by_name[dep_name]
					if not dep then
						hard_deps[i] = mt_color_red .. dep_name .. " " .. fgettext("(Unsatisfied)")
					elseif with_error[dep.virtual_path] then
						hard_deps[i] = mt_color_orange .. dep_name .. " " .. fgettext("(Enabled, has error)")
					else
						hard_deps[i] = mt_color_green .. dep_name
					end
				end
				for i, dep_name in ipairs(soft_deps) do
					local dep = enabled_mods_by_name[dep_name]
					if dep and with_error[dep.virtual_path] then
						soft_deps[i] = mt_color_orange .. dep_name .. " " .. fgettext("(Enabled, has error)")
					elseif dep then
						soft_deps[i] = mt_color_green .. dep_name
					end
				end
			end

			local hard_deps_str = table.concat(hard_deps, ",")
			local soft_deps_str = table.concat(soft_deps, ",")

			retval = retval ..
				"label[0,0.7;" .. fgettext("Mod:") .. "]" ..
				"label[0.75,0.7;" .. core.formspec_escape(mod.name) .. "]"

			if hard_deps_str == "" then
				if soft_deps_str == "" then
					retval = retval ..
						"label[0,1.25;" ..
						fgettext("No (optional) dependencies") .. "]"
				else
					retval = retval ..
						"label[0,1.25;" .. fgettext("No hard dependencies") ..
						"]" ..
						"label[0,1.75;" .. fgettext("Optional dependencies:") ..
						"]" ..
						"textlist[0,2.25;5,4;world_config_optdepends;" ..
						soft_deps_str .. ";0]"
				end
			else
				if soft_deps_str == "" then
					retval = retval ..
						"label[0,1.25;" .. fgettext("Dependencies:") .. "]" ..
						"textlist[0,1.75;5,4;world_config_depends;" ..
						hard_deps_str .. ";0]" ..
						"label[0,6;" .. fgettext("No optional dependencies") .. "]"
				else
					retval = retval ..
						"label[0,1.25;" .. fgettext("Dependencies:") .. "]" ..
						"textlist[0,1.75;5,2.125;world_config_depends;" ..
						hard_deps_str .. ";0]" ..
						"label[0,3.9;" .. fgettext("Optional dependencies:") ..
						"]" ..
						"textlist[0,4.375;5,1.8;world_config_optdepends;" ..
						soft_deps_str .. ";0]"
				end
			end
		end
	else
		-- No mod selected or game content - show instruction
		retval = retval ..
			"label[0,1;" .. fgettext("Select a mod from the list") .. "]"
	end

	retval = retval ..
		"button[3.25,7;2.5,0.5;btn_config_world_save;" ..
		fgettext("Save") .. "]" ..
		"button[5.75,7;2.5,0.5;btn_config_world_cancel;" ..
		fgettext("Cancel") .. "]" ..
		"style[btn_config_world_cdb;bgcolor=#ffc107]" ..
		"button[9.25,7;2.5,0.5;btn_config_world_cdb;" ..
		fgettext("Find More Mods") .. "]"

	-- Enable/Disable all buttons
	if enabled_all then
		retval = retval ..
			"button[9.55,0.125;2.5,0.5;btn_disable_all_mods;" ..
			fgettext("Disable all") .. "]"
	else
		retval = retval ..
			"button[9.55,0.125;2.5,0.5;btn_enable_all_mods;" ..
			fgettext("Enable all") .. "]"
	end

	-- Render mod list using textlist with large font (like worlds)
	local modlist_str, filtered_to_original, external_mods = render_modlist_textlist(all_mods, with_error)
	
	-- Store for event handling
	data.filtered_to_original = filtered_to_original
	data.external_mods = external_mods
	
	-- Find selected index in filtered list
	local filtered_selected = 1
	for fi, oi in ipairs(filtered_to_original) do
		if oi == data.selected_mod then
			filtered_selected = fi
			break
		end
	end
	
	if #external_mods == 0 then
		retval = retval ..
			"box[5.5,0.75;6,6;#1a1a1a]" ..
			"label[7,3.5;" .. fgettext("No external mods installed") .. "]"
	else
		-- Textlist with large font (like worlds list)
		retval = retval ..
			"style[world_config_modlist;font_size=*1.6]" ..
			"textlist[5.5,0.75;6,6;world_config_modlist;" .. modlist_str .. ";" .. filtered_selected .. "]"
		
		-- Checkbox with label for toggling selected mod (left side, below world name)
		local selected_external_mod = external_mods[filtered_selected]
		if selected_external_mod then
			local is_enabled = selected_external_mod.enabled
			if selected_external_mod.is_modpack then
				is_enabled = pkgmgr.is_modpack_entirely_enabled(data, selected_external_mod.name)
			end
			local cb_img = is_enabled
				and core.formspec_escape(defaulttexturedir .. "checkbox_64.png")
				or core.formspec_escape(defaulttexturedir .. "blank.png")
			
			-- Checkbox on the left with "Mod active" label
			retval = retval ..
				"image_button[5.5,0.05;0.6,0.6;" .. cb_img .. ";btn_toggle_mod;]" ..
				"label[6.15,0.1;" .. fgettext("Mod active") .. "]"
		end
	end
	
	return retval
end

local function handle_buttons(this, fields)
	-- Handle toggle button for selected mod
	if fields.btn_toggle_mod then
		local filtered_to_original = this.data.filtered_to_original
		local external_mods = this.data.external_mods
		if filtered_to_original and external_mods then
			-- Find which filtered index is currently selected
			local filtered_selected = 1
			for fi, oi in ipairs(filtered_to_original) do
				if oi == this.data.selected_mod then
					filtered_selected = fi
					break
				end
			end
			local mod = external_mods[filtered_selected]
			if mod then
				if mod.is_modpack then
					local entirely_enabled = pkgmgr.is_modpack_entirely_enabled(this.data, mod.name)
					pkgmgr.enable_mod(this, not entirely_enabled)
				else
					pkgmgr.enable_mod(this, not mod.enabled)
				end
			end
		end
		return true
	end
	
	-- Handle textlist selection
	if fields.world_config_modlist then
		local event = core.explode_textlist_event(fields.world_config_modlist)
		local filtered_to_original = this.data.filtered_to_original
		if filtered_to_original and event.index > 0 and event.index <= #filtered_to_original then
			this.data.selected_mod = filtered_to_original[event.index]
			core.settings:set("world_config_selected_mod", this.data.selected_mod)
			
			-- Double-click toggles mod
			if event.type == "DCL" then
				pkgmgr.enable_mod(this)
			end
		end
		return true
	end

	if fields.key_enter then
		pkgmgr.enable_mod(this)
		return true
	end

	if fields.btn_config_world_save then
		local filename = this.data.worldspec.path .. DIR_DELIM .. "world.mt"

		local worldfile = Settings(filename)
		local mods = worldfile:to_table()

		local rawlist = this.data.list:get_raw_list()
		local was_set = {}

		for i = 1, #rawlist do
			local mod = rawlist[i]
			if not mod.is_modpack and
					not mod.is_game_content then
				if modname_valid(mod.name) then
					if mod.enabled then
						worldfile:set("load_mod_" .. mod.name, mod.virtual_path)
						was_set[mod.name] = true
					elseif not was_set[mod.name] then
						worldfile:remove("load_mod_" .. mod.name)
					end
				elseif mod.enabled then
					gamedata.errormessage = fgettext_ne("Failed to enable mo" ..
							"d \"$1\" as it contains disallowed characters. " ..
							"Only characters [a-z0-9_] are allowed.",
							mod.name)
				end
				mods["load_mod_" .. mod.name] = nil
			end
		end

		-- Remove mods that are not present anymore
		for key in pairs(mods) do
			if key:sub(1, 9) == "load_mod_" then
				worldfile:remove(key)
			end
		end

		if not worldfile:write() then
			core.log("error", "Failed to write world config file")
		end

		this:delete()
		return true
	end

	if fields.btn_config_world_cancel then
		this:delete()
		return true
	end

	if fields.btn_config_world_cdb then
		this.data.list = nil

		local dlg = create_contentdb_dlg("mod")
		dlg:set_parent(this)
		this:hide()
		dlg:show()
		return true
	end

	if fields.btn_enable_all_mods then
		local list = this.data.list:get_raw_list()

		-- When multiple copies of a mod are installed, we need to avoid enabling multiple of them
		-- at a time. So lets first collect all the enabled mods, and then use this to exclude
		-- multiple enables.

		local was_enabled = {}
		for i = 1, #list do
			if not list[i].is_game_content
					and not list[i].is_modpack and list[i].enabled then
				was_enabled[list[i].name] = true
			end
		end

		for i = 1, #list do
			if not list[i].is_game_content and not list[i].is_modpack and
					not was_enabled[list[i].name] then
				list[i].enabled = true
				was_enabled[list[i].name] = true
			end
		end

		enabled_all = true
		return true
	end

	if fields.btn_disable_all_mods then
		local list = this.data.list:get_raw_list()

		for i = 1, #list do
			if not list[i].is_game_content
					and not list[i].is_modpack then
				list[i].enabled = false
			end
		end
		enabled_all = false
		return true
	end

	return false
end

function create_configure_world_dlg(worldidx)
	local dlg = dialog_create("sp_config_world", get_formspec, handle_buttons)

	dlg.data.selected_mod = tonumber(
			core.settings:get("world_config_selected_mod")) or 0

	dlg.data.worldspec = core.get_worlds()[worldidx]
	if not dlg.data.worldspec then
		dlg:delete()
		return
	end

	dlg.data.worldconfig = pkgmgr.get_worldconfig(dlg.data.worldspec.path)

	if not dlg.data.worldconfig or not dlg.data.worldconfig.id or
			dlg.data.worldconfig.id == "" then
		dlg:delete()
		return
	end

	return dlg
end
