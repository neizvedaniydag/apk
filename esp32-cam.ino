#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <HTTPClient.h>
#include "esp_camera.h"
#include <ArduinoJson.h>
#include <PubSubClient.h>
#include "esp_task_wdt.h"
#include "esp_system.h"

// === EDGE IMPULSE ===
#include <Person_Detector_v4_inferencing.h>
#include "edge-impulse-sdk/dsp/image/image.hpp"

// ================= WIFI SETTINGS =================
const char* ssid = "NOVA_8BA8";
const char* password = "mouth3224";

// ================= VK SETTINGS =================
String token = "vk1.a._DP_DYqg8A3dNkYa-gPVCCDlWIVzix86IlxgrkoayxogZ38WtaZd9sHtqdsw_cUecABRLkBFbjUvqTUYwg6O0JjSPOAJmBX5gKTRWVL1ZTd-ngkYtI_YS7MAU55bbjBMy-9Ziqv1EorxAiRZKggR-PEV-3SFm5Wsimg-rGIrDGWWUAiOZKG0u49MZO793LZFkE0H8IRUrU23NPXc95Brlg";
String myId = "891024947";
const String vk_version = "5.131";

// ================= MQTT SETTINGS =================
const char* mqtt_server = "srv2.clusterfly.ru";
const int mqtt_port = 9991;
const char* mqtt_user = "user_4bd2b1f5";
const char* mqtt_pass = "FnoQuMvkcV1ej";

const char* topic_status = "user_4bd2b1f5/status";
const char* topic_commands = "user_4bd2b1f5/commands";
const char* topic_video_stream = "user_4bd2b1f5/camera/stream";
const char* topic_snapshot = "user_4bd2b1f5/camera/alarm_snapshot";
const char* topic_detection = "user_4bd2b1f5/camera/detection_info";

// 🔥 НОВОЕ: Уникальный Client ID
String uniqueClientId;

// ================= PINS =================
const int triggerPin = 2;
const unsigned long PHOTO_COOLDOWN = 10000;
unsigned long lastPhotoTime = 0;

// ================= MQTT & STREAMING =================
WiFiClient mqttWifiClient;
PubSubClient mqttClient(mqttWifiClient);
unsigned long lastMqttReconnect = 0;
unsigned long lastStreamTime = 0;
unsigned long lastStatusUpdate = 0;
unsigned long lastWifiCheck = 0;
unsigned long lastHeartbeat = 0;  // 🔥 НОВОЕ
const unsigned long WIFI_CHECK_INTERVAL = 5000;
const unsigned long HEARTBEAT_INTERVAL = 5000;  // 🔥 НОВОЕ: каждые 5 сек
const unsigned long STREAM_INTERVAL = 150;
int frameCounter = 0;
int actualFps = 0;
bool streamingEnabled = false;
unsigned long fpsStartTime = 0;
int fpsCounterTemp = 0;

// ================= SYSTEM STATE =================
bool mqttConnected = false;
bool systemLocked = false;
bool alarmEnabled = false;
String pirStatus = "NONE";
int soundLevel = 25;
uint8_t *snapshot_buf = NULL;

enum PIRState { PIR_IDLE, PIR_DETECTED, PIR_ALERT, PIR_COOLDOWN };
PIRState pirState = PIR_IDLE;
unsigned long pirStateStartTime = 0;

// ================= CAMERA PINS =================
#define PWDN_GPIO_NUM -1
#define RESET_GPIO_NUM -1
#define XCLK_GPIO_NUM 15
#define SIOD_GPIO_NUM 4
#define SIOC_GPIO_NUM 5
#define Y9_GPIO_NUM 16
#define Y8_GPIO_NUM 17
#define Y7_GPIO_NUM 18
#define Y6_GPIO_NUM 12
#define Y5_GPIO_NUM 10
#define Y4_GPIO_NUM 8
#define Y3_GPIO_NUM 9
#define Y2_GPIO_NUM 11
#define VSYNC_GPIO_NUM 6
#define HREF_GPIO_NUM 7
#define PCLK_GPIO_NUM 13

// Forward declarations
void publishStatus();
void publishSnapshot(camera_fb_t * fb);
void sendPhotoToVK(camera_fb_t * fb, String messageText);

