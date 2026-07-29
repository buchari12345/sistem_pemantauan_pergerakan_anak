/*
 * ============================================================
 * SISTEM DETEKSI JATUH BERBASIS POSTUR (SLOW FALL)
 * ============================================================
 */

#include <Wire.h>
#include <math.h>
#include <WiFi.h>
#include <Firebase_ESP_Client.h>

#include "addons/TokenHelper.h"
#include "addons/RTDBHelper.h"

// ─────────────────────────────────────────────
//  KREDENSIAL WIFI & FIREBASE
// ─────────────────────────────────────────────
#define WIFI_SSID "muslem"
#define WIFI_PASSWORD "muslem12345"

#define FIREBASE_HOST "falldetection-45dfa-default-rtdb.asia-southeast1.firebasedatabase.app"
#define FIREBASE_AUTH "uBudlsbeiMBcbT89fb21ZggblPLptQGcxGVz7IzQ"

FirebaseData fbdo;
FirebaseAuth auth;
FirebaseConfig config;

// ─────────────────────────────────────────────
//  PIN & REGISTER MPU-6050
// ─────────────────────────────────────────────
#define SDA_PIN   8
#define SCL_PIN   9
#define MPU_ADDR  0x68

#define REG_PWR_MGMT_1   0x6B
#define REG_ACCEL_CONFIG 0x1C
#define REG_GYRO_CONFIG  0x1B
#define REG_CONFIG       0x1A
#define REG_ACCEL_XOUT_H 0x3B

// ─────────────────────────────────────────────
//  SKALA SENSOR & KALIBRASI TAHAP 1
// ─────────────────────────────────────────────
#define ACCEL_SCALE  4096.0f
#define GRAVITY      9.81f
#define RAD_TO_DEG   (180.0f / M_PI)

const float ACC_OFFSET_X  =  0.9252f;
const float ACC_OFFSET_Y  =  0.3717f;
const float ACC_OFFSET_Z  =  1.6163f;

// ─────────────────────────────────────────────
//  PARAMETER SLOW FALL & KALIBRASI TAHAP 2
// ─────────────────────────────────────────────
const float BATAS_KEMIRINGAN = 65.0;        
const unsigned long WAKTU_TOLERANSI = 3000; 

float pitchOffset = 0.0;
float rollOffset = 0.0;

bool sedangMiring = false;
unsigned long waktuMulaiMiring = 0;

bool isCooldown = false;
unsigned long cooldownStartTime = 0;
const unsigned long COOLDOWN_DURATION = 10000; 

unsigned long lastUpdate = 0;

// ─────────────────────────────────────────────
//  FUNGSI KONEKSI WIFI 
// ─────────────────────────────────────────────
void connectWiFi() {
  WiFi.disconnect(true, true);
  delay(1000); 
  WiFi.mode(WIFI_STA);

  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  Serial.print("Menghubungkan ke WiFi");

  unsigned long startAttemptTime = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - startAttemptTime < 15000) {
    Serial.print(".");
    delay(500);
  }
  
  if(WiFi.status() == WL_CONNECTED) {
    Serial.println("\n[OK] WiFi Terhubung! IP: " + WiFi.localIP().toString());
  } else {
    Serial.println("\n[GAGAL] Waktu koneksi WiFi habis.");
  }
}

// ─────────────────────────────────────────────
//  FUNGSI KIRIM DATA FIREBASE
// ─────────────────────────────────────────────
void updateFirebase(String status, float aMag) {
  if (Firebase.ready()) {
    FirebaseJson json;
    json.set("status", status);
    json.set("accel_magnitude", aMag);

    if (Firebase.RTDB.updateNode(&fbdo, "/sensor", &json)) {
      Serial.printf("[FIREBASE] %s terkirim.\n", status.c_str());
    } else {
      Serial.printf("[FIREBASE] Gagal Kirim: %s\n", fbdo.errorReason().c_str());
    }
  }
}

// ─────────────────────────────────────────────
//  FUNGSI I2C MPU6050
// ─────────────────────────────────────────────
void mpuWrite(uint8_t reg, uint8_t val) {
  Wire.beginTransmission(MPU_ADDR);
  Wire.write(reg);
  Wire.write(val);
  Wire.endTransmission();
}

bool mpuRead(float &axMs, float &ayMs, float &azMs) {
  Wire.beginTransmission(MPU_ADDR);
  Wire.write(REG_ACCEL_XOUT_H);
  if (Wire.endTransmission(false) != 0) return false;
  
  if (Wire.requestFrom((uint8_t)MPU_ADDR, (uint8_t)14, (uint8_t)true) != 14) return false;

  int16_t rax=(Wire.read()<<8)|Wire.read();
  int16_t ray=(Wire.read()<<8)|Wire.read();
  int16_t raz=(Wire.read()<<8)|Wire.read();
  
  Wire.read(); Wire.read(); 
  Wire.read(); Wire.read(); 
  Wire.read(); Wire.read(); 
  Wire.read(); Wire.read(); 

  axMs = ((float)rax / ACCEL_SCALE * GRAVITY) - ACC_OFFSET_X;
  ayMs = ((float)ray / ACCEL_SCALE * GRAVITY) - ACC_OFFSET_Y;
  azMs = ((float)raz / ACCEL_SCALE * GRAVITY) - ACC_OFFSET_Z;

  return true;
}

