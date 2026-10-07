// ============================================================================
// RJ45 DIORAMA - ESP32 MASTER FIRMWARE (PROVISIONING + FIREBASE + ESP-NOW)
// Branch: feature/rj45-firebase-integration
//
// HARDWARE INVENTORY (MASTER ESP32 DevKit V1):
// - ST7735 1.8" TFT (SCK: GPIO18, MOSI: GPIO14, RST: GPIO32, DC: GPIO27, CS: GPIO5)
// - 3 UI Buttons (NEXT: GPIO25, SELECT: GPIO26, BACK: GPIO13)
// - APDS-9960 Gesture Sensor (I2C: SDA 21, SCL 22, INT: GPIO33)
// - TCS-34725 RGB Color Sensor (I2C: 0x29)
// - DS3231 Real-Time Clock (I2C: 0x68)
// - PCF8591 ADC/DAC (I2C: 0x48) -> AIN0: Water Sensor, AIN2: Flex Sensor
// - DHT11 Temperature & Humidity (GPIO4)
// - PIR Motion Sensor (GPIO19)
// - DFPlayer Mini MP3 (RX: GPIO16, TX: GPIO17 through 1k resistor)
// - ESP-NOW Channel 1 -> SLAVE 1 (D4:E9:F4:A3:50:A8), SLAVE 2 (D4:E9:F4:E3:4E:2C)
//
// PHYSICAL DIORAMA SIMULATION SPECIFICATION:
// 1. Tram: Gesture Sensor -> Tram Motor (L298N) -> Hall Sensor (Safety Stop)
// 2. Street Lights: DS3231 RTC or Virtual Demo Time -> 5 Street LEDs (Slave 2)
// 3. RGB Lighting: TCS34725 Color Sensor or Manual Color (TFT/App) -> 74 WS2812B
// 4. Wind: Fan Relay (Slave 2) -> Wind Airflow physically bends Flex Sensor (AIN2)
// 5. Drainage: Water Sensor (AIN0) -> Submersible Pump -> Flood Warning Servo (Slave 1)
// ============================================================================

#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <HTTPClient.h>
#include <Preferences.h>
#include <Wire.h>
#include <esp_now.h>
#include <SPI.h>

#include <DHT.h>
#include <RTClib.h>
#include <Adafruit_TCS34725.h>
#include <DFRobotDFPlayerMini.h>
#include <Adafruit_GFX.h>
#include <Adafruit_ST7735.h>

// Bluetooth Low Energy for Wi-Fi Provisioning
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

// ============================================================================
// FIREBASE CONFIGURATION
// ============================================================================
#define FIREBASE_HOST "https://sanlo-139ce-default-rtdb.asia-southeast1.firebasedatabase.app"

// ============================================================================
// BLE PROVISIONING GATT DEFINITIONS
// ============================================================================
#define BLE_DEVICE_NAME "RJ45-MASTER"
#define POP_PIN_AUTH    "RJ45-2026"

#define SERVICE_UUID           "0000FFF0-0000-1000-8000-00805F9B34FB"
#define CHAR_POP_UUID          "0000FFF1-0000-1000-8000-00805F9B34FB"
#define CHAR_WIFI_UUID         "0000FFF2-0000-1000-8000-00805F9B34FB"
#define CHAR_STATUS_UUID       "0000FFF3-0000-1000-8000-00805F9B34FB"

// ============================================================================
// PIN DEFINITIONS
// ============================================================================
#define SDA_PIN 21
#define SCL_PIN 22

#define DHT_PIN 4
#define DHT_TYPE DHT11

#define PIR_PIN 19
#define GESTURE_INT_PIN 33

#define DFPLAYER_RX_PIN 16
#define DFPLAYER_TX_PIN 17

#define BTN_NEXT_PIN   25
#define BTN_SELECT_PIN 26
#define BTN_BACK_PIN   13

#define TFT_SCK  18
#define TFT_MOSI 14
#define TFT_RST  32
#define TFT_DC   27
#define TFT_CS   5

// PCF8591 ADC
#define PCF8591_ADDR 0x48
#define FLEX_THRESHOLD 100
#define WATER_DRAIN_THRESHOLD 65

// ============================================================================
// ESP-NOW DEFINITIONS
// ============================================================================
uint8_t slave1MAC[] = {0xD4, 0xE9, 0xF4, 0xA3, 0x50, 0xA8};
uint8_t slave2MAC[] = {0xD4, 0xE9, 0xF4, 0xE3, 0x4E, 0x2C};

struct CommandPacket {
  uint8_t command;
  int16_t value;
};

