package com.pinganbaiyun.app.ui.home

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.fragment.app.Fragment
import com.pinganbaiyun.app.R
import com.pinganbaiyun.app.databinding.FragmentHomeBinding
import com.pinganbaiyun.app.ui.scan.ScanActivity
import com.pinganbaiyun.app.util.PermissionUtils

/** 碰卡引导落地页（原型 N-01）。 */
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private var pulse: ObjectAnimator? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.btnManualScan.setOnClickListener {
            startActivity(android.content.Intent(requireContext(), ScanActivity::class.java))
        }
        startPulse()
    }

    override fun onResume() {
        super.onResume()
        // NFC 不支持时，把副标题替换成提示（不影响其余功能）
        if (!PermissionUtils.isNfcSupported(requireContext())) {
            binding.homeSubtitle.setText(R.string.nfc_unsupported)
        } else {
            binding.homeSubtitle.setText(R.string.home_subtitle)
        }
    }

    private fun startPulse() {
        pulse = ObjectAnimator.ofPropertyValuesHolder(
            binding.orbRing,
            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.12f),
            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.12f),
            android.animation.PropertyValuesHolder.ofFloat(View.ALPHA, 0.9f, 0.2f),
        ).apply {
            duration = 1600
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        pulse?.cancel()
        pulse = null
        _binding = null
    }
}
