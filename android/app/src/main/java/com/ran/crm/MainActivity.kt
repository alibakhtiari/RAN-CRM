package com.ran.crm

import android.Manifest.permission
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.navigation.compose.rememberNavController
import com.ran.crm.data.local.CrmDatabase
import com.ran.crm.data.local.PreferenceManager
import com.ran.crm.data.manager.ContactMigrationManager
import com.ran.crm.data.remote.ApiClient
import com.ran.crm.data.repository.AuthRepository
import com.ran.crm.data.repository.CallLogRepository
import com.ran.crm.data.repository.ContactRepository
import com.ran.crm.navigation.NavGraph
import com.ran.crm.service.CallLogObserver
import com.ran.crm.ui.theme.RANCRMTheme
import com.ran.crm.utils.SyncLogger
import com.ran.crm.work.SyncWorker

class MainActivity : ComponentActivity() {

    private lateinit var database: CrmDatabase
    private lateinit var authRepository: AuthRepository
    private lateinit var preferenceManager: PreferenceManager
    private lateinit var contactMigrationManager: ContactMigrationManager

    private lateinit var callLogObserver: CallLogObserver

    private val logoutReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == "com.ran.crm.ACTION_LOGOUT") {
                        SyncLogger.log("Logout broadcast received")

                        // Clear session
                        val preferenceManager = PreferenceManager(applicationContext)
                        preferenceManager.clearSession()
                        ApiClient.setAuthToken(null)

                        // Navigate to Login
                        runOnUiThread {
                            try {
                                // Restart activity to ensure clean state
                                val restartIntent =
                                        Intent(this@MainActivity, MainActivity::class.java)
                                restartIntent.addFlags(
                                        Intent.FLAG_ACTIVITY_NEW_TASK or
                                                Intent.FLAG_ACTIVITY_CLEAR_TASK
                                )
                                startActivity(restartIntent)
                                finish()

                                Toast.makeText(
                                                this@MainActivity,
                                                "Session expired. Please login again.",
                                                Toast.LENGTH_LONG
                                        )
                                        .show()
                            } catch (e: Exception) {
                                SyncLogger.log("Failed to handle logout navigation", e)
                            }
                        }
                    }
                }
            }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize dependencies
        database = (application as CrmApplication).database
        preferenceManager = PreferenceManager(this)

        // Restore token
        preferenceManager.authToken?.let { token -> ApiClient.setAuthToken(token) }

        authRepository = AuthRepository(preferenceManager)
        val contactRepository = ContactRepository(database.contactDao(), preferenceManager)
        val callLogRepository = CallLogRepository(database.callLogDao(), preferenceManager)
        contactMigrationManager = ContactMigrationManager(this, contactRepository)

        // Register logout receiver
        val filter = IntentFilter("com.ran.crm.ACTION_LOGOUT")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logoutReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(logoutReceiver, filter)
        }

        // Register Call Log Observer
        callLogObserver = CallLogObserver(this)
        if (ContextCompat.checkSelfPermission(this, permission.READ_CALL_LOG) ==
                        PackageManager.PERMISSION_GRANTED
        ) {
            callLogObserver.register()
        }

        // Periodic sync is now scheduled in CrmApplication.onCreate()
        // so it survives Activity restarts and device reboots.

        // Trigger a delta sync on app load to ensure data freshness
        SyncWorker.scheduleOneTimeSync(this, forceFullSync = false)

        enableEdgeToEdge()
        setContent {
            // Observe preferences for Theme and Font Scale
            var appTheme by remember { mutableStateOf(preferenceManager.appTheme) }
            var fontScale by remember { mutableFloatStateOf(preferenceManager.fontScale) }

            DisposableEffect(Unit) {
                val listener =
                        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                            when (key) {
                                "app_theme" -> appTheme = preferenceManager.appTheme
                                "font_scale" -> fontScale = preferenceManager.fontScale
                            }
                        }
                preferenceManager.registerOnSharedPreferenceChangeListener(listener)
                onDispose { preferenceManager.unregisterOnSharedPreferenceChangeListener(listener) }
            }

            val darkTheme =
                    when (appTheme) {
                        "light" -> false
                        "dark" -> true
                        else -> isSystemInDarkTheme()
                    }

            RANCRMTheme(darkTheme = darkTheme, fontScale = fontScale) {
                Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                ) {
                    CrmApp(
                            database,
                            authRepository,
                            preferenceManager,
                            contactMigrationManager,
                            contactRepository,
                            callLogRepository,
                            onCallLogPermissionGranted = { callLogObserver.register() }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(logoutReceiver)
            callLogObserver.unregister()
        } catch (e: Exception) {
            // Ignore if not registered
        }
    }
}

@Composable
fun CrmApp(
        database: CrmDatabase,
        authRepository: AuthRepository,
        preferenceManager: PreferenceManager,
        contactMigrationManager: ContactMigrationManager,
        contactRepository: ContactRepository,
        callLogRepository: CallLogRepository,
        onCallLogPermissionGranted: () -> Unit
) {
    val navController = rememberNavController()

    // Permission Handling
    val permissions = remember {
        mutableListOf(permission.READ_CONTACTS, permission.WRITE_CONTACTS, permission.READ_CALL_LOG)
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        add(permission.POST_NOTIFICATIONS)
                    }
                }
    }

    val launcher =
            rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
            ) { perms ->
                if (perms[permission.READ_CALL_LOG] == true) {
                    onCallLogPermissionGranted()
                }
            }

    LaunchedEffect(Unit) { launcher.launch(permissions.toTypedArray()) }

    NavGraph(
            navController = navController,
            database = database,
            authRepository = authRepository,
            preferenceManager = preferenceManager,
            contactMigrationManager = contactMigrationManager,
            contactRepository = contactRepository,
            callLogRepository = callLogRepository
    )
}