enum Command : uint8_t {
  CMD_STOP         = 0,
  CMD_TRAM_FORWARD = 1,
  CMD_TRAM_REVERSE = 2,
  CMD_TRAM_STOP    = 3,
  CMD_GATE_OPEN    = 4,
  CMD_GATE_CLOSE   = 5,
  CMD_FANS_ON      = 10,
  CMD_FANS_OFF     = 11,
  CMD_PUMP_ON      = 12,
  CMD_PUMP_OFF     = 13,
  CMD_LIGHT_LEVEL  = 14,
  CMD_STRIP_ON     = 15,
  CMD_STRIP_OFF    = 16,
  CMD_STRIP_COLOR  = 17
};

// ============================================================================
// GLOBAL INSTANCES
// ============================================================================
Preferences preferences;
SPIClass tftSPI(VSPI);
Adafruit_ST7735 tft(&tftSPI, TFT_CS, TFT_DC, TFT_RST);
DHT dht(DHT_PIN, DHT_TYPE);
RTC_DS3231 rtc;
Adafruit_TCS34725 tcs = Adafruit_TCS34725(TCS34725_INTEGRATIONTIME_50MS, TCS34725_GAIN_4X);
DFRobotDFPlayerMini dfplayer;

// ============================================================================
// SYSTEM STATES
// ============================================================================
bool inProvisioningMode = false;
bool bleAuthenticated = false;
String wifiSsid = "";
String wifiPassword = "";
bool wifiConnected = false;

// Authoritative Diorama States
bool rtcFound = false;
bool tcsFound = false;
bool pcfFound = false;

float currentTemp = 0.0;
float currentHumidity = 0.0;
uint8_t waterRaw = 0;
int waterPercent = 0;
uint8_t flexRaw = 0;
bool flexBent = false;
int visitorCountToday = 0;

// Simulation State
enum SimMode : uint8_t { SIM_IDLE, SIM_INDIVIDUAL, SIM_FULL };
SimMode currentSimMode = SIM_IDLE;
uint8_t individualOp = 0; // 1: Tram, 2: Street Lights, 3: RGB, 4: Wind, 5: Drainage
bool simRunning = false;
String simStepDesc = "System ready";

// Subsystem States
String tramState = "STOPPED";
bool streetLightsOn = false;
bool demoClockActive = false;
int demoHour = 19;
int demoMinute = 0;

String rgbMode = "SENSOR"; // SENSOR or MANUAL
uint16_t currentRgb565 = 0x4DF7; // Teal
String currentHex = "#47AFC3";
uint8_t rgbColorIndex = 0;

const char* PRESET_NAMES[] = {"Red", "Orange", "Yellow", "Green", "Blue", "Purple", "White"};
const uint16_t PRESET_565[] = {0xF800, 0xFD20, 0xFFE0, 0x07E0, 0x001F, 0x780F, 0xFFFF};
const char* PRESET_HEX[]   = {"#FF0000", "#FFA500", "#FFFF00", "#00FF00", "#0000FF", "#800080", "#FFFFFF"};

bool fanStatus = false;
bool pumpStatus = false;
int gateServoAngle = 0; // 0: Normal, 90: Flood warning

unsigned long lastTelemetryPush = 0;
unsigned long lastHeartbeatPush = 0;
unsigned long lastRequestPoll = 0;

// BLE Server components
BLEServer* pBleServer = nullptr;
BLECharacteristic* pStatusChar = nullptr;

// ============================================================================
// ESP-NOW TRANSMISSION
// ============================================================================
bool sendCommand(const uint8_t *peerMac, uint8_t cmd, int16_t val = 0) {
  CommandPacket packet;
  packet.command = cmd;
  packet.value = val;
  esp_err_t res = esp_now_send(peerMac, (uint8_t *)&packet, sizeof(packet));
  return res == ESP_OK;
}

void registerPeer(const uint8_t *peerMac) {
  esp_now_peer_info_t peer;
  memset(&peer, 0, sizeof(peer));
  memcpy(peer.peer_addr, peerMac, 6);
  peer.channel = 1;
  peer.encrypt = false;
  esp_now_add_peer(&peer);
}

// ============================================================================
// BLE CALLBACKS
// ============================================================================
class PoPCallback : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *pChar) override {
    String value = pChar->getValue().c_str();
    if (value.equals(POP_PIN_AUTH)) {
      bleAuthenticated = true;
      Serial.println("[BLE] Proof-of-Possession VALIDATED!");
      if (pStatusChar) {
        uint8_t st = 1; // Authenticated
        pStatusChar->setValue(&st, 1);
        pStatusChar->notify();
      }
    } else {
      Serial.println("[BLE] Proof-of-Possession REJECTED!");
    }
  }
};

