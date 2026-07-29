import os
import cv2
import pickle
import numpy as np
import time
import base64
import threading
import firebase_admin
from firebase_admin import credentials, db
from ultralytics import YOLO
from deepface import DeepFace
from scipy.spatial.distance import cdist

# ==========================================
# 1. PERSIAPAN DIREKTORI PENYIMPANAN LOKAL
# ==========================================
FOLDER_SIMPAN = "foto_insiden"
if not os.path.exists(FOLDER_SIMPAN):
    os.makedirs(FOLDER_SIMPAN)
    print(f"[INFO] Folder penyimpanan lokal '{FOLDER_SIMPAN}' berhasil dibuat.")


# ==========================================
# 2. CLASS THREADING UNTUK CCTV
# ==========================================
class CCTVStreamThread:
    def __init__(self, rtsp_url):
        self.cap = cv2.VideoCapture(rtsp_url)
        # Membatasi buffer memori agar hanya menyimpan 2 frame terbaru saja
        self.cap.set(cv2.CAP_PROP_BUFFERSIZE, 2)

        self.ret, self.frame = self.cap.read()
        self.is_running = True
        self.lock = threading.Lock() # Mengunci memori agar tidak bentrok antar proses

        # Menjalankan pembaruan frame secara terus-menerus di thread terpisah
        self.thread = threading.Thread(target=self.update_frame, args=())
        self.thread.daemon = True
        self.thread.start()

    def update_frame(self):
        while self.is_running:
            ret, frame = self.cap.read()
            if ret:
                with self.lock:
                    self.ret = ret
                    # Terus menimpa frame lama dengan frame paling baru (Anti-Lag)
                    self.frame = frame 
            else:
                time.sleep(0.01)

    def get_frame(self):
        # Fungsi yang dipanggil oleh loop utama untuk mengambil 1 frame terbaru
        with self.lock:
            return self.ret, self.frame.copy() if self.frame is not None else None

    def stop(self):
        # Mematikan koneksi RTSP kamera dengan aman
        self.is_running = False
        self.thread.join(timeout=3)
        self.cap.release()


# ==========================================
# 3. KONFIGURASI FIREBASE & BUKU ALAMAT
# ==========================================
# Dictionary (Kamus) yang mencocokkan "Nama Folder Dataset Wajah" dengan "UID Akun Firebase Android"
BUKU_ALAMAT_ORTU = {
    "anak 1": "UID Akun Firebase Android",
    "anak 2": "UID Akun Firebase Android",
    "anak 3": "UID Akun Firebase Android",
}

# Jeda waktu (detik) sebelum status "BAHAYA" di Firebase otomatis dikembalikan ke "AMAN"
RESET_STATUS_DELAY = 20

print("[INFO] Menghubungkan ke Firebase...")
try:
    # Memasukkan kunci rahasia Firebase untuk mendapatkan akses admin
    cred = credentials.Certificate("kunci-firebase.json")
    firebase_admin.initialize_app(cred, {
        'databaseURL': 'https://sesuaikan sendiri'
    })
    print("[OK] Firebase terhubung!")
except Exception as e:
    print(f"[ERROR] Gagal terhubung ke Firebase: {e}")
    exit()


# ==========================================
# 4. LISTENER SENSOR ESP32 (SISTEM PELATUK)
# ==========================================
pemicu_kamera = False
_event_awal_diabaikan = False

def pantau_sensor_esp(event):
    global pemicu_kamera, _event_awal_diabaikan

    # Mencegah terpicu oleh data lama saat skrip pertama kali dijalankan
    if not _event_awal_diabaikan:
        _event_awal_diabaikan = True
        print(f"[INFO] Status awal sensor saat skrip start: {event.data} (diabaikan)")
        return

    if event.data == "CAPTURE":
        pemicu_kamera = True
        print("\n[PELATUK] Sinyal CAPTURE diterima dari ESP32! Memulai Identifikasi Wajah...")

# Memasang fungsi pendengar ke alamat data sensor di Firebase
sensor_listener = db.reference('sensor/status').listen(pantau_sensor_esp)


# ==========================================
# 5. PERSIAPAN AI & CCTV
# ==========================================
print("[INFO] Memuat Model YOLO (Deteksi Wajah)...")
yolo_model = YOLO("wajah.pt", task="detect")

print("[INFO] Memuat Database FaceNet512...")
# Memuat hasil ekstraksi wajah (vektor 512 dimensi) yang sebelumnya sudah di-training
with open("database_facenet.pkl", 'rb') as f:
    data_wajah = pickle.load(f)
known_face_encodings_np = np.array(data_wajah["encodings"])
known_face_names = data_wajah["names"]

