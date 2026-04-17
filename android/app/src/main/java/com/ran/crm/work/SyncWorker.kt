package com.ran.crm.work

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.work.*
import com.ran.crm.data.local.PreferenceManager
import com.ran.crm.data.remote.ApiClient
import com.ran.crm.sync.SyncManager
import com.ran.crm.utils.SyncLogger
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext

class SyncWorker(context: Context, workerParams: WorkerParameters) :
        CoroutineWorker(context, workerParams) {

    init {
        SyncLogger.log("SyncWorker: Instance created (ID: ${workerParams.id})")
    }

    override suspend fun doWork(): Result {
        SyncLogger.log("SyncWorker: doWork() started")
        return withContext(Dispatchers.IO) {
            SyncLogger.log("SyncWorker: IO dispatch started")
                try {
                    // Promote to foreground so Android doesn't kill us in Doze
                    setForeground(SyncNotificationHelper.createForegroundInfo(applicationContext))

                    val preferenceManager = PreferenceManager(applicationContext)

                    val token = preferenceManager.authToken
                    if (token != null) {
                        ApiClient.setAuthToken(token)
                        SyncLogger.log("SyncWorker: Auth token restored")

                        // Proactively refresh the token before sync so we don't
                        // waste a full sync cycle if the token is near expiry.
                        try {
                            val refreshed = ApiClient.apiService.refreshToken()
                            ApiClient.setAuthToken(refreshed.token)
                            preferenceManager.authToken = refreshed.token
                            SyncLogger.log("SyncWorker: Token proactively refreshed")
                        } catch (e: Exception) {
                            // Non-fatal: the Authenticator will retry on 401
                            SyncLogger.log("SyncWorker: Proactive refresh skipped", e)
                        }
                    } else {
                        SyncLogger.log("SyncWorker: No auth token found - aborting sync")
                        return@withContext Result.failure()
                    }

                    SyncLogger.log("SyncWorker: Starting sync...")

                    val syncManager = SyncManager.getInstance(applicationContext)

                    val forceFullSync = inputData.getBoolean("force_full_sync", false)

                    val success =
                            if (forceFullSync) {
                                syncManager.performFullSync()
                            } else {
                                syncManager.performDeltaSync()
                            }

                    if (success) {
                        SyncLogger.log(
                                "SyncWorker: Sync completed successfully (forceFullSync=$forceFullSync)"
                        )
                        Result.success()
                    } else {
                        SyncLogger.log("SyncWorker: Sync failed (forceFullSync=$forceFullSync)")
                        SyncLogger.log(
                                "SyncWorker: Background sync failed - returning Result.retry() to queue backoff"
                        )
                        Result.retry()
                    }
                } catch (e: CancellationException) {
                    SyncLogger.log("SyncWorker: Sync cancelled")
                    throw e
                } catch (e: TimeoutCancellationException) {
                    SyncLogger.log("SyncWorker: Sync timed out", e)
                    Result.failure()
                } catch (e: Exception) {
                    SyncLogger.log("SyncWorker: Sync error", e)
                    Result.retry()
                }
            }
    }

    companion object {
        private const val SYNC_WORK_NAME = "crm_sync_work"

        fun schedulePeriodicSync(context: Context, intervalMinutes: Int) {
            val constraints =
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

            val syncWorkRequest =
                    PeriodicWorkRequestBuilder<SyncWorker>(
                                    intervalMinutes.toLong(),
                                    TimeUnit.MINUTES
                            )
                            .setConstraints(constraints)
                            .setBackoffCriteria(
                                    BackoffPolicy.EXPONENTIAL,
                                    WorkRequest.MIN_BACKOFF_MILLIS,
                                    TimeUnit.MILLISECONDS
                            )
                            .build()

            WorkManager.getInstance(context)
                    .enqueueUniquePeriodicWork(
                            SYNC_WORK_NAME,
                            ExistingPeriodicWorkPolicy.UPDATE,
                            syncWorkRequest
                    )
        }

        fun scheduleOneTimeSync(context: Context, forceFullSync: Boolean = false) {
            SyncLogger.log("SyncWorker: scheduleOneTimeSync requested (force=$forceFullSync)")
            val constraints =
                    Constraints.Builder().setRequiredNetworkType(NetworkType.NOT_REQUIRED).build()

            val data = Data.Builder().putBoolean("force_full_sync", forceFullSync).build()

            val syncWorkRequest =
                    OneTimeWorkRequestBuilder<SyncWorker>()
                            .setConstraints(constraints)
                            .setInputData(data)
                            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                            .setBackoffCriteria(
                                    BackoffPolicy.EXPONENTIAL,
                                    WorkRequest.MIN_BACKOFF_MILLIS,
                                    TimeUnit.MILLISECONDS
                            )
                            .build()

            WorkManager.getInstance(context)
                    .enqueueUniqueWork(
                            "${SYNC_WORK_NAME}_one_time",
                            ExistingWorkPolicy.REPLACE,
                            syncWorkRequest
                    )
            SyncLogger.log("SyncWorker: Work enqueued with REPLACE policy")
        }

        fun cancelPeriodicSync(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(SYNC_WORK_NAME)
        }

        fun getSyncWorkInfo(context: Context): LiveData<List<WorkInfo>> {
            return WorkManager.getInstance(context)
                    .getWorkInfosForUniqueWorkLiveData("${SYNC_WORK_NAME}_one_time")
        }
    }
}
