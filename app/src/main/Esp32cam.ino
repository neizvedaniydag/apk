#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <HTTPClient.h>
#include "esp_camera.h"
#include <ArduinoJson.h>
#include <PubSubClient.h>

// === ПОДКЛЮЧЕНИЕ БИБЛИОТЕКИ EDGE IMPULSE ===
#include <Person_Detector_v4_inferencing.h>
#include "edge-impulse-sdk/dsp/image/image.hpp"

// ================= НАСТРОЙКИ ПОЛЬЗОВАТЕЛЯ =================
const char* ssid = "NOVA_8BA8";
const char* password = "mouth3224";

// VK
String token = "vk1.a._DP_DYqg8A3dNkYa-gPVCCDlWIVzix86IlxgrkoayxogZ38WtaZd9sHtqdsw_cUecABRLkBFbjUvqTUYwg6O0JjSPOAJmBX5gKTRWVL1ZTd-ngkYtI_YS7MAU55bbjBMy-9Ziqv1EorxAiRZKggR-PEV-3SFm5Wsimg-rGIrDGWWUAiOZKG0u49MZO793LZFkE0H8IRUrU23NPXc95Brlg";
String myId = "891024947";
const String vk_version = "5.131";

// ================= MQTT CLUSTERFLY НАСТРОЙКИ (Как в приложении) =================
const char* mqtt_server = "srv2.clusterfly.ru";
const int mqtt_port = 9991;
const char* mqtt_user = "user_4bd2b1f5";
const char* mqtt_pass = "FnoQuMvkcV1ej";
const char* mqtt_client_id = "user_4bd2b1f5_camera_simulator"; // ИЗМЕНЕНО

// Топики (важно, чтобы совпадали с приложением)
const char* topic_video_stream = "user_4bd2b1f5/camera/stream"; // ИЗМЕНЕНО
const char* topic_camera_status = "user_4bd2b1f5/status"; // ИЗМЕНЕНО
const char* topic_camera_fps = "user_4bd2b1f5/camera/fps";
const char* topic_camera_control = "user_4bd2b1f5/camera/control";
const char* topic_alarm_snapshot = "user_4bd2b1f5/camera/alarm_snapshot";

// ================= ИНТЕГРАЦИЯ =================
const int triggerPin = 2;
const unsigned long PHOTO_COOLDOWN = 10000;
unsigned long lastPhotoTime = 0;

// ================= MQTT VIDEO STREAMING =================
WiFiClient mqttWifiClient;
PubSubClient mqttClient(mqttWifiClient);

unsigned long lastMqttReconnect = 0;
unsigned long lastStreamTime = 0;
unsigned long lastFpsUpdate = 0;

const unsigned long STREAM_INTERVAL = 100; // 10 FPS
int frameCounter = 0;
int fpsCounter = 0;
bool streamingEnabled = true;
unsigned long fpsStartTime = 0;

// Буфер для нейросети
uint8_t *snapshot_buf;

// ================= НАСТРОЙКИ КАМЕРЫ (ESP32-S3) =================
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

// ================= ФУНКЦИИ НЕЙРОСЕТИ =================
static int ei_camera_get_data(size_t offset, size_t length, float *out_ptr) {
    size_t pixel_ix = offset * 3;
    size_t pixels_left = length;
    size_t out_ptr_ix = 0;

    while (pixels_left != 0) {
        out_ptr[out_ptr_ix] = (snapshot_buf[pixel_ix] << 16) + (snapshot_buf[pixel_ix + 1] << 8) + snapshot_buf[pixel_ix + 2];
        out_ptr_ix++;
        pixel_ix += 3;
        pixels_left--;
    }
    return 0;
}

// ================= MQTT CALLBACK =================
void mqttCallback(char* topic, byte* payload, unsigned int length) {
    char message[length + 1];
    memcpy(message, payload, length);
    message[length] = '\0';
    String msg = String(message);

    msg.trim();
    msg.toUpperCase();

    Serial.print("[MQTT CAM] Command: ");
    Serial.println(msg);

    if (msg == "STREAM_ON") {
        streamingEnabled = true;
        Serial.println("[MQTT CAM] Video streaming enabled");
    } else if (msg == "STREAM_OFF") {
        streamingEnabled = false;
        Serial.println("[MQTT CAM] Video streaming disabled");
    } else if (msg == "STREAM_PREVIEW") {
        // Делаем снимок и отправляем как превью в alarm_snapshot
        Serial.println("[MQTT CAM] Preview requested");
        camera_fb_t * fb = esp_camera_fb_get();
        if (fb) {
            sendMqttSnapshot(fb);
            esp_camera_fb_return(fb);
            Serial.println("[MQTT CAM] Preview sent");
        } else {
            Serial.println("[MQTT CAM] Preview capture failed");
        }
    } else if (msg == "STATUS") {
        publishCameraStatus();
    }
}

void publishCameraStatus() {
    if (!mqttClient.connected()) return;

    StaticJsonDocument<256> doc;
    doc["streaming"] = streamingEnabled;
    doc["fps"] = fpsCounter;
    doc["wifi_rssi"] = WiFi.RSSI();

    char buffer[256];
    serializeJson(doc, buffer);
    mqttClient.publish(topic_camera_status, buffer, false);
}