// ================= EDGE IMPULSE HELPER =================
static int ei_camera_get_data(size_t offset, size_t length, float *out_ptr) {
  if (!snapshot_buf) return -1;
  size_t pixel_ix = offset * 3;
  size_t pixels_left = length;
  size_t out_ptr_ix = 0;
  while (pixels_left != 0) {
    out_ptr[out_ptr_ix] = (snapshot_buf[pixel_ix] << 16) + 
                          (snapshot_buf[pixel_ix + 1] << 8) + 
                          snapshot_buf[pixel_ix + 2];
    out_ptr_ix++;
    pixel_ix += 3;
    pixels_left--;
  }
  return 0;
}

void publishDetectionInfo(float personProb, String detectionType) {
  if (!mqttConnected || !mqttClient.connected()) return;
  
  StaticJsonDocument<256> doc;
  doc["timestamp"] = millis();
  doc["personProbability"] = (int)(personProb * 100);
  doc["detectionType"] = detectionType;
  doc["confidence"] = personProb;
  
  char buffer[256];
  serializeJson(doc, buffer);
  mqttClient.publish(topic_detection, buffer, false);
}

// ================= MQTT CALLBACK =================
void mqttCallback(char* topic, byte* payload, unsigned int length) {
  char message[length + 1];
  memcpy(message, payload, length);
  message[length] = '\0';
  String msg = String(message);
  msg.trim();
  
  Serial.print("[MQTT] Command: ");
  Serial.println(msg);
  
  if (strcmp(topic, topic_commands) == 0) {
    if (msg == "STREAM_START" || msg == "STREAM_ON") {
      streamingEnabled = true;
      publishStatus();
    } 
    else if (msg == "STREAM_STOP" || msg == "STREAM_OFF") {
      streamingEnabled = false;
      publishStatus();
    }
    else if (msg == "STREAM_PREVIEW") {
      camera_fb_t * fb = esp_camera_fb_get();
      if (fb) {
        publishSnapshot(fb);
        esp_camera_fb_return(fb);
      }
    }
    else if (msg == "ARM") {
      systemLocked = true;
      alarmEnabled = true;
      publishStatus();
    }
    else if (msg == "DISARM") {
      systemLocked = false;
      alarmEnabled = false;
      pirStatus = "NONE";
      pirState = PIR_IDLE;
      publishStatus();
    }
    else if (msg == "STATUS") {
      publishStatus();
    }
  }
}

// ================= PUBLISH STATUS =================
void publishStatus() {
  if (!mqttConnected || !mqttClient.connected()) return;
  
  pirStatus = (digitalRead(triggerPin) == HIGH) ? "MOTION" : "NONE";
  soundLevel = 20 + random(0, 30);
  
  StaticJsonDocument<512> doc;
  doc["device"] = "ESP32-S3-CAM";
  doc["systemLocked"] = systemLocked;
  doc["alarmEnabled"] = alarmEnabled;
  doc["pirStatus"] = pirStatus;
  doc["soundLevel"] = soundLevel;
  doc["streaming"] = streamingEnabled;
  doc["fps"] = actualFps;
  doc["frames"] = frameCounter;
  doc["wifi_rssi"] = WiFi.RSSI();
  doc["free_heap"] = ESP.getFreeHeap();
  doc["lastUpdate"] = millis();  // 🔥 КРИТИЧНО для Android!
  
  char buffer[512];
  serializeJson(doc, buffer);
  
  // 🔥 КРИТИЧНО: БЕЗ RETAIN!
  mqttClient.publish(topic_status, buffer, false);
  Serial.println("✅ Status published");
}

// ================= MQTT RECONNECT =================
void mqttReconnect() {
  if (millis() - lastMqttReconnect < 5000) return;
  lastMqttReconnect = millis();
  
  if (mqttClient.connected()) {
    mqttConnected = true;
    return;
  }
  
  mqttConnected = false;
  Serial.print("[MQTT] Connecting...");
  
  // 🔥 ИСПРАВЛЕНО: LWT сообщение
  StaticJsonDocument<512> offlineDoc;
  offlineDoc["device"] = "ESP32-S3-CAM";
  offlineDoc["systemLocked"] = false;
  offlineDoc["alarmEnabled"] = false;
  offlineDoc["pirStatus"] = "NONE";
  offlineDoc["soundLevel"] = 0;
  offlineDoc["streaming"] = false;
  offlineDoc["fps"] = 0;
  offlineDoc["frames"] = 0;
  offlineDoc["wifi_rssi"] = 0;
  offlineDoc["free_heap"] = 0;
  offlineDoc["lastUpdate"] = 0;  // 🔥 КРИТИЧНО: 0 = offline для Android!
  
  char willMessage[512];
  serializeJson(offlineDoc, willMessage);
  
  // 🔥 КРИТИЧНО: retain=FALSE в LWT!
  if (mqttClient.connect(uniqueClientId.c_str(), mqtt_user, mqtt_pass,
                         topic_status, 1, false, willMessage)) {
    mqttConnected = true;
    Serial.println(" ✅");
    Serial.printf("[MQTT] ID: %s\n", uniqueClientId.c_str());
    
    mqttClient.subscribe(topic_commands, 0);
    Serial.println("[MQTT] ✅ Subscribed");
    
    delay(500);
    publishStatus();
  } else {
    mqttConnected = false;
    Serial.print(" ❌ rc=");
    Serial.println(mqttClient.state());
  }
}

