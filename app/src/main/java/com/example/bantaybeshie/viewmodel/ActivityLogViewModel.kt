package com.example.bantaybeshie.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import com.example.bantaybeshie.data.ActivityLogDatabase
import com.example.bantaybeshie.model.ActivityLogEntry
import kotlinx.coroutines.launch

class ActivityLogViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = ActivityLogDatabase.getDb(application).logDao()

    val logs: LiveData<List<ActivityLogEntry>> = dao.getAllLogs()

    fun insert(entry: ActivityLogEntry) {
        viewModelScope.launch {
            dao.insert(entry)
        }
    }
}
