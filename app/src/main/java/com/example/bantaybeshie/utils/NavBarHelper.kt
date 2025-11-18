package com.example.bantaybeshie.utils

import android.view.View
import androidx.navigation.NavController
import com.example.bantaybeshie.R

object NavBarHelper {

    fun setup(view: View, navController: NavController, active: String) {

        val wrappers = mapOf(
            "add" to view.findViewById<View>(R.id.navAddContactWrapper),
            "contacts" to view.findViewById<View>(R.id.navContactsWrapper),
            "home" to view.findViewById<View>(R.id.navHomeWrapper),
            "logs" to view.findViewById<View>(R.id.navActivityLogWrapper),
            "settings" to view.findViewById<View>(R.id.navSettingsWrapper)
        )

        val indicators = mapOf(
            "add" to view.findViewById<View>(R.id.indicatorAdd),
            "contacts" to view.findViewById<View>(R.id.indicatorContacts),
            "home" to view.findViewById<View>(R.id.indicatorHome),
            "logs" to view.findViewById<View>(R.id.indicatorLogs),
            "settings" to view.findViewById<View>(R.id.indicatorSettings)
        )

        // Hide all indicators first
        indicators.values.forEach { it.visibility = View.GONE }

        // Show only the active one
        indicators[active]?.visibility = View.VISIBLE

        // Navigation listeners
        wrappers["add"]?.setOnClickListener {
            navController.navigate(R.id.addContactFragment)
        }
        wrappers["contacts"]?.setOnClickListener {
            navController.navigate(R.id.contactsFragment)
        }
        wrappers["home"]?.setOnClickListener {
            navController.navigate(R.id.homeFragment)
        }
        wrappers["logs"]?.setOnClickListener {
            navController.navigate(R.id.activityLogFragment)
        }
        wrappers["settings"]?.setOnClickListener {
            navController.navigate(R.id.settingsFragment)
        }
    }
}
