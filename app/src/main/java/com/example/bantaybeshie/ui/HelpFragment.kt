package com.example.bantaybeshie.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.example.bantaybeshie.databinding.FragmentHelpBinding

class HelpFragment : Fragment() {

    private var _binding: FragmentHelpBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHelpBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // OPEN FAQ
        binding.btnFaq.setOnClickListener {
            val url = "https://yourfaqpage.com"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        }

        // CONTACT SUPPORT EMAIL
        binding.btnSupport.setOnClickListener {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "message/rfc822"
                putExtra(Intent.EXTRA_EMAIL, arrayOf("support@bantaybeshie.app"))
                putExtra(Intent.EXTRA_SUBJECT, "Need Help – BantayBeshie")
                putExtra(Intent.EXTRA_TEXT, "Hi support team,\n\nI need help with...")
            }
            startActivity(Intent.createChooser(intent, "Contact Support"))
        }

        // REPORT EMAIL
        binding.btnReport.setOnClickListener {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "message/rfc822"
                putExtra(Intent.EXTRA_EMAIL, arrayOf("report@bantaybeshie.app"))
                putExtra(Intent.EXTRA_SUBJECT, "Incident Report – BantayBeshie")
                putExtra(Intent.EXTRA_TEXT, "Please describe the issue here:")
            }
            startActivity(Intent.createChooser(intent, "Send Report"))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
