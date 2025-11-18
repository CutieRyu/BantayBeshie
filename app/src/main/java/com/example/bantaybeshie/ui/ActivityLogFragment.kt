package com.example.bantaybeshie.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.bantaybeshie.databinding.FragmentActivityLogBinding
import com.example.bantaybeshie.adapters.ActivityLogAdapter
import com.example.bantaybeshie.viewmodel.ActivityLogViewModel

class ActivityLogFragment : Fragment() {

    private var _binding: FragmentActivityLogBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ActivityLogViewModel by activityViewModels()


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentActivityLogBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.logsRecycler.layoutManager = LinearLayoutManager(requireContext())

        viewModel.logs.observe(viewLifecycleOwner) { list ->
            binding.logsRecycler.adapter = ActivityLogAdapter(list)

            binding.emptyState.visibility =
                if (list.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
