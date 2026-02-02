// ====================================================
// ОХРАННАЯ СИСТЕМА НА ESP32 С LD2410 + ОТПРАВКА В ВК (MAIN)
// + MQTT VIDEO STREAMING CONTROL
// ИСПРАВЛЕНА СИНХРОНИЗАЦИЯ С ESP32-CAM (FIX v2)
// ====================================================

#include <Arduino.h>
#include <WiFi.h>
#include <HTTPClient.h>
#include <WiFiClientSecure.h>
#include <PubSubClient.h>
#include <ArduinoJson.h>
#include <Preferences.h>
#include <math.h>

#include <HardwareSerial.h>
#include <ld2410.h>

// === КОНФИГУРАЦИЯ ПИНОВ ESP32 ===
const int ledPin = 13;
const int buzzerPin = 26;
const int lampPin = 27;
const int motionLedPin = 12;
const int micPin = 34;
const int gainPin = 32;
const int cameraPin = 4;

// === SIM800L (UART1) ===
#define SIM800_RX_PIN 16
#define SIM800_TX_PIN 17
HardwareSerial sim800Serial(1);

// === LD2410 (UART2) ===
const int RADAR_RX_PIN = 25;
const int RADAR_TX_PIN = 33;
HardwareSerial radarSerial(2);
ld2410 radar;

// === ЛИЧНЫЕ ДАННЫЕ (WIFI / MQTT / TELEGRAM) ===
const char phoneNumber[] = "+79387820181";
const char* ssid = "NOVA_8BA8";
const char* password = "mouth3224";

const char* botToken = "8415270898:AAHtIQrY7Y92j2YGg0L6QgxGkq0dcIV7Iec";
String allowedChatID = "1848333915";

// === VK CONFIG ===
String vk_token = "vk1.a._DP_DYqg8A3dNkYa-gPVCCDlWIVzix86IlxgrkoayxogZ38WtaZd9sHtqdsw_cUecABRLkBFbjUvqTUYwg6O0JjSPOAJmBX5gKTRWVL1ZTd-ngkYtI_YS7MAU55bbjBMy-9Ziqv1EorxAiRZKggR-PEV-3SFm5Wsimg-rGIrDGWWUAiOZKG0u49MZO793LZFkE0H8IRUrU23NPXc95Brlg"; 
String vk_myId = "891024947";
const String vk_version = "5.131";

// === MQTT CONFIG ===
const char* mqtt_server = "srv2.clusterfly.ru";
const int mqtt_port = 9991;
const char* mqtt_user = "user_4bd2b1f5";
const char* mqtt_pass = "FnoQuMvkcV1ej";

String mqtt_client_id;

// === MQTT ТОПИКИ (ОХРАННАЯ СИСТЕМА) ===
const char* topic_status = "user_4bd2b1f5/alarm/status";
const char* topic_motion = "user_4bd2b1f5/alarm/motion";
const char* topic_sound = "user_4bd2b1f5/alarm/sound";
const char* topic_trigger = "user_4bd2b1f5/alarm/trigger";
const char* topic_control = "user_4bd2b1f5/alarm/control";
const char* topic_log = "user_4bd2b1f5/alarm/log";

// === MQTT ТОПИКИ КАМЕРЫ ===
const char* topic_camera_commands = "user_4bd2b1f5/commands";
const char* topic_camera_status = "user_4bd2b1f5/status";

// === ПАРАМЕТРЫ СИСТЕМЫ ===
const unsigned long COINCIDENCE_WINDOW_MS = 3000;
const unsigned long ALARM_DURATION = 3000;
const unsigned long SOUND_DEBOUNCE_MS = 500;
const unsigned long ALARM_COOLDOWN_MS = 5000;
const unsigned long IDLE_CONFIRM_MS = 500;
const unsigned long MOTION_LOG_INTERVAL = 2000;
const bool USE_ACTIVE_BUZZER = false;

// === ПАРАМЕТРЫ МИКРОФОНА ===
const int sampleCount = 50;
const unsigned int sampleIntervalUs = 200;
const float SOUND_THRESHOLD = 750.0f;
const unsigned long INITIAL_STABILIZE_TIME = 2000;

