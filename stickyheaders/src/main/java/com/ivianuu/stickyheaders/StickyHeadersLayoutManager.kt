/*
 * Copyright 2017 Manuel Wrage
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ivianuu.stickyheaders

import android.content.Context
import android.graphics.PointF
import android.os.Build
import android.os.Parcelable
import android.view.View
import android.view.ViewTreeObserver
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.android.parcel.Parcelize

/**
 * [LinearLayoutManager] with sticky header support
 */
class StickyHeadersLayoutManager @JvmOverloads constructor(
    context: Context,
    callback: Callback? = null,
    orientation: Int = RecyclerView.VERTICAL,
    reverseLayout: Boolean = false
) : LinearLayoutManager(context, orientation, reverseLayout) {

    /**
     * The x translation of sticky headers
     */
    var stickyHeaderTranslationX = 0f
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    /**
     * The y translation of sticky headers
     */
    var stickyHeaderTranslationY = 0f
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    /**
     * The callback which will be used internally
     */
    var callback: Callback? = null

    /**
     * Whether or not sticky headers should be shown or not
     */
    var areStickyHeadersEnabled = true
        set(value) {
            if (field != value) {
                field = value
                if (value) {
                    requestLayout()
                } else if (stickyHeader != null) {
                    scrapStickyHeader(null)
                }
            }
        }

    private var adapter: RecyclerView.Adapter<*>? = null

    private val headerPositions = mutableListOf<Int>()
    private val headerPositionsObserver = HeaderPositionsAdapterDataObserver()

    private var stickyHeader: View? = null
    private var stickyHeaderPosition = RecyclerView.NO_POSITION

    private var pendingScrollPosition = RecyclerView.NO_POSITION
    private var pendingScrollOffset = 0

    init {
        this.callback = callback
    }

    /**
     * Returns whether or not [view] is the current sticky header
     */
    fun isStickyHeader(view: View) = view == stickyHeader

    override fun onAttachedToWindow(view: RecyclerView) {
        super.onAttachedToWindow(view)
        setAdapter(view.adapter)
    }

    override fun onAdapterChanged(
        oldAdapter: RecyclerView.Adapter<*>?,
        newAdapter: RecyclerView.Adapter<*>?
    ) {
        super.onAdapterChanged(oldAdapter, newAdapter)
        newAdapter?.let { setAdapter(it) }
    }

    private fun setAdapter(adapter: RecyclerView.Adapter<*>?) {
        this.adapter?.unregisterAdapterDataObserver(headerPositionsObserver)

        if (adapter != null) {
            if (adapter is Callback) callback = adapter
            this.adapter = adapter
            adapter.registerAdapterDataObserver(headerPositionsObserver)
            headerPositionsObserver.onChanged()
        } else {
            if (this.adapter == callback) callback = null
            this.adapter = null
            headerPositions.clear()
        }
    }

    override fun onSaveInstanceState(): Parcelable? = SavedState(
        super.onSaveInstanceState(), pendingScrollPosition, pendingScrollOffset
    )

    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state != null && state is SavedState) {
            pendingScrollOffset = state.pendingScrollOffset
            pendingScrollPosition = state.pendingScrollPosition
            super.onRestoreInstanceState(state.superState)
        } else {
            super.onRestoreInstanceState(state)
        }
    }

    override fun scrollVerticallyBy(
        dy: Int,
        recycler: RecyclerView.Recycler,
        state: RecyclerView.State
    ): Int {
        detachStickyHeader()
        val scrolled = super.scrollVerticallyBy(dy, recycler, state)
        attachStickyHeader()

        if (scrolled != 0) updateStickyHeader(recycler, false)

        return scrolled
    }

    override fun scrollHorizontallyBy(
        dx: Int,
        recycler: RecyclerView.Recycler,
        state: RecyclerView.State
    ): Int {
        detachStickyHeader()
        val scrolled = super.scrollHorizontallyBy(dx, recycler, state)
        attachStickyHeader()

        if (scrolled != 0) updateStickyHeader(recycler, false)

        return scrolled
    }

    override fun onLayoutChildren(recycler: RecyclerView.Recycler, state: RecyclerView.State) {
        detachStickyHeader()
        super.onLayoutChildren(recycler, state)
        attachStickyHeader()

        if (!state.isPreLayout) updateStickyHeader(recycler, true)
    }

    override fun scrollToPosition(position: Int) {
        scrollToPositionWithOffset(position, LinearLayoutManager.INVALID_OFFSET)
    }

    override fun scrollToPositionWithOffset(position: Int, offset: Int) {
        scrollToPositionWithOffset(position, offset, true)
    }

    private fun scrollToPositionWithOffset(
        position: Int,
        offset: Int,
        adjustForStickyHeader: Boolean
    ) {
        // Reset pending scroll.
        setPendingScroll(RecyclerView.NO_POSITION, LinearLayoutManager.INVALID_OFFSET)

        // Adjusting is disabled.
        if (!adjustForStickyHeader) {
            super.scrollToPositionWithOffset(position, offset)
            return
        }

        // There is no header above or the position is a header.
        val headerIndex = findHeaderIndexOrBefore(position)
        if (headerIndex == -1 || findHeaderIndex(position) != -1) {
            super.scrollToPositionWithOffset(position, offset)
            return
        }

        // The position is right below a header, scroll to the header.
        if (findHeaderIndex(position - 1) != -1) {
            super.scrollToPositionWithOffset(position - 1, offset)
            return
        }

        val stickyHeader = stickyHeader

        // Current sticky header is the same as at the position. Adjust the scroll offset and reset pending scroll.
        if (stickyHeader != null && headerIndex == findHeaderIndex(stickyHeaderPosition)) {
            val adjustedOffset =
                (if (offset != LinearLayoutManager.INVALID_OFFSET) offset else 0) + stickyHeader.height
            super.scrollToPositionWithOffset(position, adjustedOffset)
            return
        }

        // Remember this position and offset and scroll to it to trigger creating the sticky header.
        setPendingScroll(position, offset)
        super.scrollToPositionWithOffset(position, offset)
    }

    override fun computeVerticalScrollExtent(state: RecyclerView.State): Int {
        detachStickyHeader()
        val extent = super.computeVerticalScrollExtent(state)
        attachStickyHeader()
        return extent
    }

    override fun computeVerticalScrollOffset(state: RecyclerView.State): Int {
        detachStickyHeader()
        val offset = super.computeVerticalScrollOffset(state)
        attachStickyHeader()
        return offset
    }

    override fun computeVerticalScrollRange(state: RecyclerView.State): Int {
        detachStickyHeader()
        val range = super.computeVerticalScrollRange(state)
        attachStickyHeader()
        return range
    }

    override fun computeHorizontalScrollExtent(state: RecyclerView.State): Int {
        detachStickyHeader()
        val extent = super.computeHorizontalScrollExtent(state)
        attachStickyHeader()
        return extent
    }

    override fun computeHorizontalScrollOffset(state: RecyclerView.State): Int {
        detachStickyHeader()
        val offset = super.computeHorizontalScrollOffset(state)
        attachStickyHeader()
        return offset
    }

    override fun computeHorizontalScrollRange(state: RecyclerView.State): Int {
        detachStickyHeader()
        val range = super.computeHorizontalScrollRange(state)
        attachStickyHeader()
        return range
    }

    override fun computeScrollVectorForPosition(targetPosition: Int): PointF? {
        detachStickyHeader()
        val vector = super.computeScrollVectorForPosition(targetPosition)
        attachStickyHeader()
        return vector
    }

    override fun onFocusSearchFailed(
        focused: View, focusDirection: Int, recycler: RecyclerView.Recycler,
        state: RecyclerView.State
    ): View? {
        detachStickyHeader()
        val view = super.onFocusSearchFailed(focused, focusDirection, recycler, state)
        attachStickyHeader()
        return view
    }

    private fun detachStickyHeader() {
        stickyHeader?.let { detachView(it) }
    }

    private fun attachStickyHeader() {
        stickyHeader?.let { attachView(it) }
    }

    private fun updateStickyHeader(recycler: RecyclerView.Recycler, layout: Boolean) {
        if (!areStickyHeadersEnabled) return

        val headerCount = headerPositions.size
        val childCount = childCount

        if (headerCount > 0 && childCount > 0) {
            // Find first valid child.
            var anchorView: View? = null
            var anchorIndex = -1
            var anchorPos = -1
            for (i in 0 until childCount) {
                val child = getChildAt(i)!!
                val params = child.layoutParams as RecyclerView.LayoutParams
                if (isViewValidAnchor(child, params)) {
                    anchorView = child
                    anchorIndex = i
                    anchorPos = params.viewAdapterPosition
                    break
                }
            }
            if (anchorView != null && anchorPos != -1) {
                val headerIndex = findHeaderIndexOrBefore(anchorPos)
                val headerPos = if (headerIndex != -1) headerPositions[headerIndex] else -1
                val nextHeaderPos =
                    if (headerCount > headerIndex + 1) headerPositions[headerIndex + 1] else -1

                // Show sticky header if:
                // - There's one to show;
                // - It's on the edge or it's not the anchor view;
                // - Isn't followed by another sticky header;
                if (headerPos != -1
                    && (headerPos != anchorPos || isViewOnBoundary(anchorView))
                    && nextHeaderPos != headerPos + 1) {
                    // Ensure existing sticky header, if any, is of correct type.
                    if (stickyHeader != null
                        && getItemViewType(stickyHeader!!) != adapter?.getItemViewType(headerPos)) {
                        // A sticky header was shown before but is not of the correct type. Scrap it.
                        scrapStickyHeader(recycler)
                    }

                    // Ensure sticky header is created, if absent, or bound, if being laid out or the position changed.
                    if (stickyHeader == null) {
                        createStickyHeader(recycler, headerPos)
                    }
                    if (layout || getPosition(stickyHeader!!) != headerPos) {
                        bindStickyHeader(recycler, headerPos)
                    }

                    // Draw the sticky header using translation values which depend on orientation, direction and
                    // position of the next header view.
                    var nextHeaderView: View? = null
                    if (nextHeaderPos != -1) {
                        nextHeaderView = getChildAt(anchorIndex + (nextHeaderPos - anchorPos))
                        // The header view itself is added to the RecyclerView. Discard it if it comes up.
                        if (nextHeaderView == stickyHeader) {
                            nextHeaderView = null
                        }
                    }
                    stickyHeader?.let {
                        it.translationX = getX(it, nextHeaderView)
                        it.translationY = getY(it, nextHeaderView)
                    }
                    return
                }
            }
        }

        if (stickyHeader != null) {
            scrapStickyHeader(recycler)
        }
    }

    private fun createStickyHeader(recycler: RecyclerView.Recycler, position: Int) {
        val stickyHeader = recycler.getViewForPosition(position)

        // Setup sticky header if the adapter requires it.
        callback?.setupStickyHeaderView(stickyHeader)

        // Add sticky header as a child view, to be detached / reattached whenever LinearLayoutManager#fill() is called,
        // which happens on layout and scroll (see overrides).
        addView(stickyHeader)
        measureAndLayout(stickyHeader)

        // Ignore sticky header, as it's fully managed by this LayoutManager.
        ignoreView(stickyHeader)

        this.stickyHeader = stickyHeader
        stickyHeaderPosition = position
    }

    private fun bindStickyHeader(recycler: RecyclerView.Recycler, position: Int) {
        val stickyHeader = stickyHeader ?: return

        // Bind the sticky header.
        recycler.bindViewToPosition(stickyHeader, position)
        stickyHeaderPosition = position
        measureAndLayout(stickyHeader)

        // If we have a pending scroll wait until the end of layout and scroll again.
        if (pendingScrollPosition != RecyclerView.NO_POSITION) {
            stickyHeader.viewTreeObserver.addOnGlobalLayoutListener(object :
                ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                        stickyHeader.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    } else {
                        @Suppress("DEPRECATION")
                        stickyHeader.viewTreeObserver.removeGlobalOnLayoutListener(this)
                    }

                    if (pendingScrollPosition != RecyclerView.NO_POSITION) {
                        scrollToPositionWithOffset(pendingScrollPosition, pendingScrollOffset)
                        setPendingScroll(
                            RecyclerView.NO_POSITION,
                            LinearLayoutManager.INVALID_OFFSET
                        )
                    }
                }
            })
        }
    }

    private fun measureAndLayout(stickyHeader: View) {
        measureChildWithMargins(stickyHeader, 0, 0)
        if (orientation == LinearLayoutManager.VERTICAL) {
            stickyHeader.layout(paddingLeft, 0, width - paddingRight, stickyHeader.measuredHeight)
        } else {
            stickyHeader.layout(0, paddingTop, stickyHeader.measuredWidth, height - paddingBottom)
        }
    }

    private fun scrapStickyHeader(recycler: RecyclerView.Recycler?) {
        val stickyHeader = stickyHeader
        this.stickyHeader = null
        stickyHeaderPosition = RecyclerView.NO_POSITION

        if (stickyHeader != null) {
            // Revert translation values.
            stickyHeader.translationX = 0f
            stickyHeader.translationY = 0f

            // Teardown holder if the adapter requires it.
            callback?.teardownStickyHeaderView(stickyHeader)

            // Stop ignoring sticky header so that it can be recycled.
            stopIgnoringView(stickyHeader)

            // Remove and recycle sticky header.
            removeView(stickyHeader)
            recycler?.recycleView(stickyHeader)
        }
    }

    private fun isViewValidAnchor(view: View, params: RecyclerView.LayoutParams): Boolean {
        return if (!params.isItemRemoved && !params.isViewInvalid) {
            if (orientation == LinearLayoutManager.VERTICAL) {
                if (reverseLayout) {
                    view.top + view.translationY <= height + stickyHeaderTranslationY
                } else {
                    view.bottom - view.translationY >= stickyHeaderTranslationY
                }
            } else {
                if (reverseLayout) {
                    view.left + view.translationX <= width + stickyHeaderTranslationX
                } else {
                    view.right - view.translationX >= stickyHeaderTranslationX
                }
            }
        } else {
            false
        }
    }

    private fun isViewOnBoundary(view: View): Boolean {
        return if (orientation == LinearLayoutManager.VERTICAL) {
            if (reverseLayout) {
                view.bottom - view.translationY > height + stickyHeaderTranslationY
            } else {
                view.top + view.translationY < stickyHeaderTranslationY
            }
        } else {
            if (reverseLayout) {
                view.right - view.translationX > width + stickyHeaderTranslationX
            } else {
                view.left + view.translationX < stickyHeaderTranslationX
            }
        }
    }

    private fun getY(headerView: View, nextHeaderView: View?): Float {
        return if (orientation == LinearLayoutManager.VERTICAL) {
            var y = stickyHeaderTranslationY
            if (reverseLayout) {
                y += (height - headerView.height).toFloat()
            }
            if (nextHeaderView != null) {
                y = if (reverseLayout) {
                    Math.max(nextHeaderView.bottom.toFloat(), y)
                } else {
                    Math.min((nextHeaderView.top - headerView.height).toFloat(), y)
                }
            }
            y
        } else {
            stickyHeaderTranslationY
        }
    }

    private fun getX(headerView: View, nextHeaderView: View?): Float {
        return if (orientation != LinearLayoutManager.VERTICAL) {
            var x = stickyHeaderTranslationX
            if (reverseLayout) {
                x += (width - headerView.width).toFloat()
            }
            if (nextHeaderView != null) {
                x = if (reverseLayout) {
                    Math.max(nextHeaderView.right.toFloat(), x)
                } else {
                    Math.min((nextHeaderView.left - headerView.width).toFloat(), x)
                }
            }
            x
        } else {
            stickyHeaderTranslationX
        }
    }

    private fun findHeaderIndex(position: Int): Int {
        var low = 0
        var high = headerPositions.size - 1
        while (low <= high) {
            val middle = (low + high) / 2
            when {
                headerPositions[middle] > position -> high = middle - 1
                headerPositions[middle] < position -> low = middle + 1
                else -> return middle
            }
        }
        return -1
    }

    private fun findHeaderIndexOrBefore(position: Int): Int {
        var low = 0
        var high = headerPositions.size - 1
        while (low <= high) {
            val middle = (low + high) / 2
            if (headerPositions[middle] > position) {
                high = middle - 1
            } else if (middle < headerPositions.size - 1 && headerPositions[middle + 1] <= position) {
                low = middle + 1
            } else {
                return middle
            }
        }
        return -1
    }

    private fun setPendingScroll(position: Int, offset: Int) {
        pendingScrollPosition = position
        pendingScrollOffset = offset
    }

    private fun isStickyHeader(position: Int): Boolean {
        callback?.let { return it.isStickyHeader(position) }
        return false
    }

    private inner class HeaderPositionsAdapterDataObserver : RecyclerView.AdapterDataObserver() {
        override fun onChanged() {
            handleChange()
        }

        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
            handleChange()
        }

        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
            handleChange()
        }

        override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) {
            handleChange()
        }

        private fun handleChange() {
            val adapter = adapter ?: return

            headerPositions.clear()
            val itemCount = adapter.itemCount
            (0 until itemCount).filterTo(headerPositions) { isStickyHeader(it) }

            // Remove sticky header immediately if the entry it represents has been removed. A layout will follow.
            if (stickyHeader != null && !headerPositions.contains(stickyHeaderPosition)) {
                scrapStickyHeader(null)
            }
        }
    }

    /**
     * Callback to determine if the position is a sticky header or not
     */
    interface Callback {
        fun isStickyHeader(position: Int): Boolean

        fun setupStickyHeaderView(stickyHeader: View)

        fun teardownStickyHeaderView(stickyHeader: View)
    }

    @Parcelize
    private data class SavedState(
        val superState: Parcelable?,
        val pendingScrollPosition: Int,
        val pendingScrollOffset: Int
    ) : Parcelable

}