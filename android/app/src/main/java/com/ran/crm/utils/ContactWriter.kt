package com.ran.crm.utils

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.Context
import android.provider.ContactsContract
import com.ran.crm.data.local.entity.Contact
import com.ran.crm.data.repository.ContactRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class ContactWriter(
        private val context: Context,
        private val contactRepository: ContactRepository
) {

        data class SyncResult(
                val exported: Int,
                val updated: Int,
                val skipped: Int,
                val errors: Int
        )

        private val CRM_CONTACT_NOTE = "RAN_CRM_CONTACT"

        companion object {
                // Must match the account type registered in authenticator.xml / CrmApplication
                private const val ACCOUNT_TYPE = "com.ran.crm.account"
                private const val ACCOUNT_NAME = "RAN CRM"
        }

        /**
         * Syncs CRM contacts to Android device contacts. Uses SOURCE_ID to uniquely identify CRM
         * contacts on the device.
         */
        suspend fun syncToDevice(): SyncResult =
                withContext(Dispatchers.IO) {
                        var exported = 0
                        var updated = 0
                        var skipped = 0
                        var errors = 0

                        try {
                                val contentResolver = context.contentResolver

                                SyncLogger.log("ContactWriter: Starting export to device...")

                                // Get all CRM contacts from LOCAL DB
                                val crmContacts = contactRepository.getAllContacts().first()
                                val crmContactIds = crmContacts.map { it.id }.toSet()

                                SyncLogger.log(
                                        "ContactWriter: Found ${crmContacts.size} CRM contacts to sync"
                                )

                                // 1. Sync CRM contacts to Device (Create/Update)
                                for (contact in crmContacts) {
                                        try {
                                                if (contact.phoneNormalized.isNullOrBlank()) {
                                                        skipped++
                                                        continue
                                                }

                                                // Try to find by SOURCE_ID first (Robust way)
                                                var rawContactId =
                                                        findRawContactBySourceId(
                                                                contentResolver,
                                                                contact.id
                                                        )

                                                if (rawContactId == null) {
                                                        // Fallback: Try to find by Phone Number
                                                        // (Migration path)
                                                        // Only look for contacts in our account to
                                                        // avoid messing with
                                                        // user's other contacts
                                                        rawContactId =
                                                                findRawContactByPhoneInAccount(
                                                                        contentResolver,
                                                                        contact.phoneNormalized
                                                                )

                                                        if (rawContactId != null) {
                                                                // Found by phone, so we update it
                                                                // and SET the SOURCE_ID for
                                                                // future
                                                                SyncLogger.log(
                                                                        "ContactWriter: Migrating contact ${contact.name} (ID: ${contact.id})"
                                                                )
                                                                updateDeviceContact(
                                                                        contentResolver,
                                                                        rawContactId,
                                                                        contact,
                                                                        updateSourceId = true
                                                                )
                                                                updated++
                                                        } else {
                                                                // Not found, create new
                                                                createDeviceContact(
                                                                        contentResolver,
                                                                        contact
                                                                )
                                                                exported++
                                                        }
                                                } else {
                                                        // Found by SOURCE_ID, just update details
                                                        updateDeviceContact(
                                                                contentResolver,
                                                                rawContactId,
                                                                contact,
                                                                updateSourceId = false
                                                        )
                                                        updated++
                                                }
                                        } catch (e: Exception) {
                                                SyncLogger.log(
                                                        "ContactWriter: Failed to sync contact: ${contact.name}",
                                                        e
                                                )
                                                errors++
                                        }
                                }

                                // 2. Prune orphaned contacts (Contacts in our account but not in
                                // CRM)
                                pruneOrphanedContacts(contentResolver, crmContactIds)

                                SyncLogger.log(
                                        "ContactWriter: Export complete: $exported created, $updated updated, $errors errors"
                                )
                        } catch (e: Exception) {
                                SyncLogger.log(
                                        "ContactWriter: Failed to sync contacts to device",
                                        e
                                )
                                errors++
                        }

                        SyncResult(exported, updated, skipped, errors)
                }

        /** Delete contacts that are in our account but not in the CRM list. */
        private fun pruneOrphanedContacts(
                contentResolver: ContentResolver,
                crmContactIds: Set<String>
        ) {
                try {
                        // Find all contacts in our account
                        val accountContacts = getAccountContacts(contentResolver)

                        var prunedCount = 0
                        for ((rawContactId, sourceId) in accountContacts) {
                                // If sourceId is null, it might be a legacy contact.
                                // If we strictly enforce source_id, we should delete it if it
                                // wasn't matched during
                                // the sync loop above.
                                // However, to be safe, we only delete if we are sure it's not in
                                // CRM.

                                // If sourceId is present and NOT in crmContactIds -> Delete
                                if (sourceId != null && !crmContactIds.contains(sourceId)) {
                                        SyncLogger.log(
                                                "ContactWriter: Pruning orphaned contact. SourceID: $sourceId"
                                        )
                                        deleteContact(contentResolver, rawContactId)
                                        prunedCount++
                                }
                                // If sourceId is NULL, it means it wasn't matched by phone in the
                                // sync loop
                                // (otherwise it would have been updated).
                                // So it's safe to delete as it's an extra contact in our account.
                                else if (sourceId == null) {
                                        SyncLogger.log(
                                                "ContactWriter: Pruning legacy/unmatched contact. ID: $rawContactId"
                                        )
                                        deleteContact(contentResolver, rawContactId)
                                        prunedCount++
                                }
                        }

                        if (prunedCount > 0) {
                                SyncLogger.log(
                                        "ContactWriter: Pruning complete. Removed $prunedCount orphans."
                                )
                        }
                } catch (e: Exception) {
                        com.ran.crm.utils.SyncLogger.log("ContactWriter: Pruning failed", e)
                }
        }

        private fun getAccountContacts(contentResolver: ContentResolver): Map<String, String?> {
                val contacts = mutableMapOf<String, String?>()

                val cursor =
                        contentResolver.query(
                                ContactsContract.RawContacts.CONTENT_URI,
                                arrayOf(
                                        ContactsContract.RawContacts._ID,
                                        ContactsContract.RawContacts.SOURCE_ID
                                ),
                                "${ContactsContract.RawContacts.ACCOUNT_TYPE} = ?",
                                arrayOf(ACCOUNT_TYPE),
                                null
                        )

                cursor?.use {
                        val idIndex = it.getColumnIndex(ContactsContract.RawContacts._ID)
                        val sourceIdIndex =
                                it.getColumnIndex(ContactsContract.RawContacts.SOURCE_ID)
                        while (it.moveToNext()) {
                                val id = it.getString(idIndex)
                                val sourceId = it.getString(sourceIdIndex)
                                contacts[id] = sourceId
                        }
                }
                return contacts
        }

        private fun findRawContactBySourceId(
                contentResolver: ContentResolver,
                sourceId: String
        ): String? {
                val cursor =
                        contentResolver.query(
                                ContactsContract.RawContacts.CONTENT_URI,
                                arrayOf(ContactsContract.RawContacts._ID),
                                "${ContactsContract.RawContacts.ACCOUNT_TYPE} = ? AND ${ContactsContract.RawContacts.SOURCE_ID} = ?",
                                arrayOf(ACCOUNT_TYPE, sourceId),
                                null
                        )

                return cursor?.use {
                        if (it.moveToFirst()) {
                                it.getString(it.getColumnIndex(ContactsContract.RawContacts._ID))
                        } else null
                }
        }

        private fun findRawContactByPhoneInAccount(
                contentResolver: ContentResolver,
                phoneNormalized: String
        ): String? {
                // This is more complex because we need to join Phone -> RawContact and filter by
                // Account
                val cursor =
                        contentResolver.query(
                                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                                arrayOf(ContactsContract.Data.RAW_CONTACT_ID),
                                "${ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER} = ? AND ${ContactsContract.RawContacts.ACCOUNT_TYPE} = ?",
                                arrayOf(phoneNormalized, ACCOUNT_TYPE),
                                null
                        )

                return cursor?.use {
                        if (it.moveToFirst()) {
                                it.getString(
                                        it.getColumnIndex(ContactsContract.Data.RAW_CONTACT_ID)
                                )
                        } else null
                }
        }

        private fun deleteContact(contentResolver: ContentResolver, rawContactId: String) {
                val uri =
                        ContactsContract.RawContacts.CONTENT_URI
                                .buildUpon()
                                .appendQueryParameter(
                                        ContactsContract.CALLER_IS_SYNCADAPTER,
                                        "true"
                                )
                                .build()
                contentResolver.delete(
                        uri,
                        "${ContactsContract.RawContacts._ID} = ?",
                        arrayOf(rawContactId)
                )
        }

        /** Create a new contact in device */
        private fun createDeviceContact(contentResolver: ContentResolver, contact: Contact) {
                val ops = ArrayList<ContentProviderOperation>()

                // Create raw contact associated with our Custom Account AND SOURCE_ID
                ops.add(
                        ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, ACCOUNT_TYPE)
                                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, ACCOUNT_NAME)
                                .withValue(
                                        ContactsContract.RawContacts.SOURCE_ID,
                                        contact.id
                                ) // CRITICAL: Set Source ID
                                .build()
                )

                // Add name
                ops.add(
                        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                                .withValue(
                                        ContactsContract.Data.MIMETYPE,
                                        ContactsContract.CommonDataKinds.StructuredName
                                                .CONTENT_ITEM_TYPE
                                )
                                .withValue(
                                        ContactsContract.CommonDataKinds.StructuredName
                                                .DISPLAY_NAME,
                                        contact.name
                                )
                                .build()
                )

                // Add phone number
                ops.add(
                        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                                .withValue(
                                        ContactsContract.Data.MIMETYPE,
                                        ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE
                                )
                                .withValue(
                                        ContactsContract.CommonDataKinds.Phone.NUMBER,
                                        contact.phoneRaw
                                )
                                .withValue(
                                        ContactsContract.CommonDataKinds.Phone.TYPE,
                                        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
                                )
                                .build()
                )

                // Add Note tag (Legacy support, but good for debugging)
                ops.add(
                        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                                .withValue(
                                        ContactsContract.Data.MIMETYPE,
                                        ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE
                                )
                                .withValue(
                                        ContactsContract.CommonDataKinds.Note.NOTE,
                                        CRM_CONTACT_NOTE
                                )
                                .build()
                )

                contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
        }

        /** Update an existing contact in device */
        private fun updateDeviceContact(
                contentResolver: ContentResolver,
                rawContactId: String,
                contact: Contact,
                updateSourceId: Boolean
        ) {
                val ops = ArrayList<ContentProviderOperation>()

                // Update SOURCE_ID if needed (Migration)
                if (updateSourceId) {
                        ops.add(
                                ContentProviderOperation.newUpdate(
                                                ContactsContract.RawContacts.CONTENT_URI
                                        )
                                        .withSelection(
                                                "${ContactsContract.RawContacts._ID} = ?",
                                                arrayOf(rawContactId)
                                        )
                                        .withValue(
                                                ContactsContract.RawContacts.SOURCE_ID,
                                                contact.id
                                        )
                                        .build()
                        )
                }

                // Update name
                val nameWhere =
                        "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?"
                val nameArgs =
                        arrayOf(
                                rawContactId,
                                ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE
                        )

                ops.add(
                        ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                                .withSelection(nameWhere, nameArgs)
                                .withValue(
                                        ContactsContract.CommonDataKinds.StructuredName
                                                .DISPLAY_NAME,
                                        contact.name
                                )
                                .build()
                )

                // Update phone (delete old, insert new to avoid duplicates)
                val phoneWhere =
                        "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?"
                val phoneArgs =
                        arrayOf(
                                rawContactId,
                                ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE
                        )

                ops.add(
                        ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
                                .withSelection(phoneWhere, phoneArgs)
                                .build()
                )

                ops.add(
                        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                                .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                                .withValue(
                                        ContactsContract.Data.MIMETYPE,
                                        ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE
                                )
                                .withValue(
                                        ContactsContract.CommonDataKinds.Phone.NUMBER,
                                        contact.phoneRaw
                                )
                                .withValue(
                                        ContactsContract.CommonDataKinds.Phone.TYPE,
                                        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
                                )
                                .build()
                )

                contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
        }
}