// === ПЕРЕМЕННЫЕ СОСТОЯНИЯ ===
float lastSoundLevel = 0.0f;
unsigned long lastSoundMillis = 0;
unsigned long lastMotionMillis = 0;
unsigned long lastAlarmTime = 0;
unsigned long lastMqttReconnect = 0;
unsigned long bootTime = 0;
unsigned long idleStartMillis = 0;
unsigned long lastMotionLog = 0;
unsigned long lastStatusPublish = 0;

bool systemArmed = true;
bool smsInProgress = false;
bool alarmActive = false;
bool notificationsSent = false;
bool systemLocked = false;
unsigned long systemLockedUntil = 0;

// 🔥 ИСПРАВЛЕНО: Новая логика отслеживания камеры
unsigned long lastCameraMessageTime = 0;  // Время последнего ЛЮБОГО сообщения
const unsigned long CAMERA_TIMEOUT_MS = 20000;  // 20 секунд (больше чем 10 сек публикации)

// Статус радара
volatile bool radarPresence = false;
int radarMovingDistance = -1;
int radarStationaryDistance = -1;

// === SMS БУФЕРЫ ===
char smsBuffer[160];
int smsBufferPos = 0;
char smsSender[32];

// === ОБЪЕКТЫ СЕТИ ===
WiFiClient espClient;
PubSubClient mqttClient(espClient);
Preferences preferences;

// Предварительное объявление функций
void processSMSLine();
void processCommand(const char* cmd);
void sendStatusSMS();
void sendSMS(const char* text);
void publishStatus();
void publishLog(const String &message);
void triggerAlarm();

// ====================================================
// ВСПОМОГАТЕЛЬНЫЕ ФУНКЦИИ
// ====================================================

String urlencode(const String &str) {
  String encoded;
  for (unsigned int i = 0; i < str.length(); i++) {
    char c = str.charAt(i);
    if (isalnum(c) || c == '-' || c == '_' || c == '.' || c == '~')
      encoded += c;
    else {
      char buf[5];
      sprintf(buf, "%%%02X", (uint8_t)c);
      encoded += buf;
    }
  }
  return encoded;
}

void normalizeNumber(const char* input, char* output) {
  int j = 0;
  for (int i = 0; input[i] && j < 11; i++) {
    if (input[i] >= '0' && input[i] <= '9') {
      output[j++] = input[i];
    }
  }
  output[j] = '\0';
  if (j == 11 && output[0] == '8') output[0] = '7';
}

// 🔥 НОВОЕ: Проверка статуса камеры
bool isCameraOnline() {
  if (lastCameraMessageTime == 0) return false;
  return (millis() - lastCameraMessageTime < CAMERA_TIMEOUT_MS);
}

// ====================================================
// TELEGRAM & SMS
// ====================================================

void sendTelegramMessage(const String &message) {
  if (WiFi.status() != WL_CONNECTED) return;
  
  WiFiClientSecure client;
  client.setInsecure();
  client.setTimeout(2000);
  HTTPClient https;
  https.setTimeout(2000);
  
  String url = String("https://api.telegram.org/bot") + botToken +
               "/sendMessage?chat_id=" + allowedChatID +
               "&text=" + urlencode(message);
  if (https.begin(client, url)) {
    https.GET();
    https.end();
  }
}

void checkSMS() {
  while (sim800Serial.available()) {
    char c = sim800Serial.read();
    if (c == '\n') {
      if (smsBufferPos > 0) {
        smsBuffer[smsBufferPos] = '\0';
        processSMSLine();
        smsBufferPos = 0;
      }
    } else if (c != '\r' && smsBufferPos < (int)(sizeof(smsBuffer)-1)) {
      smsBuffer[smsBufferPos++] = c;
    }
  }
}

