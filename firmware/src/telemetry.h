/*
 * telemetry.h
 *
 * Pocket Mechanic telemetry: one owner for the CAN decoder table, the latest
 * signal state and the schema-v1 JSON that the dashboard and the Android app
 * consume.
 *
 * Decoding is data-driven on purpose. Session A/B analyses produced a mix of
 * confirmed and probable signals, and the confidence of each one travels with
 * its table row so a probable guess can never be presented as a measurement.
 *
 * Only the six allowlisted IDs are stored. Everything else on the bus is
 * counted and dropped; nothing is queued.
 */

#ifndef TELEMETRY_H_
#define TELEMETRY_H_

#include <Arduino.h>
#include "config.h"

// Signals the API can expose. SIG_VOLT is not a CAN signal: it is sampled from
// the optional ADC1 divider in poll().
enum TelemetrySignal : uint8_t {
    SIG_RPM = 0,
    SIG_SPEED,
    SIG_DISTANCE,
    SIG_BRAKE,
    SIG_THROTTLE1,
    SIG_THROTTLE2,
    SIG_TEMP,
    SIG_STATUS_120_D1,
    SIG_STATUS_120_D4,
    SIG_STATUS_318_D2,
    SIG_VOLT,
    SIG_COUNT
};

enum class SignalConfidence : uint8_t {
    Confirmed,   // cross-checked against a second source or direct observation
    Probable,    // consistent with the captures, not independently calibrated
    Unverified   // candidate only; the API must not imply a physical unit
};

struct SignalDef {
    uint32_t id;          // CAN ID, standard 11-bit
    uint8_t byteOffset;   // 0 == D1, matching the capture notes
    uint8_t byteCount;    // 1, or 2 for a big-endian pair
    uint8_t bitMask;      // 0 = whole value, else single-bit test on the first byte
    float scale;          // applied to the decoded raw value
    const char *name;     // schema-v1 field name, where one exists
    SignalConfidence confidence;
};

struct TelemetrySample {
    float value;
    uint32_t updatedAt;
    bool valid;
};

struct TelemetrySnapshot {
    TelemetrySample samples[SIG_COUNT];
    bool canActive;
    bool everSeen;
    uint32_t canAgeMs;      // UINT32_MAX until the first accepted frame
    uint32_t frames;
};

class Telemetry {
public:
    void begin();

    // Decode one frame. Frames whose ID is not allowlisted are dropped here.
    void update(const CAN_FRAME &frame);

    // 1 Hz housekeeping: samples the optional ADC divider.
    void poll();

    bool canActive() const;
    uint32_t canAgeMs() const;
    uint32_t frames() const;
    uint32_t rejectedFrames() const;

    // Copies the state under a short critical section so the async HTTP task can
    // read it without racing the main loop.
    void snapshot(TelemetrySnapshot &out) const;

    // Schema v1. fastOnly emits the 10 Hz subset; the full form adds the slow
    // fields. Unavailable values are JSON null, never an inferred number.
    String json(bool fastOnly);

    float speedScale() const { return speedScale_; }
    bool setSpeedScale(float scale);

    static bool accepted(uint32_t id);
    static const SignalDef *definitions();
    static uint8_t definitionCount();

    // True when the sample is valid and younger than the freshness window.
    static bool fresh(const TelemetrySample &sample, uint32_t now);

    // Engine running from a snapshot: RPM is confirmed, 0x318 D2 only falls back
    // when no fresh RPM exists. Shared by the SSE and /api/live paths.
    static bool engineRunning(const TelemetrySnapshot &state, uint32_t now);

private:
    TelemetrySample samples_[SIG_COUNT];
    mutable portMUX_TYPE mux_ = portMUX_INITIALIZER_UNLOCKED;
    uint32_t lastFrameAt_;
    uint32_t frames_;
    uint32_t rejectedFrames_;
    uint32_t seq_;
    uint32_t lastAdcAt_;
    bool everSeen_;
    float speedScale_;
};

extern Telemetry telemetry;

#endif /* TELEMETRY_H_ */
