package com.pinganbaiyun.app.ui.devices

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.pinganbaiyun.app.data.model.DoorConfig
import com.pinganbaiyun.app.databinding.ItemDeviceBinding

/** 门禁列表适配器（原型 M-01 条目：门名 + 蓝牙名/MAC + 写卡/编辑/删除）。 */
class DeviceAdapter(
    private val onWrite: (DoorConfig) -> Unit,
    private val onEdit: (DoorConfig) -> Unit,
    private val onDelete: (DoorConfig) -> Unit,
) : RecyclerView.Adapter<DeviceAdapter.VH>() {

    private val items = mutableListOf<DoorConfig>()

    fun submit(list: List<DoorConfig>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(private val binding: ItemDeviceBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(config: DoorConfig) {
            binding.deviceName.text = config.doorName
            binding.deviceMeta.text = "${config.bluetoothName} · ${config.mac}"
            binding.deviceWrite.setOnClickListener { onWrite(config) }
            binding.deviceEdit.setOnClickListener { onEdit(config) }
            binding.deviceDelete.setOnClickListener { onDelete(config) }
        }
    }
}
