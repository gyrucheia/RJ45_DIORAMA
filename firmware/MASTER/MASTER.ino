// RJ45 MASTER: TFT visitor/menu architecture + gestures + ESP-NOW
// Note: Slave 1 Hall STOP feedback is not yet transmitted to MASTER.
// Re-upload MASTER for corrected Slave 1 MAC. SLAVE 1 need not change.

/*
   ============================================================
   RJ45 DIORAMA - ESP32 MASTER
   PIR -> VISITOR -> APDS9960 GESTURE
   ============================================================

   ---------------- TFT ST7735 ----------------

   TFT GND   -> GND
   TFT VCC   -> 3.3V
   TFT SCK   -> GPIO18
   TFT SDA   -> GPIO14   <-- THIS IS SPI MOSI, NOT I2C SDA
   TFT RES   -> GPIO32
   TFT RS/DC -> GPIO27
   TFT CS    -> GPIO5
   TFT LEDA  -> 3.3V

   ---------------- I2C BUS ----------------

   ESP32 SDA -> GPIO21
   ESP32 SCL -> GPIO22

   PCF8591:
     SDA -> GPIO21
     SCL -> GPIO22
     Address -> 0x48

     AIN0 -> Water Sensor
     AIN2 -> Flex Sensor

   DS3231:
     SDA -> GPIO21
     SCL -> GPIO22
     Address -> 0x68

   TCS34725:
     SDA -> GPIO21
     SCL -> GPIO22
     Address -> 0x29

   APDS9960:
     VCC -> 3.3V
     GND -> GND
     SDA -> GPIO21
     SCL -> GPIO22
     INT -> GPIO33
     VL  -> disconnected
     Address -> 0x39

   ---------------- OTHER SENSORS ----------------

   DHT11 DATA -> GPIO4
   PIR OUT    -> GPIO19

   ---------------- DFPLAYER ----------------

   DFPlayer TX -> ESP32 GPIO16 (RX)
   ESP32 GPIO17 (TX) -> 1k resistor -> DFPlayer RX

   ============================================================
*/


#include <WiFi.h>
#include <WebServer.h>
#include <Wire.h>
#include <esp_now.h>
#include <SPI.h>
#include <Preferences.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <HTTPClient.h>
#include <WiFiClientSecure.h>

#include <DHT.h>
#include <RTClib.h>
#include <Adafruit_TCS34725.h>
#include <DFRobotDFPlayerMini.h>

#include <Adafruit_GFX.h>
#include <Adafruit_ST7735.h>


// ============================================================
// PIN DEFINITIONS
// ============================================================

#define SDA_PIN 21
#define SCL_PIN 22

#define DHT_PIN 4
#define DHT_TYPE DHT11

#define PIR_PIN 19

#define GESTURE_INT_PIN 33

#define DFPLAYER_RX_PIN 16
#define DFPLAYER_TX_PIN 17

// 3 MASTER UI BUTTONS (normally-open to GND, INPUT_PULLUP)
#define BTN_NEXT_PIN   25
#define BTN_SELECT_PIN 26
#define BTN_BACK_PIN   13

// ============================================================
// TFT MENU / EXPERIENCE STATE
// ============================================================
enum UIScreen : uint8_t {
  UI_WAITING,
  UI_WELCOME,
  UI_MAIN_MENU,
  UI_SIMULATION,
  UI_SIM_MODE_MENU,
  UI_INDIVIDUAL,
  UI_DASHBOARD_MENU,
  UI_DASHBOARD_DETAIL,
  UI_RECORDING
};

UIScreen uiScreen = UI_WAITING;
uint8_t mainMenuIndex = 0;       // 0 Simulation, 1 Dashboard, 2 Recording
uint8_t simulationModeIndex = 0; // Individual / Full System
uint8_t individualIndex = 0; // Tram, Street Lights, RGB, Wind, Drainage
bool individualRunning = false;
// Set to the office-approved night hours if different.
const uint8_t STREET_LIGHT_ON_HOUR = 18;
const uint8_t STREET_LIGHT_OFF_HOUR = 6;
bool streetLightsKnown = false;
bool streetLightsOn = false;
unsigned long lastStreetLightCommand = 0;
uint8_t dashboardIndex = 0;      // 0..6
unsigned long welcomeStarted = 0;
bool recordingActive = false;
unsigned long lastRecordMs = 0;
unsigned long recordCount = 0;
String tramStatus = "STOPPED";
String tramLocation = "LEFT"; // Memory tracker for endpoints
int simPhase = 0;             // 0 = Tram, 1 = RGB, 2 = River/Wind
bool pumpStatus = false;
bool fanStatus = false;
unsigned long tramReturnTimer = 0; // 5-second physical motor travel timer
bool tramReturning = false;        // Tracks if tram is en route to left


// ============================================================
// TFT
// ============================================================

#define TFT_SCK  18
#define TFT_MOSI 14
#define TFT_RST  32
#define TFT_DC   27
#define TFT_CS   5


// ============================================================
// I2C ADDRESSES
// ============================================================

#define TCS34725_ADDR 0x29
#define APDS9960_ADDR 0x39
#define PCF8591_ADDR  0x48
#define DS3231_ADDR   0x68


// ============================================================
// WIFI
// ============================================================

const char* AP_SSID = "RJ45-Diorama";
const char* AP_PASSWORD = "rj45diorama";

WebServer server(80);


// ============================================================
// ESP-NOW MAC ADDRESSES
//
// REPLACE WITH REAL NODEMCU MAC ADDRESSES LATER
// ============================================================

uint8_t slave1MAC[] = {0xD4, 0xE9, 0xF4, 0xA3, 0x50, 0xA8};

uint8_t slave2MAC[] = {
  0xD4, 0xE9, 0xF4,
  0xE3, 0x4E, 0x2C
};


// ============================================================
// OBJECTS
// ============================================================

DHT dht(DHT_PIN, DHT_TYPE);

RTC_DS3231 rtc;

Adafruit_TCS34725 tcs =
  Adafruit_TCS34725(
    TCS34725_INTEGRATIONTIME_50MS,
    TCS34725_GAIN_4X
  );

HardwareSerial DFSerial(2);
DFRobotDFPlayerMini dfPlayer;


// ============================================================
// TFT
// ============================================================

SPIClass tftSPI(VSPI);

Adafruit_ST7735 tft(
  &tftSPI,
  TFT_CS,
  TFT_DC,
  TFT_RST
);


// ============================================================
// MODULE STATUS
// ============================================================

bool pcfFound = false;
bool rtcFound = false;
bool colorFound = false;

bool apdsAddressFound = false;
bool gestureFound = false;

bool dfPlayerFound = false;
int dfPlayerVolume = 8;  // same starting volume as working speaker test
bool wifiReady = false;
bool espNowReady = false;

// ============================================================
// BLE WI-FI PROVISIONING (Phase 10B.1 - SanLoMobApp GATT Service)
// ============================================================
#define BLE_DEVICE_NAME "RJ45-MASTER"
#define POP_PIN_AUTH    "RJ45-2026"

#define BLE_SERVICE_UUID     "0000FFF0-0000-1000-8000-00805F9B34FB"
#define CHAR_POP_UUID        "0000FFF1-0000-1000-8000-00805F9B34FB"
#define CHAR_WIFI_UUID       "0000FFF2-0000-1000-8000-00805F9B34FB"
#define CHAR_STATUS_UUID     "0000FFF3-0000-1000-8000-00805F9B34FB"

Preferences preferences;
bool inProvisioningMode = false;
bool provisioningCompleted = false;
bool bleAuthenticated = false;
String wifiSsid = "nona";
String wifiPassword = "12345678";
bool wifiStationConnected = false;
unsigned long wifiConnectStartTime = 0;

volatile bool remoteSimStart = false;
volatile bool remoteSimStop = false;
volatile int remoteSimOp = 0; // 0 = Full, 1..5 = Individual
volatile bool remoteActionPending = false;
volatile int remoteActionOp = 0; // 1=Tram, 2=Lights, 3=RGB, 4=Wind, 5=Drainage

BLEServer* pBleServer = nullptr;
BLECharacteristic* pStatusChar = nullptr;

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
      Serial.println("[BLE] Rejected Wi-Fi creds: not authenticated.");
      return;
    }
    String creds = pChar->getValue().c_str();
    Serial.print("[BLE] Raw credentials payload received: ");
    Serial.println(creds);

    String newSsid = "";
    String newPass = "";

    // 1. Try JSON format: {"ssid":"...","pass":"..."}
    int ssidKey = creds.indexOf("\"ssid\"");
    if (ssidKey != -1) {
      int colon = creds.indexOf(':', ssidKey);
      if (colon != -1) {
        int q1 = creds.indexOf('"', colon);
        if (q1 != -1) {
          int q2 = creds.indexOf('"', q1 + 1);
          if (q2 != -1) {
            newSsid = creds.substring(q1 + 1, q2);
          }
        }
      }
      int passKey = creds.indexOf("\"pass\"");
      if (passKey != -1) {
        int colon = creds.indexOf(':', passKey);
        if (colon != -1) {
          int q1 = creds.indexOf('"', colon);
          if (q1 != -1) {
            int q2 = creds.indexOf('"', q1 + 1);
            if (q2 != -1) {
              newPass = creds.substring(q1 + 1, q2);
            }
          }
        }
      }
    } else {
      // 2. Fallback to newline format: SSID\nPASS
      int sep = creds.indexOf('\n');
      if (sep != -1) {
        newSsid = creds.substring(0, sep);
        newPass = creds.substring(sep + 1);
      }
    }

    newSsid.trim();
    newPass.trim();
    newSsid.replace("\r", "");
    newPass.replace("\r", "");

    if (newSsid.length() > 0) {
      wifiSsid = newSsid;
      wifiPassword = newPass;
      Serial.print("[BLE] Successfully parsed SSID: '"); Serial.print(wifiSsid); Serial.println("'");
      Serial.print("[BLE] Password length: "); Serial.println(wifiPassword.length());

      preferences.begin("rj45_wifi", false);
      preferences.putString("ssid", wifiSsid);
      preferences.putString("pass", wifiPassword);
      preferences.end();

      if (pStatusChar) {
        uint8_t st = 2; // Provisioned
        pStatusChar->setValue(&st, 1);
        pStatusChar->notify();
      }
      provisioningCompleted = true;
      inProvisioningMode = false; // Trigger exit
    } else {
      Serial.println("[BLE] ERROR: Could not parse SSID from payload!");
    }
  }
};

static bool bleInitialized = false;

void setupBleProvisioning() {
  if (bleInitialized) return;
  BLEDevice::init(BLE_DEVICE_NAME);
  pBleServer = BLEDevice::createServer();
  
  BLEService *pService = pBleServer->createService(BLE_SERVICE_UUID);
  
  BLECharacteristic *pPopChar = pService->createCharacteristic(
    CHAR_POP_UUID,
    BLECharacteristic::PROPERTY_WRITE
  );
  pPopChar->setCallbacks(new PoPCallback());

  BLECharacteristic *pWifiChar = pService->createCharacteristic(
    CHAR_WIFI_UUID,
    BLECharacteristic::PROPERTY_WRITE
  );
  pWifiChar->setCallbacks(new WifiCredsCallback());

  pStatusChar = pService->createCharacteristic(
    CHAR_STATUS_UUID,
    BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY
  );
  pStatusChar->addDescriptor(new BLE2902());
  uint8_t initialStatus = 0;
  pStatusChar->setValue(&initialStatus, 1);

  pService->start();
  
  BLEAdvertising *pAdvertising = BLEDevice::getAdvertising();
  pAdvertising->addServiceUUID(BLE_SERVICE_UUID);
  pAdvertising->setScanResponse(true);
  pAdvertising->setMinPreferred(0x06);
  pAdvertising->setMinPreferred(0x12);
  bleInitialized = true;
}

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
  tft.println("Open Android App");
  tft.setCursor(10, 55);
  tft.println("Connect to Diorama");
  tft.setCursor(10, 75);
  tft.println("PIN: RJ45-2026");
  tft.setTextColor(ST77XX_YELLOW);
  tft.setCursor(10, 105);
  tft.println("BACK = CANCEL");

  setupBleProvisioning();
  BLEDevice::startAdvertising();
  Serial.println("[BLE] Provisioning server started. Advertising RJ45-MASTER...");
}

void stopBleProvisioning() {
  Serial.println("[BLE] Provisioning finished. Stopping BLE advertising...");
  if (BLEDevice::getAdvertising() != nullptr) {
    BLEDevice::getAdvertising()->stop();
  }
}

// ============================================================
// FIREBASE BACKGROUND WORKER TASK DECLARATION
// ============================================================
#define FIREBASE_HOST "https://sanlo-139ce-default-rtdb.asia-southeast1.firebasedatabase.app"

TaskHandle_t firebaseTaskHandle = NULL;
void firebaseTask(void *pvParameters);


// ============================================================
// SENSOR VALUES
// ============================================================

float temperature = 0;
float humidity = 0;

uint8_t waterRaw = 0;
uint8_t flexRaw = 0;

int waterPercent = 0;

bool pirDetected = false;

uint16_t colorR = 0;
uint16_t colorG = 0;
uint16_t colorB = 0;
uint16_t colorC = 0;

String gestureName = "NONE";
String gestureUIState = "IDLE";
unsigned long gestureUIStateTime = 0;
String clockText = "--:--:--";

// ============================================================
// CONTINUOUS COLOR SCAN MODE
// UP   -> start scanning
// DOWN -> stop scanning
// ============================================================
bool colorScanMode = false;
unsigned long lastColorScanSend = 0;
const unsigned long COLOR_SCAN_SEND_INTERVAL = 300;


// ============================================================
// VISITOR SYSTEM
// ============================================================

bool visitorActive = false;
// PIR starts one visitor session; gestures may repeat while the visitor remains present.
bool pirArmed = true;

unsigned long visitorStartTime = 0;

// Visitor session is kept alive through temporary PIR LOW periods.
const unsigned long VISITOR_TIMEOUT = 30000; // retained for compatibility; not used by updatePIR()


// ============================================================
// TIMERS
// ============================================================

unsigned long lastFastRead = 0;
unsigned long lastDHTRead = 0;
unsigned long lastSlowRead = 0;
unsigned long lastDisplayUpdate = 0;

const unsigned long FAST_INTERVAL = 50;
const unsigned long DHT_INTERVAL = 2000;
const unsigned long SLOW_INTERVAL = 1000;
const unsigned long DISPLAY_INTERVAL = 500;


// ============================================================
// THRESHOLDS
// ============================================================

int WATER_LOW_RAW = 20;
int WATER_HIGH_RAW = 200;

int WATER_DRAIN_THRESHOLD = 75;

int FLEX_THRESHOLD = 100;

// wind simulation
const uint8_t WIND_SOUND_TRACK = 4; // 0004.mp3

// Calibration (Retained classmate update: 5)
const int WIND_FULL_BEND_DELTA = 5;


// Relay heartbeat
const unsigned long RELAY_REFRESH_MS = 850;

bool windSession = false;
bool windAudioOwned = false;

unsigned long windStarted = 0;
unsigned long lastFanRefresh = 0;

int windBaseline = 0;
int windPercent = 0;

const char* windLevel = "CALM";


// ============================================================
// ESP-NOW PACKET
// ============================================================

struct CommandPacket {
  uint8_t command;
  int16_t value;
};


// ============================================================
// COMMANDS
// ============================================================

enum Command : uint8_t {

  CMD_STOP = 0,

  CMD_TRAM_FORWARD = 1,
  CMD_TRAM_REVERSE = 2,
  CMD_TRAM_STOP = 3,

  CMD_GATE_OPEN = 4,
  CMD_GATE_CLOSE = 5,

  CMD_FANS_ON = 10,
  CMD_FANS_OFF = 11,

  CMD_PUMP_ON = 12,
  CMD_PUMP_OFF = 13,

  CMD_LIGHT_LEVEL = 14,

  CMD_STRIP_ON = 15,
  CMD_STRIP_OFF = 16,

  // Slave 2 WS2812B solid color. value carries RGB565.
  CMD_STRIP_COLOR = 17
};


// ============================================================
// I2C CHECK
// ============================================================

bool checkI2C(uint8_t address) {

  Wire.beginTransmission(address);

  return Wire.endTransmission() == 0;
}


// ============================================================
// I2C SCANNER
// ============================================================

void scanI2CBus() {

  Serial.println();
  Serial.println("=================================");
  Serial.println("        I2C BUS SCANNER");
  Serial.println("=================================");

  int devices = 0;

  for (uint8_t address = 1; address < 127; address++) {

    Wire.beginTransmission(address);

    uint8_t error = Wire.endTransmission();

    if (error == 0) {

      Serial.print("FOUND DEVICE -> 0x");

      if (address < 16) {
        Serial.print("0");
      }

      Serial.println(address, HEX);

      devices++;
    }
  }

  Serial.println("---------------------------------");

  if (devices == 0) {

    Serial.println("NO I2C DEVICES FOUND");

  } else {

    Serial.print("TOTAL I2C DEVICES: ");
    Serial.println(devices);
  }

  Serial.println("=================================");
  Serial.println();
}


// ============================================================
// APDS DIRECT REGISTER DRIVER
// Bypasses SparkFun apds.init(), because this 0x39 module
// responds on I2C but reports a non-standard ID.
// ============================================================

uint8_t apdsReadReg(uint8_t reg) {
  Wire.beginTransmission(APDS9960_ADDR);
  Wire.write(reg);
  if (Wire.endTransmission(false) != 0) return 0xFF;
  Wire.requestFrom(APDS9960_ADDR, (uint8_t)1);
  if (Wire.available()) return Wire.read();
  return 0xFF;
}

bool apdsWriteReg(uint8_t reg, uint8_t value) {
  Wire.beginTransmission(APDS9960_ADDR);
  Wire.write(reg);
  Wire.write(value);
  return Wire.endTransmission() == 0;
}

bool apdsReadFIFO(uint8_t *data, uint8_t datasets) {
  if (datasets == 0 || datasets > 32) return false;
  uint8_t bytes = datasets * 4;

  Wire.beginTransmission(APDS9960_ADDR);
  Wire.write(0xFC); // GFIFO_U; FIFO streams U,D,L,R datasets
  if (Wire.endTransmission(false) != 0) return false;

  uint8_t received = Wire.requestFrom(APDS9960_ADDR, bytes);
  if (received != bytes) {
    while (Wire.available()) Wire.read();
    return false;
  }

  for (uint8_t i = 0; i < bytes; i++) data[i] = Wire.read();
  return true;
}