// ================= VIDEO & SNAPSHOT =================
void publishVideoFrame(camera_fb_t * fb) {
  if (!mqttConnected || !mqttClient.connected() || !streamingEnabled || !fb) return;
  
  if (!mqttClient.loop()) {
    Serial.println("⚠️ MQTT busy, skipping frame");
    return;
  }
  
  mqttClient.publish(topic_video_stream, (uint8_t*)"START", 5, false);
  
  const size_t chunkSize = 8192;
  size_t offset = 0;
  int chunksSent = 0;
  
  while (offset < fb->len) {
    if (chunksSent % 10 == 0 && !mqttClient.connected()) {
      Serial.println("⚠️ Lost connection during stream");
      return;
    }
    
    size_t remaining = fb->len - offset;
    size_t currentChunk = (remaining < chunkSize) ? remaining : chunkSize;
    
    if (!mqttClient.publish(topic_video_stream, fb->buf + offset, currentChunk, false)) {
      Serial.println("⚠️ Publish failed, aborting frame");
      return;
    }
    
    offset += currentChunk;
    chunksSent++;
    mqttClient.loop();
    yield();
  }
  
  mqttClient.publish(topic_video_stream, (uint8_t*)"END", 3, false);
  
  frameCounter++;
  fpsCounterTemp++;
  
  if (millis() - fpsStartTime >= 1000) {
    actualFps = fpsCounterTemp;
    fpsCounterTemp = 0;
    fpsStartTime = millis();
  }
}

void publishSnapshot(camera_fb_t * fb) {
  if (!mqttConnected || !mqttClient.connected() || !fb) return;

  if (fb->len > 100000) {
      Serial.println("📦 Snapshot chunked...");
      const size_t chunkSize = 8192;
      size_t offset = 0;
      mqttClient.publish(topic_snapshot, (uint8_t*)"START", 5, false);
      
      while (offset < fb->len) {
          if (!mqttClient.connected()) {
            Serial.println("⚠️ Lost connection during snapshot");
            return;
          }
          
          size_t remaining = fb->len - offset;
          size_t currentChunk = (remaining < chunkSize) ? remaining : chunkSize;
          mqttClient.publish(topic_snapshot, fb->buf + offset, currentChunk, false);
          offset += currentChunk;
          mqttClient.loop();
          yield();
      }
      mqttClient.publish(topic_snapshot, (uint8_t*)"END", 3, false);
  } else {
      mqttClient.publish(topic_snapshot, fb->buf, fb->len, false);
  }
  
  Serial.printf("✅ Snapshot: %d bytes\n", fb->len);
}

// ================= VK LOGIC (БЕЗ ИЗМЕНЕНИЙ) =================
String vkRequest(String method, String params) {
  HTTPClient http;
  WiFiClientSecure client;
  client.setInsecure();
  String url = "https://api.vk.com/method/" + method + "?" + params + "&access_token=" + token + "&v=" + vk_version;
  if (http.begin(client, url)) {
    int httpCode = http.GET();
    if (httpCode > 0) {
      String payload = http.getString();
      http.end();
      return payload;
    }
  }
  http.end();
  return "";
}

String getUploadServer() {
  String response = vkRequest("photos.getMessagesUploadServer", "peer_id=" + myId);
  StaticJsonDocument<2048> doc;
  deserializeJson(doc, response);
  if (doc.containsKey("response")) return doc["response"]["upload_url"].as<String>();
  return "";
}

