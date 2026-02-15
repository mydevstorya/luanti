// Luanti
// SPDX-License-Identifier: LGPL-2.1-or-later
// VocoCraft LAN Server Discovery

#include "lan_discovery.h"
#include "log.h"
#include "settings.h"
#include "version.h"
#include "porting.h"
#include <json/json.h>
#include "convert_json.h"

#ifdef _WIN32
#include <winsock2.h>
#include <ws2tcpip.h>
#else
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#endif

LANDiscoveryServer *g_lan_discovery_server = nullptr;
LANDiscoveryClient *g_lan_discovery_client = nullptr;

void init_lan_discovery()
{
	if (!g_lan_discovery_client) {
		g_lan_discovery_client = new LANDiscoveryClient();
		// Start automatic background scanning every 3 seconds
		g_lan_discovery_client->startBackgroundScan(3000);
	}
}

void shutdown_lan_discovery()
{
	if (g_lan_discovery_server) {
		g_lan_discovery_server->stop();
		delete g_lan_discovery_server;
		g_lan_discovery_server = nullptr;
	}
	if (g_lan_discovery_client) {
		g_lan_discovery_client->stopBackgroundScan();
		delete g_lan_discovery_client;
		g_lan_discovery_client = nullptr;
	}
}

//
// LANDiscoveryServer
//

LANDiscoveryServer::LANDiscoveryServer()
{
}

LANDiscoveryServer::~LANDiscoveryServer()
{
	stop();
}

bool LANDiscoveryServer::start(u16 game_port)
{
	if (m_running)
		return true;

	m_game_port = game_port;
	m_stop_requested = false;
	m_running = true;

	m_thread = std::thread(&LANDiscoveryServer::serverThread, this);
	
	infostream << "LAN Discovery Server started on port " << LAN_DISCOVERY_PORT << std::endl;
	return true;
}

void LANDiscoveryServer::stop()
{
	if (!m_running)
		return;

	m_stop_requested = true;
	
	if (m_thread.joinable()) {
		m_thread.join();
	}
	
	m_running = false;
	infostream << "LAN Discovery Server stopped" << std::endl;
}

void LANDiscoveryServer::updateServerInfo(const std::string &name, const std::string &description,
	u32 clients, u32 clients_max, bool creative, bool damage, bool pvp,
	const std::string &gameid)
{
	std::lock_guard<std::mutex> lock(m_info_mutex);
	m_name = name;
	m_description = description;
	m_clients = clients;
	m_clients_max = clients_max;
	m_creative = creative;
	m_damage = damage;
	m_pvp = pvp;
	m_gameid = gameid;
}

void LANDiscoveryServer::setPlayerCount(u32 count)
{
	std::lock_guard<std::mutex> lock(m_info_mutex);
	m_clients = count;
}

std::string LANDiscoveryServer::buildResponse()
{
	std::lock_guard<std::mutex> lock(m_info_mutex);
	
	Json::Value response;
	response["magic"] = LAN_DISCOVERY_RESPONSE;
	response["port"] = m_game_port;
	response["name"] = m_name;
	response["description"] = m_description;
	response["clients"] = m_clients;
	response["clients_max"] = m_clients_max;
	response["creative"] = m_creative;
	response["damage"] = m_damage;
	response["pvp"] = m_pvp;
	response["gameid"] = m_gameid;
	response["version"] = g_version_string;
	
	return fastWriteJson(response);
}

void LANDiscoveryServer::serverThread()
{
	try {
		UDPSocket socket(false); // IPv4
		
		// Enable broadcast and address reuse
		int broadcast = 1;
		int reuse = 1;
		setsockopt(socket.GetHandle(), SOL_SOCKET, SO_BROADCAST, 
			(const char*)&broadcast, sizeof(broadcast));
		setsockopt(socket.GetHandle(), SOL_SOCKET, SO_REUSEADDR, 
			(const char*)&reuse, sizeof(reuse));
		
		Address bind_addr(0, 0, 0, 0, LAN_DISCOVERY_PORT);
		socket.Bind(bind_addr);
		socket.setTimeoutMs(500); // 500ms timeout for checking stop flag
		
		char buffer[1024];
		
		while (!m_stop_requested) {
			Address sender;
			int received = socket.Receive(sender, buffer, sizeof(buffer) - 1);
			
			if (received > 0) {
				buffer[received] = '\0';
				std::string request(buffer);
				
				// Check if it's a discovery request
				if (request.find(LAN_DISCOVERY_MAGIC) == 0) {
					std::string response = buildResponse();
					socket.Send(sender, response.c_str(), response.size());
					
					verbosestream << "LAN Discovery: Responded to " 
						<< sender.serializeString() << std::endl;
				}
			}
		}
	} catch (const std::exception &e) {
		errorstream << "LAN Discovery Server error: " << e.what() << std::endl;
	}
	
	m_running = false;
}

//
// LANDiscoveryClient
//

LANDiscoveryClient::LANDiscoveryClient()
{
}

LANDiscoveryClient::~LANDiscoveryClient()
{
	stopBackgroundScan();
}

