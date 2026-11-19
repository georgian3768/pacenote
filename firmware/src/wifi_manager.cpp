#include "config.h"
#include "wifi_manager.h"
#include "gvret_comm.h"
#include "SerialConsole.h"
#include "telemetry.h"
#include <ESPmDNS.h>
#include <Update.h> 
#include <WiFi.h>
#include <FastLED.h>
#include "ELM327_Emulator.h"
#include "can_common.h"
#include <esp_netif.h>

extern CRGB leds[A5_NUM_LEDS];

static IPAddress broadcastAddr(255,255,255,255);

WiFiManager::WiFiManager() : dashboardServer(80), events("/events")
{
    lastBroadcast = 0;
    lastFastEvent = 0;
    lastSlowEvent = 0;
    lastPing = 0;
    sseRejectedCount = 0;
    sseDroppedCount = 0;
    netMode = POCKET_MECHANIC_MODE_A;
    servicesStarted = false;
    // No NVS read here: this runs as a file-scope constructor, before
    // Serial.begin() and before setup(), so a load would be invisible in the log
    // and unreliable. setup() calls loadNetworkMode() explicitly instead.
}

void WiFiManager::loadNetworkMode()
{
    Preferences prefs;
    if (!prefs.begin(PREF_NAME, false)) return;
    netMode = (prefs.getUChar("netmode", POCKET_MECHANIC_MODE_A) == POCKET_MECHANIC_MODE_B)
                  ? POCKET_MECHANIC_MODE_B
                  : POCKET_MECHANIC_MODE_A;
    prefs.end();
    Serial.printf("Network mode: %s\n", netMode == POCKET_MECHANIC_MODE_B ? "B" : "A");
}

bool WiFiManager::setNetworkMode(uint8_t mode)
{
    if (mode > POCKET_MECHANIC_MODE_B) return false;
    netMode = mode;
    Preferences prefs;
    if (!prefs.begin(PREF_NAME, false)) return false;
    const size_t written = prefs.putUChar("netmode", netMode);
    prefs.end();
    Serial.printf("netmode write: %u bytes\n", static_cast<unsigned int>(written));
    Serial.printf("Network mode %s stored; applies at the next AP start\n",
                  netMode == POCKET_MECHANIC_MODE_B ? "B" : "A");
    return true;
}

size_t WiFiManager::sseClients() const
{
    return const_cast<AsyncEventSource &>(events).count();
}

/*
 * Mode A hands clients an address and netmask but no router and no resolver, so
 * a phone keeps its cellular default route as soon as it joins. Mode B keeps the
 * ordinary AP gateway and points DNS at this device, whose sinkhole answers
 * every A query with the AP address; the captive-portal probe then reaches
 * /generate_204 and the network validates without a "no internet" prompt.
 *
 * Both are applied once, at AP start, because mutating a running DHCP server
 * under connected clients is not safe. Device verification is still required.
 */
void WiFiManager::applyNetworkMode()
{
    if (settings.wifiMode != 2) return;

    if (netMode == POCKET_MECHANIC_MODE_A)
    {
        esp_netif_t *ap = esp_netif_get_handle_from_ifkey("WIFI_AP_DEF");
        if (!ap)
        {
            Serial.println("Mode A: AP interface not found");
            return;
        }

        esp_netif_dhcps_stop(ap);
        esp_netif_ip_info_t info = {};
        info.ip.addr = static_cast<uint32_t>(WiFi.softAPIP());
        info.netmask.addr = static_cast<uint32_t>(IPAddress(255, 255, 255, 0));
        info.gw.addr = 0;   // no default route advertised to clients
        const esp_err_t ipResult = esp_netif_set_ip_info(ap, &info);
        const esp_err_t dnsResult = esp_netif_dhcps_option(ap, ESP_NETIF_OP_SET,
                                                          ESP_NETIF_DOMAIN_NAME_SERVER,
                                                          NULL, 0);
        const esp_err_t startResult = esp_netif_dhcps_start(ap);
        Serial.printf("Mode A: ip=%s dns=%s start=%s\n", esp_err_to_name(ipResult),
                      esp_err_to_name(dnsResult), esp_err_to_name(startResult));
    }
    else
    {
        dnsSinkhole.begin(53);
        Serial.println("Mode B: gateway + DNS sinkhole, /generate_204 answers 204");
    }
}