String uploadPhotoToVkServer(String uploadUrl, camera_fb_t * fb) {
  uploadUrl.replace("https://", "");
  int splitIndex = uploadUrl.indexOf('/');
  String host = uploadUrl.substring(0, splitIndex);
  String path = uploadUrl.substring(splitIndex);
  
  WiFiClientSecure client;
  client.setInsecure();
  if (!client.connect(host.c_str(), 443)) return "";
  
  String boundary = "ESP32Boundary";
  String head = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"photo\"; filename=\"cam.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n";
  String tail = "\r\n--" + boundary + "--\r\n";
  
  uint32_t totalLen = head.length() + fb->len + tail.length();
  
  client.println("POST " + path + " HTTP/1.1");
  client.println("Host: " + host);
  client.println("Content-Length: " + String(totalLen));
  client.println("Content-Type: multipart/form-data; boundary=" + boundary);
  client.println();
  client.print(head);
  
  uint8_t *fbBuf = fb->buf;
  size_t fbLen = fb->len;
  size_t bufferSize = 1024;
  for (size_t i = 0; i < fbLen; i += bufferSize) {
    size_t remaining = fbLen - i;
    client.write(fbBuf + i, (remaining < bufferSize) ? remaining : bufferSize);
    yield();
  }
  
  client.print(tail);
  
  String response = "";
  long timeout = millis();
  while (client.connected() && millis() - timeout < 10000) {
    if (client.available()) {
      String line = client.readStringUntil('\n');
      if (line == "\r") {
        response = client.readString();
        break;
      }
    }
    yield();
  }
  client.stop();
  return response;
}

void sendPhotoToVK(camera_fb_t * fb, String messageText) {
  esp_task_wdt_delete(NULL);
  
  Serial.println("📤 Sending to VK...");
  
  String uploadUrl = getUploadServer();
  if (uploadUrl == "") {
    Serial.println("❌ VK: No upload URL");
    esp_task_wdt_add(NULL);
    return;
  }
  
  String uploadResp = uploadPhotoToVkServer(uploadUrl, fb);
  StaticJsonDocument<1024> docUpload;
  deserializeJson(docUpload, uploadResp);
  
  if (!docUpload.containsKey("server")) {
    Serial.println("❌ VK: Upload failed");
    esp_task_wdt_add(NULL);
    return;
  }
  
  int server = docUpload["server"];
  String photoStr = docUpload["photo"];
  String hash = docUpload["hash"];
  
  HTTPClient http;
  WiFiClientSecure client;
  client.setInsecure();
  
  String saveUrl = "https://api.vk.com/method/photos.saveMessagesPhoto";
  String postData = "photo=" + photoStr + "&server=" + String(server) + "&hash=" + hash + "&access_token=" + token + "&v=" + vk_version;
  
  if (http.begin(client, saveUrl)) {
    http.addHeader("Content-Type", "application/x-www-form-urlencoded");
    http.POST(postData);
    String savedPhotoJson = http.getString();
    http.end();
    
    StaticJsonDocument<2048> docSave;
    deserializeJson(docSave, savedPhotoJson);
    
    if (docSave.containsKey("response")) {
      long owner_id = docSave["response"][0]["owner_id"];
      long media_id = docSave["response"][0]["id"];
      String attachment = "photo" + String(owner_id) + "_" + String(media_id);
      
      String msgUrl = "https://api.vk.com/method/messages.send";
      String msgData = "peer_id=" + myId + "&random_id=" + String(millis()) + "&message=" + messageText + "&attachment=" + attachment + "&access_token=" + token + "&v=" + vk_version;
      
      if (http.begin(client, msgUrl)) {
        http.addHeader("Content-Type", "application/x-www-form-urlencoded");
        http.POST(msgData);
        Serial.println("✅ VK Sent");
        http.end();
      }
    }
  }
  
  esp_task_wdt_add(NULL);
}

// ================= INIT =================
bool initCamera() {
  camera_config_t config;
  config.ledc_channel = LEDC_CHANNEL_0;
  config.ledc_timer = LEDC_TIMER_0;
  config.pin_d0 = Y2_GPIO_NUM;
  config.pin_d1 = Y3_GPIO_NUM;
  config.pin_d2 = Y4_GPIO_NUM;
  config.pin_d3 = Y5_GPIO_NUM;
  config.pin_d4 = Y6_GPIO_NUM;
  config.pin_d5 = Y7_GPIO_NUM;
  config.pin_d6 = Y8_GPIO_NUM;
  config.pin_d7 = Y9_GPIO_NUM;
  config.pin_xclk = XCLK_GPIO_NUM;
  config.pin_pclk = PCLK_GPIO_NUM;
  config.pin_vsync = VSYNC_GPIO_NUM;
  config.pin_href = HREF_GPIO_NUM;
  config.pin_sccb_sda = SIOD_GPIO_NUM;
  config.pin_sccb_scl = SIOC_GPIO_NUM;
  config.pin_pwdn = PWDN_GPIO_NUM;
  config.pin_reset = RESET_GPIO_NUM;
  config.xclk_freq_hz = 20000000;
  config.frame_size = FRAMESIZE_VGA;
  config.pixel_format = PIXFORMAT_JPEG;
  config.grab_mode = CAMERA_GRAB_LATEST;
  config.fb_location = CAMERA_FB_IN_PSRAM;
  config.jpeg_quality = 15;
  config.fb_count = 2;
  
  esp_err_t err = esp_camera_init(&config);
  return (err == ESP_OK);
}

