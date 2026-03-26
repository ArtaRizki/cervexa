package com.idn.kmed.cervexa.auth

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import com.idn.kmed.cervexa.home.HomeActivity
import com.idn.kmed.cervexa.R
import com.idn.kmed.cervexa.network.ApiResult
import com.idn.kmed.cervexa.network.CervexaRepository
import com.idn.kmed.cervexa.network.TokenManager
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var etEmail: TextInputEditText
    private lateinit var etPassword: TextInputEditText
    private lateinit var btnLogin: Button
    private lateinit var loadingOverlay: View

    private val repo by lazy { CervexaRepository.getInstance(this) }
    private val tokenManager by lazy { TokenManager.getInstance(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Jika sudah login → langsung ke Home
        if (tokenManager.isLoggedIn) {
            goHome(); return
        }

        setContentView(R.layout.activity_login)

        etEmail = findViewById(R.id.etEmail)
        etPassword = findViewById(R.id.etPassword)
        btnLogin = findViewById(R.id.btnLogin)
        loadingOverlay = findViewById(R.id.loadingOverlay)

        btnLogin.setOnClickListener { doLogin() }
    }

    private fun doLogin() {
        val email = etEmail.text?.toString()?.trim().orEmpty()
        val password = etPassword.text?.toString().orEmpty()

        if (email.isBlank() || password.isBlank()) {
            Toast.makeText(this, "Email dan password wajib diisi", Toast.LENGTH_SHORT).show()
            return
        }

        setLoading(true)
        lifecycleScope.launch {
            when (val r = repo.login(email, password)) {
                is ApiResult.Success -> {
                    setLoading(false); goHome()
                }

                is ApiResult.Error -> {
                    setLoading(false)
                    Toast.makeText(this@LoginActivity, r.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun goHome() {
        startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }

    private fun setLoading(show: Boolean) {
        loadingOverlay.visibility = if (show) View.VISIBLE else View.GONE
        btnLogin.isEnabled = !show
    }
}