void WiFiManager::serviceEventStream()
{
    if (!servicesStarted || !events.count()) return;

    const uint32_t now = millis();
    if (now - lastFastEvent >= 100)
    {
        lastFastEvent = now;
        if (events.send(telemetry.json(true), "f", now) == AsyncEventSource::DISCARDED) sseDroppedCount++;
    }
    if (now - lastSlowEvent >= 1000)
    {
        lastSlowEvent = now;
        if (events.send(telemetry.json(false), "s", now) == AsyncEventSource::DISCARDED) sseDroppedCount++;
    }
    if (now - lastPing >= 5000)
    {
        lastPing = now;
        // The library only formats "event:/data:" blocks, so the keepalive is an
        // empty ping event rather than the literal ":ping" comment line.
        events.send("", "ping", now);
    }
}

void WiFiManager::serviceDnsSinkhole()
{
    if (settings.wifiMode != 2 || netMode != POCKET_MECHANIC_MODE_B) return;

    const int packetSize = dnsSinkhole.parsePacket();
    if (packetSize < 12 || packetSize > static_cast<int>(sizeof(dnsBuffer))) return;
    const int len = dnsSinkhole.read(dnsBuffer, sizeof(dnsBuffer));
    if (len < 12) return;

    int pos = 12;
    while (pos < len && dnsBuffer[pos] != 0)
    {
        if ((dnsBuffer[pos] & 0xC0) == 0xC0) { pos += 1; break; }   // no compression in a query
        pos += dnsBuffer[pos] + 1;
    }
    if (pos + 5 > len) return;              // terminator plus QTYPE/QCLASS
    const int questionEnd = pos + 5;
    if (questionEnd + 16 > static_cast<int>(sizeof(dnsBuffer))) return;

    const uint16_t qtype = (dnsBuffer[pos + 1] << 8) | dnsBuffer[pos + 2];
    const bool answer = (qtype == 1);       // A records only

    dnsBuffer[2] = 0x81;                    // response, recursion desired
    dnsBuffer[3] = 0x80;                    // recursion available, no error
    dnsBuffer[6] = 0x00; dnsBuffer[7] = answer ? 0x01 : 0x00;
    dnsBuffer[8] = 0x00; dnsBuffer[9] = 0x00;
    dnsBuffer[10] = 0x00; dnsBuffer[11] = 0x00;

    int out = questionEnd;
    if (answer)
    {
        dnsBuffer[out++] = 0xC0; dnsBuffer[out++] = 0x0C;   // pointer to the question name
        dnsBuffer[out++] = 0x00; dnsBuffer[out++] = 0x01;   // A
        dnsBuffer[out++] = 0x00; dnsBuffer[out++] = 0x01;   // IN
        dnsBuffer[out++] = 0x00; dnsBuffer[out++] = 0x00;
        dnsBuffer[out++] = 0x00; dnsBuffer[out++] = 0x3C;   // 60 s
        dnsBuffer[out++] = 0x00; dnsBuffer[out++] = 0x04;
        const IPAddress ap = WiFi.softAPIP();
        dnsBuffer[out++] = ap[0]; dnsBuffer[out++] = ap[1];
        dnsBuffer[out++] = ap[2]; dnsBuffer[out++] = ap[3];
    }

    dnsSinkhole.beginPacket(dnsSinkhole.remoteIP(), dnsSinkhole.remotePort());
    dnsSinkhole.write(dnsBuffer, out);
    dnsSinkhole.endPacket();
}

void WiFiManager::setup()
{
    if (settings.enableBT != 0) return; //No wifi if BT is on
    if (settings.wifiMode == 1) //connect to an AP
    {        
        Serial.println("Attempting to connect to a WiFi AP.");
        WiFi.mode(WIFI_STA);
        WiFi.setSleep(true); //sleeping could cause delays
        WiFi.begin((const char *)settings.SSID, (const char *)settings.WPA2Key);

        WiFiEventId_t eventID = WiFi.onEvent([](WiFiEvent_t event, WiFiEventInfo_t info) 
        {
           if (SysSettings.fancyLED)
           {
               leds[SysSettings.LED_CONNECTION_STATUS] = CRGB::Red;
               FastLED.show();
           }
           Serial.print("WiFi lost connection. Reason: ");
           Serial.println(info.wifi_sta_disconnected.reason);
           SysSettings.isWifiConnected = false;
           if ( (info.wifi_sta_disconnected.reason == 202) || (info.wifi_sta_disconnected.reason == 3)) 
           {
              Serial.println("Connection failed, rebooting to fix it.");
              esp_sleep_enable_timer_wakeup(10);
              esp_deep_sleep_start();
              delay(100);
           }
        }, ARDUINO_EVENT_WIFI_STA_DISCONNECTED);
    }
    if (settings.wifiMode == 2) //BE an AP
    {
        WiFi.mode(WIFI_AP);
        WiFi.setSleep(true);
        WiFi.softAP((const char *)settings.SSID, (const char *)settings.WPA2Key);
        applyNetworkMode();
        if (SysSettings.fancyLED)
        {
            leds[SysSettings.LED_CONNECTION_STATUS] = CRGB::Green;
            FastLED.show();
        }
    }
}

