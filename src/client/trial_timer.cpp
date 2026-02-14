// VocoCraft Trial Timer implementation
// Persists elapsed play time to a simple binary file for tamper resistance.
// Also backs up to Android SharedPreferences via JNI (double protection).

#include "client/trial_timer.h"
#include "log.h"
#include "filesys.h"
#include "porting.h"

#ifdef __ANDROID__
#include "porting_android.h"
#endif

#include <fstream>
#include <cstring>
#include <algorithm>

// Simple obfuscation: XOR key for the save file
static constexpr uint32_t OBFUSCATION_KEY = 0xA7C3E1F9;
static constexpr uint32_t FILE_MAGIC = 0x56435446; // "VCTF" = VocoCraft Trial File

struct TrialFileData {
	uint32_t magic;
	uint32_t elapsed_seconds_x100; // elapsed * 100, XOR'd with key
	uint32_t checksum;             // simple checksum for integrity
};

static uint32_t computeChecksum(uint32_t magic, uint32_t data) {
	return (magic ^ data ^ 0x55AA55AA) + 12345;
}

TrialTimer &TrialTimer::getInstance() {
	static TrialTimer instance;
	return instance;
}

void TrialTimer::init(const std::string &userPath) {
	std::lock_guard<std::mutex> lock(m_mutex);
	if (m_initialized)
		return;

	m_file_path = userPath + DIR_DELIM + ".vctrial";
	m_initialized = true;
	m_elapsed_seconds = 0.0f;
	m_save_accumulator = 0.0f;
	m_expired_triggered = false;

	loadFromFile();

	int remaining = TOTAL_TRIAL_SECONDS - (int)m_elapsed_seconds;
	if (remaining < 0) remaining = 0;
	infostream << "[TrialTimer] Initialized. Elapsed: " << (int)m_elapsed_seconds
	           << "s, Remaining: " << remaining << "s" << std::endl;
}

bool TrialTimer::tick(float dtime) {
	// Skip if user has purchased
	if (hasPurchase())
		return false;

	std::lock_guard<std::mutex> lock(m_mutex);
	if (!m_initialized)
		return false;

	// Already expired and already triggered
	if (m_expired_triggered)
		return false;

	// Don't count negative or huge dt (lag spikes)
	if (dtime <= 0.0f || dtime > 1.0f)
		dtime = 0.0f;

	float old_elapsed = m_elapsed_seconds;
	m_elapsed_seconds += dtime;

	// Clamp to max
	if (m_elapsed_seconds > (float)TOTAL_TRIAL_SECONDS)
		m_elapsed_seconds = (float)TOTAL_TRIAL_SECONDS;

	// Periodic save
	m_save_accumulator += dtime;
	if (m_save_accumulator >= SAVE_INTERVAL_SECONDS) {
		m_save_accumulator = 0.0f;
		saveToFile();
	}

	// Check if just expired
	if (old_elapsed < (float)TOTAL_TRIAL_SECONDS &&
	    m_elapsed_seconds >= (float)TOTAL_TRIAL_SECONDS) {
		m_expired_triggered = true;
		saveToFile();
		infostream << "[TrialTimer] Trial expired!" << std::endl;
		return true; // trial just expired this tick
	}

	return false;
}

int TrialTimer::getRemainingSeconds() const {
	if (hasPurchase())
		return TOTAL_TRIAL_SECONDS; // purchased = unlimited

	std::lock_guard<std::mutex> lock(m_mutex);

	// If not initialized yet (called from main menu before game starts),
	// try to get from Android SharedPreferences backup
	if (!m_initialized) {
#ifdef __ANDROID__
		int backup = porting::getTrialElapsedSeconds();
		if (backup > 0) {
			int remaining = TOTAL_TRIAL_SECONDS - backup;
			return std::max(remaining, 0);
		}
#endif
		return TOTAL_TRIAL_SECONDS; // no data yet = full trial
	}

	int remaining = TOTAL_TRIAL_SECONDS - (int)m_elapsed_seconds;
	return std::max(remaining, 0);
}