// ─────────────────────────────────────────────
//  FUNGSI KALIBRASI TAHAP 2
// ─────────────────────────────────────────────
void kalibrasiSudutDinamis() {
  Serial.println("\n===========================================");
  Serial.println("[KALIBRASI] Memulai Kalibrasi Postur Dinamis");
  Serial.println("[KALIBRASI] Pastikan sabuk berdiri tegak dan diam!");
  Serial.println("===========================================");
  delay(2000); 

  float totalPitch = 0;
  float totalRoll = 0;
  int jumlahSampel = 200;

  for (int i = 0; i < jumlahSampel; i++) {
    float ax, ay, az;
    mpuRead(ax, ay, az); 
    
    float pitchMentah = atan2(ax, sqrt(ay * ay + az * az)) * RAD_TO_DEG;
    float rollMentah  = atan2(ay, sqrt(ax * ax + az * az)) * RAD_TO_DEG;
    
    totalPitch += pitchMentah;
    totalRoll += rollMentah;
    delay(10); 
  }

  pitchOffset = totalPitch / jumlahSampel;
  rollOffset = totalRoll / jumlahSampel;

  Serial.printf("[KALIBRASI SELESAI] Offset Pitch: %.2f | Offset Roll: %.2f\n", pitchOffset, rollOffset);
}

// ─────────────────────────────────────────────
//  SETUP
// ─────────────────────────────────────────────
void setup() {
  Serial.begin(115200);
  delay(1000);

  connectWiFi();

  config.database_url = FIREBASE_HOST;
  config.signer.tokens.legacy_token = FIREBASE_AUTH;

  fbdo.setResponseSize(4096);
  config.timeout.serverResponse = 10 * 1000; 
  config.timeout.socketConnection = 10 * 1000; 

  Firebase.reconnectWiFi(true);
  Firebase.begin(&config, &auth);

  Wire.begin(SDA_PIN, SCL_PIN);
  Wire.setClock(400000);

  mpuWrite(REG_PWR_MGMT_1, 0x01);
  delay(100);
  mpuWrite(REG_CONFIG, 0x03);
  mpuWrite(REG_ACCEL_CONFIG, 0x10);
  mpuWrite(REG_GYRO_CONFIG, 0x08);
  delay(100);

  Wire.beginTransmission(MPU_ADDR);
  Wire.write(0x75);
  Wire.endTransmission(false);
  Wire.requestFrom((uint8_t)MPU_ADDR, (uint8_t)1, (uint8_t)true);
  uint8_t who = Wire.read();

  if (who != 0x68 && who != 0x70) {
    Serial.println("[ERROR] Gagal menemukan chip MPU6050!");
    while(1) delay(500);
  }
  Serial.println("[OK] MPU6050 Siap!");
  
  kalibrasiSudutDinamis();
  
  updateFirebase("NORMAL", 0.0);
}

// ─────────────────────────────────────────────
//  LOOP UTAMA (LOGIKA SLOW FALL)
// ─────────────────────────────────────────────
void loop() {
  float ax, ay, az;
  if (!mpuRead(ax, ay, az)) return;

  bool forceUpdate = false; 

  float pitchMentah = atan2(ax, sqrt(ay * ay + az * az)) * RAD_TO_DEG;
  float rollMentah  = atan2(ay, sqrt(ax * ax + az * az)) * RAD_TO_DEG;

  float kemiringanAbsolutPitch = abs(pitchMentah - pitchOffset);
  float kemiringanAbsolutRoll  = abs(rollMentah - rollOffset);

  float kemiringanTerbesar = kemiringanAbsolutPitch;
  if (kemiringanAbsolutRoll > kemiringanAbsolutPitch) {
      kemiringanTerbesar = kemiringanAbsolutRoll;
  }

  // === TAMBAHKAN KODE INI UNTUK GRAFIK SERIAL PLOTTER ===
  Serial.print("Batas_Jatuh:");
  Serial.print(BATAS_KEMIRINGAN); // Garis lurus sebagai batas ambang (65 derajat)
  Serial.print(",");
  Serial.print("Kemiringan_Pitch:");
  Serial.print(kemiringanAbsolutPitch); // Garis pergerakan Pitch
  Serial.print(",");
  Serial.print("Kemiringan_Roll:");
  Serial.println(kemiringanAbsolutRoll); // Garis pergerakan Roll
  // =======================================================

  if (!isCooldown) {
    if (kemiringanAbsolutPitch > BATAS_KEMIRINGAN || kemiringanAbsolutRoll > BATAS_KEMIRINGAN) {
      
      if (!sedangMiring) {
        sedangMiring = true;
        waktuMulaiMiring = millis();
        Serial.println("\n[PERINGATAN] Postur horizontal! Memulai hitung mundur...");
      } 
      else {
        if (millis() - waktuMulaiMiring >= WAKTU_TOLERANSI) {
          isCooldown = true;             
          cooldownStartTime = millis();
          sedangMiring = false;          
          forceUpdate = true;            
          Serial.println("\n[BAHAYA] SLOW FALL TERDETEKSI! Memicu Kamera...");
        }
      }
    } 
    else {
      if (sedangMiring) {
        Serial.println("[AMAN] Pengguna kembali berdiri. Timer dibatalkan.");
      }
      sedangMiring = false;
    }
  } 
  else {
    if (millis() - cooldownStartTime >= COOLDOWN_DURATION) {
      isCooldown = false;
      Serial.println("\n[RESET] Cooldown selesai. Kembali memantau normal.");
      forceUpdate = true; 
    }
  }

  if (forceUpdate || (millis() - lastUpdate > 4000 && !sedangMiring && !isCooldown)) {
    String currentStatus = isCooldown ? "CAPTURE" : "NORMAL";
    
    updateFirebase(currentStatus, kemiringanTerbesar);
    lastUpdate = millis();
  }

  delay(50);
}
