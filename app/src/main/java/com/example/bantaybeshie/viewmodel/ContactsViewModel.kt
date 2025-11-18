package com.example.bantaybeshie.viewmodel

import android.app.Application
import androidx.lifecycle.*
import com.example.bantaybeshie.data.ContactsDatabase
import com.example.bantaybeshie.model.ContactEntity
import com.example.bantaybeshie.repository.ContactsRepository
import kotlinx.coroutines.launch

class ContactsViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = ContactsDatabase.getDatabase(application).contactsDao()

    // LiveData list of contacts for the UI
    val contacts = dao.getAllContacts()

    fun addContact(name: String, number: String) {
        viewModelScope.launch {
            dao.insert(ContactEntity(name = name, number = number))
        }
    }

    fun deleteContact(contact: ContactEntity) {
        viewModelScope.launch {
            dao.delete(contact)
        }

    }
}
