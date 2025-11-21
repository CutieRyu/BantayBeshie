package com.example.bantaybeshie.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.bantaybeshie.R
import com.example.bantaybeshie.utils.NavBarHelper
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.TileOverlay
import com.google.android.gms.maps.model.TileOverlayOptions
import com.google.maps.android.heatmaps.Gradient
import com.google.maps.android.heatmaps.HeatmapTileProvider

class HeatmapFragment : Fragment(), OnMapReadyCallback {

    private lateinit var mMap: GoogleMap
    private lateinit var provider: HeatmapTileProvider
    private var overlay: TileOverlay? = null

    private val riskPoints = ArrayList<LatLng>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_heatmap, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Navbar highlighting
        val navView = requireActivity().findViewById<View>(R.id.bottomNavBar)
        NavBarHelper.setup(navView, findNavController(), "heatmap")

        // Map fragment
        val mapFragment =
            childFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        // Toggle button
        val toggleButton = view.findViewById<ImageView>(R.id.btn_toggle_heatmap)
        toggleButton.setOnClickListener { toggleHeatmap() }
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap

        val manila = LatLng(14.5995, 120.9842)
        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(manila, 12f))

        loadMockRiskPoints()

        // Realistic danger gradient
        val colors = intArrayOf(
            android.graphics.Color.argb(0, 255, 255, 255),   // fully transparent
            android.graphics.Color.argb(180, 255, 165, 0),   // orange (medium)
            android.graphics.Color.argb(230, 255, 0, 0)      // red (high)
        )
        val startPoints = floatArrayOf(0.2f, 0.7f, 1.0f)
        val gradient = Gradient(colors, startPoints)

        provider = HeatmapTileProvider.Builder()
            .data(riskPoints)
            .radius(50)
            .gradient(gradient)
            .opacity(0.55)
            .build()

        // Add overlay immediately
        overlay = mMap.addTileOverlay(TileOverlayOptions().tileProvider(provider))
    }

    private fun toggleHeatmap() {
        if (overlay == null) {
            overlay = mMap.addTileOverlay(TileOverlayOptions().tileProvider(provider))
        } else {
            overlay!!.remove()
            overlay = null
        }
    }

    private fun loadMockRiskPoints() {
        riskPoints.add(LatLng(14.5995, 120.9842)) // Manila
        riskPoints.add(LatLng(14.6100, 121.0000)) // Sampaloc
        riskPoints.add(LatLng(14.5600, 121.0150)) // Makati
        riskPoints.add(LatLng(14.6500, 121.0300)) // QC
        riskPoints.add(LatLng(14.5810, 121.0440)) // BGC
        riskPoints.add(LatLng(14.5350, 121.0500)) // Taguig

        repeat(50) {
            val lat = 14.5995 + (Math.random() - 0.5) / 100
            val lng = 120.9842 + (Math.random() - 0.5) / 100
            riskPoints.add(LatLng(lat, lng))
        }
    }
}
