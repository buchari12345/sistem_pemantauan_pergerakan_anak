package com.falldetection.app;

import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;

public class ForgotPasswordActivity extends AppCompatActivity {

    private EditText emailResetEditText;
    private Button btnSendReset;
    private TextView backToLoginFromReset;
    private FirebaseAuth mAuth;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_forgot_password);

        mAuth = FirebaseAuth.getInstance();

        emailResetEditText = findViewById(R.id.emailResetEditText);
        btnSendReset = findViewById(R.id.btnSendReset);
        backToLoginFromReset = findViewById(R.id.backToLoginFromReset);

        btnSendReset.setOnClickListener(v -> resetPassword());
        backToLoginFromReset.setOnClickListener(v -> finish()); // Menutup halaman dan kembali ke Login
    }

    private void resetPassword() {
        String email = emailResetEditText.getText().toString().trim();

        if (TextUtils.isEmpty(email)) {
            emailResetEditText.setError("Email wajib diisi!");
            emailResetEditText.requestFocus();
            return;
        }

        mAuth.sendPasswordResetEmail(email).addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                Toast.makeText(ForgotPasswordActivity.this, "Berhasil! Silakan periksa kotak masuk email Anda.", Toast.LENGTH_LONG).show();
                finish(); // Otomatis kembali ke login setelah berhasil
            } else {
                Toast.makeText(ForgotPasswordActivity.this, "Gagal: " + task.getException().getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }
}