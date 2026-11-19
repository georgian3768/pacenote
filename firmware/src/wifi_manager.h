#pragma once
#include <WiFi.h>
#include <ESPmDNS.h>
#include <WiFiUdp.h>
#include <ArduinoOTA.h>
#include <ESPAsyncWebServer.h>

// Network mode, persisted in NVS. A is the default: the AP hands out an address
// and netmask but no gateway or DNS, so a phone keeps its cellular default route
// while still reaching the ESP32 by IP. B additionally answers captive-portal
// probes through a DNS sinkhole and /generate_204.
#define POCKET_MECHANIC_MODE_A 0
#define POCKET_MECHANIC_MODE_B 1

#define POCKET_MECHANIC_MAX_SSE_CLIENTS 2

class WiFiManager
{
public:
    WiFiManager();
    void setup();
    void loop();
    void sendBufferedData();
    void attemptOTAUpdate();

    uint8_t networkMode() const { return netMode; }
    // Persists the mode; it is applied at the next AP start so a running DHCP
    // server is never mutated underneath connected clients.
    bool setNetworkMode(uint8_t mode);
    // Loads the persisted network mode from NVS. Must be called from setup(),
    // after Serial.begin() and before the AP starts; the constructor cannot do it.
    void loadNetworkMode();
    size_t sseClients() const;
    uint32_t sseRejected() const { return sseRejectedCount; }
    uint32_t sseDropped() const { return sseDroppedCount; }

private:
    void applyNetworkMode();
    void serviceEventStream();
    void serviceDnsSinkhole();

    WiFiServer wifiServer;
    WiFiServer wifiOBDII;
    AsyncWebServer dashboardServer;
    AsyncEventSource events;

    WiFiClient wifiClient;
    WiFiUDP wifiUDPServer;
    WiFiUDP dnsSinkhole;
    uint8_t dnsBuffer[512];

    uint32_t lastBroadcast;
    uint32_t lastFastEvent;
    uint32_t lastSlowEvent;
    uint32_t lastPing;
    uint32_t sseRejectedCount;
    uint32_t sseDroppedCount;
    uint8_t netMode;
    bool servicesStarted;
};
