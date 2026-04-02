package com.salliptv.player.view

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.salliptv.player.R

/**
 * Premium Apple-style loader with animated dots and smooth transitions
 */
class PremiumLoader(context: Context) {
    
    private val container: FrameLayout
    private val titleView: TextView
    private val subtitleView: TextView
    private val countView: TextView
    private val dots: List<View>
    private val dotAnimators = mutableListOf<AnimatorSet>()
    
    private var isShowing = false
    
    init {
        val inflater = LayoutInflater.from(context)
        container = inflater.inflate(R.layout.view_premium_loader, null) as FrameLayout
        
        titleView = container.findViewById(R.id.loader_title)
        subtitleView = container.findViewById(R.id.loader_subtitle)
        countView = container.findViewById(R.id.loader_count)
        
        dots = listOf(
            container.findViewById(R.id.dot1),
            container.findViewById(R.id.dot2),
            container.findViewById(R.id.dot3),
            container.findViewById(R.id.dot4)
        )
        
        // Setup dot animations with stagger
        setupDotAnimations()
    }
    
    private fun setupDotAnimations() {
        dots.forEachIndexed { index, dot ->
            val animator = AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(dot, "alpha", 0.3f, 1f).apply {
                        duration = 600
                        repeatMode = ObjectAnimator.REVERSE
                        repeatCount = ObjectAnimator.INFINITE
                    },
                    ObjectAnimator.ofFloat(dot, "scaleX", 0.8f, 1.2f).apply {
                        duration = 600
                        repeatMode = ObjectAnimator.REVERSE
                        repeatCount = ObjectAnimator.INFINITE
                    },
                    ObjectAnimator.ofFloat(dot, "scaleY", 0.8f, 1.2f).apply {
                        duration = 600
                        repeatMode = ObjectAnimator.REVERSE
                        repeatCount = ObjectAnimator.INFINITE
                    }
                )
                startDelay = (index * 150).toLong() // Stagger effect
            }
            dotAnimators.add(animator)
        }
    }
    
    /**
     * Attach the loader to a parent view
     */
    fun attachTo(parent: ViewGroup) {
        if (container.parent == null) {
            parent.addView(container, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
    }
    
    /**
     * Show the loader with smooth fade-in
     */
    fun show(title: String = "Chargement", subtitle: String = "Préparation...") {
        if (isShowing) return
        
        titleView.text = title
        subtitleView.text = subtitle
        countView.text = ""
        
        container.visibility = View.VISIBLE
        container.alpha = 0f
        
        container.animate()
            .alpha(1f)
            .setDuration(300)
            .withStartAction {
                startDotAnimations()
            }
            .start()
        
        isShowing = true
    }
    
    /**
     * Update the progress count with animation
     */
    fun updateCount(count: Int, itemName: String = "chaînes") {
        countView.text = "$count $itemName"
        countView.animate()
            .scaleX(1.1f)
            .scaleY(1.1f)
            .setDuration(150)
            .withEndAction {
                countView.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(150)
                    .start()
            }
            .start()
    }
    
    /**
     * Update subtitle text
     */
    fun updateSubtitle(text: String) {
        subtitleView.text = text
    }
    
    /**
     * Hide the loader with smooth fade-out
     */
    fun hide() {
        if (!isShowing) return
        
        container.animate()
            .alpha(0f)
            .setDuration(200)
            .withEndAction {
                container.visibility = View.GONE
                stopDotAnimations()
            }
            .start()
        
        isShowing = false
    }
    
    private fun startDotAnimations() {
        dotAnimators.forEach { it.start() }
    }
    
    private fun stopDotAnimations() {
        dotAnimators.forEach { 
            it.cancel()
        }
    }
    
    /**
     * Check if loader is currently showing
     */
    fun isVisible(): Boolean = isShowing
}
