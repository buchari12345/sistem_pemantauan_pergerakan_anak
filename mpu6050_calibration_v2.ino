/*
 * ============================================================
 *  KALIBRASI MPU-6050 — Akses Register Langsung
 *  Tidak butuh library Adafruit MPU6050
 *  Hanya butuh: Wire.h (bawaan Arduino/ESP32)
 * ============================================================
 *  Cara pakai:
 *  1. Letakkan sensor DATAR di meja, jangan disentuh
 *  2. Upload sketch ini
 *  3. Buka Serial Monitor 115200 baud
 *  4. Tunggu hingga muncul "GUNAKAN NILAI INI"
 *  5. Catat nilainya untuk dipakai di kode fall detection
 * ============================================================
 */

#include <Wire.h>

#define SDA_PIN      8
#define SCL_PIN      9
#define MPU_ADDR     0x68
#define SAMPLE_COUNT 500

// ── Register MPU-6050 ──────────────────────────────────────
#define REG_PWR_MGMT_1   0x6B
#define REG_ACCEL_CONFIG 0x1C
#define REG_GYRO_CONFIG  0x1B
#define REG_CONFIG       0x1A
#define REG_ACCEL_XOUT_H 0x3B

// ── Skala akselerometer ±8g → 4096 LSB/g ──────────────────
#define ACCEL_SCALE  4096.0f
#define GRAVITY      9.81f

// ─────────────────────────────────────────────
void mpuWrite(uint8_t reg, uint8_t val) {
  Wire.beginTransmission(MPU_ADDR);
  Wire.write(reg);
  Wire.write(val);
  Wire.endTransmission();
}

// Baca 14 byte sekaligus: Accel XYZ + Temp + Gyro XYZ
void mpuRead(int16_t &ax, int16_t &ay, int16_t &az,
             int16_t &gx, int16_t &gy, int16_t &gz) {
  Wire.beginTransmission(MPU_ADDR);
  Wire.write(REG_ACCEL_XOUT_H);
  Wire.endTransmission(false);
  Wire.requestFrom((uint8_t)MPU_ADDR, (uint8_t)14, (uint8_t)true);

  ax = (Wire.read() << 8) | Wire.read();
  ay = (Wire.read() << 8) | Wire.read();
  az = (Wire.read() << 8) | Wire.read();
       Wire.read(); Wire.read(); // skip temperature
  gx = (Wire.read() << 8) | Wire.read();
  gy = (Wire.read() << 8) | Wire.read();
  gz = (Wire.read() << 8) | Wire.read();
}

