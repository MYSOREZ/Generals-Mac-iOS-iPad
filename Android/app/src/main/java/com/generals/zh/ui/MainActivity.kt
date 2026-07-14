package com.generals.zh.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import android.widget.Button
import android.widget.TextView
import android.content.Intent
import com.generals.zh.R

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Setup UI Components
        val titleText = findViewById<TextView>(R.id.title_text)
        titleText.text = "Command & Conquer\nGenerals: Zero Hour"

        val playButton = findViewById<Button>(R.id.btn_play)
        val settingsButton = findViewById<Button>(R.id.btn_settings)
        val aboutButton = findViewById<Button>(R.id.btn_about)

        playButton.setOnClickListener {
            startActivity(Intent(this, GameActivity::class.java))
        }

        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        aboutButton.setOnClickListener {
            showAboutDialog()
        }
    }

    private fun showAboutDialog() {
        val aboutText = "Command & Conquer: Generals Zero Hour\n\n" +
                "Version 1.0\n\n" +
                "Experience the ultimate real-time strategy game\n" +
                "with modern Material 3 design!\n\n" +
                "© 2024 All Rights Reserved"

        android.app.AlertDialog.Builder(this, R.style.Theme_GeneralsZH)
            .setTitle("About")
            .setMessage(aboutText)
            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
            .show()
    }
}
