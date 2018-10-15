package com.pr0gramm.app.ui

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.pr0gramm.app.util.logger
import com.pr0gramm.app.util.time
import java.util.*

/**
 * Tries to display tags in multiple line using a defined spacing.
 * It "works" but it is not nice, I guess.
 */
class TagCloudLayoutManager(private val gapX: Int, private val gapY: Int, private val maxNumberOfRows: Int) : androidx.recyclerview.widget.RecyclerView.LayoutManager() {
    private var config = Config(0, 0, 0)
    private var scrollOffset: Int = 0

    override fun generateDefaultLayoutParams(): androidx.recyclerview.widget.RecyclerView.LayoutParams {
        return androidx.recyclerview.widget.RecyclerView.LayoutParams(
                androidx.recyclerview.widget.RecyclerView.LayoutParams.WRAP_CONTENT,
                androidx.recyclerview.widget.RecyclerView.LayoutParams.WRAP_CONTENT)
    }

    override fun onLayoutChildren(recycler: androidx.recyclerview.widget.RecyclerView.Recycler, state: androidx.recyclerview.widget.RecyclerView.State) {
        detachAndScrapAttachedViews(recycler)
        if (config.width <= 0) {
            logger.warn("onLayoutChildren called before onMeasure")
            return
        }

        var top = 0
        var left = 0
        for (idx in 0 until state.itemCount) {
            val view = recycler.getViewForPosition(idx)
            removeMarginsFromView(view)

            val size = measureChildUnspecified(view)

            if (left + size.width > config.width && left > 0) {
                left = 0
                top += size.height + gapY
            }

            addView(view)
            layoutDecorated(view, left, top, left + size.width, top + size.height)
            left += size.width + gapX
        }

        scrollOffset = Math.min(scrollOffset, computeHorizontalScrollRange(state))
        offsetChildrenHorizontal(-scrollOffset)
    }

    override fun onMeasure(recycler: androidx.recyclerview.widget.RecyclerView.Recycler, state: androidx.recyclerview.widget.RecyclerView.State, widthSpec: Int, heightSpec: Int) {
        val parentWidth = View.MeasureSpec.getSize(widthSpec)

        this.config = logger.time("measure tag sizes") {
            val sizes = measureElements(recycler)

            // estimate the needed with using brute force!
            var width = if (maxNumberOfRows == 1) Integer.MAX_VALUE else parentWidth

            var config = measureConfig(sizes, width)
            while (config.rows > maxNumberOfRows) {
                width += Math.max(10, (width * 0.1).toInt())
                config = measureConfig(sizes, width)
            }

            config
        }

        setMeasuredDimension(parentWidth, config.height)
    }

    private fun measureConfig(sizes: List<Size>, maxWidth: Int): Config {
        var left = 0
        var top = 0
        var width = 0
        var height = 0
        var rows = 1
        for (idx in sizes.indices) {
            val size = sizes[idx]
            if (left + size.width > maxWidth && left > 0) {
                left = 0
                top += size.height + gapY
                rows++
            }

            height = Math.max(height, top + size.height)
            width = Math.max(width, left + size.width)

            left += size.width + gapX
        }

        return Config(width, height, rows)
    }

    private fun measureElements(recycler: androidx.recyclerview.widget.RecyclerView.Recycler): List<Size> {
        val sizes = ArrayList<Size>()
        for (idx in 0 until itemCount) {
            val view = recycler.getViewForPosition(idx)
            removeMarginsFromView(view)
            sizes.add(measureChildUnspecified(view))

            detachView(view)
            recycler.recycleView(view)
        }

        return sizes
    }

    override fun onAdapterChanged(oldAdapter: androidx.recyclerview.widget.RecyclerView.Adapter<*>?, newAdapter: androidx.recyclerview.widget.RecyclerView.Adapter<*>?) {
        removeAllViews()
    }

    override fun canScrollHorizontally(): Boolean {
        return true
    }

    override fun scrollHorizontallyBy(dx: Int, recycler: androidx.recyclerview.widget.RecyclerView.Recycler?, state: androidx.recyclerview.widget.RecyclerView.State): Int {
        var scroll = dx

        val maxScroll = computeHorizontalScrollRange(state)
        if (scrollOffset + scroll < 0) {
            scroll = -scrollOffset

        } else if (scrollOffset + scroll > maxScroll) {
            scroll = -(scrollOffset - maxScroll)
        }

        scrollOffset += scroll

        offsetChildrenHorizontal(-scroll)
        return scroll
    }

    override fun computeHorizontalScrollOffset(state: androidx.recyclerview.widget.RecyclerView.State): Int {
        return Math.min(scrollOffset, computeHorizontalScrollRange(state))
    }

    override fun computeHorizontalScrollRange(state: androidx.recyclerview.widget.RecyclerView.State): Int {
        return Math.max(0, config.width - width)
    }

    override fun computeHorizontalScrollExtent(state: androidx.recyclerview.widget.RecyclerView.State): Int {
        return Math.max(1, computeHorizontalScrollRange(state) / 10)
    }

    /**
     * Removes all margins from the given view.
     */
    private fun removeMarginsFromView(view: View) {
        (view.layoutParams as ViewGroup.MarginLayoutParams).setMargins(0, 0, 0, 0)
    }

    private fun measureChildUnspecified(view: View): Size {
        val spec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)

        val p = view.layoutParams as androidx.recyclerview.widget.RecyclerView.LayoutParams
        val childWidthSpec = ViewGroup.getChildMeasureSpec(spec, 0, p.width)
        val childHeightSpec = ViewGroup.getChildMeasureSpec(spec, 0, p.height)
        view.measure(childWidthSpec, childHeightSpec)

        return Size(view.measuredWidth, view.measuredHeight)
    }

    private class Size(val width: Int, val height: Int)

    private class Config(val width: Int, val height: Int, val rows: Int)

    companion object {
        private val logger = logger("TagCloudLayoutManager")
    }
}
