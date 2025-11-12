package com.example.bantaybeshie.utils

import android.view.View
import androidx.navigation.NavController
import com.example.bantaybeshie.R
import com.google.android.material.floatingactionbutton.FloatingActionButton

object FabMenuHelper {
    private var isMenuOpen = false

    fun setupFABMenu(view: View, navController: NavController) {
        val fabMenu: FloatingActionButton = view.findViewById(R.id.fabMenu)
        val fabSettings: FloatingActionButton = view.findViewById(R.id.fabSettings)
        val fabActivity: FloatingActionButton = view.findViewById(R.id.fabActivityLogs)
        val fabContacts: FloatingActionButton = view.findViewById(R.id.fabEmergencyContacts)
        val fabAddContact: FloatingActionButton = view.findViewById(R.id.fabAddContact)

        val fabs = listOf(fabSettings, fabActivity, fabContacts, fabAddContact)

        fabMenu.setOnClickListener {
            if (isMenuOpen) closeFABMenu(fabs, fabMenu)
            else openFABMenu(fabs, fabMenu)
            isMenuOpen = !isMenuOpen
        }

        fabSettings.setOnClickListener {
            closeFABMenu(fabs, fabMenu)
            navController.navigate(R.id.settingsFragment)
        }

        fabActivity.setOnClickListener {
            closeFABMenu(fabs, fabMenu)
            navController.navigate(R.id.activityLogFragment)
        }

        fabContacts.setOnClickListener {
            closeFABMenu(fabs, fabMenu)
            navController.navigate(R.id.contactsFragment)
        }

        fabAddContact.setOnClickListener {
            closeFABMenu(fabs, fabMenu)
            navController.navigate(R.id.addContactFragment)
        }
    }

    private fun openFABMenu(fabs: List<FloatingActionButton>, fabMenu: FloatingActionButton) {
        fabMenu.animate().rotation(90f).setDuration(200).start()

        val spacing = 120f // horizontal distance between FABs
        fabs.forEachIndexed { index, fab ->
            fab.visibility = View.VISIBLE
            val translation = -spacing * (index + 1)
            fab.animate().translationX(translation).setDuration(250).start()
        }
    }

    private fun closeFABMenu(fabs: List<FloatingActionButton>, fabMenu: FloatingActionButton) {
        fabMenu.animate().rotation(0f).setDuration(200).start()

        fabs.forEach { fab ->
            fab.animate()
                .translationX(0f)
                .setDuration(250)
                .withEndAction { fab.visibility = View.GONE }
                .start()
        }
    }
}
