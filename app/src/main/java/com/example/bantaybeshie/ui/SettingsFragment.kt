package com.example.bantaybeshie.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.bantaybeshie.R
import com.example.bantaybeshie.databinding.FragmentSettingsBinding
import com.example.bantaybeshie.databinding.SettingsButtonBinding
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupButton(
            buttonBinding = binding.btnGeneral,
            label = "General Settings",
            icon = R.drawable.ic_settings,
            navigateTo = R.id.action_settingsFragment_to_generalSettingsFragment
        )

        setupButton(
            buttonBinding = binding.btnNotifications,
            label = "Notifications",
            icon = R.drawable.ic_bell,
            navigateTo = R.id.action_settingsFragment_to_notificationsFragment
        )

        setupButton(
            buttonBinding = binding.btnPrivacy,
            label = "Privacy & Security",
            icon = R.drawable.ic_privacy,
            navigateTo = R.id.action_settingsFragment_to_privacyFragment
        )

        setupButton(
            buttonBinding = binding.btnHelp,
            label = "Help & Support",
            icon = R.drawable.ic_help,
            navigateTo = R.id.action_settingsFragment_to_helpFragment
        )
    }

    private fun setupButton(
        buttonBinding: SettingsButtonBinding,
        label: String,
        icon: Int,
        navigateTo: Int
    ) {
        buttonBinding.btnLabel.text = label
        buttonBinding.btnIcon.setImageResource(icon)

        buttonBinding.root.setOnClickListener {
            findNavController().navigate(navigateTo)
        }
    }


    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