void processSMSLine() {
  if (strncmp(smsBuffer, "+CMT:", 5) == 0) {
    smsInProgress = true;
    char* start = strchr(smsBuffer, '"');
    if (start) {
      start++;
      char* end = strchr(start, '"');
      if (end) {
        int len = end - start;
        if (len < (int)sizeof(smsSender)) {
          strncpy(smsSender, start, len);
          smsSender[len] = '\0';
        }
      }
    }
    return;
  }
  
  if (smsInProgress && smsBufferPos > 0 && smsBuffer[0] != '+') {
    smsInProgress = false;
    for (int i = 0; i < smsBufferPos; i++) {
      if (smsBuffer[i] >= 'a' && smsBuffer[i] <= 'z') smsBuffer[i] -= 32;
    }
    
    char normSender[12], normMy[12];
    normalizeNumber(smsSender, normSender);
    normalizeNumber(phoneNumber, normMy);
    
    int lenS = strlen(normSender);
    int lenM = strlen(normMy);
    
    if (lenS >= 10 && lenM >= 10) {
      if (strcmp(normSender + lenS - 10, normMy + lenM - 10) == 0) {
        smsBuffer[smsBufferPos] = '\0';
        processCommand(smsBuffer);
      }
    }
    smsSender[0] = '\0';
  }
}

void processCommand(const char* cmd) {
  Serial.print("[SMS] Команда: "); Serial.println(cmd);
  if (strcmp(cmd, "STATUS") == 0 || strcmp(cmd, "STATE") == 0) {
    sendStatusSMS();
    return;
  }
  if (strcmp(cmd, "ARM") == 0 || strcmp(cmd, "ON") == 0) {
    systemArmed = true;
    systemLocked = false;
    systemLockedUntil = 0;
    preferences.putBool("armed", true);
    sendSMS("OK: Alarm ON");
    publishStatus();
    
    if (mqttClient.connected() && isCameraOnline()) {
      delay(300);
      mqttClient.publish(topic_camera_commands, "ARM", false);
      Serial.println("[MQTT] → Camera: ARM");
    }
    return;
  }
  if (strcmp(cmd, "DISARM") == 0 || strcmp(cmd, "OFF") == 0) {
    systemArmed = false;
    systemLocked = false;
    systemLockedUntil = 0;
    preferences.putBool("armed", false);
    sendSMS("OK: Alarm OFF");
    publishStatus();
    
    if (mqttClient.connected() && isCameraOnline()) {
      delay(300);
      mqttClient.publish(topic_camera_commands, "DISARM", false);
      Serial.println("[MQTT] → Camera: DISARM");
    }
    return;
  }
  sendSMS("Unknown command");
}

void sendSMS(const char* text) {
  while (sim800Serial.available()) sim800Serial.read();
  sim800Serial.println("AT+CMGF=1");
  delay(300);
  sim800Serial.print("AT+CMGS=\"");
  sim800Serial.print(phoneNumber);
  sim800Serial.println("\"");
  delay(500);
  sim800Serial.print(text);
  delay(200);
  sim800Serial.write(26);
  delay(1500);
  while (sim800Serial.available()) sim800Serial.readString();
}

void sendStatusSMS() {
  char msg[160];
  snprintf(msg, sizeof(msg), "Alarm:%s\nLock:%s\nMotion:%s\nDist: M%d/S%d cm\nSound:%.1f\nCamera:%s",
           systemArmed ? "ON" : "OFF",
           (millis() < systemLockedUntil) ? "YES" : "NO",
           radarPresence ? "YES" : "NO",
           radarMovingDistance, radarStationaryDistance,
           lastSoundLevel,
           isCameraOnline() ? "ONLINE" : "OFFLINE");
  sendSMS(msg);
}

void sendSMSAsync(const char* text) {
  xTaskCreatePinnedToCore(
    [](void *param) {
      char* msg = (char*)param;
      sim800Serial.println("AT+CMGF=1"); delay(300);
      sim800Serial.print("AT+CMGS=\""); sim800Serial.print(phoneNumber); sim800Serial.println("\""); delay(500);
      sim800Serial.print(msg); delay(200);
      sim800Serial.write(26); delay(1500);
      free(msg);
      vTaskDelete(NULL);
    }, "SMSTask", 3000, strdup(text), 1, NULL, 0
  );
}

// ====================================================
// MQTT
// ====================================================