bool setupAPDSDirect() {
  Serial.println();
  Serial.println("=================================");
  Serial.println("       APDS DIRECT CHECK");
  Serial.println("=================================");

  apdsAddressFound = checkI2C(APDS9960_ADDR);
  if (!apdsAddressFound) {
    Serial.println("0x39 ACKNOWLEDGED: NO");
    gestureName = "NO APDS";
    gestureFound = false;
    return false;
  }

  Serial.println("0x39 ACKNOWLEDGED: YES");
  uint8_t id = apdsReadReg(0x92);
  Serial.print("ID register 0x92: 0x");
  if (id < 0x10) Serial.print("0");
  Serial.println(id, HEX);
  Serial.println("Using direct-register mode (SparkFun init bypassed).");

  // Disable first.
  if (!apdsWriteReg(0x80, 0x00)) return false;
  delay(20);

  // APDS9960-style proximity/gesture configuration.
  bool ok = true;
  ok &= apdsWriteReg(0x81, 0xDB); // ATIME
  ok &= apdsWriteReg(0x83, 0xF6); // WTIME
  ok &= apdsWriteReg(0x8E, 0x89); // PPULSE
  ok &= apdsWriteReg(0x8F, 0x0C); // CONTROL: proximity gain
  ok &= apdsWriteReg(0x90, 0x30); // CONFIG2: LED boost
  // LOW gesture thresholds for this specific 0x39 module.
  // Its measured proximity response is roughly 0-14, so the old
  // 30/20 thresholds prevented the gesture engine from entering.
  ok &= apdsWriteReg(0xA0, 3);   // GPENTH: reduce idle/noise triggering
  ok &= apdsWriteReg(0xA1, 1);    // GEXTH: clean exit after hand leaves
  ok &= apdsWriteReg(0xA2, 0x40); // GCONF1
  ok &= apdsWriteReg(0xA3, 0x41); // GCONF2
  ok &= apdsWriteReg(0xA4, 0x00); // GOFFSET_U
  ok &= apdsWriteReg(0xA5, 0x00); // GOFFSET_D
  ok &= apdsWriteReg(0xA6, 0xC9); // GPULSE
  ok &= apdsWriteReg(0xA7, 0x00); // GOFFSET_L
  ok &= apdsWriteReg(0xA9, 0x00); // GOFFSET_R
  ok &= apdsWriteReg(0xAA, 0x00); // GCONF3
  ok &= apdsWriteReg(0xAB, 0x01); // GCONF4: GMODE=1
  ok &= apdsWriteReg(0x80, 0x4D); // PON + PEN + WEN + GEN
  delay(100);

  uint8_t enable = apdsReadReg(0x80);
  uint8_t gconf4 = apdsReadReg(0xAB);
  uint8_t gpenth = apdsReadReg(0xA0);
  uint8_t gexth  = apdsReadReg(0xA1);

  Serial.print("ENABLE 0x80: 0x"); Serial.println(enable, HEX);
  Serial.print("GCONF4 0xAB: 0x"); Serial.println(gconf4, HEX);
  Serial.print("GPENTH 0xA0: "); Serial.println(gpenth);
  Serial.print("GEXTH  0xA1: "); Serial.println(gexth);

  gestureFound = ok && (enable != 0xFF) && (gconf4 != 0xFF);
  gestureName = gestureFound ? "WAITING" : "NO APDS";
  Serial.println(gestureFound ? "Direct gesture setup: READY" : "Direct gesture setup: FAILED");
  Serial.println("=================================");
  return gestureFound;
}

// ============================================================
// LCD CLEAR
// ============================================================

void lcdClear() {

  tft.fillScreen(ST77XX_BLACK);
}


// ============================================================
// LCD HEADER
// ============================================================

void lcdHeader(const char* text) {

  tft.fillRect(
    0,
    0,
    tft.width(),
    17,
    ST77XX_BLUE
  );

  tft.setTextColor(ST77XX_WHITE);
  tft.setTextSize(1);

  tft.setCursor(4, 5);

  tft.print(text);
}


// ============================================================
// TFT SETUP
// ============================================================

void setupLCD() {

  tftSPI.begin(
    TFT_SCK,
    -1,
    TFT_MOSI,
    TFT_CS
  );

  tft.initR(INITR_BLACKTAB);

  tft.setRotation(1);

  tft.setTextWrap(false);

  lcdClear();

  tft.setTextColor(ST77XX_CYAN);
  tft.setTextSize(2);

  tft.setCursor(50, 24);
  tft.print("RJ45");

  tft.setTextColor(ST77XX_WHITE);
  tft.setTextSize(1);

  tft.setCursor(36, 52);
  tft.print("DIORAMA MASTER");

  tft.setTextColor(ST77XX_YELLOW);

  tft.setCursor(53, 73);
  tft.print("BOOTING...");
}


// ============================================================
// STATUS LINE
// ============================================================

void lcdStatusLine(
  int y,
  const char* name,
  bool status
) {

  tft.setTextSize(1);

  tft.setTextColor(ST77XX_WHITE);

  tft.setCursor(4, y);
  tft.print(name);

  if (status) {

    tft.setTextColor(ST77XX_GREEN);

    tft.setCursor(125, y);
    tft.print("OK");

  } else {

    tft.setTextColor(ST77XX_RED);

    tft.setCursor(105, y);
    tft.print("MISSING");
  }
}


// ============================================================
// SYSTEM CHECK
// ============================================================

void showSystemCheck() {

  lcdClear();

  lcdHeader("RJ45 - SYSTEM CHECK");

  lcdStatusLine(
    23,
    "PCF8591",
    pcfFound
  );

  lcdStatusLine(
    36,
    "DS3231",
    rtcFound
  );

  lcdStatusLine(
    49,
    "TCS34725",
    colorFound
  );

  lcdStatusLine(
    62,
    "APDS9960",
    gestureFound
  );

  lcdStatusLine(
    75,
    "DFPLAYER",
    dfPlayerFound
  );

  lcdStatusLine(
    88,
    "WiFi",
    wifiReady
  );

  lcdStatusLine(
    101,
    "ESP-NOW",
    espNowReady
  );

  tft.setTextColor(ST77XX_CYAN);

  tft.setCursor(44, 118);

  tft.print("MASTER READY");
}


// ============================================================
// GUIDED HAND POSITION TFT UI
// DASHBOARD -> VISITOR -> PLACE HAND -> HAND FOUND
// -> MOVE LEFT/RIGHT -> HOLD -> CONFIRMED -> DASHBOARD
// ============================================================
void setGestureUI(String state) {

  if (state == "TRAM_ERROR_LEFT") {
    gestureUIState = state;
    gestureUIStateTime = millis();
    tft.fillScreen(ST77XX_BLACK);
    tft.setTextWrap(false);
    drawCenteredText("TRAM IS", 20, 3, ST77XX_YELLOW);
    drawCenteredText("ALREADY ON", 55, 2, ST77XX_WHITE);
    drawCenteredText("THE LEFT", 85, 3, ST77XX_RED);
    return;
  }

  if (state == "TRAM_ERROR_RIGHT") {
    gestureUIState = state;
    gestureUIStateTime = millis();
    tft.fillScreen(ST77XX_BLACK);
    tft.setTextWrap(false);
    drawCenteredText("TRAM IS", 20, 3, ST77XX_YELLOW);
    drawCenteredText("ALREADY ON", 55, 2, ST77XX_WHITE);
    drawCenteredText("THE RIGHT", 85, 3, ST77XX_RED);
    return;
  }

  // Dedicated vertical confirmation screens.
  if (state == "UP_CONFIRMED") {
    gestureUIState = state;
    gestureUIStateTime = millis();

    tft.fillScreen(ST77XX_BLACK);
    tft.setTextWrap(false);
    tft.setTextColor(ST77XX_WHITE);

    tft.setTextSize(1);
    tft.setCursor(50, 15);
    tft.print("GESTURE");

    tft.setTextSize(5);
    tft.setCursor(50, 42);
    tft.print("UP");

    tft.setTextSize(1);
    tft.setCursor(43, 108);
    tft.print("CONFIRMED");
    return;
  }

  if (state == "DOWN_CONFIRMED") {
    gestureUIState = state;
    gestureUIStateTime = millis();

    tft.fillScreen(ST77XX_BLACK);
    tft.setTextWrap(false);
    tft.setTextColor(ST77XX_WHITE);

    tft.setTextSize(1);
    tft.setCursor(50, 15);
    tft.print("GESTURE");

    tft.setTextSize(4);
    tft.setCursor(30, 45);
    tft.print("DOWN");

    tft.setTextSize(1);
    tft.setCursor(43, 108);
    tft.print("CONFIRMED");
    return;
  }


  if (gestureUIState != state) {
    gestureUIState = state;
    gestureUIStateTime = millis();
  }
}

void drawCenteredText(String txt, int y, uint8_t size, uint16_t color) {
  int16_t x1, y1;
  uint16_t w, h;
  tft.setTextSize(size);
  tft.setTextColor(color);
  tft.getTextBounds(txt, 0, y, &x1, &y1, &w, &h);
  int x = (tft.width() - w) / 2;
  if (x < 0) x = 0;
  tft.setCursor(x, y);
  tft.print(txt);
}

void drawGestureUI() {
  tft.fillScreen(ST77XX_BLACK);

  if (gestureUIState == "VISITOR") {
    drawCenteredText("VISITOR", 18, 2, ST77XX_CYAN);
    drawCenteredText("DETECTED", 42, 2, ST77XX_CYAN);
    drawCenteredText("PLEASE WAIT...", 84, 1, ST77XX_WHITE);
  } else if (gestureUIState == "WAIT_COMMAND") {
    drawCenteredText("READY FOR", 13, 2, ST77XX_CYAN);
    drawCenteredText("COMMAND", 39, 2, ST77XX_CYAN);
    drawCenteredText("PLACE HAND", 75, 2, ST77XX_YELLOW);
    drawCenteredText("IN CENTER", 103, 1, ST77XX_WHITE);
  } else if (gestureUIState == "HOLD_COMMAND") {
    drawCenteredText("HAND CENTERED", 13, 2, ST77XX_GREEN);
    drawCenteredText("HOLD STEADY", 45, 2, ST77XX_YELLOW);
    drawCenteredText("5 SECONDS", 77, 2, ST77XX_WHITE);
    drawCenteredText("TO COMMAND", 105, 1, ST77XX_CYAN);
  } else if (gestureUIState == "WAIT_HAND_REMOVE") {
    drawCenteredText("COMMAND DONE", 18, 2, ST77XX_GREEN);
    drawCenteredText("REMOVE HAND", 53, 2, ST77XX_YELLOW);
    drawCenteredText("KEEP HAND AWAY", 88, 1, ST77XX_WHITE);
    drawCenteredText("FOR NEXT COMMAND", 103, 1, ST77XX_WHITE);
  } else if (gestureUIState == "PLACE_HAND") {
    drawCenteredText("PLACE YOUR", 16, 2, ST77XX_WHITE);
    drawCenteredText("HAND", 43, 3, ST77XX_YELLOW);
    drawCenteredText("IN FRONT OF", 86, 1, ST77XX_WHITE);
    drawCenteredText("THE SENSOR", 100, 1, ST77XX_WHITE);
  } else if (gestureUIState == "HAND_FOUND") {
    drawCenteredText("HAND", 20, 3, ST77XX_YELLOW);
    drawCenteredText("DETECTED", 58, 2, ST77XX_YELLOW);
    drawCenteredText("CHECKING POSITION", 98, 1, ST77XX_WHITE);
  } else if (gestureUIState == "POSITION_LEFT") {
    drawCenteredText("HAND POSITION", 18, 1, ST77XX_WHITE);
    drawCenteredText("< LEFT", 46, 3, ST77XX_CYAN);
    drawCenteredText("HOLD STILL", 91, 1, ST77XX_WHITE);
    drawCenteredText("VERIFYING...", 105, 1, ST77XX_YELLOW);
  } else if (gestureUIState == "POSITION_RIGHT") {
    drawCenteredText("HAND POSITION", 18, 1, ST77XX_WHITE);
    drawCenteredText("RIGHT >", 46, 3, ST77XX_CYAN);
    drawCenteredText("HOLD STILL", 91, 1, ST77XX_WHITE);
    drawCenteredText("VERIFYING...", 105, 1, ST77XX_YELLOW);
  } else if (gestureUIState == "MOVE_LEFT") {
    drawCenteredText("<", 4, 4, ST77XX_CYAN);
    drawCenteredText("MOVE HAND", 55, 2, ST77XX_WHITE);
    drawCenteredText("MORE LEFT", 82, 2, ST77XX_CYAN);
  } else if (gestureUIState == "MOVE_RIGHT") {
    drawCenteredText(">", 4, 4, ST77XX_CYAN);
    drawCenteredText("MOVE HAND", 55, 2, ST77XX_WHITE);
    drawCenteredText("MORE RIGHT", 82, 2, ST77XX_CYAN);
  } else if (gestureUIState == "HOLD") {
    drawCenteredText("GOOD", 18, 3, ST77XX_GREEN);
    drawCenteredText("HOLD STILL", 58, 2, ST77XX_WHITE);
    drawCenteredText("VERIFYING...", 96, 1, ST77XX_CYAN);
  } else 
  
  if (gestureUIState == "CALIBRATE_IDLE") {
    tft.fillScreen(ST77XX_BLACK);
    drawCenteredText("CALIBRATING", 16, 2, ST77XX_YELLOW);
    drawCenteredText("KEEP HAND", 50, 2, ST77XX_WHITE);
    drawCenteredText("AWAY", 78, 2, ST77XX_WHITE);
    drawCenteredText("PLEASE WAIT...", 106, 1, ST77XX_CYAN);
    return;
  }

if (gestureUIState == "SWIPE_READY") {
    tft.fillScreen(ST77XX_BLACK);
    drawCenteredText("READY!", 12, 2, ST77XX_GREEN);
    drawCenteredText("<  SWIPE  >", 43, 2, ST77XX_WHITE);
    drawCenteredText("LEFT OR RIGHT", 75, 1, ST77XX_YELLOW);
    drawCenteredText("MOVE YOUR HAND", 96, 1, ST77XX_CYAN);
    return;
  }

  if (gestureUIState == "NOT_CLEAR") {
    tft.fillScreen(ST77XX_BLACK);
    drawCenteredText("NOT CLEAR", 20, 2, ST77XX_YELLOW);
    drawCenteredText("TRY AGAIN", 53, 2, ST77XX_WHITE);
    drawCenteredText("STEP AWAY", 85, 1, ST77XX_CYAN);
    drawCenteredText("THEN RETURN", 102, 1, ST77XX_CYAN);
    return;
  }

  if (gestureUIState == "RESETTING") {
    tft.fillScreen(ST77XX_BLACK);
    drawCenteredText("CLEARING", 22, 2, ST77XX_YELLOW);
    drawCenteredText("SENSOR...", 54, 2, ST77XX_WHITE);
    drawCenteredText("PLEASE WAIT", 90, 1, ST77XX_CYAN);
    return;
  }

  if (gestureUIState == "READING") {
    tft.fillScreen(ST77XX_BLACK);
    drawCenteredText("READING", 22, 2, ST77XX_YELLOW);
    drawCenteredText("SWIPE...", 56, 2, ST77XX_WHITE);
    drawCenteredText("KEEP MOVING", 91, 1, ST77XX_CYAN);
    return;
  }

if (gestureUIState == "LEFT_CONFIRMED") {
    drawCenteredText("<", 3, 4, ST77XX_GREEN);
    drawCenteredText("LEFT", 52, 3, ST77XX_GREEN);
    drawCenteredText("POSITION CONFIRMED", 102, 1, ST77XX_WHITE);
  } else if (gestureUIState == "RIGHT_CONFIRMED") {
    drawCenteredText(">", 3, 4, ST77XX_GREEN);
    drawCenteredText("RIGHT", 52, 3, ST77XX_GREEN);
    drawCenteredText("POSITION CONFIRMED", 102, 1, ST77XX_WHITE);
  }
}

// ============================================================
// LIVE TFT
// ============================================================

void updateLCD() {

  // Normal dashboard remains the default UI.
  if (visitorActive && gestureUIState != "IDLE") {
    drawGestureUI();
    return;
  }


  lcdClear();

  lcdHeader("RJ45 DIORAMA");


  // WATER

  tft.setTextColor(ST77XX_WHITE);
  tft.setCursor(4, 22);
  tft.print("Water");

  tft.setCursor(78, 22);

  if (waterPercent >= WATER_DRAIN_THRESHOLD) {

    tft.setTextColor(ST77XX_RED);

  } else {

    tft.setTextColor(ST77XX_CYAN);
  }

  tft.print(waterPercent);
  tft.print("%");


  // TEMPERATURE

  tft.setTextColor(ST77XX_WHITE);

  tft.setCursor(4, 35);
  tft.print("Temp");

  tft.setTextColor(ST77XX_YELLOW);

  tft.setCursor(78, 35);

  tft.print(temperature, 1);
  tft.print(" C");


  // PIR

  tft.setTextColor(ST77XX_WHITE);

  tft.setCursor(4, 48);
  tft.print("PIR");

  tft.setCursor(78, 48);

  if (pirDetected) {

    tft.setTextColor(ST77XX_RED);
    tft.print("MOTION");

  } else {

    tft.setTextColor(ST77XX_GREEN);
    tft.print("CLEAR");
  }


  // APDS

  tft.setTextColor(ST77XX_WHITE);

  tft.setCursor(4, 61);
  tft.print("APDS");

  tft.setCursor(78, 61);

  if (gestureFound) {

    tft.setTextColor(ST77XX_GREEN);
    tft.print("OK");

  } else if (apdsAddressFound) {

    /*
       This means 0x39 exists,
       but direct gesture setup failed.
    */

    tft.setTextColor(ST77XX_YELLOW);
    tft.print("0x39");

  } else {

    tft.setTextColor(ST77XX_RED);
    tft.print("MISSING");
  }


  // GESTURE

  tft.setTextColor(ST77XX_WHITE);

  tft.setCursor(4, 74);
  tft.print("Gesture");

  tft.setCursor(78, 74);

  if (!gestureFound) {

    tft.setTextColor(ST77XX_RED);
    tft.print("NO APDS");

  } else if (!visitorActive) {

    tft.setTextColor(ST77XX_GREEN);
    tft.print("WAITING");

  } else {

    tft.setTextColor(ST77XX_MAGENTA);
    tft.print(gestureName);
  }


  // CLOCK

  tft.setTextColor(ST77XX_WHITE);

  tft.setCursor(4, 87);
  tft.print("Time");

  tft.setTextColor(ST77XX_YELLOW);

  tft.setCursor(78, 87);
  tft.print(clockText);


  // HUMIDITY

  tft.setTextColor(ST77XX_WHITE);

  tft.setCursor(4, 100);
  tft.print("Humidity");

  tft.setTextColor(ST77XX_CYAN);

  tft.setCursor(78, 100);

  tft.print(humidity, 0);
  tft.print("%");


  // WIFI

  tft.drawFastHLine(
    0,
    113,
    tft.width(),
    ST77XX_BLUE
  );

  tft.setCursor(4, 119);

  if (wifiStationConnected) {
    tft.setTextColor(ST77XX_GREEN);
    tft.print("STA:");
    tft.print(WiFi.localIP());
  } else if (wifiReady) {

    tft.setTextColor(ST77XX_GREEN);

    tft.print("WiFi OK 192.168.4.1");

  } else {

    tft.setTextColor(ST77XX_RED);

    tft.print("WiFi OFFLINE");
  }
}


