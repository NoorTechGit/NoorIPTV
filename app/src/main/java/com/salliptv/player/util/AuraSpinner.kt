package com.salliptv.player.util

import android.content.Context
import android.view.animation.AnimationUtils
import android.widget.ImageView
import com.salliptv.player.R

object AuraSpinner {
    fun startSpinner(context: Context, imageView: ImageView) {
        imageView.setImageResource(R.drawable.ic_aura_logo)
        val anim = AnimationUtils.loadAnimation(context, R.anim.rotate_spinner)
        imageView.startAnimation(anim)
    }

    fun stopSpinner(imageView: ImageView) {
        imageView.clearAnimation()
    }
}
