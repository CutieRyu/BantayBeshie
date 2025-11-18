package com.example.bantaybeshie.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.bantaybeshie.model.ActivityLogEntry

@Database(entities = [ActivityLogEntry::class], version = 1, exportSchema = false)
abstract class ActivityLogDatabase : RoomDatabase() {

    abstract fun logDao(): ActivityLogDao

    companion object {
        @Volatile private var INSTANCE: ActivityLogDatabase? = null

        fun getDb(context: Context): ActivityLogDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ActivityLogDatabase::class.java,
                    "activity_logs_db"
                )
                    .fallbackToDestructiveMigration()
                    .build()

                INSTANCE = instance
                instance
            }
        }
    }
}