// ============================================================
// PCF8591 READ
// ============================================================

uint8_t readPCF8591(uint8_t channel) {

  if (!pcfFound) {
    return 0;
  }

  Wire.beginTransmission(PCF8591_ADDR);

  Wire.write(
    0x40 |
    (channel & 0x03)
  );

  if (Wire.endTransmission() != 0) {
    return 0;
  }

  Wire.requestFrom(
    PCF8591_ADDR,
    2
  );

  // PCF8591 first read is previous conversion
  if (Wire.available()) {
    Wire.read();
  }

  if (Wire.available()) {
    return Wire.read();
  }

  return 0;
}


// ============================================================
// WATER PERCENT
// ============================================================

int calculateWaterPercent(uint8_t raw) {

  int percent =
    map(
      raw,
      WATER_LOW_RAW,
      WATER_HIGH_RAW,
      0,
      100
    );

  return constrain(
    percent,
    0,
    100
  );
}


// ============================================================
// ESP-NOW SEND
// ============================================================

void sendCommand(
  const uint8_t* mac,
  uint8_t command,
  int16_t value = 0
) {

  bool allZero = true;

  for (int i = 0; i < 6; i++) {

    if (mac[i] != 0) {

      allZero = false;
      break;
    }
  }

  if (allZero) {
    return;
  }

  CommandPacket packet;

  packet.command = command;
  packet.value = value;

  esp_now_send(
    mac,
    (uint8_t*)&packet,
    sizeof(packet)
  );
}


// ============================================================
// ESP-NOW PEER
// ============================================================

bool registerPeer(uint8_t* mac) {

  bool allZero = true;

  for (int i = 0; i < 6; i++) {

    if (mac[i] != 0) {

      allZero = false;
      break;
    }
  }

  if (allZero) {
    return false;
  }

  esp_now_peer_info_t peerInfo = {};

  memcpy(
    peerInfo.peer_addr,
    mac,
    6
  );

  peerInfo.channel = 0; // 0 = send on current active Wi-Fi channel
  peerInfo.encrypt = false;

  if (esp_now_is_peer_exist(mac)) {
    return true;
  }

  esp_err_t result =
    esp_now_add_peer(&peerInfo);

  return result == ESP_OK;
}


// ============================================================
// AUDIO - DFPLAYER MINI
// Working wiring:
//   DFPlayer TX -> ESP32 GPIO16 (RX2)
//   ESP32 GPIO17 (TX2) -> 1k resistor -> DFPlayer RX
// microSD test file: /mp3/0001.mp3
// ============================================================

void reportDFPlayerError(int error) {
  Serial.print("[DFPLAYER] ERROR: ");

  switch (error) {
    case Busy:
      Serial.println("Module busy or SD card unavailable.");
      break;
    case Sleeping:
      Serial.println("Module sleeping.");
      break;
    case SerialWrongStack:
      Serial.println("Serial communication error.");
      break;
    case CheckSumNotMatch:
      Serial.println("Checksum error.");
      break;
    case FileIndexOut:
      Serial.println("File index out of range.");
      break;
    case FileMismatch:
      Serial.println("Requested file not found.");
      break;
    case Advertise:
      Serial.println("Advertisement playback error.");
      break;
    default:
      Serial.println(error);
      break;
  }
}

bool startDFPlayer(bool playTestTrack = true) {
  dfPlayerFound = false;

  Serial.println();
  Serial.println("[DFPLAYER] Starting...");
  dfPlayer.setTimeOut(2000);

  if (!dfPlayer.begin(DFSerial, true, true)) {
    Serial.println("[DFPLAYER] INITIALIZATION FAILED");
    Serial.println("[DFPLAYER] Check 5V, common GND, FAT32 SD, TX/RX.");
    return false;
  }

  dfPlayerFound = true;
  dfPlayer.outputDevice(DFPLAYER_DEVICE_SD);
  delay(300);

  dfPlayer.volume(dfPlayerVolume);
  delay(200);

  Serial.println("[DFPLAYER] READY");
  Serial.print("[DFPLAYER] Volume: ");
  Serial.println(dfPlayerVolume);

  if (playTestTrack) {
    dfPlayer.playMp3Folder(1);   // /mp3/0001.mp3
    Serial.println("[DFPLAYER] Playing /mp3/0001.mp3");
  }

  return true;
}

void playTrack(int track) {
  if (!dfPlayerFound) return;

  // Uses /mp3/0001.mp3, /mp3/0002.mp3, ...
  dfPlayer.playMp3Folder(track);

  Serial.print("[DFPLAYER] Play /mp3/");
  if (track < 1000) Serial.print("0");
  if (track < 100)  Serial.print("0");
  if (track < 10)   Serial.print("0");
  Serial.print(track);
  Serial.println(".mp3");
}

void updateDFPlayer() {
  // Optional Serial Monitor controls from the working standalone test:
  // p = play 0001, s = stop, +/- = volume, r = reinitialize.
  if (Serial.available()) {
    char command = Serial.read();

    if (command == 'r' || command == 'R') {
      startDFPlayer(false);
    } else if (command != '\r' && command != '\n' && command != ' ') {
      if (command == 'p' || command == 'P') {
        playTrack(1);
      } else if (command == 's' || command == 'S') {
        if (dfPlayerFound) {
          dfPlayer.stop();
          Serial.println("[DFPLAYER] STOP");
        }
      } else if (command == '+') {
        dfPlayerVolume = min(dfPlayerVolume + 1, 30);
        if (dfPlayerFound) dfPlayer.volume(dfPlayerVolume);
        Serial.print("[DFPLAYER] Volume: ");
        Serial.println(dfPlayerVolume);
      } else if (command == '-') {
        dfPlayerVolume = max(dfPlayerVolume - 1, 0);
        if (dfPlayerFound) dfPlayer.volume(dfPlayerVolume);
        Serial.print("[DFPLAYER] Volume: ");
        Serial.println(dfPlayerVolume);
      }
    }
  }

  if (!dfPlayerFound || !dfPlayer.available()) return;

  uint8_t eventType = dfPlayer.readType();
  int eventValue = dfPlayer.read();

  switch (eventType) {
    case DFPlayerPlayFinished:
      Serial.print("[DFPLAYER] Track finished: ");
      Serial.println(eventValue);
      break;
    case DFPlayerError:
      reportDFPlayerError(eventValue);
      break;
    case DFPlayerCardInserted:
      Serial.println("[DFPLAYER] SD card inserted.");
      break;
    case DFPlayerCardRemoved:
      Serial.println("[DFPLAYER] SD card removed.");
      break;
    case DFPlayerCardOnline:
      Serial.println("[DFPLAYER] SD card online.");
      break;
    case TimeOut:
      Serial.println("[DFPLAYER] Response timeout.");
      break;
    case WrongStack:
      Serial.println("[DFPLAYER] Invalid serial response.");
      break;
    default:
      break;
  }
}


// ============================================================
// PIR
// ============================================================

void updatePIR() {
  // PIR is ONLY the wake/welcome trigger.
  // Once the exhibit has been activated by a visitor, temporary or long PIR LOW
  // does NOT disable APDS command availability and does NOT restart the visitor flow.
  //
  // This is intentional because HC-SR501 detects motion, not true continuous presence.
  // A visitor may stand/sit still for minutes and must still be able to command later.

  int pirState = digitalRead(PIR_PIN);
  pirDetected = (pirState == HIGH);

  // PIR diagnostic, once per second (even while waiting for visitor).
  static unsigned long lastPIRDebug = 0;
  if (millis() - lastPIRDebug >= 1000) {
    lastPIRDebug = millis();
    Serial.print("[PIR GPIO19] ");
    Serial.print(pirState);
    Serial.print(" | armed=");
    Serial.print(pirArmed ? "YES" : "NO");
    Serial.print(" | visitor=");
    Serial.println(visitorActive ? "ACTIVE" : "WAITING");
  }


  if (!visitorActive) {
    if (pirState == HIGH && pirArmed) {
      pirArmed = false;
      visitorActive = true;

      gestureName = "READY";
      uiScreen = UI_WELCOME;
      welcomeStarted = millis();
      gestureUIState = "IDLE";
      drawCurrentUI();  // Show welcome immediately after a new PIR trigger.

      Serial.println();
      Serial.println("[PIR] NEW VISITOR DETECTED");
      Serial.println("[UI] WELCOME -> MAIN MENU");
    }
    return;
  }

  // Exhibit/session already activated:
  // Ignore PIR LOW/HIGH for session termination. APDS owns interaction now.
  // The visitor may:
  //   - do nothing for any amount of time,
  //   - command repeatedly,
  //   - leave,
  //   - or come back later and command again.
  //
  // APDS starts the command countdown when a hand is placed in the center.
}

// An individual mode activates a complete functional subsystem, not a relay toggle.
bool operationEnabled(uint8_t operation) {
  if (uiScreen == UI_SIMULATION) {
    if (operation == 0) return simPhase == 0; // Tram only in Phase 0
    if (operation == 4 || operation == 3) return simPhase == 2; // River and Wind only in Phase 2
    if (operation == 2) return true; // Color allowed to be checked anytime
    return true; 
  }
  return (uiScreen == UI_INDIVIDUAL && individualRunning && individualIndex == operation);
}

// RTC-controlled street lights. Works in individual and full-system modes.
void updateStreetLights() {
  if (!operationEnabled(1) || !rtcFound) return;
  DateTime now = rtc.now();
  bool shouldBeOn = now.hour() >= STREET_LIGHT_ON_HOUR || now.hour() < STREET_LIGHT_OFF_HOUR;
  if (!streetLightsKnown || shouldBeOn != streetLightsOn ||
      millis() - lastStreetLightCommand >= 5000) {
    streetLightsOn = shouldBeOn;
    streetLightsKnown = true;
    lastStreetLightCommand = millis();
    sendCommand(slave2MAC, CMD_LIGHT_LEVEL, shouldBeOn ? 255 : 0);
    Serial.printf("[RTC LIGHTS] %02d:%02d -> %s\n", now.hour(), now.minute(), shouldBeOn ? "ON" : "OFF");
  }
}

void updateWater() {
  waterRaw = readPCF8591(0);
  waterPercent = calculateWaterPercent(waterRaw);

  static bool riverPumpActive = false;
  static bool dangerState = false;

  // If the River mode is NOT running, ensure pump and servo are reset OFF
  if (!operationEnabled(4)) {
    if (riverPumpActive) {
      riverPumpActive = false;
      pumpStatus = false;
      sendCommand(slave2MAC, CMD_PUMP_OFF);
    }
    if (dangerState) {
      dangerState = false;
      sendCommand(slave1MAC, CMD_GATE_CLOSE); // Reset servo to 0
    }
    return;
  }

  // LOGIC: Over 50% = DANGER (Pump OFF)
  if (waterPercent >= 50) {
    if (!dangerState) {
      dangerState = true;
      riverPumpActive = false;
      pumpStatus = false;
      
      sendCommand(slave2MAC, CMD_PUMP_OFF);    // Turn pump OFF
      sendCommand(slave1MAC, CMD_GATE_OPEN);  // Send command to Slave 1 for 45 deg
      Serial.println("[WATER] >=50% DANGER! Pump OFF, Servo 45");
    }
  } 
  // LOGIC: Below 45% = SAFE (Pump ON). The 5% gap stops the relay from rapidly clicking!
  else if (waterPercent < 45) {
    if (dangerState || !riverPumpActive) {
      dangerState = false;
      riverPumpActive = true;
      pumpStatus = true;
      
      sendCommand(slave2MAC, CMD_PUMP_ON);    // Turn pump ON
      sendCommand(slave1MAC, CMD_GATE_CLOSE); // Reset servo to 0 deg
      Serial.println("[WATER] <45% Safe level. Pump ON, Servo 0");
    }
  }
}


// ============================================================
// FLEX SENSOR - PCF8591 AIN2
// Based on the working standalone flexsensor.ino.
// REAL DIORAMA behavior:
//   flexRaw < threshold  -> WIND ON  -> Slave 2 fan relay
//   flexRaw >= threshold -> WIND OFF -> Slave 2 fan relay
// ============================================================
void startWindSession() {

  if (windSession || !pcfFound) {
    if (!pcfFound) {
      Serial.println("[WIND] PCF8591 not found");
    }
    return;
  }

  // Read the flex sensor BEFORE the fan starts
  windBaseline = readPCF8591(2);

  flexRaw = (uint8_t)windBaseline;

  windPercent = 0;
  windLevel = "CALM";

  windStarted = millis();
  lastFanRefresh = windStarted;

  windSession = true;
  fanStatus = true;

  // Automatically activate fan on SLAVE 2
  sendCommand(slave2MAC, CMD_FANS_ON);

  // Play /mp3/0004.mp3
  if (dfPlayerFound) {
    dfPlayer.playMp3Folder(WIND_SOUND_TRACK);
    windAudioOwned = true;
  }

  Serial.println("[WIND] FAN ON");
  Serial.println("[WIND] Playing 0004.mp3");
}

void stopWindSession() {

  // Stop fan on SLAVE 2
  if (windSession || fanStatus) {
    sendCommand(slave2MAC, CMD_FANS_OFF);
  }

  windSession = false;
  fanStatus = false;

  // Stop the wind soundscape
  if (windAudioOwned && dfPlayerFound) {
    dfPlayer.stop();
  }

  windAudioOwned = false;

  Serial.println("[WIND] OFF");
}

void updateFlex() {

  // Continuously read Flex Sensor AIN2
  flexRaw = readPCF8591(2);

  // Only measure during active wind simulation
  if (!operationEnabled(3)) {
    if (windSession) stopWindSession();
    return;
  }

  if (!windSession) return;

  // Compare current bending with initial position
  int delta = abs((int)flexRaw - windBaseline);

  // Convert relative bending into percentage
  windPercent = constrain(
    (delta * 100) / max(1, WIND_FULL_BEND_DELTA),
    0,
    100
  );

  // Classify measured response
  if (windPercent < 15) {
    windLevel = "CALM";
  }
  else if (windPercent < 60) {
    windLevel = "BREEZE";
  }
  else {
    windLevel = "STRONG";
  }

  unsigned long now = millis();


  // Refresh fan command for relay failsafe
  if (now - lastFanRefresh >= RELAY_REFRESH_MS) {
    lastFanRefresh = now;
    sendCommand(slave2MAC, CMD_FANS_ON);
  }
}


// ============================================================
// DHT
// ============================================================

void updateDHT() {

  float newHumidity =
    dht.readHumidity();

  float newTemperature =
    dht.readTemperature();

  if (!isnan(newHumidity)) {
    humidity = newHumidity;
  }

  if (!isnan(newTemperature)) {
    temperature = newTemperature;
  }
}


// ============================================================
// CLOCK
// ============================================================

void updateClock() {

  if (!rtcFound) {

    clockText = "NO RTC";

    return;
  }

  DateTime now =
    rtc.now();

  char buffer[12];

  snprintf(
    buffer,
    sizeof(buffer),
    "%02d:%02d:%02d",
    now.hour(),
    now.minute(),
    now.second()
  );

  clockText = buffer;
}


// ============================================================
// COLOR SENSOR
// ============================================================

void updateColor() {

  if (!colorFound) {
    return;
  }

  tcs.getRawData(
    &colorR,
    &colorG,
    &colorB,
    &colorC
  );
}


// ============================================================
// TCS34725 -> SLAVE 2 RGB STRIP
// Current version intentionally uses direct normalization.
// Fine calibration can be added later without changing the flow.
// ============================================================

uint16_t packRGB565(uint8_t r, uint8_t g, uint8_t b) {
  return ((uint16_t)(r & 0xF8) << 8) |
         ((uint16_t)(g & 0xFC) << 3) |
         ((uint16_t)b >> 3);
}

void scanColorAndSendToSlave2() {

  if (!operationEnabled(2) || !colorFound) return;
  if (uiScreen == UI_SIMULATION && !colorScanMode) return;

  // Limit ESP-NOW traffic and let the 50 ms TCS integration complete.
  unsigned long now = millis();
  if (now - lastColorScanSend < COLOR_SCAN_SEND_INTERVAL) return;
  lastColorScanSend = now;

  uint16_t rawR, rawG, rawB, rawC;
  tcs.getRawData(&rawR, &rawG, &rawB, &rawC);

  colorR = rawR;
  colorG = rawG;
  colorB = rawB;
  colorC = rawC;

  uint16_t strongest = max(rawR, max(rawG, rawB));

  // Ignore unusable/dark readings while scan mode remains active.
  // The strip keeps its previous color instead of flashing OFF.
  if (strongest == 0 || rawC == 0) {
    Serial.println("[COLOR MODE] Dark/no usable color - keeping last strip color.");
    return;
  }

  // ==========================================================
  // COLOR CORRECTION / SATURATION ENHANCEMENT
  //
  // TCS34725 readings often contain a shared white component:
  // e.g. RED can look like R=850 G=310 B=280.
  // That makes the WS2812 output look pink/washed-out.
  //
  // Remove most of the common RGB component first, while keeping
  // some of it so mixed colors and shades are still possible.
  // ==========================================================

  uint16_t common = min(rawR, min(rawG, rawB));

  // Remove 85% of the shared white component.
  // This is intentionally the first practical correction and can
  // be fine-tuned later from real sensor readings.
  uint16_t whiteRemoval = (uint32_t)common * 85UL / 100UL;

  uint16_t correctedR = (rawR > whiteRemoval) ? rawR - whiteRemoval : 0;
  uint16_t correctedG = (rawG > whiteRemoval) ? rawG - whiteRemoval : 0;
  uint16_t correctedB = (rawB > whiteRemoval) ? rawB - whiteRemoval : 0;

  uint16_t correctedMax = max(correctedR, max(correctedG, correctedB));

  if (correctedMax == 0) {
    Serial.println("[COLOR MODE] No usable corrected color - keeping last strip color.");
    return;
  }

  // Scale corrected color back to the full 0-255 LED range.
  uint8_t outR = (uint32_t)correctedR * 255UL / correctedMax;
  uint8_t outG = (uint32_t)correctedG * 255UL / correctedMax;
  uint8_t outB = (uint32_t)correctedB * 255UL / correctedMax;

  uint16_t rgb565 = packRGB565(outR, outG, outB);

  sendCommand(slave2MAC, CMD_STRIP_COLOR, (int16_t)rgb565);

  Serial.print("[COLOR MODE] RAW R=");
  Serial.print(rawR);
  Serial.print(" G=");
  Serial.print(rawG);
  Serial.print(" B=");
  Serial.print(rawB);
  Serial.print(" C=");
  Serial.print(rawC);
  Serial.print(" | corrected=");
  Serial.print(correctedR);
  Serial.print(",");
  Serial.print(correctedG);
  Serial.print(",");
  Serial.print(correctedB);
  Serial.print(" -> RGB ");
  Serial.print(outR);
  Serial.print(",");
  Serial.print(outG);
  Serial.print(",");
  Serial.println(outB);
}

