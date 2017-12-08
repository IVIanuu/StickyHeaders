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

package com.ivianuu.stickyheaders.sample

import android.support.v7.widget.RecyclerView
import android.view.LayoutInflater
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * @author Manuel Wrage (IVIanuu)
 */
class SampleAdapter: RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val data = ArrayList<Any>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val view = LayoutInflater.from(parent.context)
                .inflate(viewType, parent, false)
        return when(viewType) {
            R.layout.item_header -> HeaderHolder(view)
            R.layout.item_text -> TextHolder(view)
            else -> throw IllegalStateException("wtf")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = data[position]
        holder.let {
            when(it) {
                is HeaderHolder -> it.title.text = (item as HeaderItem).title
                is TextHolder -> it.text.text = (item as TextItem).text
            }
        }
    }

    override fun getItemCount() = data.size

    fun set(data: List<Any>) {
        this.data.clear()
        this.data.addAll(data)
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        val item = data[position]
        return if(item is HeaderItem) {
            R.layout.item_header
        } else {
            R.layout.item_text
        }
    }
}

class HeaderHolder(view: View): RecyclerView.ViewHolder(view) {
    val title by lazy(LazyThreadSafetyMode.NONE) { view.findViewById<TextView>(R.id.title) }
}

class TextHolder(view: View): RecyclerView.ViewHolder(view) {
    val text by lazy(LazyThreadSafetyMode.NONE) { view.findViewById<TextView>(R.id.text) }
}

data class HeaderItem(val title: String)

data class TextItem(val text: String)