package com.idn.kmed.cervexa.media

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.idn.kmed.cervexa.R
import com.idn.kmed.cervexa.model.PatientItem

class PatientListAdapter(
    private val onClick: (PatientItem) -> Unit
) : RecyclerView.Adapter<PatientListAdapter.VH>() {

    private val items = mutableListOf<PatientItem>()

    fun submitList(newList: List<PatientItem>) {
        items.clear()
        items.addAll(newList)
        notifyDataSetChanged()
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tvName)
        private val tvNik: TextView = itemView.findViewById(R.id.tvNik)
        private val tvNrm: TextView = itemView.findViewById(R.id.tvNrm)
        private val tvRs: TextView = itemView.findViewById(R.id.tvRs)

        fun bind(item: PatientItem) {
            tvName.text = item.nama
            tvNik.text = "NIK: ${item.nik}"
            tvNrm.text = if (item.nrm.isNullOrBlank()) "NRM: -" else "NRM: ${item.nrm}"
            tvRs.text = if (item.rs.isNullOrBlank()) "RS: -" else "RS: ${item.rs}"
            itemView.setOnClickListener { onClick(item) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_patient, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size
}