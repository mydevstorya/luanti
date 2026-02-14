// VocoCraft Trial Timer
// Tracks free play time (30 minutes total). Persists between sessions.
// When time expires during gameplay, shows unclosable purchase dialog.

#pragma once

#include <string>
#include <mutex>

class TrialTimer {
public:
	// Total free play time in seconds (30 minutes)
	static constexpr int TOTAL_TRIAL_SECONDS = 30 * 60;

	// How often to save to disk (every 10 seconds of play)
	static constexpr float SAVE_INTERVAL_SECONDS = 10.0f;

	static TrialTimer &getInstance();

	// Initialize: load saved time from file. Call once at startup.
	void init(const std::string &userPath);

	// Tick the timer during gameplay. Returns true if trial just expired this tick.
	// dtime = frame delta time in seconds. Only counts when game is unpaused.
	bool tick(float dtime);

	// Get remaining trial seconds (0 if expired)
	int getRemainingSeconds() const;

	// Check if trial has expired
	bool isExpired() const;

	// Check if user has purchased (bypasses timer)
	bool hasPurchase() const;

	// Force save current state to disk
	void save();

	// Reset timer (for testing only)
	void reset();

private:
	TrialTimer() = default;
	TrialTimer(const TrialTimer &) = delete;
	TrialTimer &operator=(const TrialTimer &) = delete;

	void loadFromFile();
	void saveToFile();

	std::string m_file_path;
	// Elapsed play seconds (increases from 0 to TOTAL_TRIAL_SECONDS)
	float m_elapsed_seconds = 0.0f;
	float m_save_accumulator = 0.0f;
	bool m_initialized = false;
	bool m_expired_triggered = false; // true after we've fired the expiry event
	mutable std::mutex m_mutex;
};
