package com.example.bantaybeshie.utils

import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.TileOverlay
import com.google.android.gms.maps.model.TileOverlayOptions
import com.google.android.gms.maps.model.TileProvider
import com.google.maps.android.heatmaps.HeatmapTileProvider

class HeatmapManager(private val map: GoogleMap) {

    private val riskPoints = arrayListOf<LatLng>()

    private var provider: HeatmapTileProvider? = null
    private var overlay: TileOverlay? = null

    fun addRiskPoint(lat: Double, lng: Double) {
        riskPoints.add(LatLng(lat, lng))
        updateHeatmap()
    }

    fun loadInitial(points: List<LatLng>) {
        riskPoints.addAll(points)
        updateHeatmap()
    }

    private fun updateHeatmap() {
        if (provider == null) {
            // Create Heatmap provider
            provider = HeatmapTileProvider.Builder()
                .data(riskPoints)
                .radius(50)
                .build()

            // provider!! is safe here because we KNOW provider was just created
            overlay = map.addTileOverlay(
                TileOverlayOptions().tileProvider(provider!!)
            )
        } else {
            // Update data when heatmap already exists
            provider!!.setData(riskPoints)
            overlay?.clearTileCache()
        }
    }
}
