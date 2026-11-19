/*
 * telemetry.cpp
 *
 * Decoder table, latest-state store and schema-v1 JSON for Pocket Mechanic.
 *
 * Failure model: a signal that has not been seen recently, or that this build
 * cannot decode, is reported as null. Nothing is interpolated, remembered past
 * the freshness window, or given a physical unit that the captures do not
 * support.
 */

#include "telemetry.h"

#include <Preferences.h>

Telemetry telemetry;

namespace {

constexpr uint32_t kFreshMs = 2000;   // a signal older than this is unavailable
constexpr uint32_t kCanActiveMs = 500;
constexpr float kDefaultSpeedScale = 2.2f;   // empirical, km/h per speed unit

// The only IDs this sniffer decodes. 0x122 and 0x13F are accepted for activity
// accounting and future cross-checks; neither has a decoder row yet.
const uint16_t kAcceptedIds[] = {0x120, 0x122, 0x124, 0x13F, 0x314, 0x318};

// Row order must match the TelemetrySignal enum.
const SignalDef kSignals[] = {
    // 0x124 D2-D3 big-endian x0.25. Confirmed: 0x13F D1-D2 mirrors it.
    {0x124, 1, 2, 0x00, 0.25f, "rpm", SignalConfidence::Confirmed},
    // 0x314 D2-D3 /256, then the configurable empirical factor. Probable only:
    // the scale was fitted to narrated speedometer readings (~4 km/h RMSE).
    {0x314, 1, 2, 0x00, 1.0f / 256.0f, "speed_kph", SignalConfidence::Probable},
    // 0x314 D4-D5 /256, wraps at 32. Cross-check only, never a distance source.
    {0x314, 3, 2, 0x00, 1.0f, "dist_raw", SignalConfidence::Probable},
    // 0x318 D1 bit 2. Probable: speed falls in most of its on-intervals.
    {0x318, 0, 1, 0x04, 1.0f, "brake", SignalConfidence::Probable},
    // 0x120 D3 / D5 raw bytes. Not pedal-linear; no percentage mapping exists.
    {0x120, 2, 1, 0x00, 1.0f, "thr1", SignalConfidence::Unverified},
    {0x120, 4, 1, 0x00, 1.0f, "thr2", SignalConfidence::Unverified},
    // 0x124 D4-D5 raw. Monotonic warm-up trend, saturates; not degrees C.
    {0x124, 3, 2, 0x00, 1.0f, "temp_raw", SignalConfidence::Unverified},
    // Engine-status bytes, fed to the engine_running heuristic only.
    {0x120, 0, 1, 0x00, 1.0f, nullptr, SignalConfidence::Unverified},
    {0x120, 3, 1, 0x00, 1.0f, nullptr, SignalConfidence::Unverified},
    {0x318, 1, 1, 0x00, 1.0f, nullptr, SignalConfidence::Unverified},
};

static_assert(sizeof(kSignals) / sizeof(kSignals[0]) == SIG_VOLT,
              "decoder table must cover exactly the CAN signals");

void addNull(String &out, const char *key)
{
    out += F(",\"");
    out += key;
    out += F("\":null");
}

void addInt(String &out, const char *key, bool ok, uint32_t value)
{
    if (!ok) {
        addNull(out, key);
        return;
    }
    out += F(",\"");
    out += key;
    out += F("\":");
    out += value;
}

void addFloat(String &out, const char *key, bool ok, float value, unsigned char decimals)
{
    if (!ok) {
        addNull(out, key);
        return;
    }
    out += F(",\"");
    out += key;
    out += F("\":");
    char buf[16];
    snprintf(buf, sizeof(buf), "%.*f", static_cast<int>(decimals), value);
    out += buf;
}

void addBool(String &out, const char *key, bool ok, bool value)
{
    if (!ok) {
        addNull(out, key);
        return;
    }
    out += F(",\"");
    out += key;
    out += F("\":");
    out += value ? F("true") : F("false");
}

} // namespace

