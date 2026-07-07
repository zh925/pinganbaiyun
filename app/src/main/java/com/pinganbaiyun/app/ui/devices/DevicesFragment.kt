package com.pinganbaiyun.app.ui.devices

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pinganbaiyun.app.R
import com.pinganbaiyun.app.data.model.DoorConfig
import com.pinganbaiyun.app.databinding.FragmentDevicesBinding
import com.pinganbaiyun.app.ui.devices.edit.DeviceEditActivity
import com.pinganbaiyun.app.ui.write.WriteCardActivity

/** 蓝牙信息维护页（原型 M-01）：门禁四要素的增删改查 + 每条写卡入口。 */
class DevicesFragment : Fragment() {

    private var _binding: FragmentDevicesBinding? = null
    private val binding get() = _binding!!
    private val viewModel: DevicesViewModel by viewModels()
    private lateinit var adapter: DeviceAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentDevicesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = DeviceAdapter(
            onWrite = { openWrite(it) },
            onEdit = { openEdit(it) },
            onDelete = { confirmDelete(it) },
        )
        binding.devicesList.layoutManager = LinearLayoutManager(requireContext())
        binding.devicesList.adapter = adapter
        binding.fabAdd.setOnClickListener {
            startActivity(Intent(requireContext(), DeviceEditActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val list = viewModel.list()
        adapter.submit(list)
        binding.devicesSubtitle.text = getString(R.string.devices_subtitle_count, list.size)
        val empty = list.isEmpty()
        binding.devicesEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        binding.devicesList.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun openEdit(config: DoorConfig) {
        startActivity(DeviceEditActivity.editIntent(requireContext(), config.id))
    }

    private fun openWrite(config: DoorConfig) {
        startActivity(WriteCardActivity.intent(requireContext(), config.id))
    }

    private fun confirmDelete(config: DoorConfig) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.device_delete_confirm_title)
            .setMessage(getString(R.string.device_delete_confirm_msg, config.doorName))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.device_delete) { _, _ ->
                viewModel.delete(config.id)
                refresh()
            }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
