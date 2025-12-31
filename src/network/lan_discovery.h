// Luanti
// SPDX-License-Identifier: LGPL-2.1-or-later
// VocoCraft LAN Server Discovery

#pragma once

#include <string>
#include <vector>
#include <mutex>
#include <thread>
#include <atomic>
#include "network/socket.h"
#include "network/address.h"

#define LAN_DISCOVERY_PORT 30001
#define LAN_DISCOVERY_MAGIC "VOCO_DISCOVER"
#define LAN_DISCOVERY_RESPONSE "VOCO_SERVER"

struct LANServerInfo {
	std::string address;
	u16 port;
	std::string name;
	std::string description;
	u32 clients;
	u32 clients_max;
	bool creative;
	bool damage;
	bool pvp;
	std::string gameid;
	std::string version;
	u64 discovered_at; // timestamp when discovered
};

/**
 * LAN Discovery Server - runs on game server to respond to discovery requests
 */
class LANDiscoveryServer {
public:
	LANDiscoveryServer();
	~LANDiscoveryServer();

	// Start listening for discovery requests
	bool start(u16 game_port);
	void stop();
	bool isRunning() const { return m_running; }

	// Update server info (call when players join/leave etc)
	void updateServerInfo(const std::string &name, const std::string &description,
		u32 clients, u32 clients_max, bool creative, bool damage, bool pvp,
		const std::string &gameid);
	
	// Quick update just for player count
	void setPlayerCount(u32 count);

private:
	void serverThread();
	std::string buildResponse();

	std::thread m_thread;
	std::atomic<bool> m_running{false};
	std::atomic<bool> m_stop_requested{false};
	
	u16 m_game_port = 30000;
	std::mutex m_info_mutex;
	std::string m_name;
	std::string m_description;
	u32 m_clients = 0;
	u32 m_clients_max = 8;
	bool m_creative = false;
	bool m_damage = true;
	bool m_pvp = false;
	std::string m_gameid;
};

/**
 * LAN Discovery Client - scans for servers on local network
 * Runs background scanning automatically every few seconds
 */
class LANDiscoveryClient {
public:
	LANDiscoveryClient();
	~LANDiscoveryClient();

	// Start/stop background scanning
	void startBackgroundScan(int interval_ms = 3000);
	void stopBackgroundScan();
	
	// Perform a single network scan (blocking, with timeout)
	void scan(int timeout_ms = 2000);
	
	// Get discovered servers
	std::vector<LANServerInfo> getServers();
	
	// Clear discovered servers
	void clear();

private:
	bool parseResponse(const std::string &data, const Address &sender, LANServerInfo &info);
	void backgroundThread();
	
	std::mutex m_servers_mutex;
	std::vector<LANServerInfo> m_servers;
	
	// Background scanning
	std::thread m_bg_thread;
	std::atomic<bool> m_bg_running{false};
	std::atomic<bool> m_bg_stop_requested{false};
	int m_scan_interval_ms = 3000;
};

// Global instances
extern LANDiscoveryServer *g_lan_discovery_server;
extern LANDiscoveryClient *g_lan_discovery_client;

void init_lan_discovery();
void shutdown_lan_discovery();
