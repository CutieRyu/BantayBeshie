package com.example.bantaybeshie.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.bantaybeshie.R
import com.example.bantaybeshie.databinding.FragmentGeneralSettingsBinding


class GeneralSettingsFragment : Fragment() {

    private var _binding: FragmentGeneralSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGeneralSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Setup setting labels
        binding.itemVersion.settingLabel.text = "App Version 1"
        binding.itemVersion.settingIcon.setImageResource(R.drawable.ic_info)

        binding.itemDarkMode.settingLabel.text = "Dark Mode"
        binding.itemDarkMode.settingIcon.setImageResource(R.drawable.ic_darkmode)

        binding.itemAppSounds.settingLabel.text = "App Sounds"
        binding.itemAppSounds.settingIcon.setImageResource(R.drawable.ic_sound)

        binding.itemLocation.settingLabel.text = "Location"
        binding.itemLocation.settingIcon.setImageResource(R.drawable.ic_location)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