# Menghubungkan ke alamat stream RTSP CCTV TP-Link Tapo
URL_CCTV = "rtsp://nama:pw@ipcctv:554/stream1"
print(f"[INFO] Menyambung ke CCTV: {URL_CCTV}")
cctv = CCTVStreamThread(URL_CCTV)
time.sleep(2) # Memberi waktu kamera untuk pemanasan

print("\n=============================================")
print(" SISTEM MASTER-SLAVE AKTIF (MENUNGGU SENSOR)")
print(" Tekan tombol 'q' untuk keluar.")
print("=============================================\n")

last_heartbeat = 0
waktu_kirim_terakhir = 0
COOLDOWN_AI = 10 


# ==========================================
# 6. HELPER: FIREBASE
# ==========================================
def reset_status_aman(uids):
    for uid in uids:
        try:
            db.reference(f"data_pengguna/{uid}/notifikasi_jatuh").set({
                "status": "AMAN",
                "pesan": ""
            })
            print(f"[INFO] Status uid {uid} otomatis dikembalikan ke AMAN.")
        except Exception:
            pass

# Fungsi untuk mendorong paket data (Notifikasi merah + Gambar + Akurasi AI + Sudut) ke Firebase HP
def kirim_notifikasi_dan_history(uid, pesan, confidence_label, source_label, img_base64, nama_label="Umum", kemiringan="0.0°", akurasi_ai="--"):
    waktu_str = time.strftime("%d %b %Y, %H:%M:%S")
    
    # 1. Memicu Alarm Android
    db.reference(f"data_pengguna/{uid}/notifikasi_jatuh").set({
        "status": "BAHAYA",
        "pesan": pesan
    })
    
    # 2. Menambah baris baru di menu Riwayat
    db.reference(f"data_pengguna/{uid}/history").push({
        "time": waktu_str,
        "confidence": confidence_label,
        "source": source_label,
        "image_base64": img_base64,
        "nama": nama_label,
        "kemiringan": kemiringan,
        "akurasi_ai": akurasi_ai
    })
    
    # Menjalankan timer latar belakang untuk mereset alarm
    threading.Timer(RESET_STATUS_DELAY, reset_status_aman, args=([uid],)).start()


# ==========================================
# 7. FUNGSI BERSAMA: DETEKSI + KENALI WAJAH
# ==========================================
# Fungsi utama AI: Menggunakan YOLO untuk mencari kotak wajah, lalu menggunakan FaceNet untuk mengenali siapa itu
def deteksi_dan_kenali_wajah(frame, conf=0.60, gambar_kotak=True, kenali_identitas=True):
    hasil_yolo = yolo_model(frame, conf=conf, verbose=False)

    frame_hasil = frame.copy()
    deteksi = []
    ada_wajah_terdeteksi = False

    for r in hasil_yolo:
        boxes = r.boxes
        if boxes is None or len(boxes.xyxy) == 0:
            continue

        for box in boxes:
            ada_wajah_terdeteksi = True
            
            # Mendapatkan titik koordinat kotak wajah dari YOLO
            x1, y1, x2, y2 = map(int, box.xyxy[0])
            # Menambahkan bantalan (padding) 15% agar potongan wajah lebih luas dan jelas
            pad_x = int((x2 - x1) * 0.15)
            pad_y = int((y2 - y1) * 0.15)
            
            # Ekstrak nilai keyakinan YOLO (Persentase)
            yolo_conf = float(box.conf[0]) * 100

            # Jika mode kenali_identitas dimatikan (saat aktivitas normal/idle), cukup gambar kotak kuning
            if not kenali_identitas:
                if gambar_kotak:
                    cv2.rectangle(frame_hasil, (x1, y1), (x2, y2), (255, 255, 0), 2)
                    cv2.putText(frame_hasil, f"Wajah ({yolo_conf:.0f}%)", (x1, y1 - 10), cv2.FONT_HERSHEY_SIMPLEX, 0.6, (255, 255, 0), 2)
                
                deteksi.append({"box": (x1, y1, x2, y2)})
                continue

            # === PROSES DEEPFACE ===
            # Memotong gambar asli hanya sebesar ukuran kotak wajah
            potongan_wajah = frame[max(0, y1 - pad_y):min(frame.shape[0], y2 + pad_y),
                                   max(0, x1 - pad_x):min(frame.shape[1], x2 + pad_x)]

            # Memperbesar ukuran potongan wajah jika terlalu kecil agar bisa dibaca FaceNet
            if potongan_wajah.shape[0] < 80 or potongan_wajah.shape[1] < 80:
                potongan_wajah = cv2.resize(potongan_wajah, (0, 0), fx=2.0, fy=2.0)

            rgb_potongan = cv2.cvtColor(potongan_wajah, cv2.COLOR_BGR2RGB)

            nama_target = "Tak Dikenal"
            facenet_dist = 0.0
            
            try:
                # Mengubah gambar potongan wajah menjadi vektor angka 512-dimensi
                hasil_representasi = DeepFace.represent(img_path=rgb_potongan,
                                                        model_name="Facenet512",
                                                        enforce_detection=False,
                                                        detector_backend="skip")

                vektor_kamera = np.array(hasil_representasi[0]["embedding"])
                
                # Mengukur jarak matematis (kemiripan) antara wajah di kamera dengan dataset
                distances = cdist([vektor_kamera], known_face_encodings_np, metric='cosine')[0]
                index_terdekat = np.argmin(distances)
                
                facenet_dist = float(distances[index_terdekat])

                # PERUBAHAN: Jika jaraknya di bawah 0.65, maka identitas diakui
                if facenet_dist <= 0.65:
                    nama_target = known_face_names[index_terdekat]
            except Exception:
                pass

            dikenali = nama_target != "Tak Dikenal"
            # Mencocokkan nama dengan UID di buku alamat. Jika wajah tidak terdaftar, maka None.
            uid_target = BUKU_ALAMAT_ORTU.get(nama_target) if dikenali else None

            if gambar_kotak:
                # Warna kotak merah jika berhasil dikenali, kuning jika wajah asing
                warna_kotak = (0, 0, 255) if dikenali else (0, 255, 255)
                
                # Menggabungkan teks untuk dicetak di gambar
                label_nama = f"WAJAH: {nama_target}" if dikenali else "TAK DIKENAL"
                label_akurasi = f"YOLO: {yolo_conf:.0f}% | FN: {facenet_dist:.2f}"
                
                cv2.rectangle(frame_hasil, (x1, y1), (x2, y2), warna_kotak, 3)
                # Teks Baris 1 (Nama)
                cv2.putText(frame_hasil, label_nama, (x1, y1 - 25), cv2.FONT_HERSHEY_SIMPLEX, 0.7, warna_kotak, 2)
                # Teks Baris 2 (Akurasi warna hijau terang)
                cv2.putText(frame_hasil, label_akurasi, (x1, y1 - 5), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 255, 0), 2)

            # Menyimpan hasil deteksi frame ini ke dalam memori
            deteksi.append({
                "nama": nama_target,
                "dikenali": dikenali,
                "uid": uid_target,
                "box": (x1, y1, x2, y2),
                "yolo_conf": yolo_conf,
                "facenet_dist": facenet_dist
            })

    return frame_hasil, deteksi, ada_wajah_terdeteksi