void processDetection() {
  if (!snapshot_buf) {
    Serial.println("❌ No snapshot buffer!");
    return;
  }
  
  Serial.println("🤖 AI Detection started...");
  esp_task_wdt_reset();
  
  camera_fb_t * fb = esp_camera_fb_get();
  if (!fb) {
    Serial.println("❌ Detection: No frame");
    return;
  }
  
  bool converted = fmt2rgb888(fb->buf, fb->len, PIXFORMAT_JPEG, snapshot_buf);
  if (converted) {
    esp_task_wdt_reset();
    
    ei::image::processing::crop_and_interpolate_rgb888(
      snapshot_buf, 640, 480, snapshot_buf,
      EI_CLASSIFIER_INPUT_WIDTH, EI_CLASSIFIER_INPUT_HEIGHT);
    
    ei::signal_t signal;
    signal.total_length = EI_CLASSIFIER_INPUT_WIDTH * EI_CLASSIFIER_INPUT_HEIGHT;
    signal.get_data = &ei_camera_get_data;
    
    ei_impulse_result_t result = { 0 };
    EI_IMPULSE_ERROR err = run_classifier(&signal, &result, false);
    
    esp_task_wdt_reset();
    
    if (err == EI_IMPULSE_OK) {
        float personProb = 0.0;
        for (size_t ix = 0; ix < EI_CLASSIFIER_LABEL_COUNT; ix++) {
            if (String(result.classification[ix].label) == "person") {
                personProb = result.classification[ix].value;
            }
        }
        
        Serial.printf("🤖 Person: %.2f%%\n", personProb * 100);
        
        String type = (personProb > 0.6) ? "PERSON" : "MOTION";
        publishDetectionInfo(personProb, type);
        delay(100);
        publishSnapshot(fb);
        
        String msg = (personProb > 0.6) ? "🚨 Person detected!" : "📸 Motion detected";
        sendPhotoToVK(fb, msg);
    } else {
      Serial.println("❌ AI failed");
    }
  }
  
  esp_camera_fb_return(fb);
  Serial.println("✅ Detection complete");
}

void setup() {
  Serial.begin(115200);
  delay(500);
  Serial.println("\n\n🚀 ESP32-S3-CAM Starting...");
  
  // 🔥 НОВОЕ: Генерация уникального Client ID из MAC адреса
  uint8_t mac[6];
  WiFi.macAddress(mac);
  uniqueClientId = "esp32s3_cam_";
  for (int i = 0; i < 6; i++) {
    uniqueClientId += String(mac[i], HEX);
  }
  Serial.printf("🔑 Client ID: %s\n", uniqueClientId.c_str());
  
  // Watchdog
  esp_task_wdt_init(90, true);
  esp_task_wdt_add(NULL);
  Serial.println("✅ Watchdog: 90s");
  
  // WiFi
  WiFi.mode(WIFI_STA);
  WiFi.setSleep(false);
  WiFi.setAutoReconnect(true);
  WiFi.persistent(true);
  
  WiFi.begin(ssid, password);
  Serial.print("📡 WiFi");
  
  int attempts = 0;
  while (WiFi.status() != WL_CONNECTED && attempts < 30) {
    delay(500);
    Serial.print(".");
    attempts++;
    esp_task_wdt_reset();
  }
  
  if (WiFi.status() != WL_CONNECTED) {
    Serial.println("\n❌ WiFi FAILED! Restarting...");
    ESP.restart();
  }
  
  Serial.println(" ✅");
  Serial.printf("📶 IP: %s\n", WiFi.localIP().toString().c_str());
  Serial.printf("📶 RSSI: %d dBm\n", WiFi.RSSI());
  
  // Camera
  if (!initCamera()) {
    Serial.println("❌ Camera FAILED!");
    while(1) { 
      esp_task_wdt_reset();
      delay(1000);
    }
  }
  Serial.println("✅ Camera OK");
  
  // PSRAM
  snapshot_buf = (uint8_t*)ps_malloc(640 * 480 * 3);
  if (!snapshot_buf) {
    Serial.println("❌ PSRAM allocation failed!");
    while(1) {
      esp_task_wdt_reset();
      delay(1000);
    }
  }
  Serial.println("✅ PSRAM: 921KB allocated");
  
  // PIR
  pinMode(triggerPin, INPUT_PULLDOWN);
  
  // MQTT
  mqttClient.setServer(mqtt_server, mqtt_port);
  mqttClient.setCallback(mqttCallback);
  mqttClient.setBufferSize(16384);
  mqttClient.setKeepAlive(60);
  mqttClient.setSocketTimeout(30);
  
  Serial.println("✅ Setup complete!\n");
  esp_task_wdt_reset();
}

