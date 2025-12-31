-- Luanti
-- Copyright (C) 2014 sapier
-- SPDX-License-Identifier: LGPL-2.1-or-later
-- VocoCraft Mobile UI - Create World Dialog

local function table_to_flags(ftable)
	local str = {}
	for flag, is_set in pairs(ftable) do
		str[#str + 1] = is_set and flag or ("no" .. flag)
	end
	return table.concat(str, ",")
end

--------------------------------------------------------------------------------
-- CREATE WORLD FORMSPEC
--------------------------------------------------------------------------------
local function create_world_formspec(dialogdata)
	local W = 16
	local H = 9.5

	local current_mg = dialogdata.mg
	local mapgens = core.get_mapgen_names()

	local game = pkgmgr.find_by_gameid(core.settings:get("menu_last_game"))
	if game == nil and #pkgmgr.games > 0 then
		game = pkgmgr.games[1]
		core.settings:set("menu_last_game", game.id)
	end

	-- Filter mapgens
	if game then
		local gameconfig = Settings(game.path.."/game.conf")
		local allowed_mapgens = (gameconfig:get("allowed_mapgens") or ""):split()
		local disallowed_mapgens = (gameconfig:get("disallowed_mapgens") or ""):split()
		
		if #allowed_mapgens > 0 then
			for i = #mapgens, 1, -1 do
				if table.indexof(allowed_mapgens, mapgens[i]) == -1 then
					table.remove(mapgens, i)
				end
			end
		end
		if #disallowed_mapgens > 0 then
			for i = #mapgens, 1, -1 do
				if table.indexof(disallowed_mapgens, mapgens[i]) > 0 then
					table.remove(mapgens, i)
				end
			end
		end
	end

	-- Build mapgen list
	local mglist = ""
	local selindex = 1
	for k, v in ipairs(mapgens) do
		if current_mg == v then
			selindex = k
		end
		mglist = mglist .. core.formspec_escape(v) .. ","
	end
	mglist = mglist:sub(1, -2)

	local is_creative = dialogdata.creative_mode or false

	local fs = {}

	table.insert(fs, "formspec_version[6]")
	table.insert(fs, string.format("size[%f,%f]", W, H))
	table.insert(fs, "bgcolor[#313131;both]")
	table.insert(fs, "box[0,0;" .. W .. "," .. H .. ";#313131]")

	-- ============ HEADER ============
	local HEADER_H = 1.0
	table.insert(fs, "box[0,0;" .. W .. "," .. HEADER_H .. ";#1e1e1e]")
	table.insert(fs, "box[0.2,0.15;1,0.7;#3a3a3a]")
	table.insert(fs, "button[0.2,0.15;1,0.7;world_create_cancel;<]")
	table.insert(fs, "style_type[label;font_size=*1.3]")
	table.insert(fs, "label[1.5,0.5;" .. fgettext("Create New World") .. "]")

	-- ============ MAIN CONTENT - Full width form ============
	local CONTENT_Y = HEADER_H + 0.3
	local FORM_X = 0.5
	local FORM_W = W - 1.0

	-- === FORM FIELDS ===
	local y = CONTENT_Y
	table.insert(fs, "style_type[label;font_size=*1.1]")

	-- World name
	table.insert(fs, "label[" .. FORM_X .. "," .. y .. ";" .. fgettext("World name") .. "]")
	y = y + 0.5
	table.insert(fs, "field[" .. FORM_X .. "," .. y .. ";" .. FORM_W .. ",0.8;te_world_name;;" .. 
		core.formspec_escape(dialogdata.worldname) .. "]")
	y = y + 1.1

	-- Seed
	table.insert(fs, "label[" .. FORM_X .. "," .. y .. ";" .. fgettext("Seed (optional)") .. "]")
	y = y + 0.5
	table.insert(fs, "field[" .. FORM_X .. "," .. y .. ";" .. FORM_W .. ",0.8;te_seed;;" .. 
		core.formspec_escape(dialogdata.seed) .. "]")
	y = y + 1.1

	-- World type
	table.insert(fs, "label[" .. FORM_X .. "," .. y .. ";" .. fgettext("World type") .. "]")
	y = y + 0.5
	table.insert(fs, "dropdown[" .. FORM_X .. "," .. y .. ";" .. FORM_W .. ",0.8;dd_mapgen;" .. mglist .. ";" .. selindex .. "]")
	y = y + 1.2

	-- CREATE button - BIG GREEN at bottom
	table.insert(fs, "style[world_create_confirm;bgcolor=#4CAF50;font_size=*1.4]")
	table.insert(fs, "button[" .. FORM_X .. "," .. y .. ";" .. FORM_W .. ",1.0;world_create_confirm;" .. fgettext("Create") .. "]")

	return table.concat(fs)
end

--------------------------------------------------------------------------------
-- BUTTON HANDLER
--------------------------------------------------------------------------------
local function create_world_buttonhandler(this, fields)
	if fields["world_create_cancel"] then
		this:delete()
		return true
	end

	if fields["btn_survival"] then
		this.data.creative_mode = false
		core.settings:set("creative_mode", "false")
		return true
	end
	if fields["btn_creative"] then
		this.data.creative_mode = true
		core.settings:set("creative_mode", "true")
		return true
	end

	if fields["world_create_confirm"] or fields["key_enter"] then
		if fields["key_enter"] then
			this.parent.dlg_create_world_closed_at = core.get_us_time()
		end

		local worldname = fields["te_world_name"]
		local game, _ = pkgmgr.find_by_gameid(core.settings:get("menu_last_game"))

		local message
		if game == nil then
			message = fgettext_ne("No game selected")
		end

		if message == nil then
			if worldname == "" then
				local worldnum_max = 0
				for _, world in ipairs(menudata.worldlist:get_list()) do
					if world.name:match("^world%d+$") then
						local worldnum = tonumber(world.name:sub(6))
						worldnum_max = math.max(worldnum_max, worldnum)
					end
				end
				worldname = "world" .. worldnum_max + 1
			end

			if menudata.worldlist:uid_exists_raw(worldname) then
				message = fgettext_ne("A world named \"$1\" already exists", worldname)
			end
		end

		if message == nil then
			this.data.seed = fields["te_seed"] or ""
			this.data.mg = fields["dd_mapgen"]

			local settings = {
				fixed_map_seed = this.data.seed,
				mg_name = this.data.mg,
				mg_flags = table_to_flags(this.data.flags.main),
				mgv5_spflags = table_to_flags(this.data.flags.v5),
				mgv6_spflags = table_to_flags(this.data.flags.v6),
				mgv7_spflags = table_to_flags(this.data.flags.v7),
				mgfractal_spflags = table_to_flags(this.data.flags.fractal),
				mgcarpathian_spflags = table_to_flags(this.data.flags.carpathian),
				mgvalleys_spflags = table_to_flags(this.data.flags.valleys),
				mgflat_spflags = table_to_flags(this.data.flags.flat),
			}
			message = core.create_world(worldname, game.id, settings)
		end

		if message == nil then
			if core.send_world_created_event then
				core.send_world_created_event(worldname, game.id, this.data.mg or "v7")
			end

			core.settings:set("menu_last_game", game.id)
			menudata.worldlist:set_filtercriteria(game.id)
			menudata.worldlist:refresh()
			
			-- Set enable_damage = true for new worlds by default
			local new_world_raw_idx = menudata.worldlist:raw_index_by_uid(worldname)
			core.settings:set("mainmenu_last_selected_world", new_world_raw_idx)
			core.settings:set_bool("enable_damage", true)
			
			-- Find filtered index and write to world.mt
			local new_world_idx = filterlist.get_current_index(menudata.worldlist, new_world_raw_idx)
			if new_world_idx then
				menu_worldmt(new_world_idx, "enable_damage", "true")
			end
		end

		gamedata.errormessage = message
		this:delete()
		return true
	end

	this.data.worldname = fields["te_world_name"] or this.data.worldname
	this.data.seed = fields["te_seed"] or this.data.seed

	if fields["dd_mapgen"] then
		this.data.mg = fields["dd_mapgen"]
		return true
	end

	return false
end

local function get_random_seed()
	return tostring(math.random(1000000000, 2147483647))
end

function create_create_world_dlg()
	local retval = dialog_create("sp_create_world",
		create_world_formspec,
		create_world_buttonhandler,
		nil)

	retval.data = {
		worldname = "",
		seed = get_random_seed(),
		mg = core.settings:get("mg_name"),
		creative_mode = core.settings:get_bool("creative_mode") or false,
		enable_damage = core.settings:get_bool("enable_damage") or true,
		flags = {
			main = core.settings:get_flags("mg_flags"),
			v5 = core.settings:get_flags("mgv5_spflags"),
			v6 = core.settings:get_flags("mgv6_spflags"),
			v7 = core.settings:get_flags("mgv7_spflags"),
			fractal = core.settings:get_flags("mgfractal_spflags"),
			carpathian = core.settings:get_flags("mgcarpathian_spflags"),
			valleys = core.settings:get_flags("mgvalleys_spflags"),
			flat = core.settings:get_flags("mgflat_spflags"),
		}
	}

	return retval
end
