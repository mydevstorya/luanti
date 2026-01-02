-- Luanti
-- Copyright (C) 2014 sapier
-- SPDX-License-Identifier: LGPL-2.1-or-later

-- === VOCOCRAFT: Mobile UI Configuration ===
-- Set to false to use original Luanti UI
local VOCOCRAFT_MOBILE_UI = true
-- === END VOCOCRAFT ===

-- === VOCOCRAFT: Mobile UI dimensions ===
if VOCOCRAFT_MOBILE_UI then
	MAIN_TAB_W = 16
	MAIN_TAB_H = 9.5
	TABHEADER_H = 0
	GAMEBAR_H = 0
	GAMEBAR_OFFSET_DESKTOP = 0
	GAMEBAR_OFFSET_TOUCH = 0
else
	MAIN_TAB_W = 15.5
	MAIN_TAB_H = 7.1
	TABHEADER_H = 0.85
	GAMEBAR_H = 1.25
	GAMEBAR_OFFSET_DESKTOP = 0.375
	GAMEBAR_OFFSET_TOUCH = 0.15
end
-- === END VOCOCRAFT ===

local menupath = core.get_mainmenu_path()
local basepath = core.get_builtin_path()
defaulttexturedir = core.get_texturepath_share() .. DIR_DELIM .. "base" ..
					DIR_DELIM .. "pack" .. DIR_DELIM

dofile(basepath .. "common" .. DIR_DELIM .. "menu.lua")
dofile(basepath .. "common" .. DIR_DELIM .. "filterlist.lua")
dofile(basepath .. "fstk" .. DIR_DELIM .. "buttonbar.lua")
dofile(basepath .. "fstk" .. DIR_DELIM .. "dialog.lua")
dofile(basepath .. "fstk" .. DIR_DELIM .. "tabview.lua")
dofile(basepath .. "fstk" .. DIR_DELIM .. "ui.lua")
dofile(menupath .. DIR_DELIM .. "async_event.lua")
dofile(menupath .. DIR_DELIM .. "common.lua")
dofile(menupath .. DIR_DELIM .. "serverlistmgr.lua")
dofile(menupath .. DIR_DELIM .. "game_theme.lua")

-- === VOCOCRAFT: Load subscription module BEFORE content (needed for mod install check) ===
dofile(menupath .. DIR_DELIM .. "vococraft" .. DIR_DELIM .. "subscription.lua")
dofile(menupath .. DIR_DELIM .. "vococraft" .. DIR_DELIM .. "dlg_subscription.lua")
dofile(menupath .. DIR_DELIM .. "vococraft" .. DIR_DELIM .. "content_filter.lua")
-- === END VOCOCRAFT ===

dofile(menupath .. DIR_DELIM .. "content" .. DIR_DELIM .. "init.lua")

dofile(menupath .. DIR_DELIM .. "dlg_config_world.lua")
dofile(basepath .. "common" .. DIR_DELIM .. "settings" .. DIR_DELIM .. "init.lua")

-- === VOCOCRAFT: Load mobile or standard dialogs ===
if VOCOCRAFT_MOBILE_UI then
	dofile(menupath .. DIR_DELIM .. "vococraft" .. DIR_DELIM .. "dlg_create_world.lua")
else
	dofile(menupath .. DIR_DELIM .. "dlg_create_world.lua")
end
-- === END VOCOCRAFT ===

dofile(menupath .. DIR_DELIM .. "dlg_delete_content.lua")
dofile(menupath .. DIR_DELIM .. "dlg_delete_world.lua")
dofile(menupath .. DIR_DELIM .. "dlg_register.lua")
dofile(menupath .. DIR_DELIM .. "dlg_rename_modpack.lua")
dofile(menupath .. DIR_DELIM .. "dlg_version_info.lua")
dofile(menupath .. DIR_DELIM .. "dlg_reinstall_mtg.lua")
dofile(menupath .. DIR_DELIM .. "dlg_rebind_keys.lua")
dofile(menupath .. DIR_DELIM .. "dlg_clients_list.lua")
dofile(menupath .. DIR_DELIM .. "dlg_server_list_mods.lua")

-- === VOCOCRAFT: Load mobile or standard tabs ===
local tabs = {}
if VOCOCRAFT_MOBILE_UI then
	tabs.local_game = dofile(menupath .. DIR_DELIM .. "vococraft" .. DIR_DELIM .. "tab_local.lua")
	tabs.about = dofile(menupath .. DIR_DELIM .. "tab_about.lua")
else
	tabs.content = dofile(menupath .. DIR_DELIM .. "tab_content.lua")
	tabs.about = dofile(menupath .. DIR_DELIM .. "tab_about.lua")
	tabs.local_game = dofile(menupath .. DIR_DELIM .. "tab_local.lua")
	tabs.play_online = dofile(menupath .. DIR_DELIM .. "tab_online.lua")
end
-- === END VOCOCRAFT ===

--------------------------------------------------------------------------------
local function main_event_handler(tabview, event)
	if event == "MenuQuit" then
		core.close()
	end
	return true
end

--------------------------------------------------------------------------------
local function init_globals()
	-- === VOCOCRAFT: Hide banner on mobile ===
	if VOCOCRAFT_MOBILE_UI and core.hide_banner then
		core.hide_banner()
	end
	-- === END VOCOCRAFT ===

	-- Init gamedata
	gamedata.worldindex = 0

	menudata.worldlist = filterlist.create(
		core.get_worlds,
		compare_worlds,
		-- Unique id comparison function
		function(element, uid)
			return element.name == uid
		end,
		-- Filter function
		function(element, gameid)
			return element.gameid == gameid
		end
	)

	menudata.worldlist:add_sort_mechanism("alphabetic", sort_worlds_alphabetic)
	menudata.worldlist:set_sortmode("alphabetic")

	mm_game_theme.init()
	mm_game_theme.set_engine() -- This is just a fallback.

	-- Create main tabview
	local tv_main = tabview_create("maintab", {x = MAIN_TAB_W, y = MAIN_TAB_H}, {x = 0, y = 0})

	tv_main:set_autosave_tab(true)
	tv_main:add(tabs.local_game)

	-- === VOCOCRAFT: Conditional tabs ===
	if not VOCOCRAFT_MOBILE_UI then
		tv_main:add(tabs.play_online)
		tv_main:add(tabs.content)
	end
	-- === END VOCOCRAFT ===

	tv_main:add(tabs.about)

	tv_main:set_global_event_handler(main_event_handler)
	tv_main:set_fixed_size(false)

	local last_tab = core.settings:get("maintab_LAST")
	if last_tab and tv_main.current_tab ~= last_tab then
		tv_main:set_tab(last_tab)
	end

	-- === VOCOCRAFT: No end button for mobile UI ===
	if not VOCOCRAFT_MOBILE_UI then
		tv_main:set_end_button({
			icon = defaulttexturedir .. "settings_btn.png",
			label = fgettext("Settings"),
			name = "open_settings",
			on_click = function(tabview)
				local dlg = create_settings_dlg()
				dlg:set_parent(tabview)
				tabview:hide()
				dlg:show()
				return true
			end,
		})
	end
	-- === END VOCOCRAFT ===

	ui.set_default("maintab")
	tv_main:show()
	ui.update()

	-- synchronous, chain parents to only show one at a time
	local parent = tv_main
	parent = migrate_keybindings(parent)
	check_reinstall_mtg(parent)

	-- asynchronous, will only be shown if we're still on "maintab"
	check_new_version()
end

assert(os.execute == nil)
init_globals()
