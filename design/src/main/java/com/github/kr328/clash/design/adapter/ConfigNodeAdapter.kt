package com.github.kr328.clash.design.adapter

import android.content.Context
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.common.model.ProxyNode
import com.github.kr328.clash.design.databinding.AdapterConfigNodeBinding
import com.github.kr328.clash.design.util.layoutInflater

/**
 * Daftar node di layar Konfig: nama, ringkasan tipe/alamat, dan satu tombol
 * hapus. Adapter cuma pegang tampilan, penyimpanannya diurus lewat [removed].
 */
class ConfigNodeAdapter(
    private val context: Context,
    val values: MutableList<ProxyNode>,
    private val removed: (Int) -> Unit,
) : RecyclerView.Adapter<ConfigNodeAdapter.Holder>() {
    class Holder(val binding: AdapterConfigNodeBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(
            AdapterConfigNodeBinding
                .inflate(context.layoutInflater, parent, false)
        )
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val current = values[position]

        holder.binding.nameView.text = current.name
        holder.binding.summaryView.text = current.summary
        holder.binding.deleteView.setOnClickListener {
            val index = values.indexOf(current)

            if (index >= 0) removed(index)
        }
    }

    override fun getItemCount(): Int {
        return values.size
    }
}