void mqttReconnect() {
    if (millis() - lastMqttReconnect < 5000) return;
    lastMqttReconnect = millis();

    if (mqttClient.connected()) return;

    Serial.print("[MQTT CAM] Connecting to broker...");

    if (mqttClient.connect(mqtt_client_id, mqtt_user, mqtt_pass)) {
        Serial.println("Connected!");
        mqttClient.subscribe(topic_camera_control, 0);
        publishCameraStatus();
    } else {
        Serial.print("Failed, rc=");
        Serial.println(mqttClient.state());
    }
}

// ================= ОТПРАВКА ФОТО В ANDROID ЧЕРЕЗ MQTT =================
void sendMqttSnapshot(camera_fb_t * fb) {
    if (!mqttClient.connected()) return;

    Serial.println("[MQTT CAM] Sending snapshot to Android App...");

    // MQTT имеет лимит на размер сообщения. Обычно это 128KB или 256KB на брокере.
    // Если картинка большая, лучше уменьшить качество.

    // Публикуем изображение целиком (blob)
    // Важно: для больших фото лучше использовать HTTP, но для теста через MQTT пробуем так:
    const size_t chunkSize = 4096; // Шлем кусками

    // Начинаем транзакцию (если брокер поддерживает, но для простоты шлем одним куском если влезает)
    // HiveMQ Cloud имеет лимит 64KB на сообщение в Free tier? Проверим.
    // Если не влезает - приложение не получит.

    // В данном примере я использую beginPublish для потоковой отправки
    if (mqttClient.beginPublish(topic_alarm_snapshot, fb->len, false)) {
        size_t offset = 0;
        while (offset < fb->len) {
            size_t remaining = fb->len - offset;
            size_t toSend = (remaining < chunkSize) ? remaining : chunkSize;
            mqttClient.write(fb->buf + offset, toSend);
            offset += toSend;
        }
        mqttClient.endPublish();
        Serial.println("[MQTT CAM] Snapshot sent successfully!");
    } else {
        Serial.println("[MQTT CAM] Failed to start snapshot publish (Too large?)");
    }
}

void publishVideoFrame(camera_fb_t * fb) {
    if (!mqttClient.connected() || !streamingEnabled) return;
    if (fb->len > 100000) return; // Пропуск слишком больших кадров для стрима

    mqttClient.publish(topic_video_stream, fb->buf, fb->len, false);
    frameCounter++;
    fpsCounter++;

    if (millis() - fpsStartTime >= 1000) {
        fpsCounter = 0;
        fpsStartTime = millis();
    }
}

// ================= VK ФУНКЦИИ (ОСТАВИЛ БЕЗ ИЗМЕНЕНИЙ) =================
// ... (Сюда вставь функции VK из твоего кода выше: vkRequest, getUploadServer, uploadPhotoToVkServer, sendPhotoToVK) ...
// Я их сократил здесь, чтобы не спамить, но они должны быть в скетче.
// СКОПИРУЙ ИХ ИЗ СВОЕГО ПРОШЛОГО СКЕТЧА ЕСЛИ ИХ ТУТ НЕТ.
// (Ниже я продублирую функции VK полностью, чтобы код был готовым)

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
    DynamicJsonDocument doc(2048);
    deserializeJson(doc, response);

    if (doc.containsKey("response")) {
        return doc["response"]["upload_url"].as<String>();
    }
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

    String boundary = "------------------------ESP32Boundary";
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
    }
    client.stop();
    return response;
}

void sendPhotoToVK(camera_fb_t * fb, String messageText) {
    String uploadUrl = getUploadServer();
    if (uploadUrl == "") return;

    String uploadResp = uploadPhotoToVkServer(uploadUrl, fb);
    DynamicJsonDocument docUpload(1024);
    deserializeJson(docUpload, uploadResp);

    if (!docUpload.containsKey("server")) return;
    int server = docUpload["server"];
    String photoStr = docUpload["photo"];
    String hash = docUpload["hash"];

    HTTPClient http;
    WiFiClientSecure client;
    client.setInsecure();

    String saveUrl = "https://api.vk.com/method/photos.saveMessagesPhoto";
    String postData = "photo=" + photoStr + "&server=" + String(server) + "&hash=" + hash + "&access_token=" + token + "&v=" + vk_version;
    String savedPhotoJson = "";

    if (http.begin(client, saveUrl)) {
        http.addHeader("Content-Type", "application/x-www-form-urlencoded");
        http.POST(postData);
        savedPhotoJson = http.getString();
        http.end();
    }

    DynamicJsonDocument docSave(2048);
    deserializeJson(docSave, savedPhotoJson);

    if (!docSave.containsKey("response")) return;

    long owner_id = docSave["response"][0]["owner_id"];
    long media_id = docSave["response"][0]["id"];
    String attachment = "photo" + String(owner_id) + "_" + String(media_id);

    String msgUrl = "https://api.vk.com/method/messages.send";
    String msgData = "peer_id=" + myId + "&random_id=" + String(millis()) + "&message=" + messageText + "&attachment=" + attachment + "&access_token=" + token + "&v=" + vk_version;

    if (http.begin(client, msgUrl)) {
        http.addHeader("Content-Type", "application/x-www-form-urlencoded");
        http.POST(msgData);
        Serial.println("Сообщение VK отправлено: " + messageText);
        http.end();
    }
}