void publishStatus() {
  if (!mqttClient.connected()) return;
  
  StaticJsonDocument<256> doc;
  doc["armed"] = systemArmed;
  doc["locked"] = (millis() < systemLockedUntil);
  doc["pir"] = radarPresence;
  doc["wifi_rssi"] = WiFi.RSSI();
  doc["uptime"] = (millis() - bootTime) / 1000;
  doc["camera_online"] = isCameraOnline();
  
  char buffer[256];
  serializeJson(doc, buffer);
  
  mqttClient.publish(topic_status, buffer, false);
  lastStatusPublish = millis();
  
  Serial.println("[MQTT] ✅ Status published");
}

void publishAlarmEvent() {
  if (!mqttClient.connected()) return;
  
  StaticJsonDocument<128> doc;
  doc["type"] = "alarm";
  doc["timestamp"] = millis() / 1000;
  doc["armed"] = systemArmed;
  doc["sound_level"] = lastSoundLevel;
  
  char buffer[128];
  serializeJson(doc, buffer);
  mqttClient.publish(topic_trigger, buffer, false);
  mqttClient.publish(topic_motion, "ALARM", false);
}

void publishLog(const String &message) {
  if (!mqttClient.connected()) return;
  char logMsg[160];
  snprintf(logMsg, sizeof(logMsg), "[%lu] %s", (millis() - bootTime) / 1000, message.c_str());
  mqttClient.publish(topic_log, logMsg, false);
}

// 🔥 ИСПРАВЛЕНО: Упрощённая обработка статуса камеры
void handleCameraStatus(const char* payload, unsigned int length) {
  // Получили ЛЮБОЕ сообщение от камеры → обновляем время
  lastCameraMessageTime = millis();
  
  // Парсим для дополнительной информации (опционально)
  StaticJsonDocument<512> doc;
  DeserializationError error = deserializeJson(doc, payload, length);
  
  if (!error) {
    bool wasOnline = isCameraOnline();
    bool nowOnline = isCameraOnline();
    
    // Логируем только ИЗМЕНЕНИЯ статуса
    if (!wasOnline && nowOnline) {
      Serial.println("[MQTT] 📹 Camera ONLINE");
      publishStatus();  // Обновляем свой статус
    }
  }
}

void mqttCallback(char* topic, byte* payload, unsigned int length) {
  // Обработка статуса камеры
  if (strcmp(topic, topic_camera_status) == 0) {
    handleCameraStatus((const char*)payload, length);
    return;
  }
  
  // Обработка команд для охранной системы
  if (strcmp(topic, topic_control) != 0) return;
  
  char message[length + 1];
  memcpy(message, payload, length);
  message[length] = '\0';
  String msg = String(message);
  msg.trim(); 
  msg.toUpperCase();

  Serial.print("[MQTT] CMD: "); Serial.println(msg);
  
  if (msg == "ARM" || msg == "ON") {
    systemArmed = true;
    systemLocked = false;
    systemLockedUntil = 0;
    preferences.putBool("armed", true);
    publishStatus();
    publishLog("Armed via MQTT");
    sendTelegramMessage("✅ Alarm ARMED");
    
    if (isCameraOnline()) {
      delay(300);
      mqttClient.publish(topic_camera_commands, "ARM", false);
      Serial.println("[MQTT] → Camera: ARM");
    }
  }
  else if (msg == "DISARM" || msg == "OFF") {
    systemArmed = false;
    systemLocked = false;
    systemLockedUntil = 0;
    preferences.putBool("armed", false);
    publishStatus();
    publishLog("Disarmed via MQTT");
    sendTelegramMessage("✅ Alarm DISARMED");
    
    if (isCameraOnline()) {
      delay(300);
      mqttClient.publish(topic_camera_commands, "DISARM", false);
      Serial.println("[MQTT] → Camera: DISARM");
    }
  }
  else if (msg == "STATUS") {
    publishStatus();
    if (isCameraOnline()) {
      mqttClient.publish(topic_camera_commands, "STATUS", false);
    }
  }
}

