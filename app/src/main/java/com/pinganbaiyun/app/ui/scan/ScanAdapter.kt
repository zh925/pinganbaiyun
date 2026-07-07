package com.pinganbaiyun.app.ui.scan

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.pinganbaiyun.app.R
import com.pinganbaiyun.app.databinding.ItemScanDeviceBinding

/** 扫描结果列表（原型 B-02），已保存门禁高亮「卡片匹配」。 */
class ScanAdapter(
    private val onSelect: (ScanActivity.ScanItem) -> Unit,
) : RecyclerView.Adapter<ScanAdapter.VH>() {

    private val items = mutableListOf<ScanActivity.ScanItem>()
    private var selectedMac: String? = null

    fun submit(list: List<ScanActivity.ScanItem>, selectedMac: String?) {
        items.clear()
        items.addAll(list)
        this.selectedMac = selectedMac
        notifyDataSetChanged()
    }

    fun select(mac: String) {
        if (selectedMac == mac) return
        selectedMac = mac
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemScanDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(private val binding: ItemScanDeviceBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ScanActivity.ScanItem) {
            binding.scanItemName.text = item.displayName
            binding.scanItemMac.text = item.mac
            binding.scanItemTag.visibility = if (item.matched) View.VISIBLE else View.GONE
            binding.root.setBackgroundResource(
                if (item.mac == selectedMac) R.drawable.bg_row_selected else R.drawable.bg_card,
            )
            binding.root.setOnClickListener {
                select(item.mac)
                onSelect(item)
            }
        }
    }
}