void handlePIRStateMachine() {
  if (!alarmEnabled) {
    pirState = PIR_IDLE;
    return;
  }
  
  unsigned long elapsed = millis() - pirStateStartTime;
  
  switch (pirState) {
    case PIR_IDLE:
      if (digitalRead(triggerPin) == HIGH && millis() - lastPhotoTime > PHOTO_COOLDOWN) {
        lastPhotoTime = millis();
        pirState = PIR_DETECTED;
        pirStateStartTime = millis();
        pirStatus = "DETECTED";
        publishStatus();
        Serial.println("🚶 PIR: DETECTED");
      }
      break;
      
    case PIR_DETECTED:
      if (elapsed >= 3000) {
        pirState = PIR_ALERT;
        pirStateStartTime = millis();
        pirStatus = "ALERT";
        publishStatus();
        Serial.println("🚨 PIR: ALERT");
        processDetection();
      }
      break;
      
    case PIR_ALERT:
      if (elapsed >= 5000) {
        pirState = PIR_COOLDOWN;
        pirStateStartTime = millis();
        pirStatus = "NONE";
        publishStatus();
        Serial.println("✅ PIR: Cooldown");
      }
      break;
      
    case PIR_COOLDOWN:
      pirState = PIR_IDLE;
      break;
  }
}

void loop() {
  esp_task_wdt_reset();
  
  // WiFi check
  if (millis() - lastWifiCheck >= WIFI_CHECK_INTERVAL) {
    lastWifiCheck = millis();
    if (WiFi.status() != WL_CONNECTED) {
      Serial.println("⚠️ WiFi lost! Reconnecting...");
      mqttConnected = false;
      streamingEnabled = false;
      WiFi.disconnect();
      WiFi.begin(ssid, password);
      
      int attempts = 0;
      while (WiFi.status() != WL_CONNECTED && attempts < 30) {
        delay(500);
        Serial.print(".");
        attempts++;
        esp_task_wdt_reset();
      }
      
      if (WiFi.status() == WL_CONNECTED) {
        Serial.println(" ✅");
      } else {
        Serial.println("\n❌ WiFi failed! Restarting...");
        ESP.restart();
      }
    }
  }
  
  // MQTT
  if (!mqttClient.connected()) {
    mqttConnected = false;
    streamingEnabled = false;
    mqttReconnect();
  } else {
    mqttConnected = true;
  }
  mqttClient.loop();
  
  // 🔥 НОВОЕ: Heartbeat для Android
  if (mqttConnected && millis() - lastHeartbeat >= HEARTBEAT_INTERVAL) {
    publishStatus();  // Обновляем lastUpdate каждые 5 секунд
    lastHeartbeat = millis();
  }
  
  // PIR
  handlePIRStateMachine();
  
  // Streaming
  if (streamingEnabled && mqttConnected && millis() - lastStreamTime >= STREAM_INTERVAL) {
      camera_fb_t * fb = esp_camera_fb_get();
      if (fb) {
          publishVideoFrame(fb);
          esp_camera_fb_return(fb);
          lastStreamTime = millis();
      } else {
        Serial.println("⚠️ Frame failed!");
        delay(100);
      }
  }
  
  // Status update (уже есть в heartbeat, но оставляем как backup)
  if (millis() - lastStatusUpdate >= 10000) {
      if (mqttConnected) publishStatus();
      lastStatusUpdate = millis();
  }
  
  yield();
}