void mqttReconnect() {
  if (millis() - lastMqttReconnect < 5000) return;
  lastMqttReconnect = millis();
  if (mqttClient.connected()) return;
  
  Serial.print("[MQTT] Connecting...");
  
  StaticJsonDocument<256> offlineDoc;
  offlineDoc["armed"] = false;
  offlineDoc["locked"] = false;
  offlineDoc["pir"] = false;
  offlineDoc["wifi_rssi"] = 0;
  offlineDoc["uptime"] = 0;
  offlineDoc["camera_online"] = false;
  
  char willMessage[256];
  serializeJson(offlineDoc, willMessage);
  
  if (mqttClient.connect(mqtt_client_id.c_str(), mqtt_user, mqtt_pass,
                         topic_status, 1, false, willMessage)) {
    Serial.println(" ✅");
    Serial.printf("[MQTT] ID: %s\n", mqtt_client_id.c_str());
    
    mqttClient.subscribe(topic_control, 0);
    mqttClient.subscribe(topic_camera_status, 0);
    Serial.println("[MQTT] ✅ Subscribed");
    
    delay(1000);
    publishStatus();
    
    if (isCameraOnline()) {
      Serial.println("[MQTT] 📹 Camera detected");
      delay(300);
      
      if (systemArmed) {
        mqttClient.publish(topic_camera_commands, "ARM", false);
        Serial.println("[MQTT] → Camera: ARM (sync)");
      } else {
        mqttClient.publish(topic_camera_commands, "DISARM", false);
        Serial.println("[MQTT] → Camera: DISARM (sync)");
      }
    }
  } else {
    Serial.print(" ❌ rc=");
    Serial.println(mqttClient.state());
  }
}

// ====================================================
// МИКРОФОН И ЛОГИКА (БЕЗ ИЗМЕНЕНИЙ)
// ====================================================

float measureRMS() {
  unsigned long sum = 0;
  unsigned long sumSq = 0;
  unsigned long t0 = micros();
  for (int i = 0; i < sampleCount; ++i) {
    int val = analogRead(micPin);
    sum += val;
    sumSq += (unsigned long)val * (unsigned long)val;
    unsigned long target = t0 + (unsigned long)((i + 1) * sampleIntervalUs);
    while (micros() < target) {}
  }
  float mean = sum / (float)sampleCount;
  float meanSq = sumSq / (float)sampleCount;
  return sqrt(max(0.0f, meanSq - mean * mean));
}

void setGain(int mode) {
  if (mode == 0) pinMode(gainPin, INPUT);
  else {
    pinMode(gainPin, OUTPUT);
    digitalWrite(gainPin, mode == 2 ? HIGH : LOW);
  }
}

void checkCoincidence() {
  if (lastMotionMillis > 0 && lastSoundMillis > 0) {
    unsigned long timeDiff;
    if (lastMotionMillis > lastSoundMillis) timeDiff = lastMotionMillis - lastSoundMillis;
    else timeDiff = lastSoundMillis - lastMotionMillis;
    
    if (timeDiff <= COINCIDENCE_WINDOW_MS) {
      triggerAlarm();
      lastSoundMillis = 0;
      lastMotionMillis = 0;
      systemLocked = true;
      systemLockedUntil = millis() + ALARM_COOLDOWN_MS;
      idleStartMillis = 0;
    }
  }
}

