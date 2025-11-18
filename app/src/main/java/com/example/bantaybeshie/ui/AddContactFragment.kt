package com.example.bantaybeshie.ui.contacts

import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.example.bantaybeshie.R
import com.example.bantaybeshie.model.ContactEntity
import com.example.bantaybeshie.viewmodel.ContactsViewModel
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import androidx.fragment.app.activityViewModels
import com.example.bantaybeshie.model.LogType
import com.example.bantaybeshie.viewmodel.ContactsViewModelFactory
import com.example.bantaybeshie.MainActivity
class AddContactFragment : Fragment() {

    private val contactsViewModel: ContactsViewModel by activityViewModels {
        ContactsViewModelFactory(requireActivity().application)
    }


    // Contact Picker Launcher
    private val pickContactLauncher =
        registerForActivityResult(ActivityResultContracts.PickContact()) { uri ->
            uri?.let { loadContactData(it) }
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_add_contact, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val nameField = view.findViewById<TextInputEditText>(R.id.contactNameField)
        val numberField = view.findViewById<TextInputEditText>(R.id.contactNumberField)
        val saveBtn = view.findViewById<MaterialButton>(R.id.btnSaveContact)
        val pickBtn = view.findViewById<MaterialButton>(R.id.btnPickContact)

        // Launch phone contact picker
        pickBtn.setOnClickListener {
            pickContactLauncher.launch(null)
        }

        // Save contact
        saveBtn.setOnClickListener {
            val name = nameField.text.toString().trim()
            val number = numberField.text.toString().trim()

            if (name.isEmpty() || number.isEmpty()) {
                Toast.makeText(requireContext(), "Please fill out all fields", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // 🔥 Log user action
            (requireActivity() as MainActivity).logEvent(
                title = "Contact Added",
                message = "User added contact: $name",
                type = LogType.USER_ACTION
            )

            contactsViewModel.addContact(name, number)

            Toast.makeText(requireContext(), "Contact saved!", Toast.LENGTH_SHORT).show()
            findNavController().navigateUp()
        }
    }

    // Load contact data from picker
    private fun loadContactData(uri: Uri) {
        val resolver = requireContext().contentResolver

        val cursor = resolver.query(uri, null, null, null, null)
        cursor?.use {
            if (cursor.moveToFirst()) {

                val nameIndex = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                val name = cursor.getString(nameIndex)

                val idIndex = cursor.getColumnIndex(ContactsContract.Contacts._ID)
                val contactId = cursor.getString(idIndex)

                // Query phone number
                val phoneCursor = resolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    null,
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?",
                    arrayOf(contactId),
                    null
                )

                phoneCursor?.use { pc ->
                    if (pc.moveToFirst()) {
                        val numberIndex =
                            pc.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                        val phoneNum = pc.getString(numberIndex)

                        // Autofill fields
                        view?.findViewById<TextInputEditText>(R.id.contactNameField)?.setText(name)
                        view?.findViewById<TextInputEditText>(R.id.contactNumberField)?.setText(phoneNum)
                    }
                }
            }
        }
    }
}