// ============================================================
// GESTURE - DIRECT FIFO READING
// ============================================================

void handleGestureDirection(const String &dir) {
  gestureName = dir;
  visitorStartTime = millis();
  Serial.print("[GESTURE] ");
  Serial.println(dir);

  if (dir == "LEFT") {
    if (tramLocation == "LEFT") {
      setGestureUI("TRAM_ERROR_LEFT");
    } else {
      tramStatus = "REVERSE";
      tramLocation = "LEFT"; 
      sendCommand(slave1MAC, CMD_TRAM_REVERSE);
      
      // If returning left during Full Sim, start the 5-second travel timer
      if (uiScreen == UI_SIMULATION && simPhase == 0) {
        tramReturnTimer = millis();
        tramReturning = true;
        Serial.println("[SIMULATION] Tram returning LEFT. Auto-advancing in 5 seconds...");
      }
    }
  } else if (dir == "RIGHT") {
    if (tramLocation == "RIGHT") {
      setGestureUI("TRAM_ERROR_RIGHT");
    } else {
      tramStatus = "FORWARD";
      tramLocation = "RIGHT";
      sendCommand(slave1MAC, CMD_TRAM_FORWARD);
    }
  } else if (dir == "UP") {
    if ((uiScreen == UI_INDIVIDUAL && individualIndex == 2) || (uiScreen == UI_SIMULATION && simPhase == 1)) {
      colorScanMode = true;
      lastColorScanSend = 0;
      sendCommand(slave2MAC, CMD_STRIP_ON);
      Serial.println("[GESTURE] UP -> COLOR SCAN ON");
      
      // Advance to River Phase
      if (uiScreen == UI_SIMULATION && simPhase == 1) {
        simPhase = 2; 
        Serial.println("[SIMULATION] Phase 2 Unlocked: River/Wind ON");
      }
    }
  } else if (dir == "DOWN") {
    if ((uiScreen == UI_INDIVIDUAL && individualIndex == 2) || (uiScreen == UI_SIMULATION && simPhase == 1)) {
      colorScanMode = false;
      sendCommand(slave2MAC, CMD_STRIP_OFF);
      Serial.println("[GESTURE] DOWN -> COLOR SCAN OFF");
      
      // Advance to River Phase
      if (uiScreen == UI_SIMULATION && simPhase == 1) {
        simPhase = 2; 
        Serial.println("[SIMULATION] Phase 2 Unlocked: River/Wind ON (Lights Off)");
      }
    }
  }
}
// ============================================================
// APDS DIRECT REGISTER READ
// ============================================================

uint8_t readAPDSRegister(uint8_t reg) {

  Wire.beginTransmission(APDS9960_ADDR);

  Wire.write(reg);

  // Keep I2C connection active for repeated start
  if (Wire.endTransmission(false) != 0) {
    return 0xFF;
  }

  Wire.requestFrom(
    APDS9960_ADDR,
    (uint8_t)1
  );

  if (Wire.available()) {
    return Wire.read();
  }

  return 0xFF;
}
// ============================================================
// HORIZONTAL GESTURE CALIBRATION ONLY
// Temporary test mode:
//   LEFT  = black sensor/window side
//   RIGHT = opposite/yellow-marked side
//
// UP/DOWN ARE DISABLED in this version.
// ============================================================


// ============================================================
// TFT READY -> ZOOM/FADE COUNTDOWN ANIMATION
// ST7735 has no alpha transparency, so "fade" is simulated by
// shrinking the text toward the center and clearing each frame.
// ============================================================
void drawCenteredTFTText(const String &text, int textSize, int y) {
  int16_t x1, y1;
  uint16_t w, h;

  tft.setTextWrap(false);
  tft.setTextSize(textSize);
  tft.getTextBounds(text, 0, y, &x1, &y1, &w, &h);

  int x = (tft.width() - (int)w) / 2;
  if (x < 0) x = 0;

  tft.setCursor(x, y);
  tft.print(text);
}

void drawZoomFadeFrame(const String &text, int textSize) {
  tft.fillScreen(ST77XX_BLACK);
  tft.setTextColor(ST77XX_WHITE);

  int16_t x1, y1;
  uint16_t w, h;
  tft.setTextSize(textSize);
  tft.getTextBounds(text, 0, 0, &x1, &y1, &w, &h);

  int y = (tft.height() - (int)h) / 2;
  if (y < 0) y = 0;

  drawCenteredTFTText(text, textSize, y);
}

// READY appears first, then visually "zooms/fades out".
void animateReadyZoomOut() {
  const int sizes[] = {4, 3, 2, 1};
  const int delaysMs[] = {350, 130, 110, 90};

  for (int i = 0; i < 4; i++) {
    drawZoomFadeFrame("READY", sizes[i]);
    delay(delaysMs[i]);
  }

  tft.fillScreen(ST77XX_BLACK);
  delay(100);
}

// Each countdown number appears large and shrinks toward the center.
void animateCountdownNumber(const String &numberText) {
  const int sizes[] = {7, 6, 5, 4, 3, 2, 1};
  const int delaysMs[] = {170, 95, 85, 75, 65, 55, 45};

  for (int i = 0; i < 7; i++) {
    drawZoomFadeFrame(numberText, sizes[i]);
    delay(delaysMs[i]);
  }

  tft.fillScreen(ST77XX_BLACK);
  delay(70);
}

void animateSwipeCountdown() {
  animateReadyZoomOut();

  animateCountdownNumber("3");
  animateCountdownNumber("2");
  animateCountdownNumber("1");

  // Final GO screen.
  tft.fillScreen(ST77XX_BLACK);
  tft.setTextColor(ST77XX_WHITE);
  drawCenteredTFTText("GO!", 5, 34);
  drawCenteredTFTText("SWIPE NOW", 1, 103);
  delay(300);
}

