package com.salliptv.player.util

import android.content.Context
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView

/**
 * LinearLayoutManager that keeps the focused item centered in the viewport.
 * Like TiViMate / Apple TV channel lists — focus stays in the middle,
 * list scrolls around it. Only at the top/bottom edges does focus move.
 */
class CenterFocusLayoutManager(context: Context) : LinearLayoutManager(context) {

    override fun smoothScrollToPosition(recyclerView: RecyclerView, state: RecyclerView.State, position: Int) {
        val scroller = object : LinearSmoothScroller(recyclerView.context) {
            override fun calculateDtToFit(viewStart: Int, viewEnd: Int, boxStart: Int, boxEnd: Int, snapPreference: Int): Int {
                return (boxStart + (boxEnd - boxStart) / 2) - (viewStart + (viewEnd - viewStart) / 2)
            }
        }
        scroller.targetPosition = position
        startSmoothScroll(scroller)
    }

    override fun onFocusSearchFailed(focused: View, focusDirection: Int, recycler: RecyclerView.Recycler, state: RecyclerView.State): View? {
        return null
    }

    companion object {
        /**
         * Attach center-focus behavior to a RecyclerView.
         * Call this after setting the layout manager.
         */
        fun attachCenterFocus(recyclerView: RecyclerView) {
            recyclerView.viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus ->
                if (newFocus != null) {
                    // Only handle focus changes within OUR RecyclerView
                    var view: android.view.View? = newFocus
                    var isOurChild = false
                    while (view != null) {
                        if (view === recyclerView) { isOurChild = true; break }
                        view = view.parent as? android.view.View
                    }
                    if (!isOurChild) return@addOnGlobalFocusChangeListener

                    var pos = try { recyclerView.getChildAdapterPosition(newFocus) } catch (_: Exception) { -1 }
                    if (pos < 0 && newFocus.parent is android.view.View) {
                        pos = try { recyclerView.getChildAdapterPosition(newFocus.parent as android.view.View) } catch (_: Exception) { -1 }
                    }
                    if (pos >= 0) {
                        val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return@addOnGlobalFocusChangeListener
                        val viewHeight = recyclerView.height
                        val itemHeight = newFocus.height
                        val offset = (viewHeight - itemHeight) / 2
                        lm.scrollToPositionWithOffset(pos, offset)
                    }
                }
            }
        }
    }
}
