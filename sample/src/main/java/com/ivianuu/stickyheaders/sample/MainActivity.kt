package com.ivianuu.stickyheaders.sample

import android.content.res.Resources
import android.os.Build
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.support.v7.widget.RecyclerView
import android.view.View
import android.widget.Button
import com.ivianuu.stickyheaders.StickyHeadersLayoutManager

class MainActivity : AppCompatActivity() {

    private val button by lazy(LazyThreadSafetyMode.NONE) { findViewById<Button>(R.id.toggle_headers_button) }
    private val recycler by lazy(LazyThreadSafetyMode.NONE) { findViewById<RecyclerView>(R.id.recycler) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        button.setOnClickListener {
            (recycler.layoutManager as StickyHeadersLayoutManager).let {
                it.areStickyHeadersEnabled = !it.areStickyHeadersEnabled
            }
        }

        recycler.apply {
            adapter = SampleAdapter().apply {
                set(getSampleData())
            }
            layoutManager = StickyHeadersLayoutManager(this@MainActivity).apply {
                callback = object : StickyHeadersLayoutManager.Callback {
                    override fun isStickyHeader(position: Int) =
                        adapter!!.getItemViewType(position) == R.layout.item_header

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
            }
        }
    }

    private fun getSampleData(): List<Any> {
        var count = 0
        val data = ArrayList<Any>()
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
