#!/usr/bin/env python3
"""
🔥 ESP32-S3-CAM EMULATOR v9.3 - БЕЗ ОТПРАВКИ OFFLINE!
LWT делает ВСЮ работу!
"""

import paho.mqtt.client as mqtt
import cv2
import time
import json
import threading
import os
import random

# ================= КОНФИГУРАЦИЯ =================
MQTT_SERVER = "srv2.clusterfly.ru"
MQTT_PORT = 9991
MQTT_USER = "user_4bd2b1f5"
MQTT_PASS = "FnoQuMvkcV1ej"

TOPIC_STATUS = f"{MQTT_USER}/status"
TOPIC_COMMANDS = f"{MQTT_USER}/commands"
TOPIC_STREAM = f"{MQTT_USER}/camera/stream"
TOPIC_SNAPSHOT = f"{MQTT_USER}/camera/alarm_snapshot"
TOPIC_DETECTION = f"{MQTT_USER}/camera/detection_info"

FRAME_WIDTH = 640
FRAME_HEIGHT = 480
JPEG_QUALITY = 75
CHUNK_SIZE = 16384
FPS_TARGET = 10

# ================= СОСТОЯНИЕ УСТРОЙСТВА =================
device_state = {
    "systemLocked": False,
    "alarmEnabled": False,
    "pirStatus": "NONE",
    "soundLevel": 25,
    "streaming": False,
    "device": "ESP32-S3-CAM",
    "wifi_rssi": -42,
    "free_heap": 180000,
    "fps": 0,
    "frames": 0,
    "lastUpdate": 0
}

streaming = False
client = None
cap = None
running = True
auto_pir = False
frame_counter = 0
connection_stable = False

# ================= ЦВЕТА =================
class C:
    G = '\033[92m'; R = '\033[91m'; Y = '\033[93m'
    C = '\033[96m'; B = '\033[1m'; E = '\033[0m'
    M = '\033[95m'

def log(msg, color=C.E):
    timestamp = time.strftime("%H:%M:%S")
    print(f"{color}[{timestamp}] {msg}{C.E}")

# ================= ВИДЕО =================
def get_frame():
    if not cap or not cap.isOpened():
        return None
    
    ret, frame = cap.read()
    if not ret:
        if cap.get(cv2.CAP_PROP_POS_FRAMES) > 0:
            cap.set(cv2.CAP_PROP_POS_FRAMES, 0)
            ret, frame = cap.read()
        if not ret:
            return None
    
    if frame.shape[:2] != (FRAME_HEIGHT, FRAME_WIDTH):
        frame = cv2.resize(frame, (FRAME_WIDTH, FRAME_HEIGHT))
    
    encode_params = [int(cv2.IMWRITE_JPEG_QUALITY), JPEG_QUALITY, int(cv2.IMWRITE_JPEG_OPTIMIZE), 1]
    success, encoded = cv2.imencode('.jpg', frame, encode_params)
    return encoded.tobytes() if success else None

def send_frame_chunked(frame_data):
    global frame_counter
    try:
        client.publish(TOPIC_STREAM, b"START", qos=0)
        offset = 0
        while offset < len(frame_data):
            chunk = frame_data[offset:offset + CHUNK_SIZE]
            client.publish(TOPIC_STREAM, chunk, qos=0)
            offset += CHUNK_SIZE
            time.sleep(0.001)
        client.publish(TOPIC_STREAM, b"END", qos=0)
        frame_counter += 1
        return True
    except Exception as e:
        log(f"❌ Chunk: {e}", C.R)
        return False

def send_frame_direct(frame_data):
    try:
        client.publish(TOPIC_SNAPSHOT, frame_data, qos=1)
        return True
    except Exception as e:
        log(f"❌ Send: {e}", C.R)
        return False

def send_detection_info(person_prob=0.0, detection_type="MOTION"):
    try:
        detection_data = {
            "timestamp": int(time.time() * 1000),
            "personProbability": int(person_prob * 100),
            "detectionType": detection_type,
            "confidence": person_prob
        }
        client.publish(TOPIC_DETECTION, json.dumps(detection_data), qos=1)
        log(f"📊 Detection: {detection_type} ({int(person_prob*100)}%)", C.M)
        return True
    except Exception as e:
        log(f"❌ Detection: {e}", C.R)
        return False

# ================= ПУБЛИКАЦИЯ СТАТУСА =================
def publish_status():
    if not connection_stable:
        return
    
    device_state["lastUpdate"] = int(time.time() * 1000)
    device_state["soundLevel"] = 20 + random.randint(0, 30)
    device_state["wifi_rssi"] = -40 - random.randint(0, 20)
    device_state["frames"] = frame_counter
    
    status_json = json.dumps(device_state, indent=2)
    client.publish(TOPIC_STATUS, status_json, qos=1, retain=False)
    
    log("=" * 60, C.B)
    log("📤 STATUS:", C.B)
    log(f"   Armed: {'🔒 YES' if device_state['systemLocked'] else '🔓 NO'}", 
        C.R if device_state['systemLocked'] else C.G)
    log(f"   Stream: {'📹 YES' if device_state['streaming'] else '📴 NO'}", C.C)
    log(f"   Frames: {frame_counter}", C.C)
    log("=" * 60, C.B)

