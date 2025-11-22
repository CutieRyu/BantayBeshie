package com.example.bantaybeshie.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.bantaybeshie.adapters.SnapshotAdapter
import com.example.bantaybeshie.databinding.FragmentSnapshotBinding
import java.io.File

class SnapshotFragment : Fragment() {

    private var _binding: FragmentSnapshotBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: SnapshotAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSnapshotBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.recyclerSnapshots.layoutManager = LinearLayoutManager(requireContext())
        loadSnapshots()
    }

    private fun loadSnapshots() {
        val dir = File(requireContext().filesDir, "snapshots")
        if (!dir.exists()) dir.mkdirs()

        val files = dir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

        adapter = SnapshotAdapter(files) { file ->
            openFullImage(file)
        }

        binding.recyclerSnapshots.adapter = adapter
    }

    private fun openFullImage(file: File) {
        FullImageDialog(requireContext(), file).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
