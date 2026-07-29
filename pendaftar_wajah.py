import os
import pickle
from deepface import DeepFace

# Konfigurasi
FOLDER_DATASET = "dataset_wajah"
FILE_DATABASE = "database_facenet.pkl"

vektor_wajah_diketahui = []
nama_wajah_diketahui = []

print("=== MEMULAI EKSTRAKSI FACENET512 ===")

for nama_anak in os.listdir(FOLDER_DATASET):
    path_folder_anak = os.path.join(FOLDER_DATASET, nama_anak)

    if os.path.isdir(path_folder_anak):
        print(f"-> Memproses wajah: {nama_anak}...")

        for nama_foto in os.listdir(path_folder_anak):
            path_foto = os.path.join(path_folder_anak, nama_foto)

            try:
                # detector_backend="retinaface" + align default True (TIDAK diubah)
                # Kombinasi ini WAJIB sama persis dengan yang dipakai di script recognition!
                hasil_ekstraksi = DeepFace.represent(
                    img_path=path_foto,
                    model_name="Facenet512",
                    enforce_detection=True,
                    detector_backend="retinaface",
                )

                vektor_512 = hasil_ekstraksi[0]["embedding"]
                vektor_wajah_diketahui.append(vektor_512)
                nama_wajah_diketahui.append(nama_anak)
                print(f"   [OK] Foto {nama_foto} berhasil diekstrak.")

            except Exception as e:
                # dulu ada 2 blok except duplikat (blok kedua tidak pernah jalan) -> dihapus
                print(f"   [GAGAL] Foto {nama_foto} dilewati. Error: {str(e)}")

# Menyimpan hasil ke file .pkl
data_final = {
    "encodings": vektor_wajah_diketahui,
    "names": nama_wajah_diketahui
}

with open(FILE_DATABASE, "wb") as f:
    pickle.dump(data_final, f)

print(f"\n[SELESAI] Database berhasil disimpan di {FILE_DATABASE}")
print(f"[INFO] Total wajah terekam: {len(nama_wajah_diketahui)}")