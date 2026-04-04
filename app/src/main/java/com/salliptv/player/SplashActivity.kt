package com.salliptv.player

import android.content.Intent
import android.graphics.drawable.AnimatedVectorDrawable
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        val ivHalo = findViewById<ImageView>(R.id.iv_halo)
        val tvBrand = findViewById<TextView>(R.id.tv_brand)

        // Start the AnimatedVectorDrawable laser draw effect
        val drawable = ivHalo.drawable
        if (drawable is AnimatedVectorDrawable) {
            drawable.start()
        }

        // After halo animation: fade in "AURA" text, then navigate
        lifecycleScope.launch {
            delay(900) // Wait for halo animation
            tvBrand.animate().alpha(1f).setDuration(400).start()

            delay(900) // Wait for text to show

            // Navigate to MainActivity
            startActivity(Intent(this@SplashActivity, MainActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        }
    }
}