class WifiCredsCallback : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *pChar) override {
    if (!bleAuthenticated) {
      Serial.println("[BLE] Unauthorized write attempt blocked!");
      return;
    }
    String json = pChar->getValue().c_str();
    Serial.print("[BLE] Received Wi-Fi payload: ");
    Serial.println(json);

    // Simple JSON parse for "ssid" and "pass"
    int ssidIdx = json.indexOf("\"ssid\":\"");
    int passIdx = json.indexOf("\"pass\":\"");
    if (ssidIdx >= 0 && passIdx >= 0) {
      int ssidEnd = json.indexOf("\"", ssidIdx + 8);
      int passEnd = json.indexOf("\"", passIdx + 8);
      if (ssidEnd > ssidIdx && passEnd > passIdx) {
        wifiSsid = json.substring(ssidIdx + 8, ssidEnd);
        wifiPassword = json.substring(passIdx + 8, passEnd);

        // Store to NVS
        preferences.begin("rj45_wifi", false);
        preferences.putString("ssid", wifiSsid);
        preferences.putString("pass", wifiPassword);
        preferences.end();

        Serial.println("[NVS] Wi-Fi credentials saved successfully!");
        if (pStatusChar) {
          uint8_t st = 3; // Success
          pStatusChar->setValue(&st, 1);
          pStatusChar->notify();
        }

        // Signal to transition from Provisioning mode to Wi-Fi connect
        inProvisioningMode = false;
      }
    }
  }
};

// ============================================================================
// PROVISIONING SETUP
// ============================================================================
void startBleProvisioning() {
  inProvisioningMode = true;
  bleAuthenticated = false;

  tft.fillScreen(ST77XX_BLACK);
  tft.setTextColor(ST77XX_CYAN);
  tft.setTextSize(1);
  tft.setCursor(10, 20);
  tft.println("WI-FI SETUP MODE");
  tft.setTextColor(ST77XX_WHITE);
  tft.setCursor(10, 40);
  tft.println("BLE: RJ45-MASTER");
  tft.setCursor(10, 60);
  tft.println("PIN: RJ45-2026");
  tft.setTextColor(ST77XX_YELLOW);
  tft.setCursor(10, 90);
  tft.println("Connect using");
  tft.setCursor(10, 105);
  tft.println("SanLoMobApp...");

  Serial.println("[BLE] Starting BLE GATT Server for provisioning...");
  BLEDevice::init(BLE_DEVICE_NAME);
  pBleServer = BLEDevice::createServer();
  BLEService *pService = pBleServer->createService(SERVICE_UUID);

  BLECharacteristic *pPopChar = pService->createCharacteristic(
    CHAR_POP_UUID, BLECharacteristic::PROPERTY_WRITE
  );
  pPopChar->setCallbacks(new PoPCallback());

  BLECharacteristic *pWifiChar = pService->createCharacteristic(
    CHAR_WIFI_UUID, BLECharacteristic::PROPERTY_WRITE
  );
  pWifiChar->setCallbacks(new WifiCredsCallback());

  pStatusChar = pService->createCharacteristic(
    CHAR_STATUS_UUID, BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY
  );
  pStatusChar->addDescriptor(new BLE2902());

  pService->start();
  BLEAdvertising *pAdv = BLEDevice::getAdvertising();
  pAdv->addServiceUUID(SERVICE_UUID);
  pAdv->setScanResponse(true);
  BLEDevice::startAdvertising();

  Serial.println("[BLE] Advertising RJ45-MASTER ready for SanLoMobApp.");
}

void stopBleProvisioning() {
  Serial.println("[BLE] Provisioning finished. De-initializing Bluetooth to reclaim SRAM...");
  BLEDevice::deinit();
  pBleServer = nullptr;
  pStatusChar = nullptr;
}

// ============================================================================
// PCF8591 ADC
// ============================================================================
uint8_t readPCF8591(uint8_t channel) {
  Wire.beginTransmission(PCF8591_ADDR);
  Wire.write(channel);
  if (Wire.endTransmission() != 0) return 0;
  Wire.requestFrom(PCF8591_ADDR, 2);
  Wire.read(); // throw dummy
  return Wire.read();
}