void updateGesture() {

  // FIX: Do NOT tie the whole gesture engine to operationEnabled(0)
  if (!visitorActive) return;
  
  // Only block if we are in individual mode and it's not currently running
  if (uiScreen == UI_INDIVIDUAL && !individualRunning) return;

  if (!gestureFound) {
    gestureName = "NO APDS";
    return;
  }

  static unsigned long lastPoll = 0;
  static unsigned long lastDebug = 0;
  static unsigned long calibrationStarted = 0;
  static unsigned long captureStarted = 0;
  static unsigned long lastUsefulAt = 0;
  static unsigned long readyCountdownStarted = 0;
  static int lastCountdownShown = -1;
  static bool swipeArmed = false;

  static bool baselineReady = false;
  static bool capturing = false;

  // Command-request gate:
  // visitor must intentionally hold a steady hand near the center for 5 seconds.
  static unsigned long centerHoldStarted = 0;
  static unsigned long handMissingStarted = 0;
  static unsigned long centerLostStarted = 0;
  static uint8_t consecutiveInvalidAPDSReads = 0;
  static int previousCenterLR = 0;
  static int previousCenterUD = 0;
  static bool havePreviousCenterSample = false;

  static long baselineRatioSum = 0;
  static long baselineEnergySum = 0;
  static int baselineSamples = 0;
  static int baselineRatio = 0;
  static int baselineEnergy = 0;

  // Robust calibration samples.
  static const int CAL_SAMPLE_MAX = 64;
  static int calibrationRatios[CAL_SAMPLE_MAX];
  static int calibrationRatioCount = 0;
  static int idleMinRatio = 0;
  static int idleMaxRatio = 0;

  // Rolling pre-trigger history.
  static const int PREBUF_SIZE = 8;
  static int preBuf[PREBUF_SIZE];       // L/R ratio history
  static int preBufUD[PREBUF_SIZE];     // U/D ratio history
  static int preCount = 0;
  static int preWrite = 0;
  static int movementConfirmCount = 0;

  // Complete trajectory: pre-trigger + capture.
  static const int TRAJECTORY_MAX = 32;
  static int trajectory[TRAJECTORY_MAX];
  static int trajectoryUD[TRAJECTORY_MAX];
  static int trajectoryCount = 0;

  static int minExcursion = 0;
  static int maxExcursion = 0;
  static int peakAbsExcursion = 0;
  static int sampleCount = 0;

  const unsigned long POLL_MS = 35;
  const unsigned long VISITOR_MESSAGE_MS = 1800;
  const unsigned long INITIAL_CALIBRATION_MS = 2000;
  const unsigned long CAPTURE_MAX_MS = 560;
  const unsigned long QUIET_END_MS = 150;
  const unsigned long CONFIRMED_SHOW_MS = 3000;
  const unsigned long READY_COUNTDOWN_MS = 3000; // 3...2...1... GO!
  const unsigned long CENTER_HOLD_MS = 5000;      // intentional command request
  const unsigned long HAND_REMOVE_CONFIRM_MS = 650;
  const unsigned long CENTER_LOST_GRACE_MS = 900; // ignore brief FIFO/useful=0 dropouts

  // Center/steady gate. These do NOT change the proven swipe classifier.
  const int CENTER_LR_LIMIT = 30;
  const int CENTER_UD_LIMIT = 30;
  const int CENTER_STEADY_DELTA = 35;

  const int MIN_DATASET_ENERGY = 10;

  // Rolling READY detector. It looks for motion span, not exact threshold hits.
  const int PREBUF_MIN_SAMPLES = 5;
  const int MOVEMENT_SPAN_TRIGGER = 24;
  const int MOVEMENT_CONFIRM_SAMPLES = 3;
  const int MOVEMENT_RELEASE_SPAN = 14;

  // Final chronological decoder.
  const int MIN_TRAJECTORY_SAMPLES = 7;
  const int MIN_TRAJECTORY_RANGE = 18;
  const int MIN_TREND_DELTA = 8;

  // ==========================================================
  // RESULT SCREEN LOCK
  // All local/static gesture variables and timing constants are
  // declared above, so they are valid in this block.
  // ==========================================================
  if (gestureUIState == "LEFT_CONFIRMED" ||
      gestureUIState == "RIGHT_CONFIRMED" ||
      gestureUIState == "UP_CONFIRMED" ||
      gestureUIState == "DOWN_CONFIRMED" ||
      gestureUIState == "TRAM_ERROR_LEFT" ||
      gestureUIState == "TRAM_ERROR_RIGHT") {

    if (millis() - gestureUIStateTime < CONFIRMED_SHOW_MS) {
      return;
    }

    // Same visitor remains active. Require the hand to be removed
    // before another 5-second center hold can request a new command.
    gestureName = "READY";
    baselineReady = false;
    capturing = false;
    preCount = 0;
    preWrite = 0;
    movementConfirmCount = 0;
    trajectoryCount = 0;
    swipeArmed = false;
    centerHoldStarted = 0;
    handMissingStarted = 0;
    centerLostStarted = 0;
    havePreviousCenterSample = false;
    readyCountdownStarted = 0;
    lastCountdownShown = -1;

    setGestureUI("WAIT_HAND_REMOVE");

    Serial.println();
    Serial.println("[GESTURE] COMMAND COMPLETE");
    Serial.println("[GESTURE] REMOVE HAND BEFORE NEXT COMMAND");
    return;
  }

  // If a swipe was unclear, do not require another PIR event.
  // Give brief feedback, then prepare for another hand/gesture attempt.
  if (gestureUIState == "NOT_CLEAR") {
    if (millis() - gestureUIStateTime < 1200) return;

    gestureName = "READY";
    baselineReady = false;
    capturing = false;
    preCount = 0;
    preWrite = 0;
    movementConfirmCount = 0;
    trajectoryCount = 0;
    swipeArmed = false;
    centerHoldStarted = 0;
    handMissingStarted = 0;
    centerLostStarted = 0;
    havePreviousCenterSample = false;
    readyCountdownStarted = 0;
    lastCountdownShown = -1;

    setGestureUI("WAIT_HAND_REMOVE");

    Serial.println("[GESTURE] RETRY - REMOVE HAND, THEN HOLD CENTER AGAIN");
    return;
  }

  if (millis() - lastPoll < POLL_MS) return;
  lastPoll = millis();

  uint8_t gstatus = readAPDSRegister(0xAF);
  uint8_t fifoLevel = readAPDSRegister(0xAE);

  if (gstatus == 0xFF || fifoLevel == 0xFF) {
    consecutiveInvalidAPDSReads++;

    if (millis() - lastDebug >= 500) {
      lastDebug = millis();
      Serial.print("[APDS] INVALID GSTATUS/FIFO READ #");
      Serial.println(consecutiveInvalidAPDSReads);
    }

    // Recover automatically if the APDS I2C/register interface glitches
    // after a completed gesture/hand-removal cycle.
    if (consecutiveInvalidAPDSReads >= 3) {
      Serial.println("[APDS] Reinitializing gesture engine...");
      setupAPDSDirect();
      consecutiveInvalidAPDSReads = 0;
      lastPoll = millis();
    }
    return;
  }

  // A valid register read means the APDS bus is responsive again.
  consecutiveInvalidAPDSReads = 0;

  // ------------------------------------------------------------
  // ALWAYS DRAIN FIFO
  // ------------------------------------------------------------
  long sumU = 0, sumD = 0, sumL = 0, sumR = 0;
  int useful = 0;
  uint8_t remaining = fifoLevel;

  while (remaining > 0) {
    uint8_t chunk = remaining > 8 ? 8 : remaining;
    uint8_t data[32];

    if (!apdsReadFIFO(data, chunk)) {
      Serial.println("[APDS] FIFO READ FAILED");
      break;
    }

    for (int i = 0; i < chunk; i++) {
      int p = i * 4;
      int u = data[p + 0];
      int d = data[p + 1];
      int l = data[p + 2];
      int r = data[p + 3];
      int energy = u + d + l + r;

      if (energy >= MIN_DATASET_ENERGY && (l + r) > 0) {
        sumU += u;
        sumD += d;
        sumL += l;
        sumR += r;
        useful++;
      }
    }
    remaining -= chunk;
  }

  int avgU = 0, avgD = 0, avgL = 0, avgR = 0;
  int currentEnergy = 0;
  int currentRatio = 0;

  if (useful > 0) {
    avgU = sumU / useful;
    avgD = sumD / useful;
    avgL = sumL / useful;
    avgR = sumR / useful;
    currentEnergy = avgU + avgD + avgL + avgR;

    if ((avgL + avgR) > 0) {
      currentRatio = ((avgL - avgR) * 100) / (avgL + avgR);
    }
  }

  int currentUDRatio = 0;
  if ((avgU + avgD) > 0) {
    currentUDRatio = ((avgU - avgD) * 100) / (avgU + avgD);
  }

  if (millis() - lastDebug >= 450) {
    lastDebug = millis();
    Serial.print("[APDS] FIFO=");
    Serial.print(fifoLevel);
    Serial.print(" useful=");
    Serial.print(useful);
    Serial.print(" energy=");
    Serial.print(currentEnergy);
    Serial.print(" LR=");
    Serial.print(currentRatio);
    Serial.print(" UD=");
    Serial.println(currentUDRatio);
  }

  // ------------------------------------------------------------
  // VISITOR: stale FIFO is drained continuously above.
  // ------------------------------------------------------------
  if (gestureUIState == "VISITOR") {
    if (millis() - gestureUIStateTime >= VISITOR_MESSAGE_MS) {
      setGestureUI("CALIBRATE_IDLE");
      calibrationStarted = millis();

      baselineRatioSum = 0;
      baselineEnergySum = 0;
      baselineSamples = 0;
      calibrationRatioCount = 0;
      baselineReady = false;
      capturing = false;
      preCount = 0;
      preWrite = 0;
      trajectoryCount = 0;
      swipeArmed = false;
      readyCountdownStarted = 0;
      lastCountdownShown = -1;

      Serial.println();
      Serial.println("[APDS] STALE FIFO FLUSH COMPLETE");
      Serial.println("[CALIBRATION] KEEP HAND AWAY");
      Serial.println("[CALIBRATION] LEARNING ROBUST IDLE RANGE...");
    }
    return;
  }

  // ------------------------------------------------------------
  // AFTER A COMMAND: require real hand removal before re-arming.
  // ------------------------------------------------------------
  if (gestureUIState == "WAIT_HAND_REMOVE") {
    // FIX: Tolerate residual noise/crosstalk (energy < 30) or force reset if stuck > 3.5s
    bool handGone = (useful <= 0 || currentEnergy < 30);
    bool timeoutForce = (millis() - gestureUIStateTime >= 3500);

    if (handGone || timeoutForce) {
      if (handMissingStarted == 0) handMissingStarted = millis();

      if (millis() - handMissingStarted >= HAND_REMOVE_CONFIRM_MS || timeoutForce) {
        handMissingStarted = 0;
        centerHoldStarted = 0;
        centerLostStarted = 0;
        havePreviousCenterSample = false;

        // A completed swipe can leave this clone's gesture FIFO/register
        // state stale. Re-arm the APDS once after the hand is truly removed.
        Serial.println("[APDS] Re-arming gesture sensor for next command...");
        setupAPDSDirect();
        consecutiveInvalidAPDSReads = 0;
        lastPoll = millis();

        setGestureUI("CALIBRATE_IDLE");
        calibrationStarted = millis();
        baselineRatioSum = 0;
        baselineEnergySum = 0;
        baselineSamples = 0;
        calibrationRatioCount = 0;
        baselineReady = false;
        capturing = false;
        preCount = 0;
        preWrite = 0;
        trajectoryCount = 0;
        swipeArmed = false;
        readyCountdownStarted = 0;
        lastCountdownShown = -1;

        Serial.println("[GESTURE] HAND REMOVED - PREPARING NEXT COMMAND");
        Serial.println("[CALIBRATION] KEEP HAND AWAY");
        Serial.println("[CALIBRATION] LEARNING ROBUST IDLE RANGE...");
      }
    } else {
      handMissingStarted = 0; // Reset timer only if hand is still clearly present (high energy)
    }
    return;
  }

  // ------------------------------------------------------------
  // ROBUST CALIBRATION
  // ------------------------------------------------------------
  if (gestureUIState == "CALIBRATE_IDLE") {

    if (useful > 0) {
      baselineRatioSum += currentRatio;
      baselineEnergySum += currentEnergy;
      baselineSamples++;

      if (calibrationRatioCount < CAL_SAMPLE_MAX) {
        calibrationRatios[calibrationRatioCount++] = currentRatio;
      }
    }

    if (millis() - calibrationStarted < INITIAL_CALIBRATION_MS) return;

    if (baselineSamples > 0) {
      baselineRatio = baselineRatioSum / baselineSamples;
      baselineEnergy = baselineEnergySum / baselineSamples;
    } else {
      baselineRatio = 0;
      baselineEnergy = 0;
    }

    if (calibrationRatioCount >= 5) {
      for (int i = 1; i < calibrationRatioCount; i++) {
        int key = calibrationRatios[i];
        int j = i - 1;
        while (j >= 0 && calibrationRatios[j] > key) {
          calibrationRatios[j + 1] = calibrationRatios[j];
          j--;
        }
        calibrationRatios[j + 1] = key;
      }

      int lowIndex = calibrationRatioCount / 10;
      int highIndex = calibrationRatioCount - 1 - (calibrationRatioCount / 10);
      idleMinRatio = calibrationRatios[lowIndex];
      idleMaxRatio = calibrationRatios[highIndex];
      baselineRatio = calibrationRatios[calibrationRatioCount / 2];
    } else {
      idleMinRatio = baselineRatio - 10;
      idleMaxRatio = baselineRatio + 10;
    }

    baselineReady = true;
    preCount = 0;
    preWrite = 0;
    swipeArmed = false;
    readyCountdownStarted = millis();
    lastCountdownShown = -1;
    setGestureUI("SWIPE_READY");

    Serial.println("[CALIBRATION] COMPLETE");
    Serial.print("[CALIBRATION] Median baseline = ");
    Serial.println(baselineRatio);
    Serial.print("[CALIBRATION] Robust idle minimum (P10) = ");
    Serial.println(idleMinRatio);
    Serial.print("[CALIBRATION] Robust idle maximum (P90) = ");
    Serial.println(idleMaxRatio);
    Serial.print("[CALIBRATION] Idle noise span = ");
    Serial.println(idleMaxRatio - idleMinRatio);
    Serial.println();
    Serial.println("============================");
    Serial.println("       GET READY...");
    Serial.println("============================");
    return;
  }

  // ------------------------------------------------------------
  // READY: rolling pre-buffer movement detector.
  // ------------------------------------------------------------
  if (gestureUIState == "SWIPE_READY" && !capturing) {

    if (!baselineReady) return;

    // Run the TFT animation once. Gesture recognition stays OFF for
    // the complete READY -> 3 -> 2 -> 1 -> GO animation.
    if (!swipeArmed) {
      Serial.println();
      Serial.println("============================");
      Serial.println("       READY ANIMATION");
      Serial.println("============================");

      animateSwipeCountdown();

      // Discard anything that happened while the animation was playing.
      // The first rolling sample after this point is a fresh swipe sample.
      while (readAPDSRegister(0xAE) > 0 &&
             readAPDSRegister(0xAE) != 0xFF) {
        uint8_t flushLevel = readAPDSRegister(0xAE);
        uint8_t flushChunk = flushLevel > 8 ? 8 : flushLevel;
        uint8_t flushData[32];
        if (!apdsReadFIFO(flushData, flushChunk)) break;
      }

      preCount = 0;
      preWrite = 0;
      trajectoryCount = 0;
      swipeArmed = true;
      gestureName = "GO!";

      Serial.println();
      Serial.println("============================");
      Serial.println("            GO!");
      Serial.println("       SWIPE NOW");
      Serial.println("============================");
      return;
    }

    if (useful <= 0) return;

    preBuf[preWrite] = currentRatio;
    preBufUD[preWrite] = currentUDRatio;
    preWrite = (preWrite + 1) % PREBUF_SIZE;
    if (preCount < PREBUF_SIZE) preCount++;

    int rollingMin = currentRatio;
    int rollingMax = currentRatio;
    int rollingUDMin = currentUDRatio;
    int rollingUDMax = currentUDRatio;

    for (int i = 0; i < preCount; i++) {
      if (preBuf[i] < rollingMin) rollingMin = preBuf[i];
      if (preBuf[i] > rollingMax) rollingMax = preBuf[i];

      if (preBufUD[i] < rollingUDMin) rollingUDMin = preBufUD[i];
      if (preBufUD[i] > rollingUDMax) rollingUDMax = preBufUD[i];
    }

    int rollingSpan = rollingMax - rollingMin;
    int rollingUDSpan = rollingUDMax - rollingUDMin;
    int strongestRollingSpan = max(rollingSpan, rollingUDSpan);

    if (millis() - lastDebug >= 250) {
      lastDebug = millis();
      Serial.print("[READY] ratio=");
      Serial.print(currentRatio);
      Serial.print(" idle=");
      Serial.print(idleMinRatio);
      Serial.print("..");
      Serial.print(idleMaxRatio);
      Serial.print(" LRspan=");
      Serial.print(rollingSpan);
      Serial.print(" UDspan=");
      Serial.print(rollingUDSpan);
      Serial.print(" trigger=");
      Serial.print(MOVEMENT_SPAN_TRIGGER);
      Serial.print(" movement=");
      Serial.println((preCount >= PREBUF_MIN_SAMPLES &&
                      strongestRollingSpan >= MOVEMENT_SPAN_TRIGGER) ? "CANDIDATE" : "NO");
    }

    if (preCount < PREBUF_MIN_SAMPLES) {
      movementConfirmCount = 0;
      return;
    }

    // Do not arm from one noisy rolling-span spike.
    // Require the movement condition on 3 consecutive useful FIFO updates.
    if (strongestRollingSpan >= MOVEMENT_SPAN_TRIGGER) {
      if (movementConfirmCount < MOVEMENT_CONFIRM_SAMPLES) {
        movementConfirmCount++;
      }

      Serial.print("[ARM] sustained movement ");
      Serial.print(movementConfirmCount);
      Serial.print("/");
      Serial.print(MOVEMENT_CONFIRM_SAMPLES);
      Serial.print(" LRspan=");
      Serial.print(rollingSpan);
      Serial.print(" UDspan=");
      Serial.println(rollingUDSpan);
    } else {
      // Reset once the signal settles. Between release and trigger,
      // do not accumulate evidence.
      if (strongestRollingSpan <= MOVEMENT_RELEASE_SPAN) {
        movementConfirmCount = 0;
      }
      return;
    }

    if (movementConfirmCount < MOVEMENT_CONFIRM_SAMPLES) {
      return;
    }

    movementConfirmCount = 0;

    // Copy ring buffer into trajectory in chronological order.
    trajectoryCount = 0;
    int oldest = (preCount == PREBUF_SIZE) ? preWrite : 0;

    for (int i = 0; i < preCount && trajectoryCount < TRAJECTORY_MAX; i++) {
      int idx = (oldest + i) % PREBUF_SIZE;
      trajectory[trajectoryCount] = preBuf[idx];
      trajectoryUD[trajectoryCount] = preBufUD[idx];
      trajectoryCount++;
    }

    capturing = true;
    captureStarted = millis();
    lastUsefulAt = millis();

    minExcursion = rollingMin - baselineRatio;
    maxExcursion = rollingMax - baselineRatio;
    peakAbsExcursion = max(abs(minExcursion), abs(maxExcursion));
    sampleCount = trajectoryCount;

    setGestureUI("READING");

    Serial.println();
    Serial.println("[SWIPE] ROLLING MOVEMENT DETECTED");
    Serial.print("[SWIPE] LR pre-trigger span: ");
    Serial.println(rollingSpan);
    Serial.print("[SWIPE] UD pre-trigger span: ");
    Serial.println(rollingUDSpan);
    Serial.print("[SWIPE] Trigger axis: ");
    Serial.println(rollingUDSpan > rollingSpan ? "UP/DOWN" : "LEFT/RIGHT");
    Serial.print("[SWIPE] Pre-trigger samples: ");
    Serial.println(trajectoryCount);
    Serial.println("[SWIPE] CAPTURING COMPLETE TRAJECTORY...");
    return;
  }

  // ------------------------------------------------------------
  // CAPTURE REST OF TRAJECTORY
  // ------------------------------------------------------------
  if (gestureUIState == "READING" && capturing) {

    if (useful > 0) {
      lastUsefulAt = millis();

      if (trajectoryCount < TRAJECTORY_MAX) {
        trajectory[trajectoryCount] = currentRatio;
        trajectoryUD[trajectoryCount] = currentUDRatio;
        trajectoryCount++;
      }

      int excursion = currentRatio - baselineRatio;
      if (excursion < minExcursion) minExcursion = excursion;
      if (excursion > maxExcursion) maxExcursion = excursion;
      if (abs(excursion) > peakAbsExcursion) peakAbsExcursion = abs(excursion);
      sampleCount++;

      Serial.print("[TRACE] LR=");
      Serial.print(currentRatio);
      Serial.print(" UD=");
      Serial.print(currentUDRatio);
      Serial.print(" excursion=");
      Serial.print(excursion);
      Serial.print(" min=");
      Serial.print(minExcursion);
      Serial.print(" max=");
      Serial.println(maxExcursion);
    }

    bool timedOut = millis() - captureStarted >= CAPTURE_MAX_MS;
    bool quietEnded = millis() - lastUsefulAt >= QUIET_END_MS;

    if (!timedOut && !quietEnded) return;

    capturing = false;

    // Chronological direction: compare early and late portions.
    int edgeN = trajectoryCount / 4;
    if (edgeN < 2) edgeN = 2;
    if (edgeN > 4) edgeN = 4;
    if (edgeN * 2 > trajectoryCount) edgeN = trajectoryCount / 2;

    long earlySum = 0;
    long lateSum = 0;
    long earlyUDSum = 0;
    long lateUDSum = 0;

    for (int i = 0; i < edgeN; i++) {
      earlySum += trajectory[i];
      lateSum += trajectory[trajectoryCount - edgeN + i];

      earlyUDSum += trajectoryUD[i];
      lateUDSum += trajectoryUD[trajectoryCount - edgeN + i];
    }

    int earlyAvg = edgeN > 0 ? earlySum / edgeN : 0;
    int lateAvg = edgeN > 0 ? lateSum / edgeN : 0;
    int trendDelta = lateAvg - earlyAvg;

    int earlyUDAvg = edgeN > 0 ? earlyUDSum / edgeN : 0;
    int lateUDAvg = edgeN > 0 ? lateUDSum / edgeN : 0;
    int udTrendDelta = lateUDAvg - earlyUDAvg;

    int trajectoryMin = 1000;
    int trajectoryMax = -1000;
    for (int i = 0; i < trajectoryCount; i++) {
      if (trajectory[i] < trajectoryMin) trajectoryMin = trajectory[i];
      if (trajectory[i] > trajectoryMax) trajectoryMax = trajectory[i];
    }
    int trajectoryRange = trajectoryMax - trajectoryMin;

    int udMin = 1000;
    int udMax = -1000;
    int udMinIndex = 0;
    int udMaxIndex = 0;
    for (int i = 0; i < trajectoryCount; i++) {
      if (trajectoryUD[i] < udMin) {
        udMin = trajectoryUD[i];
        udMinIndex = i;
      }
      if (trajectoryUD[i] > udMax) {
        udMax = trajectoryUD[i];
        udMaxIndex = i;
      }
    }
    int udRange = udMax - udMin;

    Serial.println();
    Serial.println("===== CHRONOLOGICAL SWIPE =====");
    Serial.print("Samples: ");
    Serial.println(trajectoryCount);
    Serial.print("Baseline ratio: ");
    Serial.println(baselineRatio);
    Serial.print("Idle range: ");
    Serial.print(idleMinRatio);
    Serial.print(" .. ");
    Serial.println(idleMaxRatio);
    Serial.print("Trajectory min: ");
    Serial.println(trajectoryMin);
    Serial.print("Trajectory max: ");
    Serial.println(trajectoryMax);
    Serial.print("Trajectory range: ");
    Serial.println(trajectoryRange);
    Serial.print("UD min: ");
    Serial.println(udMin);
    Serial.print("UD max: ");
    Serial.println(udMax);
    Serial.print("UD range: ");
    Serial.println(udRange);
    Serial.print("UD min index: ");
    Serial.println(udMinIndex);
    Serial.print("UD max index: ");
    Serial.println(udMaxIndex);
    Serial.print("Early average: ");
    Serial.println(earlyAvg);
    Serial.print("Late average: ");
    Serial.println(lateAvg);
    Serial.print("Trend delta: ");
    Serial.println(trendDelta);
    Serial.print("UD early average: ");
    Serial.println(earlyUDAvg);
    Serial.print("UD late average: ");
    Serial.println(lateUDAvg);
    Serial.print("UD trend delta: ");
    Serial.println(udTrendDelta);

    // --------------------------------------------------------
    // FOUR-WAY DIRECTION DECODER
    // Compare L/R and U/D movement ranges. Whichever axis has
    // the stronger trajectory is used for the final direction.
    // --------------------------------------------------------
    int minIndex = 0;
    int maxIndex = 0;
    for (int i = 1; i < trajectoryCount; i++) {
      if (trajectory[i] < trajectory[minIndex]) minIndex = i;
      if (trajectory[i] > trajectory[maxIndex]) maxIndex = i;
    }

    int lrSeparation = abs(maxIndex - minIndex);
    int udSeparation = abs(udMaxIndex - udMinIndex);

    Serial.print("LR min index: ");
    Serial.println(minIndex);
    Serial.print("LR max index: ");
    Serial.println(maxIndex);
    Serial.print("LR extrema separation: ");
    Serial.println(lrSeparation);
    Serial.print("UD extrema separation: ");
    Serial.println(udSeparation);

    bool enoughSamples = trajectoryCount >= MIN_TRAJECTORY_SAMPLES;

    // ========================================================
    // ACCURACY-TUNED FOUR-WAY CLASSIFIER
    //
    // Main decision = average direction from the beginning of
    // the swipe to the end of the swipe. This is much less
    // sensitive to a single noisy min/max sample.
    //
    // Extrema order is retained only as supporting evidence.
    // If the evidence disagrees or is weak, return NOT CLEAR
    // instead of guessing the wrong direction.
    // ========================================================
    const int TREND_MIN = 8;
    const int TREND_STRONG = 12;
    const int TREND_AXIS_MARGIN = 3;
    const int AXIS_MIN_RANGE = 18;

    // Rescue path for real swipes whose beginning/end averages cancel out.
    // This does NOT weaken the swipe-start filter above.
    const int EXTREMA_MIN_SEPARATION = 7;
    const int EXTREMA_AXIS_MARGIN = 2;

    int lrTrendStrength = abs(trendDelta);
    int udTrendStrength = abs(udTrendDelta);

    bool lrTrendValid =
      trajectoryRange >= AXIS_MIN_RANGE &&
      lrTrendStrength >= TREND_MIN;

    bool udTrendValid =
      udRange >= AXIS_MIN_RANGE &&
      udTrendStrength >= TREND_MIN;

    // Extrema rescue evidence: useful when a real swipe has a curved/noisy
    // trajectory and the early/late averages nearly cancel.
    bool lrExtremaValid =
      trajectoryRange >= AXIS_MIN_RANGE &&
      lrSeparation >= EXTREMA_MIN_SEPARATION;

    bool udExtremaValid =
      udRange >= AXIS_MIN_RANGE &&
      udSeparation >= EXTREMA_MIN_SEPARATION;

    int lrEvidence = max(lrTrendStrength, lrSeparation);
    int udEvidence = max(udTrendStrength, udSeparation);

    // Define which modes are currently active
    bool tramActive = (uiScreen == UI_INDIVIDUAL && individualRunning && individualIndex == 0) || 
                      (uiScreen == UI_SIMULATION && simPhase == 0);
                      
    bool rgbActive  = (uiScreen == UI_INDIVIDUAL && individualRunning && individualIndex == 2) ||
                      (uiScreen == UI_SIMULATION && simPhase == 1);

    // Only allow Left/Right if Tram is active
    bool lrDominant =
      tramActive && 
      (lrTrendValid || lrExtremaValid) &&
      (!rgbActive || !(udTrendValid || udExtremaValid) ||
       lrEvidence >= udEvidence + EXTREMA_AXIS_MARGIN);

    // Only allow Up/Down if RGB Color Scan is active
    bool udDominant =
      rgbActive && 
      (udTrendValid || udExtremaValid) &&
      (!tramActive || !(lrTrendValid || lrExtremaValid) ||
       udEvidence >= lrEvidence + EXTREMA_AXIS_MARGIN);

    Serial.print("LR trend strength: ");
    Serial.println(lrTrendStrength);
    Serial.print("UD trend strength: ");
    Serial.println(udTrendStrength);

    if (!enoughSamples || (!lrDominant && !udDominant)) {
      Serial.println("RESULT: NOT CLEAR");
      Serial.println("===============================");
      Serial.println("[SWIPE] Direction evidence was weak/ambiguous.");
      Serial.println("[PIR] Waiting for PIR LOW before another session.");

      gestureName = "NOT CLEAR";
      // Keep the same visitor session active; reset only this gesture attempt.
      baselineReady = false;
      capturing = false;
      preCount = 0;
      preWrite = 0;
      trajectoryCount = 0;
      swipeArmed = false;
      setGestureUI("NOT_CLEAR");
      return;
    }

    if (lrDominant) {
      // Verified physical mapping:
      // LR decreases across the swipe = LEFT
      // LR increases across the swipe = RIGHT
      bool trendSaysLeft = trendDelta < 0;
      bool extremaSaysLeft = maxIndex < minIndex;

      Serial.print("LR trend vote: ");
      Serial.println(trendSaysLeft ? "LEFT" : "RIGHT");
      Serial.print("LR extrema vote: ");
      Serial.println(extremaSaysLeft ? "LEFT" : "RIGHT");

      // If averaged trend is strong enough, use it.
      // If it is weak, use chronological extrema order when separation is strong.
      bool finalSaysLeft;

      if (lrTrendStrength >= TREND_MIN) {
        finalSaysLeft = trendSaysLeft;

        // Moderate trend + strong contradictory extrema = reject instead of guess.
        if (lrTrendStrength < TREND_STRONG &&
            lrExtremaValid &&
            trendSaysLeft != extremaSaysLeft) {
          Serial.println("RESULT: NOT CLEAR (LR evidence disagrees)");
          gestureName = "NOT CLEAR";
          // Keep the same visitor session active; reset only this gesture attempt.
          baselineReady = false;
          capturing = false;
          preCount = 0;
          preWrite = 0;
          movementConfirmCount = 0;
          trajectoryCount = 0;
          swipeArmed = false;
          setGestureUI("NOT_CLEAR");
          return;
        }
      } else {
        finalSaysLeft = extremaSaysLeft;
        Serial.println("[CLASSIFIER] Weak LR average; using strong extrema order.");
      }

      if (finalSaysLeft) {
        gestureName = "LEFT";
        setGestureUI("LEFT_CONFIRMED");
        gestureUIStateTime = millis();
        Serial.println("RESULT: LEFT");
        Serial.println("===============================");
        handleGestureDirection("LEFT");
      } else {
        gestureName = "RIGHT";
        setGestureUI("RIGHT_CONFIRMED");
        gestureUIStateTime = millis();
        Serial.println("RESULT: RIGHT");
        Serial.println("===============================");
        handleGestureDirection("RIGHT");
      }

    } else {
      // Verified physical mapping for this sensor orientation:
      // UD decreases across the swipe = UP
      // UD increases across the swipe = DOWN
      bool trendSaysUp = udTrendDelta < 0;
      bool extremaSaysUp = udMinIndex > udMaxIndex;

      Serial.print("UD trend vote: ");
      Serial.println(trendSaysUp ? "UP" : "DOWN");
      Serial.print("UD extrema vote: ");
      Serial.println(extremaSaysUp ? "UP" : "DOWN");

      bool finalSaysUp;

      if (udTrendStrength >= TREND_MIN) {
        finalSaysUp = trendSaysUp;

        if (udTrendStrength < TREND_STRONG &&
            udExtremaValid &&
            trendSaysUp != extremaSaysUp) {
          Serial.println("RESULT: NOT CLEAR (UD evidence disagrees)");
          gestureName = "NOT CLEAR";
          // Keep the same visitor session active; reset only this gesture attempt.
          baselineReady = false;
          capturing = false;
          preCount = 0;
          preWrite = 0;
          movementConfirmCount = 0;
          trajectoryCount = 0;
          swipeArmed = false;
          setGestureUI("NOT_CLEAR");
          return;
        }
      } else {
        finalSaysUp = extremaSaysUp;
        Serial.println("[CLASSIFIER] Weak UD average; using strong extrema order.");
      }

      if (finalSaysUp) {
        gestureName = "UP";
        setGestureUI("UP_CONFIRMED");
        gestureUIStateTime = millis();
        Serial.println("RESULT: UP");
        Serial.println("===============================");
        handleGestureDirection("UP");
      } else {
        gestureName = "DOWN";
        setGestureUI("DOWN_CONFIRMED");
        gestureUIStateTime = millis();
        Serial.println("RESULT: DOWN");
        Serial.println("===============================");
        handleGestureDirection("DOWN");
      }
    }
    return;
  }
}
// ============================================================
// WEB PAGE
// ============================================================