# ================= MQTT CALLBACKS =================
def on_connect(client, userdata, flags, rc, properties=None):
    global connection_stable
    if rc == 0:
        connection_stable = True
        log(f"✅ CONNECTED: {MQTT_SERVER}:{MQTT_PORT}", C.G)
        client.subscribe(TOPIC_COMMANDS, qos=0)
        log(f"📡 Subscribed: {TOPIC_COMMANDS}", C.C)
        time.sleep(0.5)
        publish_status()
    else:
        connection_stable = False
        log(f"❌ Connection failed: {rc}", C.R)

def on_message(client, userdata, msg):
    global streaming, device_state, auto_pir
    
    try:
        command = msg.payload.decode('utf-8').strip()
        log(f"\n📥 COMMAND: {command}", C.Y)
        
        if command == "ARM":
            device_state["systemLocked"] = True
            device_state["alarmEnabled"] = True
            log("🔒 ARMED", C.R)
            publish_status()
            
        elif command == "DISARM":
            device_state["systemLocked"] = False
            device_state["alarmEnabled"] = False
            device_state["pirStatus"] = "NONE"
            auto_pir = False
            log("🔓 DISARMED", C.G)
            publish_status()
            
        elif command == "STATUS":
            log("📊 Status request", C.C)
            publish_status()
            
        elif command in ["STREAM_ON", "STREAM_START"]:
            streaming = True
            device_state["streaming"] = True
            log("📹 STREAMING ON", C.G)
            publish_status()
            
        elif command in ["STREAM_OFF", "STREAM_STOP"]:
            streaming = False
            device_state["streaming"] = False
            log("📴 STREAMING OFF", C.Y)
            publish_status()
            
        elif command == "STREAM_PREVIEW":
            log("📸 PREVIEW requested...", C.C)
            frame = get_frame()
            if frame:
                person_prob = random.uniform(0.3, 0.95)
                det_type = "PERSON" if person_prob > 0.6 else "MOTION"
                send_detection_info(person_prob, det_type)
                time.sleep(0.1)
                if send_frame_direct(frame):
                    log(f"✅ Preview: {len(frame)} bytes", C.G)
            else:
                log("❌ No frame", C.R)
        
        else:
            log(f"⚠️ Unknown: {command}", C.Y)
            
    except Exception as e:
        log(f"❌ Error: {e}", C.R)

def on_disconnect(client, userdata, rc, properties=None):
    global connection_stable
    connection_stable = False
    if rc != 0:
        log(f"⚠️ DISCONNECT: {rc}", C.Y)
    else:
        log(f"✅ Disconnected", C.G)

# ================= ПОТОКИ =================
def stream_loop():
    global streaming, running
    fps_count = 0
    fps_start = time.time()
    
    while running:
        if streaming and connection_stable:
            frame = get_frame()
            if frame:
                if send_frame_chunked(frame):
                    fps_count += 1
                    elapsed = time.time() - fps_start
                    if elapsed >= 1.0:
                        device_state["fps"] = fps_count
                        log(f"📹 {fps_count} FPS | Total: {frame_counter}", C.C)
                        fps_count = 0
                        fps_start = time.time()
                time.sleep(1.0 / FPS_TARGET)
            else:
                time.sleep(0.5)
        else:
            time.sleep(0.5)

def auto_status_loop():
    global running
    while running:
        time.sleep(10)
        if running and connection_stable:
            publish_status()

def auto_pir_loop():
    global running, auto_pir, device_state
    
    while running:
        time.sleep(random.randint(15, 35))
        
        if running and auto_pir and device_state["alarmEnabled"] and connection_stable:
            device_state["pirStatus"] = "DETECTED"
            log("🚶 PIR: Motion!", C.M)
            publish_status()
            time.sleep(3)
            
            if running and device_state["alarmEnabled"]:
                device_state["pirStatus"] = "ALERT"
                log("🚨 PIR: ALERT!", C.R)
                publish_status()
                
                frame = get_frame()
                if frame:
                    person_prob = random.uniform(0.3, 0.95)
                    det_type = "PERSON" if person_prob > 0.6 else "MOTION"
                    log(f"🤖 AI: {det_type} ({int(person_prob*100)}%)", C.M)
                    send_detection_info(person_prob, det_type)
                    time.sleep(0.1)
                    send_frame_direct(frame)
                    log(f"✅ Snapshot", C.G)
                
                time.sleep(5)
                device_state["pirStatus"] = "NONE"
                publish_status()

