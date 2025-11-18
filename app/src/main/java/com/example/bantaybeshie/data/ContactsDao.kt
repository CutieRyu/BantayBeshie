package com.example.bantaybeshie.data

import androidx.lifecycle.LiveData
import androidx.room.*
import com.example.bantaybeshie.model.ContactEntity

@Dao
interface ContactsDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(contact: ContactEntity)

    @Delete
    suspend fun delete(contact: ContactEntity)

    @Query("SELECT * FROM contacts ORDER BY name ASC")
    fun getAllContacts(): LiveData<List<ContactEntity>>

    @Query("SELECT * FROM contacts")
    suspend fun getAllContactsList(): List<ContactEntity>
}
