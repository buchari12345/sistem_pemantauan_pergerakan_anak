package com.falldetection.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MonitoringActivity extends AppCompatActivity {

    private String myUid = "";
    private String pesanWajah = "";

    private TextView userNameText, mainStatusText, accelMagnitudeText;
    private LinearLayout logoutButton, mainStatusBackground;
    private ImageView statusIcon;
    private RecyclerView historyRecyclerView;
    private HistoryAdapter historyAdapter;
    private List<HistoryItem> historyList;
    private DatabaseReference database;

    private boolean isYoloFalling = false;

    private CardView indicatorMpu, indicatorYolo;
    private TextView textMpuStatus, textYoloStatus;

    private Handler mpuHandler = new Handler();
    private Handler yoloHandler = new Handler();

    private Runnable mpuOfflineRunnable = () -> {
        indicatorMpu.setCardBackgroundColor(Color.parseColor("#E57373"));
        textMpuStatus.setText("Sabuk: Terputus");
        textMpuStatus.setTextColor(Color.parseColor("#E57373"));
        accelMagnitudeText.setText("--°");
    };

    private Runnable yoloOfflineRunnable = () -> {
        indicatorYolo.setCardBackgroundColor(Color.parseColor("#E57373"));
        textYoloStatus.setText("CCTV: Terputus");
        textYoloStatus.setTextColor(Color.parseColor("#E57373"));
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_monitoring);

        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
        if (currentUser != null) {
            myUid = currentUser.getUid();
        } else {
            startActivity(new Intent(this, MainActivity.class));
            finish();
            return;
        }

        initializeViews();
        createNotificationChannel();

        try {
            Intent serviceIntent = new Intent(this, BackgroundPendeteksiService.class);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (currentUser != null) {
            String namaPendek = currentUser.getDisplayName();
            if (namaPendek != null && !namaPendek.trim().isEmpty()) {
                userNameText.setText(namaPendek);
            } else if (currentUser.getEmail() != null) {
                userNameText.setText(currentUser.getEmail().split("@")[0]);
            } else {
                userNameText.setText("Pengawas");
            }
        }

        logoutButton.setOnClickListener(v -> handleLogout());
        setupFirebaseListeners();
    }

    private void initializeViews() {
        userNameText = findViewById(R.id.userNameText);
        logoutButton = findViewById(R.id.logoutButton);
        mainStatusBackground = findViewById(R.id.mainStatusBackground);
        statusIcon = findViewById(R.id.statusIcon);
        mainStatusText = findViewById(R.id.mainStatusText);
        accelMagnitudeText = findViewById(R.id.accelMagnitudeText);

        historyRecyclerView = findViewById(R.id.historyRecyclerView);
        historyList = new ArrayList<>();

        // Memanggil adapter dan menghubungkannya dengan fungsi tampilkanDialogHapus (Custom Dialog)
        historyAdapter = new HistoryAdapter(historyList, this::tampilkanDialogHapus);

        historyRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        historyRecyclerView.setAdapter(historyAdapter);

        indicatorMpu = findViewById(R.id.indicatorMpu);
        indicatorYolo = findViewById(R.id.indicatorYolo);
        textMpuStatus = findViewById(R.id.textMpuStatus);
        textYoloStatus = findViewById(R.id.textYoloStatus);

        mpuHandler.post(mpuOfflineRunnable);
        yoloHandler.post(yoloOfflineRunnable);
    }

    private void setupFirebaseListeners() {
        database = FirebaseDatabase.getInstance().getReference();

        database.child("sensor").addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (snapshot.exists()) {
                    Float sudutPitch = snapshot.child("accel_magnitude").getValue(Float.class);

                    if (sudutPitch != null) {
                        indicatorMpu.setCardBackgroundColor(Color.parseColor("#81C784"));
                        textMpuStatus.setText("Sabuk: Online");
                        textMpuStatus.setTextColor(Color.parseColor("#81C784"));

                        mpuHandler.removeCallbacks(mpuOfflineRunnable);
                        mpuHandler.postDelayed(mpuOfflineRunnable, 15000);

                        accelMagnitudeText.setText(String.format(Locale.getDefault(), "%.1f°", sudutPitch));
                    }
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError error) {}
        });

        database.child("data_pengguna").child(myUid).child("status_kamera")
                .addValueEventListener(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        if (snapshot.exists()) {
                            indicatorYolo.setCardBackgroundColor(Color.parseColor("#81C784"));
                            textYoloStatus.setText("CCTV: Online");
                            textYoloStatus.setTextColor(Color.parseColor("#81C784"));

                            yoloHandler.removeCallbacks(yoloOfflineRunnable);
                            yoloHandler.postDelayed(yoloOfflineRunnable, 15000);
                        }
                    }
                    @Override public void onCancelled(@NonNull DatabaseError error) {}
                });

        database.child("data_pengguna").child(myUid).child("notifikasi_jatuh")
                .addValueEventListener(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        if (snapshot.exists()) {
                            String statusPython = snapshot.child("status").getValue(String.class);
                            String pesanDariPython = snapshot.child("pesan").getValue(String.class);

                            if (statusPython != null && statusPython.equals("BAHAYA")) {
                                isYoloFalling = true;

                                if (pesanDariPython != null) {
                                    pesanWajah = pesanDariPython;
                                }

                                tampilkanNotifikasi("Sistem Cerdas Terpadu", pesanWajah);

                            } else {
                                isYoloFalling = false;
                                pesanWajah = "";
                            }
                            updateUIStatus();
                        }
                    }
                    @Override public void onCancelled(@NonNull DatabaseError error) {}
                });

        database.child("data_pengguna").child(myUid).child("history").addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                historyList.clear();
                if (snapshot.exists()) {
                    for (DataSnapshot childSnapshot : snapshot.getChildren()) {
                        String time = childSnapshot.child("time").getValue(String.class);
                        String confidence = childSnapshot.child("confidence").getValue(String.class);
                        String base64Data = childSnapshot.child("image_base64").getValue(String.class);
                        String source = childSnapshot.child("source").getValue(String.class);
                        String nama = childSnapshot.child("nama").getValue(String.class);
                        String kemiringan = childSnapshot.child("kemiringan").getValue(String.class);
                        String akurasiAi = childSnapshot.child("akurasi_ai").getValue(String.class);

                        if (source == null) source = "Trigger MPU6050 + CCTV";
                        if (nama == null || nama.trim().isEmpty()) nama = "Umum";
                        if (kemiringan == null) kemiringan = "--°";
                        if (akurasiAi == null) akurasiAi = "--";

                        HistoryItem item = new HistoryItem(time, confidence, source, base64Data, nama, kemiringan, akurasiAi);

                        // Menyimpan ID/Key dari Firebase ke dalam model
                        item.setKey(childSnapshot.getKey());

                        historyList.add(0, item);
                    }
                }
                historyAdapter.notifyDataSetChanged();
            }
            @Override public void onCancelled(@NonNull DatabaseError error) {}
        });
    }

    // =========================================================================
    // FUNGSI BARU: Custom Dialog Hapus Riwayat
    // =========================================================================
    private void tampilkanDialogHapus(HistoryItem item) {
        android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.setContentView(R.layout.dialog_hapus_riwayat);
        dialog.setCancelable(true);

        // Membuat background dialog menjadi transparan agar sudut melengkung terlihat
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        // Inisialisasi tombol dari file XML dialog_hapus_riwayat
        android.widget.Button btnBatal = dialog.findViewById(R.id.btnBatalHapus);
        android.widget.Button btnHapus = dialog.findViewById(R.id.btnKonfirmasiHapus);

        // Aksi ketika tombol "Batal" ditekan
        btnBatal.setOnClickListener(v -> dialog.dismiss());

        // Aksi ketika tombol "Hapus" ditekan
        btnHapus.setOnClickListener(v -> {
            dialog.dismiss(); // Tutup dialog terlebih dahulu

            // Eksekusi penghapusan data di Firebase berdasarkan Key
            if (item.getKey() != null) {
                database.child("data_pengguna").child(myUid).child("history").child(item.getKey()).removeValue()
                        .addOnSuccessListener(aVoid -> Toast.makeText(MonitoringActivity.this, "Riwayat berhasil dihapus", Toast.LENGTH_SHORT).show())
                        .addOnFailureListener(e -> Toast.makeText(MonitoringActivity.this, "Gagal menghapus riwayat", Toast.LENGTH_SHORT).show());
            }
        });

        // Tampilkan dialog ke layar
        dialog.show();
    }
    // =========================================================================

    private void updateUIStatus() {
        if (isYoloFalling) {
            mainStatusBackground.setBackgroundResource(R.drawable.warnamerah);
            statusIcon.setImageResource(R.drawable.ic_alert);

            if (!pesanWajah.isEmpty()) {
                mainStatusText.setText(pesanWajah);
                mainStatusText.setTextSize(18);
            } else {
                mainStatusText.setText("TERDETEKSI JATUH!");
                mainStatusText.setTextSize(24);
            }
        } else {
            mainStatusBackground.setBackgroundResource(R.drawable.warnaijo);
            mainStatusText.setText("AMAN");
            mainStatusText.setTextSize(32);
            statusIcon.setImageResource(R.drawable.ic_shield);
        }
    }

    private static final String CHANNEL_ID = "FallDetectionChannel";

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Peringatan Darurat",
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("Pemberitahuan saat terdeteksi jatuh");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private void tampilkanNotifikasi(String sumber, String isiPesan) {
        Intent intent = new Intent(this, MonitoringActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("⚠️ BAHAYA DARURAT!")
                .setContentText(isiPesan)
                .setSubText("Sumber: " + sumber)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVibrate(new long[]{0, 500, 200, 500})
                .setContentIntent(pendingIntent)
                .setAutoCancel(true);

        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                    manager.notify(999, builder.build());
                } else {
                    ActivityCompat.requestPermissions(this, new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 101);
                }
            } else {
                manager.notify(999, builder.build());
            }
        }
    }

    private void handleLogout() {
        android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.setContentView(R.layout.dialog_logout);
        dialog.setCancelable(true);

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        android.widget.Button btnBatal = dialog.findViewById(R.id.btnBatal);
        android.widget.Button btnKeluar = dialog.findViewById(R.id.btnKeluar);

        btnBatal.setOnClickListener(v -> dialog.dismiss());

        btnKeluar.setOnClickListener(v -> {
            dialog.dismiss();
            try {
                stopService(new Intent(this, BackgroundPendeteksiService.class));
            } catch (Exception ignored) {}

            FirebaseAuth.getInstance().signOut();

            Intent intent = new Intent(this, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        });

        dialog.show();
    }

    @Override
    public void onBackPressed() {
        handleLogout();
    }
}