void Telemetry::begin()
{
    for (uint8_t i = 0; i < SIG_COUNT; i++) {
        samples_[i].value = 0;
        samples_[i].updatedAt = 0;
        samples_[i].valid = false;
    }
    lastFrameAt_ = 0;
    frames_ = 0;
    rejectedFrames_ = 0;
    seq_ = 0;
    lastAdcAt_ = 0;
    everSeen_ = false;

    speedScale_ = kDefaultSpeedScale;
    Preferences prefs;
    if (prefs.begin(PREF_NAME, false)) {
        const float stored = prefs.getFloat("spdscale", kDefaultSpeedScale);
        if (stored >= 0.1f && stored <= 20.0f) speedScale_ = stored;
        prefs.end();
    }

#if POCKET_MECHANIC_ADC_VOLT
    analogSetPinAttenuation(POCKET_MECHANIC_ADC_PIN, ADC_11db);
#endif
}

bool Telemetry::accepted(uint32_t id)
{
    if (id > 0x7FF) return false;   // standard frames only
    for (uint8_t i = 0; i < sizeof(kAcceptedIds) / sizeof(kAcceptedIds[0]); i++) {
        if (kAcceptedIds[i] == id) return true;
    }
    return false;
}

const SignalDef *Telemetry::definitions()
{
    return kSignals;
}

uint8_t Telemetry::definitionCount()
{
    return sizeof(kSignals) / sizeof(kSignals[0]);
}

void Telemetry::update(const CAN_FRAME &frame)
{
    if (!accepted(frame.id)) {
        rejectedFrames_++;
        return;
    }

    const uint32_t now = millis();
    portENTER_CRITICAL(&mux_);
    lastFrameAt_ = now;
    everSeen_ = true;
    frames_++;
    for (uint8_t i = 0; i < definitionCount(); i++) {
        const SignalDef &def = kSignals[i];
        if (def.id != frame.id) continue;
        if (def.byteOffset + def.byteCount > frame.length) continue;

        uint32_t raw;
        if (def.byteCount == 2) {
            raw = (static_cast<uint32_t>(frame.data.uint8[def.byteOffset]) << 8)
                  | frame.data.uint8[def.byteOffset + 1];
        } else {
            raw = frame.data.uint8[def.byteOffset];
        }
        if (def.bitMask) raw = (raw & def.bitMask) ? 1 : 0;

        samples_[i].value = static_cast<float>(raw) * def.scale;
        samples_[i].updatedAt = now;
        samples_[i].valid = true;
    }
    portEXIT_CRITICAL(&mux_);
}

void Telemetry::poll()
{
#if POCKET_MECHANIC_ADC_VOLT
    const uint32_t now = millis();
    if (now - lastAdcAt_ < 1000) return;
    lastAdcAt_ = now;

    const float volts = (analogReadMilliVolts(POCKET_MECHANIC_ADC_PIN) / 1000.0f)
                        * POCKET_MECHANIC_ADC_DIVIDER;
    portENTER_CRITICAL(&mux_);
    samples_[SIG_VOLT].value = volts;
    samples_[SIG_VOLT].updatedAt = now;
    samples_[SIG_VOLT].valid = true;
    portEXIT_CRITICAL(&mux_);
#endif
}

bool Telemetry::canActive() const
{
    return everSeen_ && (millis() - lastFrameAt_) < kCanActiveMs;
}

uint32_t Telemetry::canAgeMs() const
{
    return everSeen_ ? millis() - lastFrameAt_ : UINT32_MAX;
}

uint32_t Telemetry::frames() const
{
    return frames_;
}

uint32_t Telemetry::rejectedFrames() const
{
    return rejectedFrames_;
}

