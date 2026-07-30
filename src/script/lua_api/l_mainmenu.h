// Luanti
// SPDX-License-Identifier: LGPL-2.1-or-later
// Copyright (C) 2013 sapier

#pragma once

#include "lua_api/l_base.h"


/** Implementation of lua api support for mainmenu */
class ModApiMainMenu: public ModApiBase
{

private:
	/**
	 * read a text variable from gamedata table within lua stack
	 * @param L stack to read variable from
	 * @param name name of variable to read
	 * @return string value of requested variable
	 */
	static std::string getTextData(lua_State *L, const std::string &name);

	/**
	 * read an integer variable from gamedata table within lua stack
	 * @param L stack to read variable from
	 * @param name name of variable to read
	 * @return integer value of requested variable
	 */
	static int getIntegerData(lua_State *L, const std::string &name, bool& valid);

	/**
	 * read a bool variable from gamedata table within lua stack
	 * @param L stack to read variable from
	 * @param name name of variable to read
	 * @return bool value of requested variable
	 */
	static int getBoolData(lua_State *L, const std::string &name ,bool& valid);

	//api calls

	static int l_start(lua_State *L);

	static int l_close(lua_State *L);

	static int l_create_world(lua_State *L);

	static int l_delete_world(lua_State *L);

	static int l_get_worlds(lua_State *L);

	static int l_get_mapgen_names(lua_State *L);

	static int l_get_language(lua_State *L);

	//packages

	static int l_get_games(lua_State *L);

	static int l_get_content_info(lua_State *L);

	static int l_get_mod_list(lua_State *L);

	static int l_check_mod_configuration(lua_State *L);

	static int l_get_content_translation(lua_State *L);

	//gui

	static int l_show_touchscreen_layout(lua_State *L);

	static int l_show_path_select_dialog(lua_State *L);

	static int l_set_topleft_text(lua_State *L);

	static int l_set_clouds(lua_State *L);

	static int l_set_clouds_color(lua_State *L);

	static int l_set_sky_color(lua_State *L);

	static int l_get_textlist_index(lua_State *L);

	static int l_get_table_index(lua_State *L);

	static int l_set_background(lua_State *L);

	static int l_update_formspec(lua_State *L);

	static int l_set_formspec_prepend(lua_State *L);

	static int l_get_window_info(lua_State *L);

	static int l_get_active_renderer(lua_State *L);

	static int l_get_active_irrlicht_device(lua_State *L);

	//filesystem

	static int l_get_mainmenu_path(lua_State *L);

	static int l_get_user_path(lua_State *L);

	static int l_get_modpath(lua_State *L);

	static int l_get_modpaths(lua_State *L);

	static int l_get_clientmodpath(lua_State *L);

	static int l_get_gamepath(lua_State *L);

	static int l_get_texturepath(lua_State *L);

	static int l_get_texturepath_share(lua_State *L);

	static int l_get_cache_path(lua_State *L);

	static int l_get_temp_path(lua_State *L);

	static int l_create_dir(lua_State *L);

	static int l_delete_dir(lua_State *L);

	static int l_copy_dir(lua_State *L);

	static int l_is_dir(lua_State *L);

	static int l_extract_zip(lua_State *L);

	static int l_may_modify_path(lua_State *L);

	static int l_download_file(lua_State *L);

	//version compatibility
	static int l_get_min_supp_proto(lua_State *L);

	static int l_get_max_supp_proto(lua_State *L);

	static int l_get_formspec_version(lua_State  *L);

	static int l_is_debug_build(lua_State  *L);

	// other
	static int l_open_url(lua_State *L);

	static int l_open_url_dialog(lua_State *L);

	static int l_open_dir(lua_State *L);

	static int l_share_file(lua_State *L);

	// Clipboard
	static int l_copy_to_clipboard(lua_State *L);

	// Analytics
	static int l_send_analytics_event(lua_State *L);
	static int l_send_world_created_event(lua_State *L);

	// LAN Discovery
	static int l_scan_lan_servers(lua_State *L);
	static int l_get_lan_servers(lua_State *L);
	static int l_clear_lan_servers(lua_State *L);

	// RuStore Pay SDK (commented out - replaced by YooKassa)
	/*
	static int l_rustore_has_subscription(lua_State *L);
	static int l_rustore_check_subscription_async(lua_State *L);
	static int l_rustore_purchase_subscription(lua_State *L);
	static int l_rustore_get_expiration_date(lua_State *L);
	static int l_rustore_get_monthly_price(lua_State *L);
	static int l_rustore_get_trial_price(lua_State *L);
	static int l_rustore_get_trial_days(lua_State *L);
	static int l_rustore_get_promo_price(lua_State *L);
	static int l_rustore_get_promo_days(lua_State *L);
	static int l_rustore_is_product_info_fetched(lua_State *L);
	static int l_rustore_is_operation_in_progress(lua_State *L);
	static int l_rustore_get_last_operation_result(lua_State *L);
	static int l_rustore_get_last_error(lua_State *L);
	static int l_rustore_clear_operation_result(lua_State *L);
	static int l_rustore_restore_purchases(lua_State *L);
	static int l_rustore_fetch_product_info(lua_State *L);
	static int l_rustore_clear_cache(lua_State *L);
	*/

	// YooKassa Pay SDK
	static int l_yookassa_has_purchase(lua_State *L);
	static int l_yookassa_start_purchase(lua_State *L);
	static int l_yookassa_get_device_uuid(lua_State *L);
	static int l_yookassa_get_product_price(lua_State *L);
	static int l_yookassa_get_product_amount(lua_State *L);
	static int l_yookassa_get_product_currency(lua_State *L);
	static int l_yookassa_is_product_info_fetched(lua_State *L);
	static int l_yookassa_is_operation_in_progress(lua_State *L);
	static int l_yookassa_get_last_operation_result(lua_State *L);
	static int l_yookassa_get_last_error(lua_State *L);
	static int l_yookassa_clear_operation_result(lua_State *L);
	static int l_yookassa_restore_purchases(lua_State *L);
	static int l_yookassa_fetch_product_info(lua_State *L);
	static int l_yookassa_clear_cache(lua_State *L);
	static int l_yookassa_get_pending_confirmation_url(lua_State *L);
	static int l_yookassa_get_pending_payment_method_type(lua_State *L);
	static int l_yookassa_start_confirmation(lua_State *L);
	static int l_check_activity_resumed(lua_State *L);

	// Native purchase dialog overlay
	static int l_show_native_purchase_dialog(lua_State *L);

	// async
	static int l_do_async_callback(lua_State *L);

public:

	/**
	 * initialize this API module
	 * @param L lua stack to initialize
	 * @param top index (in lua stack) of global API table
	 */
	static void Initialize(lua_State *L, int top);

	static void InitializeAsync(lua_State *L, int top);

};