// ─────────────────────────────────────────────
void setup() {
  Serial.begin(115200);
  delay(500);

  Wire.begin(SDA_PIN, SCL_PIN);
  Wire.setClock(100000); // 100kHz — lebih stabil

  // Wake up MPU-6050 (keluar dari sleep mode)
  mpuWrite(REG_PWR_MGMT_1, 0x00);
  delay(100);

  // Set clock source ke gyro X (lebih stabil dari internal oscillator)
  mpuWrite(REG_PWR_MGMT_1, 0x01);
  delay(10);

  // Low-pass filter: bandwidth 21Hz (0x04)
  mpuWrite(REG_CONFIG, 0x04);

  // Akselerometer range ±8g → 0x10
  mpuWrite(REG_ACCEL_CONFIG, 0x10);

  // Gyro range ±500°/s → 0x08
  mpuWrite(REG_GYRO_CONFIG, 0x08);

  delay(200); // beri waktu sensor stabil

  // Verifikasi WHO_AM_I register (harus 0x68)
  Wire.beginTransmission(MPU_ADDR);
  Wire.write(0x75);
  Wire.endTransmission(false);
  Wire.requestFrom((uint8_t)MPU_ADDR, (uint8_t)1, (uint8_t)true);
  uint8_t whoami = Wire.read();

  Serial.println("\n====================================");
  Serial.println("  KALIBRASI MPU-6050");
  Serial.println("====================================");
  Serial.printf("  WHO_AM_I : 0x%02X %s\n", whoami,
                whoami == 0x68 ? "(OK)" : "(tidak sesuai!)");
  Serial.println("  Range Acc: ±8g");
  Serial.println("  Letakkan sensor DATAR dan DIAM");
  Serial.println("====================================\n");

  // Countdown
  for (int i = 3; i > 0; i--) {
    Serial.printf("  Mulai dalam %d...\n", i);
    delay(1000);
  }
  Serial.printf("  Mengambil %d sampel...\n\n", SAMPLE_COUNT);

  // ── Kumpulkan sampel ──────────────────────
  double sumAx=0, sumAy=0, sumAz=0;
  double sumGx=0, sumGy=0, sumGz=0;

  for (int i = 0; i < SAMPLE_COUNT; i++) {
    int16_t ax, ay, az, gx, gy, gz;
    mpuRead(ax, ay, az, gx, gy, gz);

    sumAx += ax; sumAy += ay; sumAz += az;
    sumGx += gx; sumGy += gy; sumGz += gz;

    if (i % 100 == 0)
      Serial.printf("  [%3d/%d] ax=%6d ay=%6d az=%6d\n",
                    i, SAMPLE_COUNT, ax, ay, az);
    delay(10);
  }

  // ── Hitung rata-rata raw (LSB) ────────────
  float mAx = sumAx / SAMPLE_COUNT;
  float mAy = sumAy / SAMPLE_COUNT;
  float mAz = sumAz / SAMPLE_COUNT;
  float mGx = sumGx / SAMPLE_COUNT;
  float mGy = sumGy / SAMPLE_COUNT;
  float mGz = sumGz / SAMPLE_COUNT;

  // ── Konversi ke m/s² ──────────────────────
  // ±8g → sensitivity = 4096 LSB/g
  float rawAxMs = (mAx / ACCEL_SCALE) * GRAVITY;
  float rawAyMs = (mAy / ACCEL_SCALE) * GRAVITY;
  float rawAzMs = (mAz / ACCEL_SCALE) * GRAVITY;

  // ── Hitung offset ─────────────────────────
  // Sensor flat di meja → ideal: X=0, Y=0, Z=+9.81 m/s²
  float offAx = rawAxMs;           // selisih dari 0
  float offAy = rawAyMs;           // selisih dari 0
  float offAz = rawAzMs - GRAVITY; // selisih dari 9.81

  // Gyro offset (rad/s) → ideal semua = 0
  // Skala ±500°/s → 65.5 LSB/(°/s) → konversi ke rad/s
  float offGx = (mGx / 65.5f) * (M_PI / 180.0f);
  float offGy = (mGy / 65.5f) * (M_PI / 180.0f);
  float offGz = (mGz / 65.5f) * (M_PI / 180.0f);

  // ── Tampilkan hasil ───────────────────────
  Serial.println("\n====================================");
  Serial.println("  HASIL KALIBRASI");
  Serial.println("====================================");
  Serial.println("  Rata-rata raw (LSB):");
  Serial.printf("    Acc  X: %+8.1f  Y: %+8.1f  Z: %+8.1f\n", mAx, mAy, mAz);
  Serial.printf("    Gyro X: %+8.1f  Y: %+8.1f  Z: %+8.1f\n", mGx, mGy, mGz);
  Serial.println();
  Serial.println("  Rata-rata dalam m/s²:");
  Serial.printf("    X: %+.4f  Y: %+.4f  Z: %+.4f\n",
                rawAxMs, rawAyMs, rawAzMs);
  Serial.println();
  Serial.println("  GUNAKAN NILAI INI di kode fall detection:");
  Serial.println("  ─────────────────────────────────────────");
  Serial.printf("  const float ACC_OFFSET_X = %.4ff;\n", offAx);
  Serial.printf("  const float ACC_OFFSET_Y = %.4ff;\n", offAy);
  Serial.printf("  const float ACC_OFFSET_Z = %.4ff;\n", offAz);
  Serial.println();
  Serial.printf("  const float GYRO_OFFSET_X = %.6ff;\n", offGx);
  Serial.printf("  const float GYRO_OFFSET_Y = %.6ff;\n", offGy);
  Serial.printf("  const float GYRO_OFFSET_Z = %.6ff;\n", offGz);
  Serial.println("  ─────────────────────────────────────────");
  Serial.println();

  // ── Verifikasi ────────────────────────────
  Serial.println("  Verifikasi (setelah offset diterapkan):");
  Serial.printf("    X: %.4f (ideal  0.00)\n", rawAxMs - offAx);
  Serial.printf("    Y: %.4f (ideal  0.00)\n", rawAyMs - offAy);
  Serial.printf("    Z: %.4f (ideal  9.81)\n", rawAzMs - offAz);
  Serial.println("====================================");
  Serial.println("  Kalibrasi selesai!");
}

void loop() {}
