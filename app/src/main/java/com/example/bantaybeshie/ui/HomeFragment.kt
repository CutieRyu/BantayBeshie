package com.example.bantaybeshie.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.example.bantaybeshie.MainActivity
import com.example.bantaybeshie.databinding.FragmentHomeBinding

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val main = requireActivity() as MainActivity

        // Attach camera preview + overlay
        main.attachHomeViews(
            preview = binding.previewView,
            overlay = binding.overlayView,
            resultText = binding.resultText
        )

        // Sensitivity mode buttons
        binding.btnWalking.setOnClickListener { main.setSensitivity(0.6f) }
        binding.btnTranspo.setOnClickListener { main.setSensitivity(0.75f) }
        binding.btnRoom.setOnClickListener { main.setSensitivity(0.9f) }

        // SOS
        binding.btnSOS.setOnClickListener { main.triggerManualSOS() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        (requireActivity() as MainActivity).detachHomeViews()
        _binding = null
    }
}
