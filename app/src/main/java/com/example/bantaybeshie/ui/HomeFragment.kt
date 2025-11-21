package com.example.bantaybeshie.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.camera.view.PreviewView
import androidx.fragment.app.Fragment
import com.example.bantaybeshie.MainActivity
import com.example.bantaybeshie.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch

class HomeFragment : Fragment() {

    private var previewView: PreviewView? = null
    private var overlayView: OverlayView? = null
    private var resultText: TextView? = null
    private var scenarioWalking: MaterialButton? = null
    private var scenarioRoom: MaterialButton? = null
    private var scenarioTranspo: MaterialButton? = null
    private var btnSOS: MaterialButton? = null
    private var switchDetection: MaterialSwitch? = null

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

        // Bind UI
        previewView = view.findViewById(R.id.previewView)
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

        // Attach camera views to MainActivity
        act?.attachHomeViews(
            previewView!!,
            overlayView!!,
            resultText!!
        )


        scenarioWalking?.setOnClickListener {
            act?.setScenarioNormal()
        }

        scenarioRoom?.setOnClickListener {
            act?.setScenarioCrowded()
        }

        scenarioTranspo?.setOnClickListener {
            act?.setScenarioNight()
        }

        btnSOS?.setOnClickListener {
            act?.setScenarioHighRisk()
            act?.triggerManualSOS()
        }

        // ============================
        // DETECTION SWITCH
        // ============================
        switchDetection?.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                act?.enableDetection()
            } else {
                act?.disableDetection()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        act?.detachHomeViews()

        previewView = null
        overlayView = null
        resultText = null
        scenarioWalking = null
        scenarioRoom = null
        scenarioTranspo = null
        btnSOS = null
        switchDetection = null
    }
}
