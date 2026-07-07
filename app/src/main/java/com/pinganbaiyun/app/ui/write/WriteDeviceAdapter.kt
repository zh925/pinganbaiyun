package com.pinganbaiyun.app.ui.write

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.pinganbaiyun.app.R
import com.pinganbaiyun.app.data.model.DoorConfig
import com.pinganbaiyun.app.databinding.ItemWriteDeviceBinding

/** 写卡选择设备列表（原型 W-01），单选高亮。 */
class WriteDeviceAdapter(
    private val onSelect: (DoorConfig) -> Unit,
) : RecyclerView.Adapter<WriteDeviceAdapter.VH>() {

    private val items = mutableListOf<DoorConfig>()
    private var selectedId: String? = null

    fun submit(list: List<DoorConfig>, selectedId: String?) {
        items.clear()
        items.addAll(list)
        this.selectedId = selectedId
        notifyDataSetChanged()
    }

    fun select(id: String) {
        if (selectedId == id) return
        selectedId = id
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemWriteDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(private val binding: ItemWriteDeviceBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(config: DoorConfig) {
            binding.writeItemName.text = config.doorName
            binding.writeItemMeta.text = "${config.bluetoothName} · ${config.mac}"
            val selected = config.id == selectedId
            binding.writeItemTag.visibility = if (selected) View.VISIBLE else View.GONE
            binding.root.setBackgroundResource(
                if (selected) R.drawable.bg_row_selected else R.drawable.bg_card,
            )
            binding.root.setOnClickListener {
                select(config.id)
                onSelect(config)
            }
        }
    }
}
