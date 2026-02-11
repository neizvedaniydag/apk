// ====================================================
// ОХРАННАЯ СИСТЕМА НА ESP32 С LD2410 + ОТПРАВКА В ВК (MAIN)
// + MQTT VIDEO STREAMING CONTROL
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
// === ИНТЕГРАЦИЯ С КАМЕРОЙ ===
const int cameraPin = 4; // Пин для отправки сигнала на ESP32-S3 Cam

// === SIM800L (UART1) ===
#define SIM800_RX_PIN 16
#define SIM800_TX_PIN 17
HardwareSerial sim800Serial(1);
// === LD2410 (UART2) ===
const int RADAR_RX_PIN = 25; // RX от LD2410
const int RADAR_TX_PIN = 33; // TX к LD2410
HardwareSerial radarSerial(2);
ld2410 radar;

// === ЛИЧНЫЕ ДАННЫЕ (WIFI / MQTT / TELEGRAM) ===
const char phoneNumber[] = "+79387820181";
const char* ssid = "NOVA_8BA8";
const char* password = "mouth3224";

const char* botToken = "8415270898:AAHtIQrY7Y92j2YGg0L6QgxGkq0dcIV7Iec";
String allowedChatID = "1848333915";

// === VK CONFIG (ДОБАВЛЕНО ИЗ КОДА КАМЕРЫ) ===
String vk_token = "vk1.a._DP_DYqg8A3dNkYa-gPVCCDlWIVzix86IlxgrkoayxogZ38WtaZd9sHtqdsw_cUecABRLkBFbjUvqTUYwg6O0JjSPOAJmBX5gKTRWVL1ZTd-ngkYtI_YS7MAU55bbjBMy-9Ziqv1EorxAiRZKggR-PEV-3SFm5Wsimg-rGIrDGWWUAiOZKG0u49MZO793LZFkE0H8IRUrU23NPXc95Brlg"; 
String vk_myId = "891024947";
const String vk_version = "5.131";

const char* mqtt_server = "srv2.clusterfly.ru";
const int mqtt_port = 9991;
const char* mqtt_user = "user_4bd2b1f5";
const char* mqtt_pass = "FnoQuMvkcV1ej";
const char* mqtt_client_id = "user_4bd2b1f5_alarm";

// MQTT ТОПИКИ
const char* topic_status = "user_4bd2b1f5/alarm/status";
const char* topic_motion = "user_4bd2b1f5/alarm/motion";
const char* topic_sound = "user_4bd2b1f5/alarm/sound";
const char* topic_trigger = "user_4bd2b1f5/alarm/trigger";
const char* topic_control = "user_4bd2b1f5/alarm/control";
const char* topic_log = "user_4bd2b1f5/alarm/log";

// === НОВЫЕ ТОПИКИ ДЛЯ УПРАВЛЕНИЯ КАМЕРОЙ ===
const char* topic_camera_control = "user_4bd2b1f5/camera/control";
const char* topic_camera_status = "user_4bd2b1f5/camera/status";

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
const float SOUND_THRESHOLD = 690.0f;
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