void LANDiscoveryClient::startBackgroundScan(int interval_ms)
{
	if (m_bg_running)
		return;
	
	m_scan_interval_ms = interval_ms;
	m_bg_stop_requested = false;
	m_bg_running = true;
	m_bg_thread = std::thread(&LANDiscoveryClient::backgroundThread, this);
	
	infostream << "LAN Discovery: Background scanning started (every " 
		<< interval_ms << "ms)" << std::endl;
}

void LANDiscoveryClient::stopBackgroundScan()
{
	if (!m_bg_running)
		return;
	
	m_bg_stop_requested = true;
	if (m_bg_thread.joinable())
		m_bg_thread.join();
	m_bg_running = false;
	
	infostream << "LAN Discovery: Background scanning stopped" << std::endl;
}

void LANDiscoveryClient::backgroundThread()
{
	while (!m_bg_stop_requested) {
		// Clear old servers before each scan - if server is still there, it will respond again
		{
			std::lock_guard<std::mutex> lock(m_servers_mutex);
			m_servers.clear();
		}
		
		// Do a quick scan
		scan(1000);  // 1 second timeout
		
		// Wait for interval (but check stop flag every 100ms)
		int waited = 0;
		while (waited < m_scan_interval_ms && !m_bg_stop_requested) {
			std::this_thread::sleep_for(std::chrono::milliseconds(100));
			waited += 100;
		}
	}
}

void LANDiscoveryClient::scan(int timeout_ms)
{
	try {
		UDPSocket socket(false); // IPv4
		
		// Enable broadcast
		int broadcast = 1;
		setsockopt(socket.GetHandle(), SOL_SOCKET, SO_BROADCAST, 
			(const char*)&broadcast, sizeof(broadcast));
		
		// Bind to any port
		Address bind_addr(0, 0, 0, 0, 0);
		socket.Bind(bind_addr);
		
		// Send broadcast discovery request
		std::string request = LAN_DISCOVERY_MAGIC;
		Address broadcast_addr(255, 255, 255, 255, LAN_DISCOVERY_PORT);
		socket.Send(broadcast_addr, request.c_str(), request.size());
		
		infostream << "LAN Discovery: Sent broadcast, waiting for responses..." << std::endl;
		
		// Wait for responses
		socket.setTimeoutMs(100);
		char buffer[4096];
		
		u64 start_time = porting::getTimeMs();
		u64 end_time = start_time + timeout_ms;
		
		while (porting::getTimeMs() < end_time) {
			Address sender;
			int received = socket.Receive(sender, buffer, sizeof(buffer) - 1);
			
			if (received > 0) {
				buffer[received] = '\0';
				std::string data(buffer);
				
				LANServerInfo info;
				if (parseResponse(data, sender, info)) {
					std::lock_guard<std::mutex> lock(m_servers_mutex);
					
					// Check if we already have this server (dedupe by port only - same PC may have multiple IPs)
					bool found = false;
					for (auto &existing : m_servers) {
						if (existing.port == info.port && existing.name == info.name) {
							// Update with most recent info, prefer non-loopback address
							if (info.address.find("127.") != 0 || existing.address.find("127.") == 0) {
								existing = info;
							}
							found = true;
							break;
						}
					}
					
					if (!found) {
						m_servers.push_back(info);
						infostream << "LAN Discovery: Found server '" << info.name 
							<< "' at " << info.address << ":" << info.port << std::endl;
					}
				}
			}
		}
		
		infostream << "LAN Discovery: Scan complete, found " << m_servers.size() << " servers" << std::endl;
		
	} catch (const std::exception &) {
		// Silently ignore — network may be unavailable (Wi-Fi off, local game, etc.)
	}
}

bool LANDiscoveryClient::parseResponse(const std::string &data, const Address &sender, LANServerInfo &info)
{
	try {
		Json::Value root;
		std::istringstream stream(data);
		stream >> root;
		
		if (!root.isMember("magic") || root["magic"].asString() != LAN_DISCOVERY_RESPONSE) {
			return false;
		}
		
		info.address = sender.serializeString();
		// Remove port from address if present
		size_t colon = info.address.find(':');
		if (colon != std::string::npos) {
			info.address = info.address.substr(0, colon);
		}
		
		info.port = root.get("port", 30000).asUInt();
		info.name = root.get("name", "Unknown Server").asString();
		info.description = root.get("description", "").asString();
		info.clients = root.get("clients", 0).asUInt();
		info.clients_max = root.get("clients_max", 8).asUInt();
		info.creative = root.get("creative", false).asBool();
		info.damage = root.get("damage", true).asBool();
		info.pvp = root.get("pvp", false).asBool();
		info.gameid = root.get("gameid", "").asString();
		info.version = root.get("version", "").asString();
		info.discovered_at = porting::getTimeMs();
		
		return true;
		
	} catch (const std::exception &e) {
		verbosestream << "LAN Discovery: Failed to parse response: " << e.what() << std::endl;
		return false;
	}
}

std::vector<LANServerInfo> LANDiscoveryClient::getServers()
{
	std::lock_guard<std::mutex> lock(m_servers_mutex);
	return m_servers;
}

void LANDiscoveryClient::clear()
{
	std::lock_guard<std::mutex> lock(m_servers_mutex);
	m_servers.clear();
}
