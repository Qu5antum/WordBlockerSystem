package com.example.wordblocker

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    // Вынесли пароль на уровень класса, ALERT: пароль для деактиваций приложение
    private val ADMIN_PASSWORD = "your_admin_password"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val etPassword = findViewById<EditText>(R.id.etPassword)
        val btnSubmit = findViewById<Button>(R.id.btnSubmit)

        // Запрашиваем права администратора устройства при первом открытии
        val compName = ComponentName(this, MyAdminReceiver::class.java)
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, compName)
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Это необходимо для защиты приложения от удаления.")
        }
        startActivity(intent)

        btnSubmit.setOnClickListener {
            val enteredPassword = etPassword.text.toString()

            if (enteredPassword == ADMIN_PASSWORD) {
                // Снимаем права администратора устройства, чтобы можно было удалить/отключить
                val compName = ComponentName(this, MyAdminReceiver::class.java)
                val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                if (dpm.isAdminActive(compName)) {
                    dpm.removeActiveAdmin(compName)
                }

                // Пароль верный! Сохраняем флаг в SharedPreferences
                val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
                prefs.edit().putBoolean("disable_settings_protection", true).apply()

                Toast.makeText(this, "Защита настроек отключена", Toast.LENGTH_SHORT).show()

            } else {
                Toast.makeText(this, "Неверный пароль!", Toast.LENGTH_SHORT).show()
                val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
                prefs.edit().putBoolean("disable_settings_protection", false).apply()
            }
        }
    }
}