String buildPage() {

  String html;

  html.reserve(6000);

  html += R"rawliteral(

<!DOCTYPE html>

<html>

<head>

<meta name="viewport"
content="width=device-width, initial-scale=1">

<title>RJ45 Diorama</title>

<style>

body {
  font-family: Arial, sans-serif;
  background: #f4f6f8;
  margin: 0;
  padding: 20px;
  text-align: center;
}

.container {
  max-width: 700px;
  margin: auto;
}

.card {
  background: white;
  padding: 18px;
  margin: 12px 0;
  border-radius: 12px;
}

button {
  padding: 14px 18px;
  margin: 5px;
  border: none;
  border-radius: 8px;
  font-size: 16px;
}

.value {
  font-size: 24px;
  font-weight: bold;
}

</style>

</head>

<body>

<div class="container">

<h1>RJ45 Diorama</h1>

<p>
San Lorenzo Ruiz Tramline
&
Colgante River
</p>

<div class="card">

<h2>Live Sensors</h2>

<p>Water Level</p>
<div class="value" id="water">--</div>

<p>Flex</p>
<div id="flex">--</div>

<p>Temperature</p>
<div id="temp">--</div>

<p>Humidity</p>
<div id="humidity">--</div>

<p>PIR</p>
<div id="pir">--</div>

<p>APDS9960</p>
<div id="apds">--</div>

<p>Visitor</p>
<div id="visitor">--</div>

<p>Gesture</p>
<div id="gesture">--</div>

<p>Clock</p>
<div id="clock">--</div>

</div>


<div class="card">

<h2>Tram Control</h2>

<button onclick="cmd('/tram/forward')">
Forward
</button>

<button onclick="cmd('/tram/stop')">
STOP
</button>

<button onclick="cmd('/tram/reverse')">
Reverse
</button>

</div>


<div class="card">

<h2>Environment</h2>

<button onclick="cmd('/fans/on')">
Fans ON
</button>

<button onclick="cmd('/fans/off')">
Fans OFF
</button>

<br>

<button onclick="cmd('/pump/on')">
Pump ON
</button>

<button onclick="cmd('/pump/off')">
Pump OFF
</button>

</div>

</div>


<script>

function cmd(url) {
  fetch(url);
}


function updateSensors() {

  fetch('/api/status')

  .then(response => response.json())

  .then(data => {

    document.getElementById('water').innerHTML =
      data.water + '%';

    document.getElementById('flex').innerHTML =
      data.flex;

    document.getElementById('temp').innerHTML =
      data.temperature + ' C';

    document.getElementById('humidity').innerHTML =
      data.humidity + '%';

    document.getElementById('pir').innerHTML =
      data.pir ? 'MOTION' : 'CLEAR';

    document.getElementById('apds').innerHTML =
      data.apds ? 'OK' : 'MISSING';

    document.getElementById('visitor').innerHTML =
      data.visitor ? 'ACTIVE' : 'WAITING';

    document.getElementById('gesture').innerHTML =
      data.gesture;

    document.getElementById('clock').innerHTML =
      data.clock;

  });
}


setInterval(updateSensors, 500);

updateSensors();

</script>

</body>

</html>

)rawliteral";

  return html;
}


// ============================================================
// WEB SERVER
// ============================================================

void setupWebServer() {

  server.on(
    "/",
    []() {

      server.send(
        200,
        "text/html",
        buildPage()
      );
    }
  );


  server.on(
    "/api/status",
    []() {

      String json = "{";

      json += "\"water\":";
      json += waterPercent;

      json += ",\"waterRaw\":";
      json += waterRaw;

      json += ",\"flex\":";
      json += flexRaw;

      json += ",\"temperature\":";
      json += String(temperature, 1);

      json += ",\"humidity\":";
      json += String(humidity, 1);

      json += ",\"pir\":";
      json += pirDetected ? "true" : "false";

      json += ",\"apds\":";
      json += gestureFound ? "true" : "false";

      json += ",\"apdsAddress\":";
      json += apdsAddressFound ? "true" : "false";

      json += ",\"visitor\":";
      json += visitorActive ? "true" : "false";

      json += ",\"gesture\":\"";
      json += gestureName;
      json += "\"";

      json += ",\"clock\":\"";
      json += clockText;
      json += "\"";

      json += "}";

      server.send(
        200,
        "application/json",
        json
      );
    }
  );


  server.on(
    "/tram/forward",
    []() {

      sendCommand(
        slave1MAC,
        CMD_TRAM_FORWARD
      );

      server.send(
        200,
        "text/plain",
        "FORWARD"
      );
    }
  );


  server.on(
    "/tram/reverse",
    []() {

      sendCommand(
        slave1MAC,
        CMD_TRAM_REVERSE
      );

      server.send(
        200,
        "text/plain",
        "REVERSE"
      );
    }
  );


  server.on(
    "/tram/stop",
    []() {

      sendCommand(
        slave1MAC,
        CMD_TRAM_STOP
      );

      server.send(
        200,
        "text/plain",
        "STOP"
      );
    }
  );


  server.on(
    "/fans/on",
    []() {

      sendCommand(
        slave2MAC,
        CMD_FANS_ON
      );

      server.send(
        200,
        "text/plain",
        "FANS ON"
      );
    }
  );


  server.on(
    "/fans/off",
    []() {

      sendCommand(
        slave2MAC,
        CMD_FANS_OFF
      );

      server.send(
        200,
        "text/plain",
        "FANS OFF"
      );
    }
  );


  server.on(
    "/pump/on",
    []() {

      sendCommand(
        slave2MAC,
        CMD_PUMP_ON
      );

      server.send(
        200,
        "text/plain",
        "PUMP ON"
      );
    }
  );


  server.on(
    "/pump/off",
    []() {

      sendCommand(
        slave2MAC,
        CMD_PUMP_OFF
      );

      server.send(
        200,
        "text/plain",
        "PUMP OFF"
      );
    }
  );


  server.begin();
}


// ============================================================
// MASTER TFT MENU + BUTTONS
// NEXT = GPIO25, SELECT = GPIO26, BACK = GPIO13
// ============================================================
const char* mainMenuItems[] = {"SIMULATION", "DASHBOARD", "RECORDING", "WI-FI SETUP"};
const char* dashboardItems[] = {"TRAM", "RGB / COLOR", "RIVER / WATER", "WIND / FAN", "ENVIRONMENT", "VISITORS", "AUDIO"};

void drawMainMenu() {
  tft.fillScreen(ST77XX_BLACK);
  lcdHeader("RJ45 - MAIN MENU");
  for (int i = 0; i < 4; i++) {
    int y = 25 + i * 22;
    tft.setTextSize(1);
    tft.setCursor(10, y);
    tft.setTextColor(i == mainMenuIndex ? ST77XX_YELLOW : ST77XX_WHITE);
    tft.print(i == mainMenuIndex ? "> " : "  ");
    tft.print(mainMenuItems[i]);
  }
  tft.setTextColor(ST77XX_CYAN);
  tft.setCursor(9, 118);
  tft.print("NEXT     OK     BACK");
}

void drawWaitingScreen() {
  tft.fillScreen(ST77XX_BLACK);
  drawCenteredText("RJ45 DIORAMA", 28, 2, ST77XX_CYAN);
  drawCenteredText("WAITING FOR", 68, 1, ST77XX_WHITE);
  drawCenteredText("VISITOR...", 85, 2, ST77XX_YELLOW);
}

void drawWelcomeScreen() {
  tft.fillScreen(ST77XX_BLACK);
  drawCenteredText("WELCOME!", 28, 3, ST77XX_CYAN);
  drawCenteredText("VISITOR DETECTED", 75, 1, ST77XX_WHITE);
  drawCenteredText("LOADING MENU...", 96, 1, ST77XX_YELLOW);
}

void drawSimulationModeMenu() {
  tft.fillScreen(ST77XX_BLACK);
  lcdHeader("SIMULATION MODE");
  const char* modes[] = {"INDIVIDUAL", "FULL SYSTEM"};
  for (int i = 0; i < 2; i++) {
    tft.setCursor(10, 40 + i * 30);
    tft.setTextColor(i == simulationModeIndex ? ST77XX_YELLOW : ST77XX_WHITE);
    tft.print(i == simulationModeIndex ? "> " : "  ");
    tft.print(modes[i]);
  }
  tft.setTextColor(ST77XX_CYAN);
  tft.setCursor(5, 118); tft.print("NEXT  OK  BACK");
}

const char* individualLabels[] = {"TRAM", "STREET LIGHTS", "RGB LIGHTING", "WIND / FAN", "RIVER"};

void drawIndividualScreen() {
  tft.fillScreen(ST77XX_BLACK);
  lcdHeader("INDIVIDUAL MODE");
  for (int i = 0; i < 5; i++) {
    int y = 23 + i * 18;
    tft.setCursor(4, y);
    tft.setTextColor(i == individualIndex ? ST77XX_YELLOW : ST77XX_WHITE);
    tft.print(i == individualIndex ? ">" : " ");
    tft.print(individualLabels[i]);
    if (individualRunning && i == individualIndex) {
      tft.setTextColor(ST77XX_GREEN);
      tft.print(" *");
    }
  }
  tft.setTextColor(ST77XX_CYAN);
  tft.setCursor(3, 117); tft.print("NEXT   OK   BACK");
}

void drawIndividualActive() {
  tft.fillScreen(ST77XX_BLACK);
  lcdHeader(individualLabels[individualIndex]);
  tft.setTextColor(ST77XX_GREEN);
  tft.setCursor(5, 23); tft.print("AUTO OPERATION ACTIVE");
  tft.setTextColor(ST77XX_WHITE);
  
  if (individualIndex == 0) {
    tft.setCursor(5, 45); tft.print("APDS gesture -> tram");
    tft.setCursor(5, 62); tft.print("Hall + servo automatic");
    tft.setCursor(5, 80); tft.print("Tram: "); tft.print(tramStatus);
  } else if (individualIndex == 1) {
    tft.setCursor(5, 45); tft.print("RTC: "); tft.print(clockText);
    tft.setCursor(5, 65); tft.print("Lights: ");
    tft.print(!rtcFound ? "NO RTC" : streetLightsOn ? "ON" : "OFF");
  } else if (individualIndex == 2) {
    tft.setCursor(5, 45); tft.print("TCS color -> RGB strip");
    tft.setCursor(5, 65); tft.print("R:"); tft.print(colorR);
    tft.print(" G:"); tft.print(colorG);
  } else if (individualIndex == 3) {
    tft.setCursor(5, 45); tft.print("Flex: "); tft.print(flexRaw);
    tft.setCursor(5, 65); tft.print("Fan: "); tft.print(fanStatus ? "ON" : "OFF");
  } else {
    tft.setCursor(5, 45); tft.print("Water: "); tft.print(waterPercent); tft.print("%");
    
    // Show RED DANGER text if water is 50% or higher
    if (waterPercent >= 50 && individualRunning) {
      tft.setTextColor(ST77XX_RED);
      tft.setCursor(5, 65); tft.print("DANGER! RIVER WATER HIGH");
      tft.setTextColor(ST77XX_WHITE);
    } else {
      tft.setCursor(5, 65); tft.print("Pump: "); tft.print(pumpStatus ? "ON" : "OFF");
    }
  }
  
  tft.setTextColor(ST77XX_YELLOW);
  tft.setCursor(5, 117); tft.print("BACK = STOP TEST");
}

void stopIndividualOperation() {
  if (!individualRunning) return;
  uint8_t old = individualIndex;
  individualRunning = false;
  if (old == 0) {
    sendCommand(slave1MAC, CMD_TRAM_STOP);
    tramStatus = "STOPPED";
    gestureUIState = "IDLE";
  } else if (old == 1) {
    sendCommand(slave2MAC, CMD_LIGHT_LEVEL, 0);
    streetLightsKnown = false;
  } else if (old == 2) {
    sendCommand(slave2MAC, CMD_STRIP_OFF);
    colorScanMode = false;
  } else if (old == 3) {
    stopWindSession();
  } else if (old == 4) {
    sendCommand(slave2MAC, CMD_PUMP_OFF);
    pumpStatus = false;
  }
  Serial.println("[INDIVIDUAL] TEST STOPPED");
}

void startIndividualOperation() {
  individualRunning = true;
  if (individualIndex == 0) {
    gestureName = gestureFound ? "READY" : "NO APDS";
    gestureUIState = "IDLE";
    sendCommand(slave1MAC, CMD_TRAM_FORWARD);
    tramStatus = "FORWARD";
  } else if (individualIndex == 1) {
    streetLightsKnown = false;
    streetLightsOn = true;
    sendCommand(slave2MAC, CMD_LIGHT_LEVEL, 255);
  } else if (individualIndex == 2) {
    lastColorScanSend = 0;
    sendCommand(slave2MAC, CMD_STRIP_ON);
  } else if (individualIndex == 3) {
    // Wind/Fan selected
    startWindSession();
  } else if (individualIndex == 4) {
    // Operation 5: Flood Warning & Drainage
    pumpStatus = true;
    sendCommand(slave2MAC, CMD_PUMP_ON);
  }
  Serial.print("[INDIVIDUAL] START: ");
  Serial.println(individualLabels[individualIndex]);
}


void drawSimulationScreen() {
  tft.fillScreen(ST77XX_BLACK);
  lcdHeader("SIMULATION - ACTIVE");
  tft.setTextColor(ST77XX_GREEN); tft.setCursor(5, 26); tft.print("Automatic sensors ACTIVE");
  tft.setTextColor(ST77XX_WHITE); tft.setCursor(5, 43); tft.print("Gesture: "); tft.setTextColor(ST77XX_MAGENTA); tft.print(gestureName);
  tft.setTextColor(ST77XX_WHITE); tft.setCursor(5, 58); tft.print("Water: "); tft.setTextColor(ST77XX_CYAN); tft.print(waterPercent); tft.print("%");
  tft.setTextColor(ST77XX_WHITE); tft.setCursor(5, 73); tft.print("Pump: "); tft.print(pumpStatus ? "ON" : "OFF");
  tft.setCursor(5, 88); tft.print("Fan:  "); tft.print(fanStatus ? "ON" : "OFF");
  tft.setCursor(5, 103); tft.print("Tram: "); tft.print(tramStatus);
  tft.setTextColor(ST77XX_YELLOW); tft.setCursor(5, 119); tft.print("BACK = Main Menu");
}

void drawDashboardMenu() {
  tft.fillScreen(ST77XX_BLACK);
  lcdHeader("DASHBOARD");
  for (int row = 0; row < 5; row++) {
    int idx = (dashboardIndex + row) % 7;
    int y = 25 + row * 18;
    tft.setCursor(8, y);
    tft.setTextColor(row == 0 ? ST77XX_YELLOW : ST77XX_WHITE);
    tft.print(row == 0 ? "> " : "  ");
    tft.print(dashboardItems[idx]);
  }
  tft.setTextColor(ST77XX_CYAN); tft.setCursor(5, 119); tft.print("NEXT  OK  BACK");
}

