package com.ran.crm.account

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Bound service that exposes [ContactsSyncAdapter] to the Android contacts sync framework.
 *
 * This is separate from [CrmSyncService] because Android requires a distinct service
 * for each content authority. This one binds to `com.android.contacts` so the system
 * Contacts app recognises our account and displays CRM contacts.
 */
class ContactsSyncService : Service() {

    companion object {
        private var syncAdapter: ContactsSyncAdapter? = null
        private val lock = Object()
    }

    override fun onCreate() {
        super.onCreate()
        synchronized(lock) {
            if (syncAdapter == null) {
                syncAdapter = ContactsSyncAdapter(applicationContext, true)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = syncAdapter!!.syncAdapterBinder
}