void Telemetry::snapshot(TelemetrySnapshot &out) const
{
    const uint32_t now = millis();
    portENTER_CRITICAL(&mux_);
    for (uint8_t i = 0; i < SIG_COUNT; i++) out.samples[i] = samples_[i];
    out.everSeen = everSeen_;
    out.frames = frames_;
    out.canAgeMs = everSeen_ ? now - lastFrameAt_ : UINT32_MAX;
    out.canActive = everSeen_ && (now - lastFrameAt_) < kCanActiveMs;
    portEXIT_CRITICAL(&mux_);
}

bool Telemetry::fresh(const TelemetrySample &sample, uint32_t now)
{
    return sample.valid && (now - sample.updatedAt) < kFreshMs;
}

bool Telemetry::engineRunning(const TelemetrySnapshot &state, uint32_t now)
{
    // RPM is the confirmed signal: a running engine cannot sit below cranking
    // speed. The old 0x120 D1/D4 rule was wrong for ~40% of Session B samples
    // (D4 takes 2/3/4 while running), and 0x318 D2 == 6 also occurs with the
    // engine off, so it is only a fallback when no RPM is available.
    if (fresh(state.samples[SIG_RPM], now)) {
        return state.samples[SIG_RPM].value >= 400.0f;
    }
    if (fresh(state.samples[SIG_STATUS_318_D2], now)) {
        return static_cast<uint32_t>(state.samples[SIG_STATUS_318_D2].value) == 0x06;
    }
    return false;
}

bool Telemetry::setSpeedScale(float scale)
{
    if (!(scale >= 0.1f && scale <= 20.0f)) return false;
    speedScale_ = scale;
    Preferences prefs;
    if (!prefs.begin(PREF_NAME, false)) return false;
    prefs.putFloat("spdscale", scale);
    prefs.end();
    return true;
}

String Telemetry::json(bool fastOnly)
{
    TelemetrySnapshot state;
    snapshot(state);

    const uint32_t now = millis();
    bool fresh_[SIG_COUNT];
    for (uint8_t i = 0; i < SIG_COUNT; i++) fresh_[i] = fresh(state.samples[i], now);

    const bool engineKnown = fresh_[SIG_RPM] || fresh_[SIG_STATUS_318_D2];
    const bool engineRunningNow = engineRunning(state, now);

    String out;
    out.reserve(fastOnly ? 220 : 340);
    out += F("{\"v\":1,\"seq\":");
    out += ++seq_;
    out += F(",\"t_ms\":");
    out += now;

    addFloat(out, "rpm", fresh_[SIG_RPM], state.samples[SIG_RPM].value, 2);
    // The stored value is already /256; the empirical km/h factor stays outside
    // the table because it is user-calibratable at runtime.
    addFloat(out, "speed_kph", fresh_[SIG_SPEED],
             state.samples[SIG_SPEED].value * speedScale_, 1);
    addBool(out, "brake", fresh_[SIG_BRAKE], state.samples[SIG_BRAKE].value > 0.5f);
    addInt(out, "thr1", fresh_[SIG_THROTTLE1],
           static_cast<uint32_t>(state.samples[SIG_THROTTLE1].value));
    addInt(out, "thr2", fresh_[SIG_THROTTLE2],
           static_cast<uint32_t>(state.samples[SIG_THROTTLE2].value));
    addBool(out, "engine_running", engineKnown, engineRunningNow);
    addBool(out, "can_active", true, state.canActive);

    if (!fastOnly) {
        addInt(out, "temp_raw", fresh_[SIG_TEMP],
               static_cast<uint32_t>(state.samples[SIG_TEMP].value));
        addInt(out, "dist_raw", fresh_[SIG_DISTANCE],
               static_cast<uint32_t>(state.samples[SIG_DISTANCE].value));
        addFloat(out, "volt", fresh_[SIG_VOLT], state.samples[SIG_VOLT].value, 2);
        // Phase 2 fields: no decoder exists, so they stay null rather than being
        // guessed. The app hides widgets for null fields.
        addNull(out, "coolant_c");
        addNull(out, "load");
        addNull(out, "maf");
        addNull(out, "fuel_lvl");
    }

    out += '}';
    return out;
}
