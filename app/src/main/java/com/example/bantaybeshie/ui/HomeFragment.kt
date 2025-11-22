package com.example.bantaybeshie.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.example.bantaybeshie.MainActivity
import com.example.bantaybeshie.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import android.widget.Toast
import androidx.navigation.fragment.findNavController
import com.example.bantaybeshie.ui.MjpegView

class HomeFragment : Fragment() {

    private var overlayView: OverlayView? = null
    private var resultText: TextView? = null
    private var scenarioWalking: MaterialButton? = null
    private var scenarioRoom: MaterialButton? = null
    private var scenarioTranspo: MaterialButton? = null
    private var btnSOS: MaterialButton? = null
    private var switchDetection: MaterialSwitch? = null
    private var mjpegView: MjpegView? = null


    private var act: MainActivity? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        act = context as? MainActivity
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_home, container, false)

        overlayView = view.findViewById(R.id.overlayView)
        resultText = view.findViewById(R.id.resultText)

        scenarioWalking = view.findViewById(R.id.btnWalking)
        scenarioRoom = view.findViewById(R.id.btnRoom)
        scenarioTranspo = view.findViewById(R.id.btnTranspo)
        btnSOS = view.findViewById(R.id.btnSOS)
        switchDetection = view.findViewById(R.id.switchDetection)

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Attach camera views to MainActivity - CORRECT ORDER
        act?.attachHomeViews(
            overlay = overlayView!!,
            resultText = resultText!!
        )


        // SCENARIO BUTTONS ---------------------
        scenarioWalking?.setOnClickListener {
            act?.setScenarioNormal()
            Toast.makeText(requireContext(), "Scenario: Walking", Toast.LENGTH_SHORT).show()
        }

        scenarioRoom?.setOnClickListener {
            act?.setScenarioCrowded()
            Toast.makeText(requireContext(), "Scenario: Room", Toast.LENGTH_SHORT).show()
        }

        scenarioTranspo?.setOnClickListener {
            act?.setScenarioNight()
            Toast.makeText(requireContext(), "Scenario: Transportation", Toast.LENGTH_SHORT).show()
        }

        btnSOS?.setOnClickListener {
            act?.setScenarioHighRisk()
            act?.triggerManualSOS()
        }

        // DETECTION TOGGLE ----------------------
        switchDetection?.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) act?.enableDetection()
            else act?.disableDetection()
        }

        // SNAPSHOT BUTTONS ----------------------
        val btnSnapshot = view.findViewById<MaterialButton>(R.id.btnSnapshot)
        val btnViewSnapshots = view.findViewById<MaterialButton>(R.id.btnViewSnapshots)

        btnSnapshot.setOnClickListener {
            val path = act?.takeSnapshot()
            if (path != null)
                Toast.makeText(requireContext(), "Snapshot saved!", Toast.LENGTH_SHORT).show()
            else
                Toast.makeText(requireContext(), "No frame available yet.", Toast.LENGTH_SHORT).show()
        }

        btnViewSnapshots.setOnClickListener {
            findNavController().navigate(R.id.action_homeFragment_to_snapshotFragment)
        }
    }



    override fun onDestroyView() {
        super.onDestroyView()
        act?.detachHomeViews()

        overlayView = null
        resultText = null
        scenarioWalking = null
        scenarioRoom = null
        scenarioTranspo = null
        btnSOS = null
        switchDetection = null
    }
}