bool TrialTimer::isExpired() const {
	if (hasPurchase())
		return false;

	std::lock_guard<std::mutex> lock(m_mutex);

	if (!m_initialized) {
#ifdef __ANDROID__
		int backup = porting::getTrialElapsedSeconds();
		return backup >= TOTAL_TRIAL_SECONDS;
#endif
		return false;
	}

	return m_elapsed_seconds >= (float)TOTAL_TRIAL_SECONDS;
}

bool TrialTimer::hasPurchase() const {
#ifdef __ANDROID__
	return porting::yookassaHasPurchase();
#else
	return true; // PC = always unlocked
#endif
}

void TrialTimer::save() {
	std::lock_guard<std::mutex> lock(m_mutex);
	if (m_initialized)
		saveToFile();
}

void TrialTimer::reset() {
	std::lock_guard<std::mutex> lock(m_mutex);
	m_elapsed_seconds = 0.0f;
	m_save_accumulator = 0.0f;
	m_expired_triggered = false;
	if (m_initialized)
		saveToFile();
	infostream << "[TrialTimer] Reset to 0" << std::endl;
}

void TrialTimer::loadFromFile() {
	// Try loading from file first
	std::ifstream file(m_file_path, std::ios::binary);
	if (file.is_open()) {
		TrialFileData data;
		file.read(reinterpret_cast<char*>(&data), sizeof(data));
		file.close();

		if (data.magic == FILE_MAGIC) {
			uint32_t check = computeChecksum(data.magic, data.elapsed_seconds_x100);
			if (check == data.checksum) {
				uint32_t raw = data.elapsed_seconds_x100 ^ OBFUSCATION_KEY;
				m_elapsed_seconds = (float)raw / 100.0f;

				// Sanity check
				if (m_elapsed_seconds < 0.0f)
					m_elapsed_seconds = 0.0f;
				if (m_elapsed_seconds > (float)TOTAL_TRIAL_SECONDS)
					m_elapsed_seconds = (float)TOTAL_TRIAL_SECONDS;

				if (m_elapsed_seconds >= (float)TOTAL_TRIAL_SECONDS)
					m_expired_triggered = true;

				infostream << "[TrialTimer] Loaded from file: " << (int)m_elapsed_seconds << "s" << std::endl;
				return;
			} else {
				warningstream << "[TrialTimer] File checksum mismatch, checking backup" << std::endl;
			}
		}
	}

#ifdef __ANDROID__
	// Try loading from Android SharedPreferences backup
	int backup_seconds = porting::getTrialElapsedSeconds();
	if (backup_seconds > 0) {
		m_elapsed_seconds = (float)backup_seconds;
		if (m_elapsed_seconds > (float)TOTAL_TRIAL_SECONDS)
			m_elapsed_seconds = (float)TOTAL_TRIAL_SECONDS;
		if (m_elapsed_seconds >= (float)TOTAL_TRIAL_SECONDS)
			m_expired_triggered = true;

		infostream << "[TrialTimer] Loaded from SharedPreferences backup: "
		           << backup_seconds << "s" << std::endl;
		// Re-save to file
		saveToFile();
		return;
	}
#endif

	// No saved data — fresh install
	m_elapsed_seconds = 0.0f;
	infostream << "[TrialTimer] No saved data, starting fresh trial" << std::endl;
}

void TrialTimer::saveToFile() {
	// Save to binary file
	TrialFileData data;
	data.magic = FILE_MAGIC;
	uint32_t raw = (uint32_t)(m_elapsed_seconds * 100.0f);
	data.elapsed_seconds_x100 = raw ^ OBFUSCATION_KEY;
	data.checksum = computeChecksum(data.magic, data.elapsed_seconds_x100);

	std::ofstream file(m_file_path, std::ios::binary | std::ios::trunc);
	if (file.is_open()) {
		file.write(reinterpret_cast<const char*>(&data), sizeof(data));
		file.close();
	}

#ifdef __ANDROID__
	// Backup to SharedPreferences
	porting::saveTrialElapsedSeconds((int)m_elapsed_seconds);
#endif
}
