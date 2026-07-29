package com.falldetection.app;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;

public class MainActivity extends AppCompatActivity {

    private EditText emailEditText, passwordEditText;
    private Button loginButton;
    private TextView forgotPasswordText, registerText;
    private ImageView iconEyePassword; // Tambahan deklarasi ikon mata
    private FirebaseAuth mAuth;
    private boolean isPasswordVisible = false; // Status awal password tersembunyi

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Inisialisasi Firebase Auth
        mAuth = FirebaseAuth.getInstance();

        // Hubungkan dengan ID di XML
        emailEditText = findViewById(R.id.emailEditText);
        passwordEditText = findViewById(R.id.passwordEditText);
        loginButton = findViewById(R.id.loginButton);
        forgotPasswordText = findViewById(R.id.forgotPasswordText);
        registerText = findViewById(R.id.registerText);

        // Hubungkan ID ikon mata dari XML
        iconEyePassword = findViewById(R.id.iconEyePassword);

        // ==========================================
        // LOGIKA TOMBOL MATA (TAMPILKAN/SEMBUNYIKAN)
        // ==========================================
        iconEyePassword.setOnClickListener(v -> {
            if (isPasswordVisible) {
                // Sembunyikan password (jadikan titik-titik)
                passwordEditText.setTransformationMethod(PasswordTransformationMethod.getInstance());
                iconEyePassword.setColorFilter(Color.parseColor("#999999")); // Kembali warna abu-abu netral
            } else {
                // Tampilkan password aslinya
                passwordEditText.setTransformationMethod(HideReturnsTransformationMethod.getInstance());
                iconEyePassword.setColorFilter(Color.parseColor("#3D405B")); // Berubah jadi warna Navy Gelap
            }
            isPasswordVisible = !isPasswordVisible; // Balikkan status
            // Agar kursor ketikan tetap berada di ujung paling kanan
            passwordEditText.setSelection(passwordEditText.getText().length());
        });
        // ==========================================

        // ===============================================================
        // KODE AUTO-LOGIN DIHAPUS DI SINI
        // Sekarang aplikasi akan SELALU diam di halaman Login saat dibuka
        // ===============================================================

        // 1. Logika Tombol Login
        loginButton.setOnClickListener(v -> loginUser());

        // 2. Logika Pindah ke Halaman Daftar
        registerText.setOnClickListener(v -> {
            startActivity(new Intent(MainActivity.this, RegisterActivity.class));
        });

        // 3. Logika Pindah ke Halaman Lupa Password
        forgotPasswordText.setOnClickListener(v -> {
            startActivity(new Intent(MainActivity.this, ForgotPasswordActivity.class));
        });
    }

    private void loginUser() {
        String email = emailEditText.getText().toString().trim();
        String password = passwordEditText.getText().toString().trim();

        // PELACAK 1: Memastikan tombol benar-benar merespons saat diklik
        Toast.makeText(MainActivity.this, "Menghubungkan ke server...", Toast.LENGTH_SHORT).show();

        if (TextUtils.isEmpty(email)) {
            emailEditText.setError("Email tidak boleh kosong!");
            emailEditText.requestFocus();
            return;
        }
        if (TextUtils.isEmpty(password)) {
            passwordEditText.setError("Password tidak boleh kosong!");
            passwordEditText.requestFocus();
            return;
        }

        // PELACAK 2: Matikan tombol sementara agar tidak diklik berkali-kali (mencegah spam ke server)
        loginButton.setEnabled(false);

        // Proses Login Firebase
        mAuth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener(this, task -> {

                    // Nyalakan tombolnya kembali apa pun hasilnya
                    loginButton.setEnabled(true);

                    if (task.isSuccessful()) {
                        Toast.makeText(MainActivity.this, "Login Berhasil!", Toast.LENGTH_SHORT).show();
                        goToMonitoring();
                    } else {
                        // PELACAK 3: Menangkap error sedetail mungkin
                        String errorMessage = "Kesalahan tidak diketahui";
                        if (task.getException() != null) {
                            errorMessage = task.getException().getMessage();
                            // Kirim pesan error ke Logcat Android Studio (warna merah)
                            android.util.Log.e("DEBUG_LOGIN", "Penyebab Gagal: ", task.getException());
                        }

                        Toast.makeText(MainActivity.this, "Gagal: " + errorMessage, Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void goToMonitoring() {
        Intent intent = new Intent(MainActivity.this, MonitoringActivity.class);
        startActivity(intent);
        finish(); // Menutup halaman login agar tidak bisa di-back
    }
}