void WiFiManager::loop()
{
    boolean needServerInit = false; 
    int i;    

    if (settings.enableBT != 0) return; //No wifi if BT is on

    if (settings.wifiMode > 0)
    {
        if (!SysSettings.isWifiConnected)
        {
            if (WiFi.isConnected())
            {
                //WiFi.setSleep(false);
                Serial.print("Wifi now connected to SSID ");
                Serial.println((const char *)settings.SSID);
                Serial.print("IP address: ");
                Serial.println(WiFi.localIP());
                Serial.print("RSSI: ");
                Serial.println(WiFi.RSSI());
                needServerInit = true;
                if (SysSettings.fancyLED)
                {
                    leds[SysSettings.LED_CONNECTION_STATUS] = CRGB::Green;
                    FastLED.show();
                }
            }
            if (settings.wifiMode == 2)
            {
                Serial.print("Wifi setup as SSID ");
                Serial.println((const char *)settings.SSID);
                Serial.print("IP address: ");
                Serial.println(WiFi.softAPIP());
                needServerInit = true;
            }
            if (needServerInit)
            {
                SysSettings.isWifiConnected = true;
                //MDNS.begin wants the name we will register as without the .local on the end. That's added automatically.
                if (!MDNS.begin(deviceName)) Serial.println("Error setting up MDNS responder!");
                MDNS.addService("telnet", "tcp", 23);// Add service to MDNS-SD
                MDNS.addService("ELM327", "tcp", 1000);// Add service to MDNS-SD
                MDNS.addService("http", "tcp", 80);
                dashboardServer.on("/", HTTP_GET, [](AsyncWebServerRequest *request) {
                    static const char dashboard[] PROGMEM = R"HTML(<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="theme-color" content="#101820"><title>Pocket Mechanic</title>
<style>
:root{color-scheme:dark;--bg:#101820;--panel:#192630;--line:#2c414d;--muted:#9aafb9;--text:#edf5f7;--accent:#63e6be;--warn:#ffcc66}*{box-sizing:border-box}body{margin:0;background:radial-gradient(ellipse at top,#1c303b,var(--bg) 65%);color:var(--text);font:16px system-ui,-apple-system,Segoe UI,sans-serif;min-height:100vh}.wrap{max-width:900px;margin:auto;padding:26px 18px 40px}header{display:flex;align-items:center;justify-content:space-between;gap:16px;margin-bottom:22px}.brand{font-size:13px;text-transform:uppercase;letter-spacing:.16em;color:var(--muted)}h1{font-size:clamp(25px,5vw,38px);margin:5px 0 0;letter-spacing:-.04em}.status{border:1px solid var(--line);border-radius:999px;padding:8px 12px;color:var(--muted);font-size:13px;white-space:nowrap}.dot{display:inline-block;width:8px;height:8px;border-radius:50%;background:var(--warn);margin-right:7px}.status.live .dot{background:var(--accent);box-shadow:0 0 12px #63e6be88}.grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}.card{background:linear-gradient(145deg,#1b2b35,#17232c);border:1px solid var(--line);border-radius:18px;padding:18px;min-height:132px;box-shadow:0 10px 30px #0002}.label{font-size:12px;letter-spacing:.1em;text-transform:uppercase;color:var(--muted)}.value{font-variant-numeric:tabular-nums;font-size:clamp(28px,6vw,44px);font-weight:650;letter-spacing:-.04em;margin:11px 0 2px}.unit{color:var(--muted);font-size:14px}.hint{font-size:12px;color:var(--muted);margin-top:8px}.pill{display:inline-block;border:1px solid var(--line);border-radius:999px;padding:7px 11px;font-size:14px}.pill.active{border-color:#63e6be77;color:var(--accent)}.notice{margin-top:14px;padding:14px 16px;border:1px solid #806c3b;border-radius:14px;background:#3a321f66;color:#f0dfb5;font-size:13px;line-height:1.5}.foot{color:var(--muted);font-size:12px;margin-top:18px;line-height:1.6}@media(min-width:650px){.grid{grid-template-columns:repeat(3,minmax(0,1fr))}}@media(max-width:390px){.wrap{padding:18px 12px}.card{padding:15px;min-height:120px}}
</style></head><body><main class="wrap"><header><div><div class="brand">Pocket Mechanic · Local telemetry</div><h1>Live dashboard</h1></div><div id="status" class="status"><span class="dot"></span><span id="connection">Waiting for frames</span></div></header>
<section class="grid" aria-label="Vehicle signals"><article class="card"><div class="label">Engine speed</div><div id="rpm" class="value">—</div><div class="unit">rpm · ID 0x124</div></article><article class="card"><div class="label">Throttle channel 1</div><div id="throttle1" class="value">—</div><div class="unit">raw · 0x120 D3</div></article><article class="card"><div class="label">Throttle channel 2</div><div id="throttle2" class="value">—</div><div class="unit">raw · 0x120 D5</div></article><article class="card"><div class="label">Brake pedal</div><div id="brake" class="value"><span class="pill">—</span></div><div class="unit">switch · 0x318 D1 bit 2</div></article><article class="card"><div class="label">Engine status</div><div id="engine" class="value" style="font-size:22px">—</div><div class="unit">raw status bytes</div></article><article class="card"><div class="label">Temperature candidate</div><div id="temperature" class="value">—</div><div class="unit">raw · 0x124 D4–D5</div></article><article class="card"><div class="label">Vehicle speed</div><div class="value">—</div><div class="unit">not decoded yet · Session B</div></article></section>
<div class="notice"><strong>Prototype signal labels.</strong> RPM and brake are decoded from the current capture. Throttle channels are raw bytes, and the temperature field is an unconfirmed raw candidate—not degrees. Speed will be added after a drive capture identifies it.</div><div class="foot">Connect to the ESP32 Wi-Fi network, then open this page. Values update from broadcast CAN frames; no requests are transmitted to the vehicle.<br>Last update: <span id="updated">waiting</span></div></main>
<script>const fields={rpm:'rpm',throttle1:'throttle1',throttle2:'throttle2',temperature:'temperature'};function show(id,value){document.getElementById(id).textContent=value==null?'—':value}async function refresh(){try{const r=await fetch('/api/live',{cache:'no-store'});if(!r.ok)throw new Error('HTTP '+r.status);const d=await r.json();for(const [id,key] of Object.entries(fields))show(id,d[key]);const pill=document.querySelector('#brake .pill');pill.textContent=d.brake==null?'—':d.brake?'PRESSED':'RELEASED';pill.classList.toggle('active',d.brake===true);show('engine',d.engineRunning==null?'—':d.engineRunning?'RUNNING':'OFF / UNKNOWN');const live=d.framesSeen>0;document.getElementById('status').classList.toggle('live',live);document.getElementById('connection').textContent=live?'Receiving CAN frames':'Waiting for frames';document.getElementById('updated').textContent=d.ageMs==null?'waiting':d.ageMs+' ms ago'}catch(e){document.getElementById('connection').textContent='Dashboard reconnecting'}}setInterval(refresh,300);refresh();</script></body></html>)HTML";
                    AsyncWebServerResponse *response =
                        request->beginResponse(200, "text/html; charset=utf-8", dashboard);
                    response->addHeader("Cache-Control", "no-store");
                    request->send(response);
                });
                dashboardServer.on("/api/live", HTTP_GET, [](AsyncWebServerRequest *request) {
                    TelemetrySnapshot state;
                    telemetry.snapshot(state);
                    const uint32_t now = millis();
                    const auto fresh = [now](const TelemetrySample &sample) {
                        return Telemetry::fresh(sample, now);
                    };
                    const auto valueOrNull = [&fresh](const TelemetrySample &sample) {
                        return fresh(sample) ? String(static_cast<uint32_t>(sample.value)) : String("null");
                    };
                    const bool rpmFresh = fresh(state.samples[SIG_RPM]);
                    String json = "{\"rpm\":";
                    json += rpmFresh ? String(state.samples[SIG_RPM].value, 2) : String("null");
                    json += ",\"throttle1\":" + valueOrNull(state.samples[SIG_THROTTLE1]);
                    json += ",\"throttle2\":" + valueOrNull(state.samples[SIG_THROTTLE2]);
                    json += ",\"brake\":";
                    json += fresh(state.samples[SIG_BRAKE])
                                ? (state.samples[SIG_BRAKE].value > 0.5f ? "true" : "false")
                                : "null";
                    const bool engineStateKnown = fresh(state.samples[SIG_RPM])
                                                  || fresh(state.samples[SIG_STATUS_318_D2]);
                    json += ",\"engineRunning\":";
                    json += engineStateKnown
                        ? (Telemetry::engineRunning(state, now) ? "true" : "false") : "null";
                    json += ",\"temperature\":" + valueOrNull(state.samples[SIG_TEMP]);
                    json += ",\"framesSeen\":";
                    json += state.canActive ? 1 : 0;
                    json += ",\"ageMs\":";
                    json += rpmFresh
                        ? String(static_cast<uint32_t>(now - state.samples[SIG_RPM].updatedAt))
                        : String("null");
                    json += "}";
                    AsyncWebServerResponse *response =
                        request->beginResponse(200, "application/json; charset=utf-8", json);
                    response->addHeader("Cache-Control", "no-store");
                    request->send(response);
                });
                events.onConnect([](AsyncEventSourceClient *) {
                    Serial.println("SSE client connected");
                });
                // A third subscriber is refused before the stream starts.
                events.addMiddleware(new AsyncAuthorizationMiddleware(503,
                    [this](AsyncWebServerRequest *) {
                        if (events.count() >= POCKET_MECHANIC_MAX_SSE_CLIENTS)
                        {
                            sseRejectedCount++;
                            return false;
                        }
                        return true;
                    }));
                dashboardServer.addHandler(&events);

                dashboardServer.on("/status", HTTP_GET, [this](AsyncWebServerRequest *request) {
                    TelemetrySnapshot state;
                    telemetry.snapshot(state);
                    String json = "{\"mode\":\"";
                    json += (netMode == POCKET_MECHANIC_MODE_B) ? "B" : "A";
                    json += "\",\"wifi_mode\":";
                    json += settings.wifiMode;
                    json += ",\"heap_free\":";
                    json += ESP.getFreeHeap();
                    json += ",\"sse_clients\":";
                    json += String(events.count());
                    json += ",\"sse_rejected\":";
                    json += sseRejectedCount;
                    json += ",\"events_dropped\":";
                    json += sseDroppedCount;
                    json += ",\"can_age_ms\":";
                    json += (state.canAgeMs == UINT32_MAX) ? String("null") : String(state.canAgeMs);
                    json += ",\"can_active\":";
                    json += state.canActive ? "true" : "false";
                    json += ",\"frames\":";
                    json += state.frames;
                    json += ",\"rejected_frames\":";
                    json += telemetry.rejectedFrames();
                    json += ",\"speed_scale\":";
                    json += String(telemetry.speedScale(), 3);
                    json += ",\"uptime_s\":";
                    json += millis() / 1000;
                    json += ",\"build\":";
                    json += CFG_BUILD_NUM;
                    json += "}";
                    AsyncWebServerResponse *response =
                        request->beginResponse(200, "application/json; charset=utf-8", json);
                    response->addHeader("Cache-Control", "no-store");
                    request->send(response);
                });

                dashboardServer.on("/mode", HTTP_GET, [this](AsyncWebServerRequest *request) {
                    bool changed = false;
                    if (request->hasArg("set"))
                    {
                        const String value = request->arg("set");
                        if (value == "A" || value == "a") changed = setNetworkMode(POCKET_MECHANIC_MODE_A);
                        else if (value == "B" || value == "b") changed = setNetworkMode(POCKET_MECHANIC_MODE_B);
                        else
                        {
                            request->send(400, "text/plain; charset=utf-8", "mode must be A or B");
                            return;
                        }
                    }
                    String json = "{\"mode\":\"";
                    json += (netMode == POCKET_MECHANIC_MODE_B) ? "B" : "A";
                    json += "\",\"applies_at\":\"next_ap_start\",\"changed\":";
                    json += changed ? "true" : "false";
                    json += "}";
                    request->send(200, "application/json; charset=utf-8", json);
                });

                // Speed scale: the Session B factor is empirical, so it must be
                // settable at runtime without reflashing. Persisted in NVS.
                dashboardServer.on("/speed", HTTP_GET, [](AsyncWebServerRequest *request) {
                    bool changed = false;
                    if (request->hasArg("scale"))
                    {
                        if (!telemetry.setSpeedScale(request->arg("scale").toFloat()))
                        {
                            request->send(400, "text/plain; charset=utf-8",
                                          "scale must be between 0.1 and 20");
                            return;
                        }
                        changed = true;
                    }
                    char buf[16];
                    snprintf(buf, sizeof(buf), "%.3f", telemetry.speedScale());
                    String json = "{\"speed_scale\":";
                    json += buf;
                    json += ",\"changed\":";
                    json += changed ? "true" : "false";
                    json += "}";
                    request->send(200, "application/json; charset=utf-8", json);
                });

                // Captive-portal probe. Mode B answers it so the phone validates
                // this network instead of showing "no internet".
                dashboardServer.on("/generate_204", HTTP_GET, [](AsyncWebServerRequest *request) {
                    request->send(204);
                });

                dashboardServer.onNotFound([](AsyncWebServerRequest *request) {
                    request->send(404, "text/plain; charset=utf-8", "Not found");
                });
                dashboardServer.begin();
                servicesStarted = true;
                const IPAddress dashboardIP = settings.wifiMode == 2 ? WiFi.softAPIP() : WiFi.localIP();
                Serial.println("Dashboard ready at http://" + dashboardIP.toString() + "/");
                wifiServer.begin(23); //setup as a telnet server
                wifiServer.setNoDelay(true);
                Serial.println("TCP server started");
                wifiOBDII.begin(1000); //setup for wifi linked ELM327 emulation
                wifiOBDII.setNoDelay(true);
                ArduinoOTA.setPort(3232);
                ArduinoOTA.setHostname(deviceName);
                // No authentication by default
                //ArduinoOTA.setPassword("admin");
                
                ArduinoOTA
                   .onStart([]() {
                      String type;
                      if (SysSettings.fancyLED)
                      {
                          leds[SysSettings.LED_CONNECTION_STATUS] = CRGB::Purple;
                          FastLED.show();
                      }
                      if (ArduinoOTA.getCommand() == U_FLASH)
                         type = "sketch";
                      else // U_SPIFFS
                         type = "filesystem";

                      // NOTE: if updating SPIFFS this would be the place to unmount SPIFFS using SPIFFS.end()
                      Serial.println("Start updating " + type);
                   })
                   .onEnd([]() {
                      Serial.println("\nEnd");
                   })
                   .onProgress([](unsigned int progress, unsigned int total) {
                       Serial.printf("Progress: %u%%\r", (progress / (total / 100)));
                   })
                   .onError([](ota_error_t error) {
                      Serial.printf("Error[%u]: ", error);
                      if (error == OTA_AUTH_ERROR) Serial.println("Auth Failed");
                      else if (error == OTA_BEGIN_ERROR) Serial.println("Begin Failed");
                      else if (error == OTA_CONNECT_ERROR) Serial.println("Connect Failed");
                      else if (error == OTA_RECEIVE_ERROR) Serial.println("Receive Failed");
                      else if (error == OTA_END_ERROR) Serial.println("End Failed");
                   });
                   
                ArduinoOTA.begin();
            }
        }
        else
        {
            // The async server runs in its own task; nothing to poll here.
            serviceEventStream();
            serviceDnsSinkhole();
            if (WiFi.isConnected() || settings.wifiMode == 2)
            {
                if (wifiServer.hasClient())
                {
                    for(i = 0; i < MAX_CLIENTS; i++)
                    {
                        if (!SysSettings.clientNodes[i] || !SysSettings.clientNodes[i].connected())
                        {
                            if (SysSettings.clientNodes[i]) SysSettings.clientNodes[i].stop();
                            SysSettings.clientNodes[i] = wifiServer.available();
                            if (!SysSettings.clientNodes[i]) Serial.println("Couldn't accept client connection!");
                            else 
                            {
                                Serial.print("New client: ");
                                Serial.print(i); Serial.print(' ');
                                Serial.println(SysSettings.clientNodes[i].remoteIP());
                                if (SysSettings.fancyLED)
                                {
                                    leds[SysSettings.LED_CONNECTION_STATUS] = CRGB::Blue;
                                    FastLED.show();
                                }
                            }
                        }
                    }
                    if (i >= MAX_CLIENTS) {
                        //no free/disconnected spot so reject
                        wifiServer.available().stop();
                    }
                }

                if (wifiOBDII.hasClient())
                {
                    for(i = 0; i < MAX_CLIENTS; i++)
                    {
                        if (!SysSettings.wifiOBDClients[i] || !SysSettings.wifiOBDClients[i].connected())
                        {
                            if (SysSettings.wifiOBDClients[i]) SysSettings.wifiOBDClients[i].stop();
                            SysSettings.wifiOBDClients[i] = wifiOBDII.available();
                            if (!SysSettings.wifiOBDClients[i]) Serial.println("Couldn't accept client connection!");
                            else 
                            {
                                Serial.print("New wifi ELM client: ");
                                Serial.print(i); Serial.print(' ');
                                Serial.println(SysSettings.wifiOBDClients[i].remoteIP());
                            }
                        }
                    }
                    if (i >= MAX_CLIENTS) {
                        //no free/disconnected spot so reject
                        wifiOBDII.available().stop();
                    }
                }

                //check clients for data
                for(i = 0; i < MAX_CLIENTS; i++)
                {
                    if (SysSettings.clientNodes[i] && SysSettings.clientNodes[i].connected())
                    {
                        if(SysSettings.clientNodes[i].available())
                        {
                            //get data from the telnet client and push it to input processing
                            while(SysSettings.clientNodes[i].available()) 
                            {
                                uint8_t inByt;
                                inByt = SysSettings.clientNodes[i].read();
                                SysSettings.isWifiActive = true;
                                //Serial.write(inByt); //echo to serial - just for debugging. Don't leave this on!
                                wifiGVRET.processIncomingByte(inByt);
                            }
                        }
                    }
                    else
                    {
                        if (SysSettings.clientNodes[i]) 
                        {
                            SysSettings.clientNodes[i].stop();
                            if (SysSettings.fancyLED)
                            {
                                leds[SysSettings.LED_CONNECTION_STATUS] = CRGB::Green;
                                FastLED.show();
                            }
                        }
                    }

                    if (SysSettings.wifiOBDClients[i] && SysSettings.wifiOBDClients[i].connected())
                    {
                        elmEmulator.setWiFiClient(&SysSettings.wifiOBDClients[i]);
                        /*if(SysSettings.wifiOBDClients[i].available())
                        {
                            //get data from the telnet client and push it to input processing
                            while(SysSettings.wifiOBDClients[i].available()) 
                            {
                                uint8_t inByt;
                                inByt = SysSettings.wifiOBDClients[i].read();
                                SysSettings.isWifiActive = true;
                                //wifiGVRET.processIncomingByte(inByt);
                            }
                        }*/
                    }
                    else
                    {
                        if (SysSettings.wifiOBDClients[i])
                        {
                            SysSettings.wifiOBDClients[i].stop();
                            elmEmulator.setWiFiClient(0);
                        }
                    }
                }                    
            }
            else 
            {
                if (settings.wifiMode == 1)
                {
                    Serial.println("WiFi disconnected. Bummer!");
                    SysSettings.isWifiConnected = false;
                    SysSettings.isWifiActive = false;
                    if (SysSettings.fancyLED)
                    {  
                        leds[SysSettings.LED_CONNECTION_STATUS] = CRGB::Red;
                        FastLED.show();
                    }
                }
            }
        }
    }

    if (SysSettings.isWifiConnected && ((micros() - lastBroadcast) > 1000000ul) ) //every second send out a broadcast ping
    {
        uint8_t buff[4] = {0x1C,0xEF,0xAC,0xED};
        lastBroadcast = micros();
        wifiUDPServer.beginPacket(broadcastAddr, 17222);
        wifiUDPServer.write(buff, 4);
        wifiUDPServer.endPacket();
    }

    ArduinoOTA.handle();
}