void drawDashboardDetail() {
  tft.fillScreen(ST77XX_BLACK);
  lcdHeader(dashboardItems[dashboardIndex]);
  tft.setTextSize(1); tft.setTextColor(ST77XX_WHITE);
  if (dashboardIndex == 0) {
    tft.setCursor(8, 35); tft.print("Tram status: "); tft.print(tramStatus);
    tft.setCursor(8, 55); tft.print("Control: APDS gesture");
  } else if (dashboardIndex == 1) {
    tft.setCursor(8, 32); tft.print("TCS: "); tft.print(colorFound ? "OK" : "MISSING");
    tft.setCursor(8, 50); tft.print("R:"); tft.print(colorR); tft.print(" G:"); tft.print(colorG);
    tft.setCursor(8, 68); tft.print("B:"); tft.print(colorB); tft.print(" C:"); tft.print(colorC);
    tft.setCursor(8, 86); tft.print("Scan: "); tft.print(colorScanMode ? "ON" : "OFF");
  } else if (dashboardIndex == 2) {
    tft.setCursor(8, 35); tft.print("Water raw: "); tft.print(waterRaw);
    tft.setCursor(8, 55); tft.print("Water level: "); tft.print(waterPercent); tft.print("%");
    tft.setCursor(8, 75); tft.print("Pump: "); tft.print(pumpStatus ? "ON" : "OFF");
  } else if (dashboardIndex == 3) {
    tft.setCursor(8, 35); tft.print("Flex raw: "); tft.print(flexRaw);
    tft.setCursor(8, 55); tft.print("Fan: "); tft.print(fanStatus ? "ON" : "OFF");
  } else if (dashboardIndex == 4) {
    tft.setCursor(8, 35); tft.print("Temp: "); tft.print(temperature, 1); tft.print(" C");
    tft.setCursor(8, 55); tft.print("Humidity: "); tft.print(humidity, 0); tft.print("%");
    tft.setCursor(8, 75); tft.print("Time: "); tft.print(clockText);
  } else if (dashboardIndex == 5) {
    tft.setCursor(8, 35); tft.print("PIR: "); tft.print(pirDetected ? "MOTION" : "CLEAR");
    tft.setCursor(8, 55); tft.print("Session: "); tft.print(visitorActive ? "ACTIVE" : "WAITING");
  } else {
    tft.setCursor(8, 35); tft.print("DFPlayer: "); tft.print(dfPlayerFound ? "READY" : "MISSING");
    tft.setCursor(8, 55); tft.print("Volume: "); tft.print(dfPlayerVolume);
  }
  tft.setTextColor(ST77XX_YELLOW); tft.setCursor(5, 119); tft.print("BACK = Dashboard");
}

void drawRecordingScreen() {
  tft.fillScreen(ST77XX_BLACK);
  lcdHeader("SESSION RECORDING");
  tft.setCursor(8, 32); tft.setTextColor(recordingActive ? ST77XX_RED : ST77XX_GREEN);
  tft.print(recordingActive ? "STATUS: RECORDING" : "STATUS: STOPPED");
  tft.setTextColor(ST77XX_WHITE); tft.setCursor(8, 55); tft.print("Samples: "); tft.print(recordCount);
  tft.setCursor(8, 75); tft.print("OK = Start / Stop");
  tft.setCursor(8, 92); tft.print("Data -> Serial session");
  tft.setTextColor(ST77XX_YELLOW); tft.setCursor(8, 115); tft.print("BACK = Main Menu");
}

void drawCurrentUI() {
  if (uiScreen == UI_WAITING) drawWaitingScreen();
  else if (uiScreen == UI_WELCOME) drawWelcomeScreen();
  else if (uiScreen == UI_MAIN_MENU) drawMainMenu();
  else if (uiScreen == UI_SIMULATION) drawSimulationScreen();
  else if (uiScreen == UI_SIM_MODE_MENU) drawSimulationModeMenu();
  else if (uiScreen == UI_INDIVIDUAL) {
    if (individualRunning) drawIndividualActive();
    else drawIndividualScreen();
  }
  else if (uiScreen == UI_DASHBOARD_MENU) drawDashboardMenu();
  else if (uiScreen == UI_DASHBOARD_DETAIL) drawDashboardDetail();
  else if (uiScreen == UI_RECORDING) drawRecordingScreen();
}

void enterSimulation() {
  uiScreen = UI_SIMULATION;
  simPhase = 0;             // Start at Phase 0: Tram
  tramLocation = "LEFT";    // Assume tram is at start
  colorScanMode = false;    // Lights off by default
  tramReturning = false;    // Reset timer state
  
  gestureName = gestureFound ? "READY" : "NO APDS";
  if (gestureFound) setGestureUI("VISITOR");
  Serial.println("[UI] FULL SIMULATION STARTED - Phase 0 (Tram)");
}

void updateMasterButtons() {
  static bool lastNext = HIGH, lastSelect = HIGH, lastBack = HIGH;
  static unsigned long lastPress = 0;
  const unsigned long debounceMs = 120;
  bool n = digitalRead(BTN_NEXT_PIN), s = digitalRead(BTN_SELECT_PIN), b = digitalRead(BTN_BACK_PIN);
  unsigned long now = millis();

  // Welcome automatically opens the menu.
  if (uiScreen == UI_WELCOME && now - welcomeStarted >= 1500) {
    uiScreen = UI_MAIN_MENU;
    Serial.println("[LCD] OPENING MAIN MENU");
    drawCurrentUI();
  }

  bool nextPressed = lastNext == HIGH && n == LOW;
  bool selectPressed = lastSelect == HIGH && s == LOW;
  bool backPressed = lastBack == HIGH && b == LOW;

  if ((nextPressed || selectPressed || backPressed) && now - lastPress >= debounceMs) {
    lastPress = now;

    if (nextPressed) {
      Serial.println("[BUTTON] NEXT");
      if (uiScreen == UI_MAIN_MENU) mainMenuIndex = (mainMenuIndex + 1) % 4;
      else if (uiScreen == UI_SIM_MODE_MENU) simulationModeIndex = (simulationModeIndex + 1) % 2;
      else if (uiScreen == UI_INDIVIDUAL && !individualRunning) individualIndex = (individualIndex + 1) % 5;
      else if (uiScreen == UI_DASHBOARD_MENU) dashboardIndex = (dashboardIndex + 1) % 7;
      drawCurrentUI();
    }

    if (selectPressed) {
      Serial.println("[BUTTON] SELECT / OK");
      if (uiScreen == UI_MAIN_MENU) {
        if (mainMenuIndex == 0) { simulationModeIndex = 0; uiScreen = UI_SIM_MODE_MENU; }
        else if (mainMenuIndex == 1) uiScreen = UI_DASHBOARD_MENU;
        else if (mainMenuIndex == 2) uiScreen = UI_RECORDING;
        else {
          // mainMenuIndex == 3: Launch BLE Provisioning screen immediately
          startBleProvisioning();
          return;
        }
      } else if (uiScreen == UI_SIM_MODE_MENU) {
        if (simulationModeIndex == 0) {
          individualIndex = 0;
          individualRunning = false;
          uiScreen = UI_INDIVIDUAL;
        } else {
          enterSimulation();
        }
      } else if (uiScreen == UI_INDIVIDUAL) {
        if (!individualRunning) startIndividualOperation();
      } else if (uiScreen == UI_DASHBOARD_MENU) {
        uiScreen = UI_DASHBOARD_DETAIL;
      } else if (uiScreen == UI_RECORDING) {
        recordingActive = !recordingActive;
        if (recordingActive) { recordCount = 0; lastRecordMs = 0; Serial.println("[RECORDING] START"); }
        else Serial.println("[RECORDING] STOP");
      }
      drawCurrentUI();
    }

    if (backPressed) {
      Serial.println("[BUTTON] BACK");
      if (uiScreen == UI_SIMULATION) {
        sendCommand(slave1MAC, CMD_TRAM_STOP);
        sendCommand(slave2MAC, CMD_FANS_OFF);
        sendCommand(slave2MAC, CMD_PUMP_OFF);
        tramStatus = "STOPPED"; fanStatus = false; pumpStatus = false;
        colorScanMode = false;
        gestureUIState = "IDLE";
        tramReturning = false;
        sendCommand(slave2MAC, CMD_LIGHT_LEVEL, 0);
        streetLightsKnown = false;
        uiScreen = UI_SIM_MODE_MENU;
      } else if (uiScreen == UI_INDIVIDUAL) {
        if (individualRunning) stopIndividualOperation();
        else uiScreen = UI_SIM_MODE_MENU;
      } else if (uiScreen == UI_SIM_MODE_MENU) {
        uiScreen = UI_MAIN_MENU;
      } else if (uiScreen == UI_DASHBOARD_DETAIL) uiScreen = UI_DASHBOARD_MENU;
      else if (uiScreen == UI_DASHBOARD_MENU || uiScreen == UI_RECORDING) {
        recordingActive = false;
        uiScreen = UI_MAIN_MENU;
      }
      drawCurrentUI();
    }
  }

  lastNext = n; lastSelect = s; lastBack = b;
}

void updateRecording() {
  if (!recordingActive || uiScreen != UI_RECORDING) return;
  if (millis() - lastRecordMs < 2000) return;
  lastRecordMs = millis();
  recordCount++;
  Serial.print("[REC] #"); Serial.print(recordCount);
  Serial.print(" time="); Serial.print(clockText);
  Serial.print(" temp="); Serial.print(temperature,1);
  Serial.print(" hum="); Serial.print(humidity,1);
  Serial.print(" water="); Serial.print(waterPercent);
  Serial.print(" flex="); Serial.println(flexRaw);
}

// ============================================================
// FIREBASE BACKGROUND WORKER TASK (True Non-Blocking FreeRTOS)
// Runs on Core 0 so loop() on Core 1 never waits for network I/O.
// Uses PATCH on /system.json so sibling fields are NOT wiped.
// Syncs live Telemetry and executes Remote App Commands.
// ============================================================
void firebaseTask(void *pvParameters) {
  for (;;) {
    if (WiFi.status() == WL_CONNECTED) {
      wifiStationConnected = true;
      // -------------------------------------------------------------
      // CYCLE 1: CHECK & EXECUTE REMOTE APP COMMANDS FIRST (/requests.json)
      // -------------------------------------------------------------
      {
        WiFiClientSecure reqClient;
        reqClient.setInsecure();
        reqClient.setTimeout(3);
        HTTPClient http;
        String urlReq = String(FIREBASE_HOST) + "/requests.json";
        if (http.begin(reqClient, urlReq)) {
          http.setTimeout(2500);
          int code = http.GET();
          if (code == 200) {
            String payload = http.getString();
            http.end(); // Close GET connection BEFORE sending any ACK!
            reqClient.stop();

            // A. Simulation Requests (/requests/simulation)
            if (payload.indexOf("\"simulation\":{") >= 0 && payload.indexOf("\"PENDING\"") >= 0) {
              if (payload.indexOf("\"action\":\"START\"") >= 0) {
                if (payload.indexOf("\"target_mode\":\"FULL\"") >= 0) {
                  Serial.println("[APP COMMAND] Full System Simulation START");
                  remoteSimOp = 0;
                  remoteSimStart = true;
                } else if (payload.indexOf("\"target_mode\":\"INDIVIDUAL\"") >= 0) {
                  int opIdx = payload.indexOf("\"individual_op\":");
                  if (opIdx >= 0) {
                    int op = payload.substring(opIdx + 16, opIdx + 17).toInt();
                    if (op >= 1 && op <= 5) {
                      Serial.printf("[APP COMMAND] Individual Operation %d START\n", op);
                      remoteSimOp = op;
                      remoteSimStart = true;
                    }
                  }
                }
                // Immediately acknowledge so Android app shows Running / Accepted
                WiFiClientSecure ackClient;
                ackClient.setInsecure();
                ackClient.setTimeout(3);
                HTTPClient ackHttp;
                if (ackHttp.begin(ackClient, String(FIREBASE_HOST) + "/requests/simulation/status.json")) {
                  ackHttp.setTimeout(2500);
                  ackHttp.PUT("\"ACCEPTED\"");
                  ackHttp.end();
                }
                ackClient.stop();
              } else if (payload.indexOf("\"action\":\"STOP\"") >= 0) {
                Serial.println("[APP COMMAND] Simulation STOP");
                remoteSimStop = true;
                WiFiClientSecure ackClient;
                ackClient.setInsecure();
                ackClient.setTimeout(3);
                HTTPClient ackHttp;
                if (ackHttp.begin(ackClient, String(FIREBASE_HOST) + "/requests/simulation/status.json")) {
                  ackHttp.setTimeout(2500);
                  ackHttp.PUT("\"COMPLETED\"");
                  ackHttp.end();
                }
                ackClient.stop();
              }
            }

            // B. Tram Requests (/requests/tram)
            if (payload.indexOf("\"tram\":{") >= 0 && payload.indexOf("\"PENDING\"") >= 0) {
              if (payload.indexOf("\"FORWARD\"") >= 0) {
                Serial.println("[APP COMMAND] Tram FORWARD");
                sendCommand(slave1MAC, CMD_TRAM_FORWARD);
                tramStatus = "FORWARD";
              } else if (payload.indexOf("\"REVERSE\"") >= 0) {
                Serial.println("[APP COMMAND] Tram REVERSE");
                sendCommand(slave1MAC, CMD_TRAM_REVERSE);
                tramStatus = "REVERSE";
              } else if (payload.indexOf("\"STOP\"") >= 0) {
                Serial.println("[APP COMMAND] Tram STOP");
                sendCommand(slave1MAC, CMD_TRAM_STOP);
                tramStatus = "STOPPED";
              }
              remoteActionOp = 1;
              remoteActionPending = true;
              WiFiClientSecure ackClient;
              ackClient.setInsecure();
              ackClient.setTimeout(3);
              HTTPClient ackHttp;
              if (ackHttp.begin(ackClient, String(FIREBASE_HOST) + "/requests/tram/status.json")) {
                ackHttp.setTimeout(2500);
                ackHttp.PUT("\"ACCEPTED\"");
                ackHttp.end();
              }
              ackClient.stop();
            }

            // C. Actuator Requests (Pump & Fan)
            if (payload.indexOf("\"pump\":{") >= 0 && payload.indexOf("\"PENDING\"") >= 0) {
              if (payload.indexOf("\"target_state\":true") >= 0 || payload.indexOf("\"action\":\"ON\"") >= 0) {
                Serial.println("[APP COMMAND] Pump ON");
                sendCommand(slave2MAC, CMD_PUMP_ON);
                pumpStatus = true;
              } else {
                Serial.println("[APP COMMAND] Pump OFF");
                sendCommand(slave2MAC, CMD_PUMP_OFF);
                pumpStatus = false;
              }
              remoteActionOp = 5;
              remoteActionPending = true;
              WiFiClientSecure ackClient;
              ackClient.setInsecure();
              ackClient.setTimeout(3);
              HTTPClient ackHttp;
              if (ackHttp.begin(ackClient, String(FIREBASE_HOST) + "/requests/actuators/pump/status.json")) {
                ackHttp.setTimeout(2500);
                ackHttp.PUT("\"ACCEPTED\"");
                ackHttp.end();
              }
              ackClient.stop();
            }

            if (payload.indexOf("\"fan\":{") >= 0 && payload.indexOf("\"PENDING\"") >= 0) {
              if (payload.indexOf("\"target_state\":true") >= 0 || payload.indexOf("\"action\":\"ON\"") >= 0) {
                Serial.println("[APP COMMAND] Fan ON");
                sendCommand(slave2MAC, CMD_FANS_ON);
                fanStatus = true;
              } else {
                Serial.println("[APP COMMAND] Fan OFF");
                sendCommand(slave2MAC, CMD_FANS_OFF);
                fanStatus = false;
              }
              remoteActionOp = 4;
              remoteActionPending = true;
              WiFiClientSecure ackClient;
              ackClient.setInsecure();
              ackClient.setTimeout(3);
              HTTPClient ackHttp;
              if (ackHttp.begin(ackClient, String(FIREBASE_HOST) + "/requests/actuators/fan/status.json")) {
                ackHttp.setTimeout(2500);
                ackHttp.PUT("\"ACCEPTED\"");
                ackHttp.end();
              }
              ackClient.stop();
            }

            // D. Street Lights Requests (/requests/street_lights)
            if (payload.indexOf("\"street_lights\":{") >= 0 && payload.indexOf("\"PENDING\"") >= 0) {
              if (payload.indexOf("\"action\":\"DEMO_TIME\"") >= 0 || payload.indexOf("\"mode\":\"DEMO\"") >= 0) {
                Serial.println("[APP COMMAND] Street Lights DEMO");
                streetLightsKnown = false;
                streetLightsOn = true;
                sendCommand(slave2MAC, CMD_LIGHT_LEVEL, 255);
              } else {
                Serial.println("[APP COMMAND] Street Lights RTC / REAL");
                streetLightsKnown = false;
                updateStreetLights();
              }
              remoteActionOp = 2;
              remoteActionPending = true;
              WiFiClientSecure ackClient;
              ackClient.setInsecure();
              ackClient.setTimeout(3);
              HTTPClient ackHttp;
              if (ackHttp.begin(ackClient, String(FIREBASE_HOST) + "/requests/street_lights/status.json")) {
                ackHttp.setTimeout(2500);
                ackHttp.PUT("\"ACCEPTED\"");
                ackHttp.end();
              }
              ackClient.stop();
            }

            // E. RGB Requests (/requests/rgb)
            if (payload.indexOf("\"rgb\":{") >= 0 && payload.indexOf("\"PENDING\"") >= 0) {
              if (payload.indexOf("\"SENSOR\"") >= 0) {
                Serial.println("[APP COMMAND] RGB Mode SENSOR");
                colorScanMode = true;
                sendCommand(slave2MAC, CMD_STRIP_ON);
              } else {
                Serial.println("[APP COMMAND] RGB Mode MANUAL");
                colorScanMode = false;
                sendCommand(slave2MAC, CMD_STRIP_ON);
              }
              remoteActionOp = 3;
              remoteActionPending = true;
              WiFiClientSecure ackClient;
              ackClient.setInsecure();
              ackClient.setTimeout(3);
              HTTPClient ackHttp;
              if (ackHttp.begin(ackClient, String(FIREBASE_HOST) + "/requests/rgb/status.json")) {
                ackHttp.setTimeout(2500);
                ackHttp.PUT("\"ACCEPTED\"");
                ackHttp.end();
              }
              ackClient.stop();
            }
          } else {
            http.end();
            reqClient.stop();
          }

        } else {
          reqClient.stop();
        }
      }

      // -------------------------------------------------------------
      // CYCLE 2: SINGLE CONSOLIDATED PATCH FOR SYSTEM, TELEMETRY & STATE
      // -------------------------------------------------------------
      {
        String simMode = "IDLE";
        bool simRunning = false;
        int simOp = 0;
        String simDesc = "System ready";

        if (uiScreen == UI_SIMULATION) {
          simMode = "FULL";
          simRunning = true;
          simOp = 0;
          simDesc = "Full System Simulation Active";
        } else if (uiScreen == UI_INDIVIDUAL && individualRunning) {
          simMode = "INDIVIDUAL";
          simRunning = true;
          simOp = individualIndex + 1;
          simDesc = String(individualLabels[individualIndex]) + " Test Active";
        }

        String bundle = "{";
        bundle += "\"system\":{\"master_online\":true,\"master_heartbeat\":";
        bundle += String(millis());
        bundle += ",\"firmware_version\":\"1.0.0-rj45\",\"wifi_rssi\":";
        bundle += String(WiFi.RSSI());
        bundle += "},";

        bundle += "\"telemetry\":{\"temperature\":";
        bundle += String(temperature, 1);
        bundle += ",\"humidity\":";
        bundle += String(humidity, 1);
        bundle += ",\"water_raw\":";
        bundle += String(waterRaw);
        bundle += ",\"water_level_pct\":";
        bundle += String(waterPercent);
        bundle += ",\"water_status\":\"";
        bundle += (waterPercent >= 50 ? "CRITICAL" : "NORMAL");
        bundle += "\",\"flex_raw\":";
        bundle += String(flexRaw);
        bundle += ",\"flex_bent\":";
        bundle += (flexRaw > 50 ? "true" : "false");
        bundle += ",\"pir_active\":";
        bundle += (pirDetected ? "true" : "false");
        bundle += ",\"timestamp\":";
        bundle += String(millis());
        bundle += "},";

        bundle += "\"reported_state\":{\"active_mode\":\"";
        bundle += (simRunning ? (simMode == "FULL" ? "SIMULATION_FULL" : "SIMULATION_INDIVIDUAL") : "IDLE");
        bundle += "\",\"simulation\":{\"mode\":\"";
        bundle += simMode;
        bundle += "\",\"running\":";
        bundle += (simRunning ? "true" : "false");
        bundle += ",\"individual_op\":";
        bundle += String(simOp);
        bundle += ",\"step_description\":\"";
        bundle += simDesc;
        bundle += "\",\"started_at\":";
        bundle += String(millis());
        bundle += "},\"tram_state\":\"";
        bundle += tramStatus;
        bundle += "\",\"street_lights_on\":";
        bundle += (streetLightsOn ? "true" : "false");
        bundle += ",\"fan_state\":";
        bundle += (fanStatus ? "true" : "false");
        bundle += ",\"pump_state\":";
        bundle += (pumpStatus ? "true" : "false");
        bundle += ",\"last_updated\":";
        bundle += String(millis());
        bundle += "}}";

        WiFiClientSecure syncClient;
        syncClient.setInsecure();
        syncClient.setTimeout(3);
        HTTPClient http;
        if (http.begin(syncClient, String(FIREBASE_HOST) + "/.json")) {
          http.addHeader("Content-Type", "application/json");
          http.setTimeout(3500);
          int code = http.PATCH(bundle);
          if (code == 200) {
            Serial.printf("[FIREBASE] Sync OK (RSSI %d)\n", WiFi.RSSI());
          } else {
            Serial.printf("[FIREBASE] Sync HTTP %d\n", code);
          }
          http.end();
        }
        syncClient.stop();
      }
    }
    // Delay 2 seconds between cycles for swift command pickup
    vTaskDelay(pdMS_TO_TICKS(2000));
  }
}