void triggerAlarm() {
  if (!systemArmed) return;
  if (millis() - lastAlarmTime < ALARM_COOLDOWN_MS) return;
  lastAlarmTime = millis();
  
  Serial.println("🚨 TRIGGERED! Motion + Sound! 🚨");
  notificationsSent = false;
  unsigned long alarmStartTime = millis();
  
  while (millis() - alarmStartTime < ALARM_DURATION) {
    digitalWrite(ledPin, HIGH);
    digitalWrite(lampPin, HIGH);
    digitalWrite(motionLedPin, HIGH);
    
    if (USE_ACTIVE_BUZZER) digitalWrite(buzzerPin, HIGH);
    else tone(buzzerPin, 2500);

    if (!notificationsSent) {
      notificationsSent = true;
      publishAlarmEvent();
      
      xTaskCreatePinnedToCore([](void *param) {
        WiFiClientSecure c; c.setInsecure();
        HTTPClient h;
        String u = String("https://api.telegram.org/bot") + botToken + 
                  "/sendMessage?chat_id=" + allowedChatID + "&text=" + urlencode("🚨 СРАБОТАЛА СИГНАЛИЗАЦИЯ! (Движение + Звук)");
        if (h.begin(c, u)) { h.GET(); h.end(); }
        vTaskDelete(NULL);
      }, "TgTask", 5000, NULL, 1, NULL, 0);
      
      xTaskCreatePinnedToCore([](void *param) {
        WiFiClientSecure c; c.setInsecure();
        HTTPClient h;
        String msg = urlencode("🚨 СРАБОТАЛА СИГНАЛИЗАЦИЯ! (Движение + Звук)");
        String u = "https://api.vk.com/method/messages.send?peer_id=" + vk_myId + 
                   "&random_id=" + String(millis()) + 
                   "&message=" + msg + 
                   "&access_token=" + vk_token + 
                   "&v=" + vk_version;
        if (h.begin(c, u)) { h.GET(); h.end(); }
        vTaskDelete(NULL);
      }, "VkTask", 6000, NULL, 1, NULL, 0);

      sendSMSAsync("ALARM! Motion+Sound detected!");
    }

    delay(100);
    digitalWrite(ledPin, LOW);
    digitalWrite(lampPin, LOW);
    digitalWrite(motionLedPin, LOW);
    if (USE_ACTIVE_BUZZER) digitalWrite(buzzerPin, LOW);
    else noTone(buzzerPin);
    
    checkSMS();
    if (mqttClient.connected()) mqttClient.loop();
    delay(100);
  }
  publishLog("Alarm finished");
}

// ====================================================
// SETUP
// ====================================================

void setup() {
  Serial.begin(115200);
  delay(500);
  
  Serial.println("\n\n=== ALARM SYSTEM STARTING (v2 FIX) ===");
  
  uint8_t mac[6];
  WiFi.macAddress(mac);
  mqtt_client_id = "alarm_";
  for (int i = 0; i < 6; i++) {
    mqtt_client_id += String(mac[i], HEX);
  }
  Serial.printf("[MQTT] Client ID: %s\n", mqtt_client_id.c_str());
  
  pinMode(ledPin, OUTPUT);
  pinMode(buzzerPin, OUTPUT);
  pinMode(lampPin, OUTPUT);
  pinMode(motionLedPin, OUTPUT);
  pinMode(cameraPin, OUTPUT);
  digitalWrite(cameraPin, LOW);

  sim800Serial.begin(9600, SERIAL_8N1, SIM800_RX_PIN, SIM800_TX_PIN);
  radarSerial.begin(256000, SERIAL_8N1, RADAR_RX_PIN, RADAR_TX_PIN);
  delay(500);
  
  Serial.println("[RADAR] Connecting...");
  if (radar.begin(radarSerial)) {
    Serial.println("[RADAR] OK");
  } else {
    Serial.println("[RADAR] Failed");
  }

  sim800Serial.println("AT+CMGF=1");
  delay(200);
  sim800Serial.println("AT+CNMI=2,2,0,0,0");

  analogReadResolution(12);
  analogSetPinAttenuation(micPin, ADC_11db);
  setGain(1);

  preferences.begin("alarm", false);
  systemArmed = preferences.getBool("armed", true);
  
  Serial.print("[WiFi] Connecting");
  WiFi.begin(ssid, password);
  unsigned long startWifi = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - startWifi < 10000) {
    delay(200);
    Serial.print(".");
  }
  
  if (WiFi.status() == WL_CONNECTED) {
    Serial.println(" ✅");
    Serial.printf("[WiFi] IP: %s\n", WiFi.localIP().toString().c_str());
    Serial.printf("[WiFi] RSSI: %d dBm\n", WiFi.RSSI());
    
    mqttClient.setServer(mqtt_server, mqtt_port);
    mqttClient.setCallback(mqttCallback);
    mqttClient.setBufferSize(512);
    mqttClient.setKeepAlive(60);
    
    sendTelegramMessage("✅ Alarm System Started (Fixed v2)");
  } else {
    Serial.println(" ❌");
  }
  
  delay(INITIAL_STABILIZE_TIME);
  bootTime = millis();
  Serial.println("=== SYSTEM READY ===\n");
}