# ==========================================
# 8. LOOP UTAMA
# ==========================================
frame_count = 0
skip_frames = 5
last_deteksi = []

while cctv.is_running:
    success, frame_asli = cctv.get_frame()
    if not success or frame_asli is None:
        time.sleep(0.01)
        continue

    # Mengecilkan resolusi video untuk mempercepat pengolahan citra
    frame = cv2.resize(frame_asli, (640, 480))
    current_time = time.time()
    
    frame_layar = frame.copy()
    frame_count += 1

    # Logika Frame Skipping (mengurangi beban kerja CPU saat memantau normal)
    if frame_count % skip_frames == 0:
        # Jalankan YOLO (tanpa FaceNet)
        frame_layar, last_deteksi, _ = deteksi_dan_kenali_wajah(frame, conf=0.60, gambar_kotak=True, kenali_identitas=False)
    else:
        # Pada sisa frame, gambar saja kotak lama dari memori tanpa menjalankan AI lagi
        for d in last_deteksi:
            x1, y1, x2, y2 = d["box"]
            cv2.rectangle(frame_layar, (x1, y1), (x2, y2), (255, 255, 0), 2)
            cv2.putText(frame_layar, "Wajah", (x1, y1 - 10), cv2.FONT_HERSHEY_SIMPLEX, 0.6, (255, 255, 0), 2)

    # --- HANYA BEKERJA JIKA ESP32 MENEKAN PELATUK ---
    if pemicu_kamera:
        # Jika belum masuk masa tunggu cooldown (mencegah spam notifikasi)
        if current_time - waktu_kirim_terakhir > COOLDOWN_AI:
            # Menarik angka kemiringan derajat terakhir dari MPU6050
            sudut_jatuh = db.reference('sensor/accel_magnitude').get()
            if sudut_jatuh is None:
                sudut_jatuh = 0.0
            string_kemiringan = f"{float(sudut_jatuh):.1f}°"

            # Tangkap 1 foto kejadian dan jalankan YOLO penuh berserta FaceNet
            frame_untuk_kirim, deteksi, ada_wajah_terdeteksi = deteksi_dan_kenali_wajah(
                frame, conf=0.60, gambar_kotak=True, kenali_identitas=True
            )
            
            # --- FITUR BARU: SIMPAN FOTO KE RASPBERRY PI LOKAL ---
            waktu_file = time.strftime("%Y%m%d_%H%M%S")
            nama_file_lokal = f"{FOLDER_SIMPAN}/jatuh_{waktu_file}.jpg"
            cv2.imwrite(nama_file_lokal, frame_untuk_kirim)
            print(f"[INFO] Foto insiden disimpan di: {nama_file_lokal}")

            wajah_dikenali = False  

            for d in deteksi:
                if d.get("dikenali", False) and d.get("uid") is not None:
                    wajah_dikenali = True
                    uid = d["uid"]
                    
                    string_akurasi = f"YOLO ({d['yolo_conf']:.0f}%) | FaceNet ({d['facenet_dist']:.2f})"

                    # Kompresi gambar menjadi format teks Base64 untuk dikirim via internet
                    _, buffer = cv2.imencode('.jpg', cv2.resize(frame_untuk_kirim, (480, 360)),
                                             [int(cv2.IMWRITE_JPEG_QUALITY), 60])
                    img_base64 = base64.b64encode(buffer).decode('utf-8')

                    # Kirim notifikasi ke satu UID
                    kirim_notifikasi_dan_history(
                        uid,
                        pesan=f"AWAS! {d['nama']} terdeteksi jatuh oleh sensor tubuh!",
                        confidence_label="Validasi FaceNet",
                        source_label="Trigger MPU6050 + CCTV",
                        img_base64=img_base64,
                        nama_label=d['nama'], 
                        kemiringan=string_kemiringan,
                        akurasi_ai=string_akurasi
                    )
                    print(f"[SUKSES] Data darurat {d['nama']} terkirim ke HP Orang Tua!")

            if not wajah_dikenali:
                string_akurasi = "YOLO (Aktif) | FaceNet (Gagal)"
                
                # Mengompresi gambar untuk skenario broadcast
                _, buffer = cv2.imencode('.jpg', cv2.resize(frame_untuk_kirim, (480, 360)),
                                         [int(cv2.IMWRITE_JPEG_QUALITY), 60])
                img_base64_umum = base64.b64encode(buffer).decode('utf-8')

                # Logika penentuan pesan (Apakah tertutup, asing, atau sama sekali tak terekam kamera)
                if ada_wajah_terdeteksi:
                    pesan_umum = "AWAS! Sensor mendeteksi jatuh. Ada orang di kamera tapi wajahnya tidak dikenali sistem."
                    confidence_umum = "Wajah Tidak Dikenali"
                    print("[INFO] Wajah terlihat tapi tidak cocok database -> notifikasi umum dikirim.")
                else:
                    pesan_umum = "AWAS! Sensor mendeteksi jatuh, namun wajah tidak terlihat oleh kamera."
                    confidence_umum = "Tanpa Wajah Terdeteksi"
                    print("[INFO] Wajah korban tidak terlihat di CCTV -> notifikasi umum dikirim.")

                # Broadcast (Sebarkan) pengiriman notifikasi ke SELURUH akun yang ada di Buku Alamat
                for uid in BUKU_ALAMAT_ORTU.values():
                    kirim_notifikasi_dan_history(
                        uid,
                        pesan=pesan_umum,
                        confidence_label=confidence_umum,
                        source_label="Trigger MPU6050 + CCTV (Umum)",
                        img_base64=img_base64_umum,
                        nama_label="Umum",
                        kemiringan=string_kemiringan,
                        akurasi_ai=string_akurasi
                    )
                print("[SUKSES] Notifikasi umum terkirim ke semua akun!")

            waktu_kirim_terakhir = current_time

        # Mengembalikan status trigger ke false agar siap untuk menerima sensor berikutnya
        pemicu_kamera = False

    # Mengirim sinyal 'detak jantung' ke Firebase setiap 3 detik 
    # agar aplikasi Android tahu bahwa kamera pengawas dalam keadaan hidup
    if current_time - last_heartbeat > 3:
        for uid in BUKU_ALAMAT_ORTU.values():
            db.reference(f"data_pengguna/{uid}/status_kamera").set({
                "status": "Online",
                "timestamp": current_time
            })
        last_heartbeat = current_time

    # Menampilkan siaran langsung kamera di monitor Raspberry Pi
    cv2.imshow("Master-Slave Monitoring", frame_layar)
    
    # Jika tombol 'q' ditekan di keyboard, sistem mati (exit loop)
    if cv2.waitKey(1) & 0xFF == ord('q'):
        print("\n[INFO] Tombol 'q' ditekan, mematikan sistem...")
        break

# Blok penutupan tuntas saat program berakhir
print("[INFO] Menutup koneksi CCTV...")
cctv.stop()

print("[INFO] Menutup pendengar Firebase...")
sensor_listener.close()

cv2.destroyAllWindows()

# Hard exit OS untuk mencegah proses menggantung (freeze) di memori
print("[INFO] Program selesai. Mematikan seluruh proses latar belakang...")
os._exit(0)
