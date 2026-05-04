package com.ran.crm

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Application
import android.content.ContentResolver
import android.os.Bundle
import com.ran.crm.data.local.CrmDatabase
import com.ran.crm.data.local.PreferenceManager
import com.ran.crm.utils.SyncLogger
import com.ran.crm.work.SyncNotificationHelper
import com.ran.crm.work.SyncWorker

class CrmApplication : Application() {

    val database: CrmDatabase by lazy { CrmDatabase.getDatabase(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Create notification channel early so foreground workers can use it
        SyncNotificationHelper.createChannel(this)

        // Auto-schedule periodic sync if the user has a saved session.
        // This runs even when MainActivity hasn't been opened yet (e.g. after
        // device reboot or app update) so sync survives Activity restarts.
        val prefs = PreferenceManager(this)
        if (prefs.authToken != null) {
            val interval = prefs.syncIntervalMinutes
            if (interval > 0) {
                SyncWorker.schedulePeriodicSync(this, interval)
                // Ensure a system account exists for SyncAdapter
                ensureSystemAccount(interval)
            }
        }
    }

    /**
     * Creates the CRM system account (visible in Settings > Accounts) and enables periodic sync via
     * the SyncAdapter framework for both our custom provider and the contacts authority.
     */
    private fun ensureSystemAccount(intervalMinutes: Int) {
        val accountManager = AccountManager.get(this)
        val account = Account(ACCOUNT_NAME, ACCOUNT_TYPE)

        // addAccountExplicitly returns false if the account already exists — safe to call
        // repeatedly
        if (accountManager.addAccountExplicitly(account, null, null)) {
            SyncLogger.log("CrmApplication: System account created")
        }

        // Enable auto-sync for the custom provider authority
        ContentResolver.setIsSyncable(account, AUTHORITY, 1)
        ContentResolver.setSyncAutomatically(account, AUTHORITY, true)
        ContentResolver.addPeriodicSync(
                account,
                AUTHORITY,
                Bundle.EMPTY,
                intervalMinutes.toLong() * 60
        )

        // Enable auto-sync for the contacts authority — this is what makes
        // contacts visible in the phone's Contacts app and adds a "Contacts"
        // toggle in Settings > Accounts > RAN CRM
        ContentResolver.setIsSyncable(account, CONTACTS_AUTHORITY, 1)
        ContentResolver.setSyncAutomatically(account, CONTACTS_AUTHORITY, true)
        ContentResolver.addPeriodicSync(
                account,
                CONTACTS_AUTHORITY,
                Bundle.EMPTY,
                intervalMinutes.toLong() * 60
        )
    }

    companion object {
        const val ACCOUNT_TYPE = "com.ran.crm.account"
        const val ACCOUNT_NAME = "RAN CRM"
        const val AUTHORITY = "com.ran.crm.provider"
        const val CONTACTS_AUTHORITY = "com.android.contacts"

        lateinit var instance: CrmApplication
            private set
    }
}
