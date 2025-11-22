package com.example.bantaybeshie.ui.contacts

import android.database.Cursor
import android.os.Bundle
import android.provider.ContactsContract
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
import androidx.activity.result.contract.ActivityResultContracts

class AddContactFragment : Fragment() {

    private var _binding: FragmentAddContactBinding? = null
    private val binding get() = _binding!!

    // -------------------------------
    // CONTACT PICKER
    // -------------------------------
    private val pickContact =
        registerForActivityResult(ActivityResultContracts.PickContact()) { uri ->
            if (uri != null) {
                val cursor: Cursor? = requireContext().contentResolver.query(
                    uri, null, null, null, null
                )

                cursor?.use {
                    if (it.moveToFirst()) {

                        // Extract contact name
                        val name = it.getString(
                            it.getColumnIndexOrThrow(
                                ContactsContract.Contacts.DISPLAY_NAME
                            )
                        )

                        // Extract ID for number lookup
                        val id = it.getString(
                            it.getColumnIndexOrThrow(
                                ContactsContract.Contacts._ID
                            )
                        )

                        var number: String? = null

                        val pCur = requireContext().contentResolver.query(
                            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                            null,
                            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID}=?",
                            arrayOf(id),
                            null
                        )

                        pCur?.use { phoneCursor ->
                            if (phoneCursor.moveToFirst()) {
                                number = phoneCursor.getString(
                                    phoneCursor.getColumnIndexOrThrow(
                                        ContactsContract.CommonDataKinds.Phone.NUMBER
                                    )
                                )
                            }
                        }

                        // Auto fill UI
                        binding.contactNameField.setText(name)
                        if (number != null) binding.contactNumberField.setText(number)
                    }
                }
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAddContactBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // PICK CONTACT BUTTON
        binding.btnPickContact.setOnClickListener {
            pickContact.launch(null)
        }

        // SAVE CONTACT BUTTON
        binding.btnSaveContact.setOnClickListener {
            saveContact()
        }
    }

    private fun saveContact() {
        val name = binding.contactNameField.text.toString().trim()
        val number = binding.contactNumberField.text.toString().trim()
        val email = binding.contactEmailField.text.toString().trim()

        if (name.isEmpty() || number.isEmpty()) {
            Toast.makeText(requireContext(), "Name & Number are required.", Toast.LENGTH_SHORT).show()
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

        Toast.makeText(requireContext(), "Contact Saved!", Toast.LENGTH_SHORT).show()
        requireActivity().onBackPressedDispatcher.onBackPressed()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
