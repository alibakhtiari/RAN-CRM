package com.ran.crm.data.manager

import android.content.Context
import android.provider.ContactsContract
import com.ran.crm.data.repository.ContactRepository
import com.ran.crm.utils.SyncLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ContactMigrationManager(
        private val context: Context,
        private val contactRepository: ContactRepository
) {

        suspend fun importSystemContacts(deleteAfterImport: Boolean): Int =
                withContext(Dispatchers.IO) {
                        var importedCount = 0
                        val contentResolver = context.contentResolver
                        val cursor =
                                contentResolver.query(
                                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                                        arrayOf(
                                                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                                                ContactsContract.CommonDataKinds.Phone.NUMBER,
                                                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                                                ContactsContract.CommonDataKinds.Phone
                                                        .NORMALIZED_NUMBER
                                        ),
                                        // Exclude contacts that belong to our own CRM account
                                        // to prevent re-importing contacts we exported
                                        "${ContactsContract.RawContacts.ACCOUNT_TYPE} IS NULL OR ${ContactsContract.RawContacts.ACCOUNT_TYPE} != ?",
                                        arrayOf("com.ran.crm.account"),
                                        null
                                )

                        val potentialContacts = mutableListOf<PotentialContact>()

                        cursor?.use {
                                val nameIndex =
                                        it.getColumnIndex(
                                                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                                        )
                                val numberIndex =
                                        it.getColumnIndex(
                                                ContactsContract.CommonDataKinds.Phone.NUMBER
                                        )
                                val contactIdIndex =
                                        it.getColumnIndex(
                                                ContactsContract.CommonDataKinds.Phone.CONTACT_ID
                                        )
                                val normalizedNumberIndex =
                                        it.getColumnIndex(
                                                ContactsContract.CommonDataKinds.Phone
                                                        .NORMALIZED_NUMBER
                                        )

                                while (it.moveToNext()) {
                                        val name = it.getString(nameIndex) ?: "Unknown"
                                        val phoneRaw = it.getString(numberIndex) ?: ""
                                        val contactId = it.getString(contactIdIndex)
                                        // Use normalized number if available, otherwise fallback to
                                        // raw or basic
                                        // normalization
                                        val phoneNormalized =
                                                it.getString(normalizedNumberIndex)
                                                        ?: phoneRaw.replace(Regex("[^0-9+]"), "")

                                        if (phoneRaw.isNotBlank() && phoneNormalized.isNotBlank()) {
                                                potentialContacts.add(
                                                        PotentialContact(
                                                                name,
                                                                phoneRaw,
                                                                phoneNormalized,
                                                                contactId
                                                        )
                                                )
                                        }
                                }
                        }
                        SyncLogger.log(
                                "ContactMigrationManager: Found ${potentialContacts.size} potential system contacts"
                        )

                        if (potentialContacts.isNotEmpty()) {
                                // Batch existing check
                                val phoneList = potentialContacts.map { it.phoneNormalized }
                                // Using chunks to avoid SQLite parameter limit (999)
                                val existingPhones = mutableSetOf<String>()
                                phoneList.chunked(900).forEach { chunk ->
                                        existingPhones.addAll(
                                                contactRepository.getExistingNormalizedPhones(chunk)
                                        )
                                }

                                for (contact in potentialContacts) {
                                        if (!existingPhones.contains(contact.phoneNormalized)) {
                                                // Create new contact in CRM
                                                val newContact =
                                                        contactRepository.createContact(
                                                                contact.name,
                                                                contact.phoneRaw,
                                                                contact.phoneNormalized
                                                        )
                                                if (newContact != null) {
                                                        importedCount++
                                                        existingPhones.add(
                                                                contact.phoneNormalized
                                                        ) // Prevent duplicates in this run
                                                        if (deleteAfterImport) {
                                                                deleteSystemContact(
                                                                        contentResolver,
                                                                        contact.contactId
                                                                )
                                                        }
                                                }
                                        } else if (deleteAfterImport) {
                                                // Contact exists in CRM, but we want to move
                                                // (delete from system)
                                                deleteSystemContact(
                                                        contentResolver,
                                                        contact.contactId
                                                )
                                        }
                                }
                        }

                        importedCount
                }

        private data class PotentialContact(
                val name: String,
                val phoneRaw: String,
                val phoneNormalized: String,
                val contactId: String
        )

        private fun deleteSystemContact(
                contentResolver: android.content.ContentResolver,
                contactId: String
        ) {
                try {
                        val uri =
                                android.net.Uri.withAppendedPath(
                                        ContactsContract.Contacts.CONTENT_URI,
                                        contactId
                                )
                        contentResolver.delete(uri, null, null)
                } catch (e: Exception) {
                        e.printStackTrace()
                }
        }
}
