package com.ran.crm.account

import android.accounts.Account
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.Context
import android.content.SyncResult
import android.os.Bundle
import com.ran.crm.sync.SyncManager
import com.ran.crm.utils.SyncLogger
import kotlinx.coroutines.runBlocking

/**
 * SyncAdapter that the Android OS calls for the `com.android.contacts` authority.
 *
 * This is what makes "Sync Contacts" appear in Settings > Accounts > RAN CRM.
 * When the user (or system) triggers a contacts sync, this adapter delegates to [SyncManager].
 */
class ContactsSyncAdapter(context: Context, autoInitialize: Boolean) :
        AbstractThreadedSyncAdapter(context, autoInitialize) {

    override fun onPerformSync(
            account: Account?,
            extras: Bundle?,
            authority: String?,
            provider: ContentProviderClient?,
            syncResult: SyncResult?
    ) {
        SyncLogger.log("ContactsSyncAdapter: System contacts sync triggered")
        try {
            val syncManager = SyncManager.getInstance(context)
            runBlocking {
                val success = syncManager.performFullSync()
                if (!success) {
                    syncResult?.stats?.numIoExceptions = 1
                }
            }
            SyncLogger.log("ContactsSyncAdapter: System contacts sync completed")
        } catch (e: Exception) {
            SyncLogger.log("ContactsSyncAdapter: System contacts sync failed", e)
            syncResult?.stats?.numIoExceptions = 1
        }
    }
}
