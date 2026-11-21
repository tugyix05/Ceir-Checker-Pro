package com.myokooo.ceir

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.myokooo.ceir.data.ScanEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryAdapter(
    private val onDelete: (ScanEntity) -> Unit
) : ListAdapter<ScanEntity, HistoryAdapter.VH>(DIFF) {

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    private val expanded = mutableSetOf<String>()

    // Item 8 — adaptive: set from HistoryActivity once the list is measured.
    private var cardHeightPx: Int = 0
    fun setCardHeight(px: Int) {
        if (px > 0 && px != cardHeightPx) {
            cardHeightPx = px
            notifyDataSetChanged()
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ScanEntity>() {
            override fun areItemsTheSame(a: ScanEntity, b: ScanEntity) = a.id == b.id
            override fun areContentsTheSame(a: ScanEntity, b: ScanEntity) = a == b
        }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val cardRoot: LinearLayout = v.findViewById(R.id.cardRoot)
        val headerArea: LinearLayout = v.findViewById(R.id.headerArea)
        val deviceName: TextView = v.findViewById(R.id.deviceName)
        val dateText: TextView = v.findViewById(R.id.dateText)
        val imeiCountBadge: TextView = v.findViewById(R.id.imeiCountBadge)
        val uncheckedBadge: TextView = v.findViewById(R.id.uncheckedBadge)
        val deleteBtn: Button = v.findViewById(R.id.deleteBtn)
        val expandArea: LinearLayout = v.findViewById(R.id.expandArea)
        val imei1RowText: TextView = v.findViewById(R.id.imei1RowText)
        val imei1Badge: TextView = v.findViewById(R.id.imei1Badge)
        val imei2RowText: TextView = v.findViewById(R.id.imei2RowText)
        val imei2Badge: TextView = v.findViewById(R.id.imei2Badge)
        val copyBothBtn: Button = v.findViewById(R.id.copyBothBtn)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_history, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        val ctx = holder.itemView.context

        // Item 4 — brand dedupe: "XIAOMI XIAOMI NOTE 14 PRO+" → "XIAOMI NOTE 14 PRO+"
        val brand = item.brand?.trim().orEmpty()
        val model = item.model?.trim().orEmpty()
        val name = when {
            brand.isNotEmpty() && model.isNotEmpty() ->
                if (model.startsWith(brand, ignoreCase = true)) model
                else "$brand $model"
            model.isNotEmpty() -> model
            brand.isNotEmpty() -> brand
            else -> "Unknown Device"
        }
        holder.deviceName.text = name
        holder.dateText.text = dateFmt.format(Date(item.scannedAt))

        val hasImei2 = !item.imei2.isNullOrBlank()
        holder.imeiCountBadge.text = if (hasImei2) "2 IMEIs" else "1 IMEI"

        // Scan-save badge: no CEIR result yet (imei1Status blank) → show
        // "မစစ်ရသေးဘူး" on the collapsed card. A later check fills the
        // status in place, so the badge clears by itself — no extra code.
        val unchecked = item.imei1Status.isNullOrBlank()
        holder.uncheckedBadge.visibility = if (unchecked) View.VISIBLE else View.GONE

        // Expand / collapse — model name ကို နှိပ်ရင်
        val isOpen = expanded.contains(item.id)
        holder.expandArea.visibility = if (isOpen) View.VISIBLE else View.GONE

        // Item 8 — adaptive density: collapsed cards are exactly listH ÷ 9;
        // fonts/padding scale proportionally. Expanded cards wrap content.
        if (cardHeightPx > 0) {
            val lp = holder.itemView.layoutParams
            lp.height = if (isOpen) ViewGroup.LayoutParams.WRAP_CONTENT else cardHeightPx
            holder.itemView.layoutParams = lp
            val dm = ctx.resources.displayMetrics
            val f = DensityUtils.cardFactor(cardHeightPx.toFloat(), dm)
            DensityUtils.scaledPadding(holder.cardRoot, dm, 10f, 8f, 10f, 8f, f)
            DensityUtils.scaledSp(holder.deviceName, 14f, f)
            DensityUtils.scaledSp(holder.dateText, 11f, f)
            DensityUtils.scaledSp(holder.imeiCountBadge, 10f, f)
            DensityUtils.scaledSp(holder.uncheckedBadge, 10f, f)
            DensityUtils.scaledSp(holder.deleteBtn, 13f, f)
            DensityUtils.scaledSp(holder.imei1RowText, 12f, f)
            DensityUtils.scaledSp(holder.imei2RowText, 12f, f)
            DensityUtils.scaledSp(holder.imei1Badge, 10f, f)
            DensityUtils.scaledSp(holder.imei2Badge, 10f, f)
            DensityUtils.scaledSp(holder.copyBothBtn, 12f, f)
        }
        // Item 3 — expand-tap fix: bind-time `position` goes stale after list
        // changes; always resolve the current adapter position.
        holder.headerArea.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
            val cur = getItem(pos)
            if (expanded.contains(cur.id)) expanded.remove(cur.id) else expanded.add(cur.id)
            notifyItemChanged(pos)
        }

        // IMEI rows + paid/unpaid badges
        holder.imei1RowText.text = item.imei1
        setPayBadge(holder.imei1Badge, item.imei1TaxPayment)
        if (hasImei2) {
            holder.imei2RowText.visibility = View.VISIBLE
            holder.imei2Badge.visibility = View.VISIBLE
            holder.imei2RowText.text = item.imei2
            setPayBadge(holder.imei2Badge, item.imei2TaxPayment)
        } else {
            holder.imei2RowText.visibility = View.GONE
            holder.imei2Badge.visibility = View.GONE
        }

        // Copy both — နှိပ်တိုင်း copy ရ, ✓ feedback, reset
        holder.copyBothBtn.text = "📋 Copy both"
        holder.copyBothBtn.setOnClickListener {
            val both = if (hasImei2) "${item.imei1}\n${item.imei2}" else item.imei1
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("IMEIs", both))
            holder.copyBothBtn.text = "✓ Copied"
            Toast.makeText(ctx, "Copy ✓", Toast.LENGTH_SHORT).show()
            holder.copyBothBtn.postDelayed({ holder.copyBothBtn.text = "📋 Copy both" }, 1500)
        }

        holder.deleteBtn.setOnClickListener { onDelete(item) }
    }

    private fun setPayBadge(tv: TextView, taxPayment: String?) {
        // Unchecked row (scan-saved, no CEIR result yet): blank must NOT
        // read as Unpaid — show the yellow "မစစ်ရသေးဘူး" badge instead.
        if (taxPayment.isNullOrBlank()) {
            tv.text = "မစစ်ရသေးဘူး"
            val bg = GradientDrawable().apply {
                cornerRadius = 999f
                setColor(0xFF3A2E00.toInt())
            }
            tv.background = bg
            tv.setTextColor(0xFFFFC400.toInt())
            return
        }
        val paid = taxPayment.equals("Paid", ignoreCase = true)
        tv.text = if (paid) "✓ Paid" else "✗ Unpaid"
        val bg = GradientDrawable().apply {
            cornerRadius = 999f
            setColor(if (paid) 0xFF0F2E1F.toInt() else 0xFF3B1212.toInt())
        }
        tv.background = bg
        tv.setTextColor(if (paid) 0xFF4ADE80.toInt() else 0xFFF87171.toInt())
    }
}
