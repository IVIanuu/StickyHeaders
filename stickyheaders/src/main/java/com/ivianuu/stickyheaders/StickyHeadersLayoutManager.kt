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

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PointF
import android.os.Build
import android.os.Parcelable
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.view.View
import android.view.ViewTreeObserver
import kotlinx.android.parcel.Parcelize
import java.util.*

/**
 * Callback to determine if the position is a sticky header or not
 */
interface StickyHeadersCallback {
    /**
     * Returns whether the view at this position is a sticky header or not
     */
    fun isStickyHeader(position: Int): Boolean

    /**
     * This will be called when the view turns into a sticky state
     */
    fun setupStickyHeaderView(stickyHeader: View)

    /**
     * Should revert any changes made in [setupStickyHeaderView]
     */
    fun teardownStickyHeaderView(stickyHeader: View)
}

/**
 * LinearLayoutManager which supports sticky headers
 */
class StickyHeadersLayoutManager @JvmOverloads constructor(context: Context,
                                                           orientation: Int = LinearLayoutManager.VERTICAL,
                                                           reverseLayout: Boolean = false
): LinearLayoutManager(context, orientation, reverseLayout) {

    private var mAdapter: RecyclerView.Adapter<*>? = null

    private var mTranslationX: Float = 0.toFloat()
    private var mTranslationY: Float = 0.toFloat()

    private val mHeaderPositions = ArrayList<Int>()
    private val mHeaderPositionsObserver = HeaderPositionsAdapterDataObserver()

    private var mStickyHeader: View? = null
    private var mStickyHeaderPosition = RecyclerView.NO_POSITION

    private var mPendingScrollPosition = RecyclerView.NO_POSITION
    private var mPendingScrollOffset = 0

    private var callback: StickyHeadersCallback? = null

    /**
     * Offsets the vertical location of the sticky header relative to the its default position.
     */
    fun setStickyHeaderTranslationY(translationY: Float) {
        mTranslationY = translationY
        requestLayout()
    }

    /**
     * Offsets the horizontal location of the sticky header relative to the its default position.
     */
    fun setStickyHeaderTranslationX(translationX: Float) {
        mTranslationX = translationX
        requestLayout()
    }

    /**
     * Returns true if `view` is the current sticky header.
     */
    fun isStickyHeader(view: View): Boolean {
        return view === mStickyHeader
    }

    /**
     * Sets the sticky headers callback
     */
    fun setCallback(callback: StickyHeadersCallback?) {
        this.callback = callback
    }

    override fun onAttachedToWindow(view: RecyclerView?) {
        super.onAttachedToWindow(view)
        view?.let { setAdapter(it.adapter) }
    }

    override fun onAdapterChanged(oldAdapter: RecyclerView.Adapter<*>?, newAdapter: RecyclerView.Adapter<*>?) {
        super.onAdapterChanged(oldAdapter, newAdapter)
        newAdapter?.let { setAdapter(it) }
    }

    private fun setAdapter(adapter: RecyclerView.Adapter<*>?) {
        mAdapter?.unregisterAdapterDataObserver(mHeaderPositionsObserver)

        if (adapter != null) {
            mAdapter = adapter
            adapter.registerAdapterDataObserver(mHeaderPositionsObserver)
            mHeaderPositionsObserver.onChanged()
        } else {
            mAdapter = null
            mHeaderPositions.clear()
        }
    }

    override fun onSaveInstanceState() =
            com.ivianuu.stickyheaders.SavedState(super.onSaveInstanceState(), mPendingScrollPosition, mPendingScrollOffset)

    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state != null && state is com.ivianuu.stickyheaders.SavedState) {
            mPendingScrollOffset = state.pendingScrollOffset
            mPendingScrollPosition = state.pendingScrollPosition
            super.onRestoreInstanceState(state.superState)
        } else {
            super.onRestoreInstanceState(state)
        }
    }

    override fun scrollVerticallyBy(dy: Int, recycler: RecyclerView.Recycler?, state: RecyclerView.State?): Int {
        detachStickyHeader()
        val scrolled = super.scrollVerticallyBy(dy, recycler, state)
        attachStickyHeader()

        if (scrolled != 0) {
            recycler?.let { updateStickyHeader(it, false) }
        }

        return scrolled
    }

    override fun scrollHorizontallyBy(dx: Int, recycler: RecyclerView.Recycler?, state: RecyclerView.State?): Int {
        detachStickyHeader()
        val scrolled = super.scrollHorizontallyBy(dx, recycler, state)
        attachStickyHeader()

        if (scrolled != 0) {
            recycler?.let { updateStickyHeader(it, false) }
        }

        return scrolled
    }

    override fun onLayoutChildren(recycler: RecyclerView.Recycler?, state: RecyclerView.State) {
        detachStickyHeader()
        super.onLayoutChildren(recycler, state)
        attachStickyHeader()

        if (!state.isPreLayout) {
            recycler?.let { updateStickyHeader(it, true) }
        }
    }

    override fun scrollToPosition(position: Int) {
        scrollToPositionWithOffset(position, LinearLayoutManager.INVALID_OFFSET)
    }

    override fun scrollToPositionWithOffset(position: Int, offset: Int) {
        scrollToPositionWithOffset(position, offset, true)
    }

    private fun scrollToPositionWithOffset(position: Int, offset: Int, adjustForStickyHeader: Boolean) {
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

        val stickyHeader = mStickyHeader

        // Current sticky header is the same as at the position. Adjust the scroll offset and reset pending scroll.
        if (stickyHeader != null && headerIndex == findHeaderIndex(mStickyHeaderPosition)) {
            val adjustedOffset = (if (offset != LinearLayoutManager.INVALID_OFFSET) offset else 0) + stickyHeader.height
            super.scrollToPositionWithOffset(position, adjustedOffset)
            return
        }

        // Remember this position and offset and scroll to it to trigger creating the sticky header.
        setPendingScroll(position, offset)
        super.scrollToPositionWithOffset(position, offset)
    }

    override fun computeVerticalScrollExtent(state: RecyclerView.State?): Int {
        detachStickyHeader()
        val extent = super.computeVerticalScrollExtent(state)
        attachStickyHeader()
        return extent
    }

    override fun computeVerticalScrollOffset(state: RecyclerView.State?): Int {
        detachStickyHeader()
        val offset = super.computeVerticalScrollOffset(state)
        attachStickyHeader()
        return offset
    }

    override fun computeVerticalScrollRange(state: RecyclerView.State?): Int {
        detachStickyHeader()
        val range = super.computeVerticalScrollRange(state)
        attachStickyHeader()
        return range
    }

    override fun computeHorizontalScrollExtent(state: RecyclerView.State?): Int {
        detachStickyHeader()
        val extent = super.computeHorizontalScrollExtent(state)
        attachStickyHeader()
        return extent
    }

    override fun computeHorizontalScrollOffset(state: RecyclerView.State?): Int {
        detachStickyHeader()
        val offset = super.computeHorizontalScrollOffset(state)
        attachStickyHeader()
        return offset
    }

    override fun computeHorizontalScrollRange(state: RecyclerView.State?): Int {
        detachStickyHeader()
        val range = super.computeHorizontalScrollRange(state)
        attachStickyHeader()
        return range
    }

    override fun computeScrollVectorForPosition(targetPosition: Int): PointF {
        detachStickyHeader()
        val vector = super.computeScrollVectorForPosition(targetPosition)
        attachStickyHeader()
        return vector
    }

    override fun onFocusSearchFailed(focused: View?, focusDirection: Int, recycler: RecyclerView.Recycler?,
                                     state: RecyclerView.State?
    ): View? {
        detachStickyHeader()
        val view = super.onFocusSearchFailed(focused, focusDirection, recycler, state)
        attachStickyHeader()
        return view
    }

    private fun detachStickyHeader() {
        if (mStickyHeader != null) {
            detachView(mStickyHeader)
        }
    }

    private fun attachStickyHeader() {
        if (mStickyHeader != null) {
            attachView(mStickyHeader)
        }
    }

    /**
     * Updates the sticky header state (creation, binding, display), to be called whenever there's a layout or scroll
     */
    private fun updateStickyHeader(recycler: RecyclerView.Recycler, layout: Boolean) {
        val headerCount = mHeaderPositions.size
        val childCount = childCount

        if (headerCount > 0 && childCount > 0) {
            // Find first valid child.
            var anchorView: View? = null
            var anchorIndex = -1
            var anchorPos = -1
            for (i in 0 until childCount) {
                val child = getChildAt(i)
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
                val headerPos = if (headerIndex != -1) mHeaderPositions[headerIndex] else -1
                val nextHeaderPos = if (headerCount > headerIndex + 1) mHeaderPositions[headerIndex + 1] else -1

                // Show sticky header if:
                // - There's one to show;
                // - It's on the edge or it's not the anchor view;
                // - Isn't followed by another sticky header;
                if (headerPos != -1
                        && (headerPos != anchorPos || isViewOnBoundary(anchorView))
                        && nextHeaderPos != headerPos + 1) {
                    // Ensure existing sticky header, if any, is of correct type.
                    if (mStickyHeader != null && getItemViewType(
                            mStickyHeader) != mAdapter?.getItemViewType(headerPos)) {
                        // A sticky header was shown before but is not of the correct type. Scrap it.
                        scrapStickyHeader(recycler)
                    }

                    // Ensure sticky header is created, if absent, or bound, if being laid out or the position changed.
                    if (mStickyHeader == null) {
                        createStickyHeader(recycler, headerPos)
                    }
                    if (layout || getPosition(mStickyHeader!!) != headerPos) {
                        bindStickyHeader(recycler, headerPos)
                    }

                    // Draw the sticky header using translation values which depend on orientation, direction and
                    // position of the next header view.
                    var nextHeaderView: View? = null
                    if (nextHeaderPos != -1) {
                        nextHeaderView = getChildAt(anchorIndex + (nextHeaderPos - anchorPos))
                        // The header view itself is added to the RecyclerView. Discard it if it comes up.
                        if (nextHeaderView === mStickyHeader) {
                            nextHeaderView = null
                        }
                    }
                    mStickyHeader?.let {
                        it.translationX = getX(it, nextHeaderView)
                        it.translationY = getY(it, nextHeaderView)
                    }
                    return
                }
            }
        }

        if (mStickyHeader != null) {
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

        mStickyHeader = stickyHeader
        mStickyHeaderPosition = position
    }

    private fun bindStickyHeader(recycler: RecyclerView.Recycler, position: Int) {
        val stickyHeader = mStickyHeader ?: return

        // Bind the sticky header.
        recycler.bindViewToPosition(stickyHeader, position)
        mStickyHeaderPosition = position
        measureAndLayout(stickyHeader)

        // If we have a pending scroll wait until the end of layout and scroll again.
        if (mPendingScrollPosition != RecyclerView.NO_POSITION) {
            stickyHeader.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                        stickyHeader.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    } else {
                        @Suppress("DEPRECATION")
                        stickyHeader.viewTreeObserver.removeGlobalOnLayoutListener(this)
                    }

                    if (mPendingScrollPosition != RecyclerView.NO_POSITION) {
                        scrollToPositionWithOffset(mPendingScrollPosition, mPendingScrollOffset)
                        setPendingScroll(RecyclerView.NO_POSITION,
                                LinearLayoutManager.INVALID_OFFSET)
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
        val stickyHeader = mStickyHeader
        mStickyHeader = null
        mStickyHeaderPosition = RecyclerView.NO_POSITION

        stickyHeader?.let {
            // Revert translation values.
            it.translationX = 0f
            it.translationY = 0f

            // Teardown holder if the adapter requires it.
            callback?.teardownStickyHeaderView(it)

            // Stop ignoring sticky header so that it can be recycled.
            stopIgnoringView(it)

            // Remove and recycle sticky header.
            removeView(it)
            recycler?.recycleView(it)
        }
    }

    private fun isViewValidAnchor(view: View, params: RecyclerView.LayoutParams): Boolean {
        return if (!params.isItemRemoved && !params.isViewInvalid) {
            if (orientation == LinearLayoutManager.VERTICAL) {
                if (reverseLayout) {
                    view.top + view.translationY <= height + mTranslationY
                } else {
                    view.bottom - view.translationY >= mTranslationY
                }
            } else {
                if (reverseLayout) {
                    view.left + view.translationX <= width + mTranslationX
                } else {
                    view.right - view.translationX >= mTranslationX
                }
            }
        } else {
            false
        }
    }

    private fun isViewOnBoundary(view: View): Boolean {
        return if (orientation == LinearLayoutManager.VERTICAL) {
            if (reverseLayout) {
                view.bottom - view.translationY > height + mTranslationY
            } else {
                view.top + view.translationY < mTranslationY
            }
        } else {
            if (reverseLayout) {
                view.right - view.translationX > width + mTranslationX
            } else {
                view.left + view.translationX < mTranslationX
            }
        }
    }

    private fun getY(headerView: View, nextHeaderView: View?): Float {
        if (orientation == LinearLayoutManager.VERTICAL) {
            var y = mTranslationY
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
            return y
        } else {
            return mTranslationY
        }
    }

    private fun getX(headerView: View, nextHeaderView: View?): Float {
        if (orientation != LinearLayoutManager.VERTICAL) {
            var x = mTranslationX
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
            return x
        } else {
            return mTranslationX
        }
    }

    private fun findHeaderIndex(position: Int): Int {
        var low = 0
        var high = mHeaderPositions.size - 1
        while (low <= high) {
            val middle = (low + high) / 2
            when {
                mHeaderPositions[middle] > position -> high = middle - 1
                mHeaderPositions[middle] < position -> low = middle + 1
                else -> return middle
            }
        }
        return -1
    }

    private fun findHeaderIndexOrBefore(position: Int): Int {
        var low = 0
        var high = mHeaderPositions.size - 1
        while (low <= high) {
            val middle = (low + high) / 2
            if (mHeaderPositions[middle] > position) {
                high = middle - 1
            } else if (middle < mHeaderPositions.size - 1 && mHeaderPositions[middle + 1] <= position) {
                low = middle + 1
            } else {
                return middle
            }
        }
        return -1
    }

    private fun setPendingScroll(position: Int, offset: Int) {
        mPendingScrollPosition = position
        mPendingScrollOffset = offset
    }

    private fun isStickyHeader(position: Int): Boolean {
        callback?.let { return it.isStickyHeader(position) }
        return false
    }

    private inner class HeaderPositionsAdapterDataObserver : RecyclerView.AdapterDataObserver() {
        override fun onChanged() {
            mAdapter?.let {
                // There's no hint at what changed, so go through the adapter.
                mHeaderPositions.clear()
                val itemCount = it.itemCount
                (0 until itemCount).filterTo(mHeaderPositions) { isStickyHeader(it) }

                // Remove sticky header immediately if the entry it represents has been removed. A layout will follow.
                if (mStickyHeader != null && !mHeaderPositions.contains(mStickyHeaderPosition)) {
                    scrapStickyHeader(null)
                }
            }
        }

        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
            onChanged()
        }

        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
            onChanged()
        }

        override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) {
            onChanged()
        }
    }
}

@SuppressLint("ParcelCreator")
@Parcelize
data class SavedState(val superState: Parcelable,
                      val pendingScrollPosition: Int,
                      val pendingScrollOffset: Int): Parcelable