// ============================================================================
// FIREBASE REST HELPERS (Lightweight & Zero-Crash)
// ============================================================================
void pushHeartbeatToFirebase() {
  if (WiFi.status() != WL_CONNECTED) return;
  HTTPClient http;
  String url = String(FIREBASE_HOST) + "/system.json";
  http.begin(url);
  http.addHeader("Content-Type", "application/json");

  String json = "{\"master_online\":true,\"master_heartbeat\":";
  json += String(millis());
  json += ",\"slave1_online\":true,\"slave2_online\":true,\"firmware_version\":\"1.0.0-rj45\"}";

  http.PUT(json);
  http.end();
}

void pushTelemetryToFirebase() {
  if (WiFi.status() != WL_CONNECTED) return;
  HTTPClient http;
  String url = String(FIREBASE_HOST) + "/telemetry.json";
  http.begin(url);
  http.addHeader("Content-Type", "application/json");

  String json = "{\"temperature\":";
  json += String(currentTemp, 1);
  json += ",\"humidity\":";
  json += String(currentHumidity, 1);
  json += ",\"water_raw\":";
  json += String(waterRaw);
  json += ",\"water_level_pct\":";
  json += String(waterPercent);
  json += ",\"water_status\":\"";
  json += (waterPercent >= WATER_DRAIN_THRESHOLD ? "CRITICAL" : "NORMAL");
  json += "\",\"flex_raw\":";
  json += String(flexRaw);
  json += ",\"flex_bent\":";
  json += (flexBent ? "true" : "false");
  json += ",\"visitor_count_today\":";
  json += String(visitorCountToday);
  json += ",\"timestamp\":";
  json += String(millis());
  json += "}";

  http.PUT(json);
  http.end();
}

void pushReportedStateToFirebase() {
  if (WiFi.status() != WL_CONNECTED) return;
  HTTPClient http;
  String url = String(FIREBASE_HOST) + "/reported_state.json";
  http.begin(url);
  http.addHeader("Content-Type", "application/json");

  String json = "{\"active_mode\":\"";
  json += (currentSimMode == SIM_FULL ? "SIMULATION_FULL" : (currentSimMode == SIM_INDIVIDUAL ? "SIMULATION_INDIVIDUAL" : "IDLE"));
  json += "\",\"tram_state\":\"";
  json += tramState;
  json += "\",\"gate_servo_deg\":";
  json += String(gateServoAngle);
  json += ",\"street_lights_on\":";
  json += (streetLightsOn ? "true" : "false");
  json += ",\"street_lights_mode\":\"";
  json += (demoClockActive ? "DEMO" : "RTC");
  json += "\",\"rgb_mode\":\"";
  json += rgbMode;
  json += "\",\"rgb_color_hex\":\"";
  json += currentHex;
  json += "\",\"fan_state\":";
  json += (fanStatus ? "true" : "false");
  json += ",\"pump_state\":";
  json += (pumpStatus ? "true" : "false");
  json += ",\"last_updated\":";
  json += String(millis());
  json += "}";

  http.PUT(json);
  http.end();
}

void pollFirebaseRequests() {
  if (WiFi.status() != WL_CONNECTED) return;
  HTTPClient http;
  String url = String(FIREBASE_HOST) + "/requests.json";
  http.begin(url);
  int code = http.GET();
  if (code == 200) {
    String payload = http.getString();
    // Tram command poll
    if (payload.indexOf("\"tram\":{\"action\":\"FORWARD\"") >= 0) {
      Serial.println("[REMOTE] Tram FORWARD requested!");
      tramState = "FORWARD";
      sendCommand(slave1MAC, CMD_TRAM_FORWARD);
      pushReportedStateToFirebase();
      // Clear or acknowledge
      HTTPClient ack;
      ack.begin(String(FIREBASE_HOST) + "/requests/tram/status.json");
      ack.PUT("\"ACKNOWLEDGED\"");
      ack.end();
    } else if (payload.indexOf("\"tram\":{\"action\":\"STOP\"") >= 0) {
      Serial.println("[REMOTE] Tram STOP requested!");
      tramState = "STOPPED";
      sendCommand(slave1MAC, CMD_TRAM_STOP);
      pushReportedStateToFirebase();
      HTTPClient ack;
      ack.begin(String(FIREBASE_HOST) + "/requests/tram/status.json");
      ack.PUT("\"ACKNOWLEDGED\"");
      ack.end();
    }
  }
  http.end();
}