// ====================================================
// LOOP
// ====================================================

void loop() {
  checkSMS();
  
  // MQTT
  if (WiFi.status() == WL_CONNECTED) {
    if (!mqttClient.connected()) {
      mqttReconnect();
    } else {
      mqttClient.loop();
    }
    
    // 🔥 ИСПРАВЛЕНО: Проверка таймаута камеры ПОСЛЕ обработки сообщений
    static bool wasCameraOnline = false;
    bool nowCameraOnline = isCameraOnline();
    
    if (wasCameraOnline && !nowCameraOnline) {
      Serial.println("[MQTT] ⚠️ Camera TIMEOUT");
      publishStatus();
    }
    
    wasCameraOnline = nowCameraOnline;
    
    // Периодическая публикация
    if (millis() - lastStatusPublish > 10000) {
      publishStatus();
    }
  }

  // РАДАР
  radar.read();
  bool isPresent = radar.isConnected() && radar.presenceDetected();
  radarPresence = isPresent;
  
  digitalWrite(cameraPin, isPresent ? HIGH : LOW);
  
  if (radar.movingTargetDetected()) radarMovingDistance = radar.movingTargetDistance();
  if (radar.stationaryTargetDetected()) radarStationaryDistance = radar.stationaryTargetDistance();
  digitalWrite(motionLedPin, isPresent ? HIGH : LOW);

  if (isPresent) {
    lastMotionMillis = millis();
    
    if (millis() - lastMotionLog > MOTION_LOG_INTERVAL) {
      lastMotionLog = millis();
      Serial.printf("[RADAR] Motion! Dist: %dcm\n", radarMovingDistance);
      
      if (mqttClient.connected()) {
        mqttClient.publish(topic_motion, "1", false);
      }
    }
  }

  // Разблокировка системы
  if (systemLocked) {
    if (millis() >= systemLockedUntil) {
      systemLocked = false;
      systemLockedUntil = 0;
      idleStartMillis = 0;
      Serial.println("[SYS] System Unlocked (timeout)");
    } else {
      if (!isPresent) {
        if (idleStartMillis == 0) idleStartMillis = millis();
        else if (millis() - idleStartMillis >= IDLE_CONFIRM_MS) {
          systemLocked = false;
          systemLockedUntil = 0;
          idleStartMillis = 0;
          Serial.println("[SYS] System Unlocked (area clear)");
        }
      } else {
        idleStartMillis = 0;
      }
    }
  }

  if (systemArmed && !systemLocked) {
    checkCoincidence();
  }

  // Звук
  float rms = measureRMS();
  lastSoundLevel = rms;

  if (rms > SOUND_THRESHOLD && (millis() - lastSoundMillis > SOUND_DEBOUNCE_MS)) {
    lastSoundMillis = millis();
    Serial.printf("[MIC] Loud sound: %.1f\n", rms);
    
    if (mqttClient.connected()) {
      char sVal[16]; 
      snprintf(sVal, sizeof(sVal), "%.1f", rms);
      mqttClient.publish(topic_sound, sVal, false);
    }
    
    if (systemArmed && !systemLocked) checkCoincidence();
  }

  // Сброс старых таймеров
  if (lastMotionMillis > 0 && (millis() - lastMotionMillis > COINCIDENCE_WINDOW_MS)) {
    lastMotionMillis = 0;
  }
  if (lastSoundMillis > 0 && (millis() - lastSoundMillis > SOUND_DEBOUNCE_MS)) {
    lastSoundMillis = 0;
  }

  // WiFi reconnect
  if (WiFi.status() != WL_CONNECTED && millis() - bootTime > 30000) {
    static unsigned long lastWifiTry = 0;
    if (millis() - lastWifiTry > 30000) {
      lastWifiTry = millis();
      Serial.println("[WiFi] Reconnecting...");
      WiFi.reconnect();
    }
  }

  delay(10);
}