// ================= ГЛАВНАЯ ЛОГИКА =================
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
    config.xclk_freq_hz = 10000000;
    // ВАЖНО: Для нейросети и MQTT лучше использовать разрешение поменьше
    config.frame_size = FRAMESIZE_VGA; // 640x480
    config.pixel_format = PIXFORMAT_JPEG;
    config.grab_mode = CAMERA_GRAB_WHEN_EMPTY;
    config.fb_location = CAMERA_FB_IN_PSRAM;
    config.jpeg_quality = 12; // 10-15 оптимально
    config.fb_count = 2;

    if(esp_camera_init(&config) != ESP_OK) {
        Serial.println("Camera Init Failed");
        return false;
    }
    return true;
}

void processDetection() {
    Serial.println("Processing Alarm Photo...");
    camera_fb_t * fb = esp_camera_fb_get();
    if (!fb) { Serial.println("Capture failed"); return; }

    // 1. Конвертация для нейросети
    bool converted = fmt2rgb888(fb->buf, fb->len, PIXFORMAT_JPEG, snapshot_buf);
    if (!converted) {
        Serial.println("Conversion failed");
        esp_camera_fb_return(fb);
        return;
    }

    ei::image::processing::crop_and_interpolate_rgb888(
        snapshot_buf, 640, 480, snapshot_buf, EI_CLASSIFIER_INPUT_WIDTH, EI_CLASSIFIER_INPUT_HEIGHT);

    ei::signal_t signal;
    signal.total_length = EI_CLASSIFIER_INPUT_WIDTH * EI_CLASSIFIER_INPUT_HEIGHT;
    signal.get_data = &ei_camera_get_data;

    ei_impulse_result_t result = { 0 };
    EI_IMPULSE_ERROR err = run_classifier(&signal, &result, false);

    float personProb = 0.0;
    if (err == EI_IMPULSE_OK) {
        for (size_t ix = 0; ix < EI_CLASSIFIER_LABEL_COUNT; ix++) {
            if (String(result.classification[ix].label) == "person") {
                personProb = result.classification[ix].value;
            }
        }
        if (personProb < 0.1 && EI_CLASSIFIER_LABEL_COUNT > 1) {
            personProb = result.classification[1].value;
        }
    }

    String msg = "";
    int percent = (int)(personProb * 100);

    if (personProb > 0.60) {
        msg = "🚨 ВНИМАНИЕ: Человек! (" + String(percent) + "%)";
    } else {
        msg = "📸 Замечено движение (Человек: " + String(percent) + "%)";
    }
    Serial.println(msg);

    // === ОТПРАВКА В APP И VK ===
    sendMqttSnapshot(fb); // <--- ОТПРАВЛЯЕМ В ПРИЛОЖЕНИЕ
    sendPhotoToVK(fb, msg); // ОТПРАВЛЯЕМ В VK

    lastPhotoTime = millis();
    esp_camera_fb_return(fb);
}

void setup() {
    Serial.begin(115200);
    WiFi.begin(ssid, password);
    while (WiFi.status() != WL_CONNECTED) { delay(500); Serial.print("."); }
    Serial.println("\nWiFi Connected");

    pinMode(triggerPin, INPUT_PULLDOWN);

    if (initCamera()) Serial.println("Camera OK");
    else return;

    snapshot_buf = (uint8_t*)ps_malloc(640 * 480 * 3);
    if (!snapshot_buf) Serial.println("ERR: PSRAM Alloc Failed");

    mqttClient.setServer(mqtt_server, mqtt_port);
    mqttClient.setCallback(mqttCallback);

    // ВАЖНО: Увеличиваем буфер для приема длинных команд и отправки фото
    mqttClient.setBufferSize(32000);

    Serial.println("System Ready");
    fpsStartTime = millis();
}

void loop() {
    if (WiFi.status() != WL_CONNECTED) { WiFi.reconnect(); delay(1000); return; }

    if (!mqttClient.connected()) {
        mqttReconnect();
    } else {
        mqttClient.loop();
    }

    // ТЕСТ: Кнопка/сенсор на пине 2
    int signal = digitalRead(triggerPin);
    if (signal == HIGH) {
        if (millis() - lastPhotoTime > PHOTO_COOLDOWN) {
            processDetection();
        }
    }

    // Видео-стрим
    if (streamingEnabled && (millis() - lastStreamTime >= STREAM_INTERVAL)) {
        camera_fb_t * fb = esp_camera_fb_get();
        if (fb) {
            publishVideoFrame(fb);
            esp_camera_fb_return(fb);
            lastStreamTime = millis();
        }
    }
    delay(10);
}