// ============================================================================
// HARDWARE INITIALIZATION
// ============================================================================
void setup() {
  Serial.begin(115200);
  delay(500);
  Serial.println("\n============================================");
  Serial.println("  RJ45 DIORAMA - MASTER FIRMWARE (PROVISIONING)");
  Serial.println("============================================");

  pinMode(BTN_NEXT_PIN, INPUT_PULLUP);
  pinMode(BTN_SELECT_PIN, INPUT_PULLUP);
  pinMode(BTN_BACK_PIN, INPUT_PULLUP);
  pinMode(PIR_PIN, INPUT);

  Wire.begin(SDA_PIN, SCL_PIN);
  dht.begin();

  // Initialize TFT
  tft.initR(INITR_BLACKTAB);
  tft.setRotation(1);
  tft.fillScreen(ST77XX_BLACK);

  // Initialize ESP-NOW on Channel 1
  WiFi.mode(WIFI_AP_STA);
  WiFi.softAP("RJ45-Diorama-AP", "SanLo1234", 1);
  if (esp_now_init() == ESP_OK) {
    registerPeer(slave1MAC);
    registerPeer(slave2MAC);
    Serial.println("[ESP-NOW] Initialized on Channel 1");
  }

  // Load Saved Wi-Fi Credentials from NVS
  preferences.begin("rj45_wifi", true);
  wifiSsid = preferences.getString("ssid", "");
  wifiPassword = preferences.getString("pass", "");
  preferences.end();

  // If SELECT button is held on boot OR no SSID stored, enter BLE Setup mode
  if (digitalRead(BTN_SELECT_PIN) == LOW || wifiSsid.length() == 0) {
    Serial.println("[SETUP] Entering BLE Provisioning mode...");
    startBleProvisioning();
  } else {
    Serial.print("[WIFI] Connecting to saved SSID: ");
    Serial.println(wifiSsid);
    WiFi.begin(wifiSsid.c_str(), wifiPassword.c_str());
  }
}

// ============================================================================
// MAIN LOOP
// ============================================================================
void loop() {
  // 1. If in BLE Provisioning Mode
  if (inProvisioningMode) {
    delay(50);
    return;
  }

  // Check if just exited provisioning mode
  if (pBleServer != nullptr) {
    stopBleProvisioning();
    tft.fillScreen(ST77XX_BLACK);
    tft.setCursor(10, 30);
    tft.setTextColor(ST77XX_GREEN);
    tft.println("Connecting Wi-Fi...");
    WiFi.begin(wifiSsid.c_str(), wifiPassword.c_str());
  }

  // 2. Sensor Readings (Non-blocking)
  static unsigned long lastSensorRead = 0;
  if (millis() - lastSensorRead >= 1500) {
    lastSensorRead = millis();
    float t = dht.readTemperature();
    float h = dht.readHumidity();
    if (!isnan(t)) currentTemp = t;
    if (!isnan(h)) currentHumidity = h;

    waterRaw = readPCF8591(0);
    waterPercent = (int)((waterRaw / 255.0) * 100);

    flexRaw = readPCF8591(2);
    flexBent = (flexRaw < FLEX_THRESHOLD);

    // Physical Diorama Safety: High Water Drainage Protocol
    // Water Sensor -> Pump Relay -> Warning Flood Gate Servo (Slave 1)
    if (waterPercent >= WATER_DRAIN_THRESHOLD && !pumpStatus) {
      Serial.println("[SAFETY] Critical river level! Activating Drainage Pump & Warning Gate.");
      pumpStatus = true;
      gateServoAngle = 90; // Open gate / Warning
      sendCommand(slave2MAC, CMD_PUMP_ON);
      sendCommand(slave1MAC, CMD_GATE_OPEN); // Reassigned to Flood/Drainage Servo
      pushReportedStateToFirebase();
    } else if (waterPercent < (WATER_DRAIN_THRESHOLD - 10) && pumpStatus) {
      Serial.println("[SAFETY] River normalized. Stopping Drainage Pump.");
      pumpStatus = false;
      gateServoAngle = 0; // Safe / Normal
      sendCommand(slave2MAC, CMD_PUMP_OFF);
      sendCommand(slave1MAC, CMD_GATE_CLOSE);
      pushReportedStateToFirebase();
    }
  }

  // 3. Periodic Firebase Sync
  if (millis() - lastHeartbeatPush >= 8000) {
    lastHeartbeatPush = millis();
    pushHeartbeatToFirebase();
  }

  if (millis() - lastTelemetryPush >= 5000) {
    lastTelemetryPush = millis();
    pushTelemetryToFirebase();
  }

  if (millis() - lastRequestPoll >= 3000) {
    lastRequestPoll = millis();
    pollFirebaseRequests();
  }

  delay(20);
}