bool systemArmed = true;
bool smsInProgress = false;
bool alarmActive = false;
bool notificationsSent = false;
bool systemLocked = false;
unsigned long systemLockedUntil = 0; 

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
    // Включаем видео-стриминг
    if (mqttClient.connected()) {
      mqttClient.publish(topic_camera_control, "STREAM_ON", false);
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
    // Выключаем видео-стриминг
    if (mqttClient.connected()) {
      mqttClient.publish(topic_camera_control, "STREAM_OFF", false);
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
  snprintf(msg, sizeof(msg), "Alarm:%s\nLock:%s\nMotion:%s\nDist: M%d/S%d cm\nSound:%.1f",
           systemArmed ? "ON" : "OFF",
           (millis() < systemLockedUntil) ? "YES" : "NO",
           radarPresence ? "YES" : "NO",
           radarMovingDistance, radarStationaryDistance,
           lastSoundLevel);
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
  doc["sound_level"] = (int)lastSoundLevel;
  doc["wifi_rssi"] = WiFi.RSSI();
  doc["uptime"] = (millis() - bootTime) / 1000;
  char buffer[256];
  serializeJson(doc, buffer);
  mqttClient.publish(topic_status, buffer, true);
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

void mqttCallback(char* topic, byte* payload, unsigned int length) {
  char message[length + 1];
  memcpy(message, payload, length);
  message[length] = '\0';
  String msg = String(message);
  msg.trim(); msg.toUpperCase();

  Serial.print("[MQTT] CMD: "); Serial.println(msg);
  
  if (msg == "ARM" || msg == "ON") {
    systemArmed = true;
    systemLocked = false;
    systemLockedUntil = 0;
    preferences.putBool("armed", true);
    publishStatus();
    publishLog("Armed via MQTT");
    sendTelegramMessage("✅ Alarm ARMED");
    // Включаем видео-стриминг при включении системы
    mqttClient.publish(topic_camera_control, "STREAM_ON", false);
  }
  else if (msg == "DISARM" || msg == "OFF") {
    systemArmed = false;
    systemLocked = false;
    systemLockedUntil = 0;
    preferences.putBool("armed", false);
    publishStatus();
    publishLog("Disarmed via MQTT");
    sendTelegramMessage("✅ Alarm DISARMED");
    // Выключаем видео-стриминг при выключении системы
    mqttClient.publish(topic_camera_control, "STREAM_OFF", false);
  }
  else if (msg == "STATUS") {
    publishStatus();
    // Запрашиваем статус камеры
    mqttClient.publish(topic_camera_control, "STATUS", false);
  }
  else if (msg == "STREAM_ON") {
    // Ручное включение видео-стриминга
    mqttClient.publish(topic_camera_control, "STREAM_ON", false);
    publishLog("Video streaming enabled manually");
    sendTelegramMessage("📹 Видео-стриминг включен");
  }
  else if (msg == "STREAM_OFF") {
    // Ручное выключение видео-стриминга
    mqttClient.publish(topic_camera_control, "STREAM_OFF", false);
    publishLog("Video streaming disabled manually");
    sendTelegramMessage("📹 Видео-стриминг выключен");
  }
}

void mqttReconnect() {
  if (millis() - lastMqttReconnect < 5000) return;
  lastMqttReconnect = millis();
  if (mqttClient.connected()) return;
  
  if (mqttClient.connect(mqtt_client_id, mqtt_user, mqtt_pass)) {
    mqttClient.subscribe(topic_control, 0);
    mqttClient.subscribe(topic_camera_status, 0); // Подписка на статус камеры
    publishStatus();
    Serial.println("[MQTT] Connected");
    
    // Синхронизируем состояние видео-стриминга с состоянием системы
    if (systemArmed) {
      mqttClient.publish(topic_camera_control, "STREAM_ON", false);
      Serial.println("[MQTT] Camera streaming enabled (system armed)");
    } else {
      mqttClient.publish(topic_camera_control, "STREAM_OFF", false);
      Serial.println("[MQTT] Camera streaming disabled (system disarmed)");
    }
  }
}

// ====================================================
// МИКРОФОН И ЛОГИКА
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
  // Проверяем, были ли оба события в пределах окна
  if (lastMotionMillis > 0 && lastSoundMillis > 0) {
    unsigned long timeDiff;
    if (lastMotionMillis > lastSoundMillis) timeDiff = lastMotionMillis - lastSoundMillis;
    else timeDiff = lastSoundMillis - lastMotionMillis;
    
    if (timeDiff <= COINCIDENCE_WINDOW_MS) {
      triggerAlarm();
      lastSoundMillis = 0;
      lastMotionMillis = 0;
      // блокируем систему на фиксированный таймаут
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
    // При тревоге радар видит движение -> cameraPin уже HIGH из loop
    
    if (USE_ACTIVE_BUZZER) digitalWrite(buzzerPin, HIGH);
    else tone(buzzerPin, 2500);

    if (!notificationsSent) {
      notificationsSent = true;
      publishAlarmEvent();
      
      // === TELEGRAM TASK ===
      xTaskCreatePinnedToCore([](void *param) {
        WiFiClientSecure c; c.setInsecure();
        HTTPClient h;
        String u = String("https://api.telegram.org/bot") + botToken + 
                  "/sendMessage?chat_id=" + allowedChatID + "&text=" + urlencode("🚨 СРАБОТАЛА СИГНАЛИЗАЦИЯ! (Движение + Звук)");
        if (h.begin(c, u)) { h.GET(); h.end(); }
        vTaskDelete(NULL);
      }, "TgTask", 5000, NULL, 1, NULL, 0);
      
      // === VK TASK (НОВОЕ: ОТПРАВКА В ВК СРАЗУ) ===
      xTaskCreatePinnedToCore([](void *param) {
        WiFiClientSecure c; c.setInsecure();
        HTTPClient h;
        // Сообщение для ВК
        String msg = urlencode("🚨 СРАБОТАЛА СИГНАЛИЗАЦИЯ! (Движение + Звук)");
        String u = "https://api.vk.com/method/messages.send?peer_id=" + vk_myId + 
                   "&random_id=" + String(millis()) + 
                   "&message=" + msg + 
                   "&access_token=" + vk_token + 
                   "&v=" + vk_version;
        if (h.begin(c, u)) { h.GET(); h.end(); }
        vTaskDelete(NULL);
      }, "VkTask", 6000, NULL, 1, NULL, 0);

      // === SMS TASK ===
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
  
  pinMode(ledPin, OUTPUT);
  pinMode(buzzerPin, OUTPUT);
  pinMode(lampPin, OUTPUT);
  pinMode(motionLedPin, OUTPUT);
  
  // Инициализация пина камеры (по умолчанию LOW)
  pinMode(cameraPin, OUTPUT);
  digitalWrite(cameraPin, LOW);

  // UARTs
  sim800Serial.begin(9600, SERIAL_8N1, SIM800_RX_PIN, SIM800_TX_PIN);
  radarSerial.begin(256000, SERIAL_8N1, RADAR_RX_PIN, RADAR_TX_PIN);
  delay(500);
  
  // Radar init
  Serial.println("[RADAR] Connecting...");
  if (radar.begin(radarSerial)) {
    Serial.println("[RADAR] OK");
  } else {
    Serial.println("dog library");
  }

  // SIM800 Init
  sim800Serial.println("AT+CMGF=1");
  delay(200);
  sim800Serial.println("AT+CNMI=2,2,0,0,0");

  // Mic
  analogReadResolution(12);
  analogSetPinAttenuation(micPin, ADC_11db);
  setGain(1);

  // Settings
  preferences.begin("alarm", false);
  systemArmed = preferences.getBool("armed", true);
  
  // WiFi
  WiFi.begin(ssid, password);
  unsigned long startWifi = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - startWifi < 10000) delay(200);
  
  if (WiFi.status() == WL_CONNECTED) {
    mqttClient.setServer(mqtt_server, mqtt_port);
    mqttClient.setCallback(mqttCallback);
    sendTelegramMessage("✅ System Started (Main + VK Integration + Video Control)");
  }
  
  delay(INITIAL_STABILIZE_TIME);
  bootTime = millis();
  Serial.println("=== SYSTEM READY WITH VIDEO STREAMING CONTROL ===");
}

// ====================================================
// LOOP (С ПОДДЕРЖКОЙ УПРАВЛЕНИЯ ВИДЕО)
// ====================================================

void loop() {
  unsigned long now = millis();
  checkSMS();
  if (WiFi.status() == WL_CONNECTED) {
    if (!mqttClient.connected()) mqttReconnect();
    else mqttClient.loop();
  }

  // --- ЛОГИКА РАДАРА ---
  radar.read();
  
  // Получаем состояние: присутствие (движение ИЛИ статика)
  bool isPresent = radar.isConnected() && radar.presenceDetected();
  radarPresence = isPresent;
  
  // --- ЛОГИКА ИНТЕГРАЦИИ КАМЕРЫ ---
  // Если радар видит человека - подаем HIGH на камеру.
  // Камера сама решит, делать фото или ждать (анти-спам в коде камеры).
  digitalWrite(cameraPin, isPresent ? HIGH : LOW);
  
  // Обновляем дистанции для SMS/Debug
  if (radar.movingTargetDetected()) radarMovingDistance = radar.movingTargetDistance();
  if (radar.stationaryTargetDetected()) radarStationaryDistance = radar.stationaryTargetDistance();
  digitalWrite(motionLedPin, isPresent ? HIGH : LOW);

  // Обновляем таймер присутствия постоянно пока есть presence
  if (isPresent) {
    lastMotionMillis = now;
    // Таймер всегда свежий, пока человек здесь

    // Анти-спам логов (выводим не чаще раз в 2 сек)
    if (now - lastMotionLog > MOTION_LOG_INTERVAL) {
      lastMotionLog = now;
      Serial.printf("[RADAR] Motion/Presence Active! Dist: %dcm\n", radarMovingDistance);
      
      if (mqttClient.connected()) {
        mqttClient.publish(topic_motion, "1", false);
      }
    }
  }

  // Если система заблокирована по таймауту — проверим истёк ли таймаут или зона пуста
  if (systemLocked) {
    if (millis() >= systemLockedUntil) {
      // таймаут истёк — разблокировать
      systemLocked = false;
      systemLockedUntil = 0;
      idleStartMillis = 0;
      Serial.println("[SYS] System Unlocked (timeout)");
    } else {
      // Можно позволить раннюю разблокировку, если зона подтвержденно пуста IDLE_CONFIRM_MS
      if (!isPresent) {
        if (idleStartMillis == 0) idleStartMillis = now;
        else if (now - idleStartMillis >= IDLE_CONFIRM_MS) {
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

  // Проверка совпадения со звуком (если вооружена и не заблокирована)
  if (systemArmed && !systemLocked) {
    checkCoincidence();
  }

  // Редкий технический лог (раз в 5 сек)
  static unsigned long lastDebug = 0;
  if (now - lastDebug > 5000) {
    Serial.printf("[STATUS] Sound:%.1f | Presence:%d | Armed:%d | LockedUntil:%lu\n", lastSoundLevel, isPresent, systemArmed, systemLockedUntil);
    lastDebug = now;
  }
  // --- КОНЕЦ ЛОГИКИ РАДАРА ---

  // Звук
  float rms = measureRMS();
  lastSoundLevel = rms;

  if (rms > SOUND_THRESHOLD && (now - lastSoundMillis > SOUND_DEBOUNCE_MS)) {
    lastSoundMillis = now;
    Serial.printf("[MIC] Loud sound detected: %.1f\n", rms);
    
    if (mqttClient.connected()) {
      char sVal[16]; snprintf(sVal, sizeof(sVal), "%.1f", rms);
      mqttClient.publish(topic_sound, sVal, false);
    }
    
    if (systemArmed && !systemLocked) checkCoincidence();
  }

  // Сброс старых таймеров (если движения/шума нет более окна совпадения)
  if (lastMotionMillis > 0 && (now - lastMotionMillis > COINCIDENCE_WINDOW_MS)) {
    lastMotionMillis = 0;
  }
  if (lastSoundMillis > 0 && (now - lastSoundMillis > COINCIDENCE_WINDOW_MS)) {
    lastSoundMillis = 0;
  }

  // WiFi reconnect watchdog
  if (WiFi.status() != WL_CONNECTED && now - bootTime > 30000) {
    static unsigned long lastWifiTry = 0;
    if (now - lastWifiTry > 30000) {
      lastWifiTry = now;
      WiFi.reconnect();
    }
  }

  delay(10); // Небольшая задержка для стабильности
}
