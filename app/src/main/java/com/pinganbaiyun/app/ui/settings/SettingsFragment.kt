package com.pinganbaiyun.app.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.pinganbaiyun.app.BuildConfig
import com.pinganbaiyun.app.R
import com.pinganbaiyun.app.databinding.FragmentSettingsBinding
import com.pinganbaiyun.app.ui.permission.PermissionActivity

/** 设置页——权限入口与关于信息。 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.settingsPerm.setOnClickListener {
            startActivity(Intent(requireContext(), PermissionActivity::class.java))
        }
        binding.settingsVersion.text = getString(R.string.settings_version, BuildConfig.VERSION_NAME)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