# ================= MAIN =================
def main():
    global client, cap, running, auto_pir, connection_stable
    
    print("\n" + "=" * 60)
    log("🚀 ESP32 EMULATOR v9.3", C.B)
    print("=" * 60)
    
    print(f"\n{C.C}📹 Video:{C.E}")
    print("  [1] Webcam")
    print("  [2] File")
    
    choice = input(f"\n{C.Y}Choice (1/2): {C.E}").strip() or "1"
    
    if choice == "2":
        path = input(f"{C.Y}Path: {C.E}").strip()
        if not os.path.exists(path):
            log(f"❌ Not found: {path}", C.R)
            return
        cap = cv2.VideoCapture(path)
        log(f"📹 File: {os.path.basename(path)}", C.C)
    else:
        cap = cv2.VideoCapture(0)
        log("📹 Webcam", C.C)
    
    if not cap.isOpened():
        log("❌ Video failed!", C.R)
        return
    
    cap.set(cv2.CAP_PROP_FRAME_WIDTH, FRAME_WIDTH)
    cap.set(cv2.CAP_PROP_FRAME_HEIGHT, FRAME_HEIGHT)
    cap.set(cv2.CAP_PROP_BUFFERSIZE, 1)
    log(f"✅ Video OK", C.G)
    
    log(f"\n🔌 MQTT: {MQTT_SERVER}:{MQTT_PORT}", C.C)
    
    # 🔥 LWT СООБЩЕНИЕ
    offline_lwt = json.dumps({
        "systemLocked": False,
        "alarmEnabled": False,
        "pirStatus": "NONE",
        "soundLevel": 0,
        "streaming": False,
        "device": "ESP32-S3-CAM",
        "wifi_rssi": 0,
        "free_heap": 0,
        "fps": 0,
        "frames": 0,
        "lastUpdate": 0
    })
    
    try:
        from paho.mqtt.client import CallbackAPIVersion
        client = mqtt.Client(
            callback_api_version=CallbackAPIVersion.VERSION2,
            client_id=f"esp32s3_emu_{int(time.time())}"
        )
    except:
        client = mqtt.Client(client_id=f"esp32s3_emu_{int(time.time())}")
    
    client.username_pw_set(MQTT_USER, MQTT_PASS)
    
    # 🔥 LWT БЕЗ RETAIN!
    client.will_set(topic=TOPIC_STATUS, payload=offline_lwt, qos=1, retain=False)
    log("✅ LWT configured", C.G)
    
    client.on_connect = on_connect
    client.on_message = on_message
    client.on_disconnect = on_disconnect
    
    threading.Thread(target=stream_loop, daemon=True).start()
    threading.Thread(target=auto_status_loop, daemon=True).start()
    threading.Thread(target=auto_pir_loop, daemon=True).start()
    
    try:
        log("⏳ Connecting...", C.Y)
        client.connect(MQTT_SERVER, MQTT_PORT, keepalive=60)
        client.loop_start()
        
        log("\n✅ STARTED!\n", C.G)
        print("=" * 60)
        log("🎮 COMMANDS:", C.B)
        print("=" * 60)
        print("  1 - ARM")
        print("  2 - DISARM")
        print("  3 - STATUS")
        print("  4 - STREAM")
        print("  5 - Auto-PIR")
        print("  6 - PREVIEW")
        print("  q - EXIT")
        print("=" * 60 + "\n")
        
        while running:
            try:
                cmd = input(f"{C.Y}💡: {C.E}").strip().lower()
                
                if cmd == 'q':
                    running = False
                    break
                elif cmd == '1':
                    device_state["alarmEnabled"] = True
                    device_state["systemLocked"] = True
                    auto_pir = True
                    publish_status()
                elif cmd == '2':
                    device_state["alarmEnabled"] = False
                    device_state["systemLocked"] = False
                    device_state["pirStatus"] = "NONE"
                    auto_pir = False
                    publish_status()
                elif cmd == '3':
                    publish_status()
                elif cmd == '4':
                    streaming = not streaming
                    device_state["streaming"] = streaming
                    publish_status()
                elif cmd == '5':
                    auto_pir = not auto_pir
                    log(f"🔄 Auto-PIR: {'ON' if auto_pir else 'OFF'}", C.M)
                elif cmd == '6':
                    frame = get_frame()
                    if frame and connection_stable:
                        person_prob = random.uniform(0.3, 0.95)
                        det_type = "PERSON" if person_prob > 0.6 else "MOTION"
                        send_detection_info(person_prob, det_type)
                        time.sleep(0.1)
                        send_frame_direct(frame)
                        log(f"📸 Sent", C.G)
                    
            except KeyboardInterrupt:
                running = False
                break
                
    except Exception as e:
        log(f"\n❌ ERROR: {e}", C.R)
        
    finally:
        running = False
        log("\n🛑 STOPPING...", C.Y)
        
        # 🔥 БЕЗ ОТПРАВКИ! LWT СДЕЛАЕТ ВСЁ!
        
        if client:
            try:
                client.loop_stop()
                client.disconnect()
            except:
                pass
        
        if cap:
            cap.release()
        
        log("✅ STOPPED\n", C.G)

if __name__ == "__main__":
    try:
        main()
    except ImportError as e:
        print(f"\n❌ Install: pip3 install opencv-python paho-mqtt")
