package com.ivianuu.stickyheaders.sample

import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.ivianuu.stickyheaders.StickyHeadersLayoutManager
import kotlinx.android.synthetic.main.activity_main.*

class MainActivity : AppCompatActivity() {

    private val stickyHeadersCallback = object : StickyHeadersLayoutManager.Callback {
        override fun isStickyHeader(position: Int) =
            recycler.adapter?.getItemViewType(position) == R.layout.item_header

        override fun setupStickyHeaderView(stickyHeader: View) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                stickyHeader.animate()
                    .translationZ(4.px)
                    .start()
            }
        }

        override fun teardownStickyHeaderView(stickyHeader: View) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                stickyHeader.animate()
                    .translationZ(0f)
                    .start()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recycler.apply {
            adapter = SampleAdapter(getSampleData())
            layoutManager = StickyHeadersLayoutManager(this@MainActivity, stickyHeadersCallback)
        }

        toggle_headers_button.setOnClickListener { _ ->
            (recycler.layoutManager as StickyHeadersLayoutManager).let {
                it.areStickyHeadersEnabled = !it.areStickyHeadersEnabled
            }
        }
    }

    private fun getSampleData(): List<Any> {
        var count = 0
        val data = mutableListOf<Any>()
        for (i in 0 until 1000) {
            if (count == 0) {
                data.add(HeaderItem("Header $i"))
            } else {
                data.add(TextItem("Text $i"))
            }

            count++
            if (count > 10) {
                count = 0
            }
        }

        return data
    }

    private val Int.px
        get() = this * Resources.getSystem().displayMetrics.density
}
