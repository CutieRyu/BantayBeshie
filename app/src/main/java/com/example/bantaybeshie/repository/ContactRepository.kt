package com.example.bantaybeshie.repository

import com.example.bantaybeshie.data.ContactsDao
import com.example.bantaybeshie.model.ContactEntity

class ContactsRepository(private val dao: ContactsDao) {

    val allContacts = dao.getAllContacts()

    suspend fun insert(contact: ContactEntity) = dao.insert(contact)

    suspend fun delete(contact: ContactEntity) = dao.delete(contact)
}