// ============================================================
// SETUP
// ============================================================



void setup() {

  Serial.begin(115200);

  delay(1000);

  Serial.println();
  Serial.println("=================================");
  Serial.println("       RJ45 MASTER BOOT");
  Serial.println("=================================");


  // TFT

  setupLCD();


  // GPIO

  pinMode(
    PIR_PIN,
    INPUT
  );

  pinMode(
    GESTURE_INT_PIN,
    INPUT_PULLUP
  );

  // MASTER UI buttons
  pinMode(BTN_NEXT_PIN, INPUT_PULLUP);
  pinMode(BTN_SELECT_PIN, INPUT_PULLUP);
  pinMode(BTN_BACK_PIN, INPUT_PULLUP);


  // I2C

  Wire.begin(
    SDA_PIN,
    SCL_PIN
  );

  Wire.setClock(100000);

  delay(500);


  // ==========================================================
  // SCAN EVERYTHING CONNECTED TO GPIO21 / GPIO22
  // ==========================================================

  scanI2CBus();


  // PCF8591

  pcfFound =
    checkI2C(
      PCF8591_ADDR
    );

  Serial.print("PCF8591 0x48: ");
  Serial.println(
    pcfFound ? "FOUND" : "MISSING"
  );


  // RTC

  rtcFound =
    rtc.begin();

  Serial.print("DS3231 0x68: ");

  Serial.println(
    rtcFound ? "FOUND" : "MISSING"
  );


  // COLOR SENSOR

  colorFound =
    tcs.begin();

  Serial.print("TCS34725 0x29: ");

  Serial.println(
    colorFound ? "FOUND" : "MISSING"
  );


  // ==========================================================
  // APDS9960
  // ==========================================================

  setupAPDSDirect();

Serial.println();
Serial.println("===== APDS FINAL STATUS =====");

Serial.print("0x39 detected: ");
Serial.println(apdsAddressFound ? "YES" : "NO");

Serial.print("Direct APDS setup: ");
Serial.println(gestureFound ? "READY" : "FAILED");

Serial.print("Gesture status: ");
Serial.println(gestureName);

Serial.println("=============================");
Serial.println();
  // DHT

  dht.begin();


  // ==========================================================
  // DFPLAYER
  //
  // GPIO16 = ESP32 RX <- DFPlayer TX
  // GPIO17 = ESP32 TX -> DFPlayer RX
  // ==========================================================

  DFSerial.begin(
    9600,
    SERIAL_8N1,
    DFPLAYER_RX_PIN,
    DFPLAYER_TX_PIN
  );

  // Allow DFPlayer power + SD card to settle, same as the working test.
  delay(2000);
  startDFPlayer(false);  // real mode: initialize audio without forced boot-test playback


  // WIFI

  WiFi.mode(
    WIFI_AP_STA
  );
  WiFi.setSleep(false);

  wifiReady =
    WiFi.softAP(
      AP_SSID,
      AP_PASSWORD
    );

  Serial.print("WiFi AP: ");

  Serial.println(
    wifiReady ? "READY" : "FAILED"
  );

  if (wifiReady) {

    Serial.print("IP: ");

    Serial.println(
      WiFi.softAPIP()
    );
  }

  // Wi-Fi Credentials: nona / 12345678
  wifiSsid = "nona";
  wifiPassword = "12345678";
  preferences.begin("rj45_wifi", false);
  preferences.putString("ssid", wifiSsid);
  preferences.putString("pass", wifiPassword);
  preferences.end();

  // If SELECT button is held LOW at boot, enter BLE Setup mode. Otherwise, connect directly to nona hotspot.
  if (digitalRead(BTN_SELECT_PIN) == LOW) {
    Serial.println("[SETUP] SELECT button held: Entering BLE Provisioning mode...");
    startBleProvisioning();
  } else {
    Serial.print("[WIFI] Auto-connecting to Hotspot SSID: '");
    Serial.print(wifiSsid);
    Serial.println("'...");
    WiFi.disconnect(false);
    delay(150);
    WiFi.begin(wifiSsid.c_str(), wifiPassword.c_str());
    wifiConnectStartTime = millis();

    Serial.print("[WIFI] Connecting to '");
    Serial.print(wifiSsid);
    Serial.print("'");
    int connectAttempts = 0;
    while (WiFi.status() != WL_CONNECTED && connectAttempts < 16) {
      delay(500);
      Serial.print(".");
      connectAttempts++;
    }
    Serial.println();

    if (WiFi.status() == WL_CONNECTED) {
      wifiStationConnected = true;
      Serial.println("=================================");
      Serial.println("[WIFI] Station Connected Successfully!");
      Serial.print("[WIFI] IP Address: ");
      Serial.println(WiFi.localIP());
      Serial.print("[WIFI] Active Channel: ");
      Serial.println(WiFi.channel());
      Serial.println("=================================");
    } else {
      Serial.print("[WIFI] Could not connect immediately (Status: ");
      switch (WiFi.status()) {
        case WL_NO_SSID_AVAIL:   Serial.println("WL_NO_SSID_AVAIL - SSID 'nona' not found! Make sure Hotspot is 2.4 GHz)"); break;
        case WL_CONNECT_FAILED:  Serial.println("WL_CONNECT_FAILED - Password wrong or Security is WPA3! Change to WPA2-Personal)"); break;
        case WL_DISCONNECTED:    Serial.println("WL_DISCONNECTED - Background connection in progress...)"); break;
        default:                 Serial.println(String(WiFi.status()) + ")"); break;
      }
    }
  }


  // ESP-NOW

  if (
    esp_now_init() == ESP_OK
  ) {

    espNowReady = true;

    registerPeer(slave1MAC);
    registerPeer(slave2MAC);

    Serial.println("ESP-NOW: INITIALIZED");

  } else {

    espNowReady = false;

    Serial.println("ESP-NOW: FAILED");
  }


  // WEB

  setupWebServer();


  // INITIAL SENSOR READS

  updatePIR();

  if (pcfFound) {

    updateWater();
    updateFlex();
  }

  updateDHT();

  updateColor();

  updateClock();


  // TFT SYSTEM CHECK

  showSystemCheck();
  delay(1200);
  // PIR may already have detected a visitor during setup.
  // Do not overwrite the active session with the WAITING screen.
  if (visitorActive) {
    uiScreen = UI_WELCOME;
    welcomeStarted = millis();
  } else {
    uiScreen = UI_WAITING;
  }
  drawCurrentUI();


  Serial.println();
  Serial.println("=================================");
  Serial.println("       MASTER READY");
  Serial.println("=================================");

  // Launch Firebase Background Worker Task on Core 0 (True Non-Blocking)
  xTaskCreatePinnedToCore(
    firebaseTask,
    "FirebaseTask",
    10240, // 10KB stack for secure mbedtls TLS handshakes
    NULL,
    1,
    &firebaseTaskHandle,
    0 // Core 0
  );
}


// ============================================================
// LOOP
// ============================================================

void loop() {

  unsigned long now = millis();

  // 1. If in BLE Provisioning Mode
  if (inProvisioningMode) {
    // If BACK button is pressed, cancel provisioning and return to normal UI
    if (digitalRead(BTN_BACK_PIN) == LOW) {
      Serial.println("[BLE] Provisioning cancelled by BACK button.");
      stopBleProvisioning();
      inProvisioningMode = false;
      drawCurrentUI();
    }
    delay(50);
    return;
  }

  // 2. If just exited BLE Provisioning Mode after receiving credentials
  if (provisioningCompleted) {
    provisioningCompleted = false;
    stopBleProvisioning();
    tft.fillScreen(ST77XX_BLACK);
    tft.setTextColor(ST77XX_GREEN);
    tft.setTextSize(1);
    tft.setCursor(10, 30);
    tft.println("Connecting Wi-Fi...");
    if (wifiSsid.length() > 0) {
      Serial.print("[WIFI] Attempting connection to SSID: '");
      Serial.print(wifiSsid);
      Serial.println("'...");
      WiFi.disconnect(false);
      delay(100);
      WiFi.begin(wifiSsid.c_str(), wifiPassword.c_str());
      wifiConnectStartTime = millis();
    }
    delay(600);
    drawCurrentUI();
  }

  // 3. Non-blocking Wi-Fi station status monitor + Auto-BLE advertising
  static unsigned long lastWifiCheck = 0;
  static wl_status_t lastWifiStatus = WL_IDLE_STATUS;
  static bool bgBleAdvertising = false;

  // If Wi-Fi is disconnected for >8 seconds, auto-enable BLE advertising in background
  if (!wifiStationConnected && !inProvisioningMode && !bgBleAdvertising && now > 8000) {
    bgBleAdvertising = true;
    setupBleProvisioning();
    BLEDevice::startAdvertising();
    Serial.println("[BLE] Background advertising active: RJ45-MASTER is discoverable by phone.");
  }

  if (now - lastWifiCheck >= 1500) {
    lastWifiCheck = now;
    wl_status_t curStatus = WiFi.status();
    if (curStatus != lastWifiStatus) {
      lastWifiStatus = curStatus;
      Serial.print("[WIFI] Status changed: ");
      switch (curStatus) {
        case WL_NO_SSID_AVAIL:
          Serial.println("WL_NO_SSID_AVAIL (Hotspot SSID not found! Ensure Redmi AP Band is set to 2.4 GHz)");
          break;
        case WL_CONNECT_FAILED:
          Serial.println("WL_CONNECT_FAILED (Password wrong or Security set to WPA3! Change to WPA2-Personal)");
          break;
        case WL_CONNECTED:
          Serial.print("WL_CONNECTED! IP: ");
          Serial.print(WiFi.localIP());
          Serial.print(", Channel: ");
          Serial.println(WiFi.channel());
          break;
        case WL_DISCONNECTED:
          Serial.println("WL_DISCONNECTED (Connecting or disconnected)");
          break;
        case WL_IDLE_STATUS:
          Serial.println("WL_IDLE_STATUS");
          break;
        default:
          Serial.printf("Status code: %d\n", curStatus);
          break;
      }
    }
    if (curStatus == WL_CONNECTED) {
      if (!wifiStationConnected) {
        wifiStationConnected = true;
        if (bgBleAdvertising) {
          bgBleAdvertising = false;
          stopBleProvisioning();
          Serial.println("[BLE] Wi-Fi online! Background BLE advertising stopped.");
        }
        Serial.println();
        Serial.println("=================================");
        Serial.print("[WIFI] Station Connected! IP: ");
        Serial.println(WiFi.localIP());
        Serial.print("[WIFI] Active Channel: ");
        Serial.println(WiFi.channel());
        if (WiFi.channel() != 1) {
          Serial.printf("[ESP-NOW WARNING] Hotspot is on Channel %d. Set hotspot/router to Channel 1 if possible!\n", WiFi.channel());
        }
        Serial.println("=================================");
      }
    } else {
      if (wifiStationConnected) {
        wifiStationConnected = false;
        Serial.println("[WIFI] Station Disconnected!");
      }
      static unsigned long lastReconnectAttempt = 0;
      if (now - lastReconnectAttempt >= 10000 && wifiSsid.length() > 0 && !inProvisioningMode) {
        lastReconnectAttempt = now;
        Serial.print("[WIFI] Re-attempting connection to '");
        Serial.print(wifiSsid);
        Serial.println("'...");
        WiFi.disconnect(false);
        delay(100);
        WiFi.begin(wifiSsid.c_str(), wifiPassword.c_str());
      }
    }
  }

  // 5-SECOND TRAM RETURN TIMER
  if (tramReturning && (now - tramReturnTimer >= 5000)) {
    tramReturning = false;
    
    // Motor has stopped at the left. Now advance to River & RGB phase.
    if (uiScreen == UI_SIMULATION && simPhase == 0) {
      simPhase = 2; 
      colorScanMode = true; 
      lastColorScanSend = 0;
      sendCommand(slave2MAC, CMD_STRIP_ON);
      
      // FORCE the LCD to clear the "LEFT" screen and return to dashboard
      gestureUIState = "IDLE";
      gestureName = "READY";
      
      Serial.println("[SIMULATION] 5 seconds elapsed! Phase 2 Unlocked: RGB & River ON.");
    }
  }


  // 6. PROCESS REMOTE APP COMMANDS (Thread-Safe from Core 0 to Core 1)
  if (remoteSimStart) {
    remoteSimStart = false;
    if (remoteSimOp == 0) {
      enterSimulation();
      drawCurrentUI();
    } else if (remoteSimOp >= 1 && remoteSimOp <= 5) {
      individualIndex = remoteSimOp - 1;
      uiScreen = UI_INDIVIDUAL;
      startIndividualOperation();
      drawCurrentUI();
    }
  }
  if (remoteSimStop) {
    remoteSimStop = false;
    if (uiScreen == UI_INDIVIDUAL && individualRunning) {
      stopIndividualOperation();
    }
    uiScreen = UI_MAIN_MENU;
    drawCurrentUI();
  }
  if (remoteActionPending) {
    remoteActionPending = false;
    if (remoteActionOp >= 1 && remoteActionOp <= 5) {
      individualIndex = remoteActionOp - 1;
      individualRunning = true;
      uiScreen = UI_INDIVIDUAL;
      gestureUIState = "IDLE";
      drawCurrentUI();
    }
  }


  // MASTER UI BUTTONS
  updateMasterButtons();

  // SESSION RECORDING
  updateRecording();

  // WEB

  server.handleClient();

  // DFPLAYER - nonblocking event/Serial service
  updateDFPlayer();


  // GESTURE

  updateGesture();

  // CONTINUOUS TCS34725 -> SLAVE 2 RGB MODE
  // UP enables this; DOWN disables it.
  scanColorAndSendToSlave2();


  // FAST SENSORS

  if (
    now - lastFastRead >=
    FAST_INTERVAL
  ) {

    lastFastRead = now;

    updatePIR();

    if (pcfFound) {

      updateWater();
      updateFlex();
    }
  }


  // DHT

  if (
    now - lastDHTRead >=
    DHT_INTERVAL
  ) {

    lastDHTRead = now;

    updateDHT();
  }


  // COLOR + CLOCK

  if (
    now - lastSlowRead >=
    SLOW_INTERVAL
  ) {

    lastSlowRead = now;

    updateColor();

    updateClock();
    updateStreetLights();
  }


  // TFT

  if (
    now - lastDisplayUpdate >=
    DISPLAY_INTERVAL
  ) {

    lastDisplayUpdate = now;

    // Static screens are drawn only on state changes, avoiding full-screen flicker.
    if (uiScreen == UI_SIMULATION) {
      if (gestureUIState != "IDLE") updateLCD();
      else drawCurrentUI();
    } else if (uiScreen == UI_INDIVIDUAL && individualRunning) {
      if (individualIndex != 0 || gestureUIState == "IDLE") drawIndividualActive();
    } else if (uiScreen == UI_DASHBOARD_DETAIL || uiScreen == UI_RECORDING) {
      drawCurrentUI();
    }
  }
}