package com.example.bantaybeshie.ui.contacts

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.bantaybeshie.data.ContactsDatabase
import com.example.bantaybeshie.databinding.FragmentAddContactBinding
import com.example.bantaybeshie.model.ContactEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AddContactFragment : Fragment() {

    private var _binding: FragmentAddContactBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAddContactBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnSaveContact.setOnClickListener {
            saveContact()
        }
    }

    private fun saveContact() {
        val name = binding.contactNameField.text.toString().trim()
        val number = binding.contactNumberField.text.toString().trim()
        val email = binding.contactEmailField.text.toString().trim()

        if (name.isEmpty() || number.isEmpty()) {
            Toast.makeText(requireContext(), "Name & number required", Toast.LENGTH_SHORT).show()
            return
        }

        val contact = ContactEntity(
            name = name,
            number = number,
            email = if (email.isEmpty()) null else email
        )

        lifecycleScope.launch(Dispatchers.IO) {
            ContactsDatabase.getDatabase(requireContext()).contactsDao().insert(contact)
        }

        Toast.makeText(requireContext(), "Saved", Toast.LENGTH_SHORT).show()
        requireActivity().onBackPressedDispatcher.onBackPressed()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
