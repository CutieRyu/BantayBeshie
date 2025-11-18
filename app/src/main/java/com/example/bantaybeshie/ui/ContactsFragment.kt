package com.example.bantaybeshie.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.bantaybeshie.R
import com.example.bantaybeshie.databinding.FragmentContactsBinding
import com.example.bantaybeshie.adapters.EmergencyContactsAdapter
import com.example.bantaybeshie.viewmodel.ContactsViewModel
import androidx.fragment.app.activityViewModels
import com.example.bantaybeshie.viewmodel.ContactsViewModelFactory
import com.example.bantaybeshie.model.LogType
import com.example.bantaybeshie.MainActivity

class ContactsFragment : Fragment() {

    private var _binding: FragmentContactsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ContactsViewModel by activityViewModels {
        ContactsViewModelFactory(requireActivity().application)
    }

    private lateinit var adapter: EmergencyContactsAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentContactsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {

        adapter = EmergencyContactsAdapter { contact ->

            // ✅ LOG EVENT HERE
            (requireActivity() as MainActivity).logEvent(
                title = "Contact Deleted",
                message = "User deleted ${contact.name}",
                type = LogType.USER_ACTION
            )

            // Continue your delete logic
            viewModel.deleteContact(contact)
        }

        binding.contactsRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.contactsRecycler.adapter = adapter

        viewModel.contacts.observe(viewLifecycleOwner) { list ->
            adapter.submitList(list)

            if (list.isEmpty()) {
                binding.emptyState.visibility = View.VISIBLE
                binding.contactsRecycler.visibility = View.GONE
            } else {
                binding.emptyState.visibility = View.GONE
                binding.contactsRecycler.visibility = View.VISIBLE
            }
        }

        binding.addContactBtn.setOnClickListener {

            // Optional: log add button click
            (requireActivity() as MainActivity).logEvent(
                title = "Add Contact Button",
                message = "User opened add contact screen",
                type = LogType.USER_ACTION
            )

            findNavController().navigate(R.id.addContactFragment)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
