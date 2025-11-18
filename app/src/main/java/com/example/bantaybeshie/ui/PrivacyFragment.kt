package com.example.bantaybeshie.ui.settings

import android.os.Bundle
import android.view.*
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.bantaybeshie.R

class PrivacyFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_privacy, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setup(view.findViewById(R.id.btnPasscode), "App Passcode", R.drawable.ic_lock)
        setup(view.findViewById(R.id.btnDataAccess), "Data Access", R.drawable.ic_menu)
        setup(view.findViewById(R.id.btnPermissions), "Permissions", R.drawable.ic_settings)
    }

    private fun setup(root: View, label: String, icon: Int) {
        root.findViewById<TextView>(R.id.btnLabel).text = label
        root.findViewById<ImageView>(R.id.btnIcon).setImageResource(icon)
    }
}
