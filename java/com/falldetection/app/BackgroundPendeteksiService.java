package com.falldetection.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

public class BackgroundPendeteksiService extends Service {

    private boolean isSensorCapture = false;
    private boolean isYoloBahaya = false;
    private static final String CHANNEL_BAHAYA = "FallDetectionChannel";
    private static final String CHANNEL_INFO = "ForegroundServiceChannel";

    // UID pengguna yang sedang login, dipakai untuk membaca path data_pengguna/{uid}/...
    private String myUid = null;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannels();
        mulaiNotifikasiStandby();

        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
        if (currentUser != null) {
            myUid = currentUser.getUid();
        }

        mulaiPantauFirebase();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY; // Agar service tidak dimatikan paksa oleh Android
    }

    private void mulaiNotifikasiStandby() {
        // Membuat notifikasi kecil yang terus menempel agar Android membiarkan aplikasi tetap hidup
        Intent intent = new Intent(this, MonitoringActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_INFO)
                .setContentTitle("Pemantauan Aktif")
                .setContentText("Aplikasi mengawasi deteksi jatuh di latar belakang...")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentIntent(pendingIntent)
                .build();

        startForeground(1, notification);
    }

    private void mulaiPantauFirebase() {
        DatabaseReference database = FirebaseDatabase.getInstance().getReference();

        // ==============================================================
        // PERBAIKAN: Pantau Sensor MPU (ESP32 HANYA mengirim "NORMAL" atau "CAPTURE",
        // jadi status "jatuh" yang valid untuk dicek adalah "CAPTURE", bukan "FALL"/"TRIGGERED"
        // yang tidak pernah dikirim oleh firmware.
        // ==============================================================
        database.child("sensor").child("status").addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                String status = snapshot.getValue(String.class);
                if (status != null) {
                    boolean captureNow = status.equalsIgnoreCase("CAPTURE");
                    if (captureNow && !isSensorCapture) {
                        tembakNotifikasiBahaya("Sensor Tubuh (MPU6050) - Slow Fall Terdeteksi");
                    }
                    isSensorCapture = captureNow;
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError error) {}
        });

        // ==============================================================
        // PERBAIKAN: Path lama "yolo/current/status" TIDAK PERNAH ditulis oleh
        // script Python (fall_detection.py menulis ke data_pengguna/{uid}/notifikasi_jatuh),
        // sehingga listener lama ini adalah dead code dan tidak pernah terpicu.
        // Sekarang diarahkan ke path yang benar-benar dipakai Python, sama seperti
        // yang dipantau MonitoringActivity, supaya kedua tempat konsisten.
        // ==============================================================
        if (myUid != null) {
            database.child("data_pengguna").child(myUid).child("notifikasi_jatuh")
                    .addValueEventListener(new ValueEventListener() {
                        @Override
                        public void onDataChange(@NonNull DataSnapshot snapshot) {
                            if (!snapshot.exists()) return;

                            String status = snapshot.child("status").getValue(String.class);
                            String pesan = snapshot.child("pesan").getValue(String.class);

                            boolean bahayaNow = status != null && status.equalsIgnoreCase("BAHAYA");
                            if (bahayaNow && !isYoloBahaya) {
                                tembakNotifikasiBahaya(
                                        pesan != null ? pesan : "Kamera Pengawas (YOLO) - Wajah Terverifikasi Jatuh"
                                );
                            }
                            isYoloBahaya = bahayaNow;
                        }
                        @Override public void onCancelled(@NonNull DatabaseError error) {}
                    });
        }
    }

    private void tembakNotifikasiBahaya(String sumber) {
        Intent intent = new Intent(this, MonitoringActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_BAHAYA)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("⚠️ BAHAYA! Terdeteksi Jatuh")
                .setContentText("Peringatan dari: " + sumber)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVibrate(new long[]{0, 500, 200, 500})
                .setContentIntent(pendingIntent)
                .setAutoCancel(true);

        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            // Gunakan ID statis (999) agar notifikasi menimpa yang lama, bukan numpuk
            manager.notify(999, builder.build());
        }
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                NotificationChannel chBahaya = new NotificationChannel(CHANNEL_BAHAYA, "Peringatan Darurat", NotificationManager.IMPORTANCE_HIGH);
                NotificationChannel chInfo = new NotificationChannel(CHANNEL_INFO, "Status Pemantauan", NotificationManager.IMPORTANCE_LOW);
                manager.createNotificationChannel(chBahaya);
                manager.createNotificationChannel(chInfo);
            }
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }
}