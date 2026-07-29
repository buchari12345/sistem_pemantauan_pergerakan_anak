package com.falldetection.app;

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
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.UserProfileChangeRequest;

public class RegisterActivity extends AppCompatActivity {

    private EditText nameRegEditText, emailRegEditText, passwordRegEditText;
    private Button registerButton;
    private TextView backToLoginText;
    private ImageView iconEyePassword; // Tambahan deklarasi ikon mata
    private FirebaseAuth mAuth;
    private boolean isPasswordVisible = false; // Status awal password tersembunyi

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register);

        mAuth = FirebaseAuth.getInstance();

        nameRegEditText = findViewById(R.id.nameRegEditText);
        emailRegEditText = findViewById(R.id.emailRegEditText);
        passwordRegEditText = findViewById(R.id.passwordRegEditText);
        registerButton = findViewById(R.id.registerButton);
        backToLoginText = findViewById(R.id.backToLoginText);

        // Hubungkan ID ikon mata dari XML
        iconEyePassword = findViewById(R.id.iconEyePassword);

        // ==========================================
        // LOGIKA TOMBOL MATA (TAMPILKAN/SEMBUNYIKAN)
        // ==========================================
        iconEyePassword.setOnClickListener(v -> {
            if (isPasswordVisible) {
                // Sembunyikan password
                passwordRegEditText.setTransformationMethod(PasswordTransformationMethod.getInstance());
                iconEyePassword.setColorFilter(Color.parseColor("#999999"));
            } else {
                // Tampilkan password
                passwordRegEditText.setTransformationMethod(HideReturnsTransformationMethod.getInstance());
                iconEyePassword.setColorFilter(Color.parseColor("#3D405B"));
            }
            isPasswordVisible = !isPasswordVisible;
            // Pastikan kursor tetap di ujung teks
            passwordRegEditText.setSelection(passwordRegEditText.getText().length());
        });
        // ==========================================

        registerButton.setOnClickListener(v -> registerUser());
        backToLoginText.setOnClickListener(v -> finish());
    }

    private void registerUser() {
        String name = nameRegEditText.getText().toString().trim();
        String email = emailRegEditText.getText().toString().trim();
        String password = passwordRegEditText.getText().toString().trim();

        if (TextUtils.isEmpty(name)) {
            nameRegEditText.setError("Nama diperlukan");
            nameRegEditText.requestFocus(); // Mengarahkan kursor ke sini
            return;
        }
        if (TextUtils.isEmpty(email)) {
            emailRegEditText.setError("Email diperlukan");
            emailRegEditText.requestFocus();
            return;
        }
        if (password.length() < 6) {
            passwordRegEditText.setError("Password minimal 6 karakter");
            passwordRegEditText.requestFocus();
            return;
        }

        mAuth.createUserWithEmailAndPassword(email, password)
                .addOnCompleteListener(this, task -> {
                    if (task.isSuccessful()) {
                        FirebaseUser user = mAuth.getCurrentUser();
                        if (user != null) {
                            // Proses Menyimpan Nama Panggilan ke Profil Google
                            UserProfileChangeRequest profileUpdates = new UserProfileChangeRequest.Builder()
                                    .setDisplayName(name)
                                    .build();

                            user.updateProfile(profileUpdates).addOnCompleteListener(task1 -> {
                                Toast.makeText(RegisterActivity.this, "Akun Berhasil Dibuat! Silakan Login.", Toast.LENGTH_LONG).show();
                                mAuth.signOut();
                                finish();
                            });
                        }
                    } else {
                        Toast.makeText(RegisterActivity.this, "Gagal Mendaftar: " + task.getException().getMessage(), Toast.LENGTH_LONG).show();
                    }
                });
    }
}