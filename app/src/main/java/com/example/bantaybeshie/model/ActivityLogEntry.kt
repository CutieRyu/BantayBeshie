package com.example.bantaybeshie.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.*

@Entity(tableName = "activity_logs")
data class ActivityLogEntry(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val timestamp: String,
    val title: String,
    val message: String,
    val type: LogType
)
