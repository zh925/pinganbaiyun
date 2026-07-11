package com.pinganbaiyun.app.ui.devices

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.pinganbaiyun.app.R
import com.pinganbaiyun.app.data.model.DoorConfig
import com.pinganbaiyun.app.databinding.ItemDeviceBinding

/**
 * 门禁列表适配器（原型 M-01 条目：门名 + 蓝牙名/MAC + 开门/写卡/编辑）。
 *
 * 「开门」为该行主操作，点击后按状态就地反馈（原型 M-03 开门中 / M-04 成功 / M-05 失败）；
 * 行内开门状态由外部通过 [setOpenState] 驱动，一次只应有一行处于 [OpenState.Loading]。
 */
class DeviceAdapter(
    private val onOpen: (DoorConfig) -> Unit,
    private val onWrite: (DoorConfig) -> Unit,
    private val onEdit: (DoorConfig) -> Unit,
    private val onDelete: (DoorConfig) -> Unit,
) : RecyclerView.Adapter<DeviceAdapter.VH>() {

    /** 单行开门状态。 */
    sealed class OpenState {
        data object Idle : OpenState()
        data object Loading : OpenState()
        data object Success : OpenState()
        data class Failed(val reason: String) : OpenState()
    }

    private val items = mutableListOf<DoorConfig>()
    private val openStates = mutableMapOf<String, OpenState>()

    fun submit(list: List<DoorConfig>) {
        items.clear()
        items.addAll(list)
        // 清理已不存在门禁的残留状态
        openStates.keys.retainAll(items.map { it.id }.toSet())
        notifyDataSetChanged()
    }

    /** 更新某条门禁的开门状态并刷新该行。 */
    fun setOpenState(id: String, state: OpenState) {
        if (state is OpenState.Idle) openStates.remove(id) else openStates[id] = state
        val pos = items.indexOfFirst { it.id == id }
        if (pos >= 0) notifyItemChanged(pos)
    }

    fun openState(id: String): OpenState = openStates[id] ?: OpenState.Idle

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
            binding.deviceOpen.setOnClickListener { onOpen(config) }
            renderOpen(config, openStates[config.id] ?: OpenState.Idle)
        }

        private fun renderOpen(config: DoorConfig, state: OpenState) {
            val ctx = binding.root.context
            when (state) {
                OpenState.Idle -> {
                    binding.deviceOpen.isEnabled = true
                    binding.deviceOpen.setText(R.string.device_open)
                    binding.deviceOpen.setTextColor(ContextCompat.getColor(ctx, R.color.white))
                    binding.deviceOpen.setBackgroundResource(R.drawable.bg_button_door)
                    binding.deviceHint.visibility = View.GONE
                }
                OpenState.Loading -> {
                    binding.deviceOpen.isEnabled = false
                    binding.deviceOpen.setText(R.string.device_opening)
                    binding.deviceOpen.setTextColor(ContextCompat.getColor(ctx, R.color.white))
                    binding.deviceOpen.setBackgroundResource(R.drawable.bg_button_bt)
                    binding.deviceHint.visibility = View.GONE
                }
                OpenState.Success -> {
                    binding.deviceOpen.isEnabled = false
                    binding.deviceOpen.setText(R.string.device_opened)
                    binding.deviceOpen.setTextColor(ContextCompat.getColor(ctx, R.color.white))
                    binding.deviceOpen.setBackgroundResource(R.drawable.bg_button_ok)
                    showHint(
                        ctx.getString(R.string.device_open_ok_hint),
                        R.drawable.bg_alert_ok,
                        R.drawable.ic_check,
                        R.color.alert_ok_fg,
                    )
                }
                is OpenState.Failed -> {
                    binding.deviceOpen.isEnabled = true
                    binding.deviceOpen.setText(R.string.device_open_retry)
                    binding.deviceOpen.setTextColor(ContextCompat.getColor(ctx, R.color.err))
                    binding.deviceOpen.setBackgroundResource(R.drawable.bg_button_err)
                    showHint(
                        state.reason,
                        R.drawable.bg_alert_warn,
                        R.drawable.ic_warning,
                        R.color.alert_warn_fg,
                    )
                }
            }
        }

        private fun showHint(text: String, bg: Int, icon: Int, fg: Int) {
            val fgColor = ContextCompat.getColor(binding.root.context, fg)
            binding.deviceHint.setBackgroundResource(bg)
            binding.deviceHintIcon.setImageResource(icon)
            binding.deviceHintIcon.setColorFilter(fgColor)
            binding.deviceHintText.setTextColor(fgColor)
            binding.deviceHintText.text = text
            binding.deviceHint.visibility = View.VISIBLE
        }
    }
}