void WiFiManager::sendBufferedData()
{
    if (settings.enableBT != 0) return; //No wifi if BT is on
    for(int i = 0; i < MAX_CLIENTS; i++)
    {
        size_t wifiLength = wifiGVRET.numAvailableBytes();
        uint8_t* buff = wifiGVRET.getBufferedBytes();
        if (SysSettings.clientNodes[i] && SysSettings.clientNodes[i].connected())
        {
            SysSettings.clientNodes[i].write(buff, wifiLength);
        }
    }
    wifiGVRET.clearBufferedBytes();
}

// Utility to extract header value from headers
String getHeaderValue(String header, String headerName)
{
  return header.substring(strlen(headerName.c_str()));
}

void onOTAProgress(uint32_t progress, size_t fullSize)
{
    static int OTAcount = 0;
    //esp_task_wdt_reset();
    if (OTAcount++ == 10)
    {
        Serial.println(progress);
        OTAcount = 0;
    }
    else 
    {
        Serial.print("...");
        Serial.print(progress);
    }
}

void WiFiManager::attemptOTAUpdate()
{
    if (WiFi.status() == WL_CONNECTED)
    {
        Serial.println("\n\n");
        Serial.print("*WIFI STATUS* SSID:");
        Serial.print(WiFi.SSID());
        Serial.print(" with IP Address: ");
        Serial.println(WiFi.localIP());
        Serial.println("Attempting the OTA update from remote host");
    }
    else
    {
        Serial.println("\n\nIt appears there is no wireless connection. Cannot update.");
        return;
    }

    int contentLength = 0;
    bool isValidContentType = false;
    //TODO: figure out where github stores files in releases and/or how and where the images will be stored.
    int port = 80; // Non https. HTTPS would be 443 but that might not work.
    String host = String(otaHost);
    String bin = String(otaFilename);
    //esp_task_wdt_reset(); //in case watchdog was set, update it.
    Update.onProgress(onOTAProgress);

    Serial.println("Connecting to OTA server: " + host);

    if (wifiClient.connect(otaHost, port))
    {
        wifiClient.print(String("GET ") + bin + " HTTP/1.1\r\n" +
                     "Host: " + String(host) + "\r\n" +
                     "Cache-Control: no-cache\r\n" +
                     "Connection: close\r\n\r\n");  // Get the contents of the bin file

        unsigned long timeout = millis();
        while (wifiClient.available() == 0)
        {
            if (millis() - timeout > 5000)
            {
                Serial.println("Timeout trying to connect! Aborting!");
                wifiClient.stop();
                return;
            }
        }
    
        while (wifiClient.available())
        {
            String line = wifiClient.readStringUntil('\n');// read line till /n
            line.trim();// remove space, to check if the line is end of headers

            // if the the line is empty,this is end of headers break the while and feed the
            // remaining `client` to the Update.writeStream();

            if (!line.length()) {
                break;
            }

            // Check if the HTTP Response is 200 else break and Exit Update

            if (line.startsWith("HTTP/1.1"))
            {
                if (line.indexOf("200") < 0)
                {
                    Serial.println("FAIL...Got a non 200 status code from server. Exiting OTA Update.");
                    break;
                }
            }

            // extract headers here
            // Start with content length

            if (line.startsWith("Content-Length: "))
            {
                contentLength = atoi((getHeaderValue(line, "Content-Length: ")).c_str());
                Serial.println("              ...Server indicates " + String(contentLength) + " byte file size\n");
            }

            if (line.startsWith("Content-Type: "))
            {
                String contentType = getHeaderValue(line, "Content-Type: ");
                Serial.println("\n              ...Server indicates correct " + contentType + " payload.\n");
                if (contentType == "application/octet-stream")
                {
                    isValidContentType = true;
                }
            }
        } //end while client available
    }
    else 
    {
        // Connect to remote failed
        Serial.println("Connection to " + String(host) + " failed. Please check your setup!");
    }

    // Check what is the contentLength and if content type is `application/octet-stream`
    //Serial.println("File length: " + String(contentLength) + ", Valid Content Type flag:" + String(isValidContentType));

    // check contentLength and content type
    if (contentLength && isValidContentType) // Check if there is enough to OTA Update
    {
        bool canBegin = Update.begin(contentLength);
        if (canBegin)
        {
            Serial.println("There is sufficient space to update. Beginning update. \n");
            size_t written = Update.writeStream(wifiClient);

            if (written == contentLength)
            {
                Serial.println("\nWrote " + String(written) + " bytes to memory...");
            }
            else
            {
                Serial.println("\n********** FAILED - Wrote:" + String(written) + " of " + String(contentLength) + ". Try again later. ********\n\n" );
                return;
            }

            if (Update.end())
            {
                //  Serial.println("OTA file transfer completed!");
                if (Update.isFinished())
                {
                    Serial.println("Rebooting new firmware...\n");
                    ESP.restart();
                }
                else Serial.println("FAILED...update not finished? Something went wrong!");

            }
            else
            {
                Serial.println("Error Occurred. Error #: " + String(Update.getError()));
                return;
            }
        } //end if can begin
        else 
        {
            // not enough space to begin OTA
            // Understand the partitions and space availability

            Serial.println("Not enough space to begin OTA");
            wifiClient.flush();
        }
    } //End contentLength && isValidContentType
    else
    {
        Serial.println("There was no content in the response");
        wifiClient.flush();
    }
}
