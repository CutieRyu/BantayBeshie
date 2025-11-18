package com.example.bantaybeshie.ui

import android.os.Bundle
import android.view.*
import androidx.fragment.app.Fragment
import android.widget.ImageView
import android.widget.TextView
import androidx.navigation.fragment.findNavController
import com.example.bantaybeshie.R


class NotificationsFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_notifications, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setup(view.findViewById(R.id.btnAppNotifications), "App Notifications", R.drawable.ic_bell)
        setup(view.findViewById(R.id.btnCriticalAlerts), "Critical Alerts", R.drawable.ic_sos)
        setup(view.findViewById(R.id.btnEmailNotif), "Email Notifications", R.drawable.ic_mail)
    }

    private fun setup(root: View, label: String, icon: Int) {
        root.findViewById<TextView>(R.id.btnLabel).text = label
        root.findViewById<ImageView>(R.id.btnIcon).setImageResource(icon)
    }
}
