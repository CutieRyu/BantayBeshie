package com.example.bantaybeshie.data

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.bantaybeshie.model.ActivityLogEntry

@Dao
interface ActivityLogDao {

    @Insert
    suspend fun insert(entry: ActivityLogEntry)

    @Query("SELECT * FROM activity_logs ORDER BY id DESC")
    fun getAllLogs(): LiveData<List<ActivityLogEntry>>
}
