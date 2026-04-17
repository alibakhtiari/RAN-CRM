package com.ran.crm.ui.screen

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateUtils
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.ran.crm.data.local.PreferenceManager
import com.ran.crm.data.remote.ApiClient
import com.ran.crm.ui.AppConfig
import com.ran.crm.work.SyncWorker
import android.content.SharedPreferences
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.ran.crm.utils.SyncLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
        navController: NavController,
        preferenceManager: PreferenceManager,
        onBackClick: () -> Unit
) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()

        // Sync Status
        var lastSyncTime by remember { mutableStateOf(preferenceManager.lastSyncContacts) }

        // Observe preference changes to update timestamp dynamically
        DisposableEffect(Unit) {
                val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                        if (key == "last_sync_contacts") {
                                lastSyncTime = preferenceManager.lastSyncContacts
                        }
                }
                preferenceManager.registerOnSharedPreferenceChangeListener(listener)
                onDispose {
                        preferenceManager.unregisterOnSharedPreferenceChangeListener(listener)
                }
        }

        // Log Viewer state
        var showLogsDialog by remember { mutableStateOf(false) }
        var logsText by remember { mutableStateOf("") }
        val clipboardManager = LocalClipboardManager.current

        // Server Status
        var isServerConnected by remember { mutableStateOf<Boolean?>(null) }

        // Check server status periodically
        LaunchedEffect(Unit) {
                while (true) {
                        try {
                                val response = ApiClient.apiService.healthCheck()
                                isServerConnected = response.status == "OK"
                        } catch (e: Exception) {
                                Log.e("SettingsScreen", "Health check failed", e)
                                isServerConnected = false
                        }
                        delay(30000) // Check every 30 seconds
                }
        }

        // Force recomposition when permissions change
        var permissionRefreshCount by remember { mutableIntStateOf(0) }

        // Permission launcher
        val permissionLauncher =
                rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions()
                ) { permissionRefreshCount++ }

        // Local state for immediate UI feedback
        var currentTheme by remember { mutableStateOf(preferenceManager.appTheme) }
        var currentScale by remember { mutableFloatStateOf(preferenceManager.fontScale) }
        var currentInterval by remember { mutableIntStateOf(preferenceManager.syncIntervalMinutes) }

        // Permissions State
        val permissions = remember {
                buildList {
                        add("Read Contacts" to Manifest.permission.READ_CONTACTS)
                        add("Write Contacts" to Manifest.permission.WRITE_CONTACTS)
                        add("Call Log" to Manifest.permission.READ_CALL_LOG)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                add("Notifications" to Manifest.permission.POST_NOTIFICATIONS)
                        }
                }
        }

        // Force recomposition when permissions change

        Scaffold(
                topBar = {
                        TopAppBar(
                                title = { Text("Settings") },
                                navigationIcon = {
                                        IconButton(onClick = onBackClick) {
                                                Icon(
                                                        imageVector =
                                                                Icons.AutoMirrored.Filled.ArrowBack,
                                                        contentDescription = "Back"
                                                )
                                        }
                                }
                        )
                }
        ) { paddingValues ->
                Column(
                        modifier =
                                Modifier.fillMaxSize()
                                        .padding(paddingValues)
                                        .padding(16.dp)
                                        .verticalScroll(rememberScrollState())
                ) {
                        // 1. Server Status
                        Card(
                                modifier =
                                        Modifier.fillMaxWidth().clickable {
                                                scope.launch {
                                                        isServerConnected = null
                                                        try {
                                                                val response =
                                                                        ApiClient.apiService
                                                                                .healthCheck()
                                                                isServerConnected =
                                                                        response.status == "OK"
                                                        } catch (e: Exception) {
                                                                isServerConnected = false
                                                        }
                                                }
                                        },
                                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                                Row(
                                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                ) {
                                        Text(
                                                text = "Server Status",
                                                style = MaterialTheme.typography.bodyMedium
                                        )

                                        Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                                when (isServerConnected) {
                                                        true -> {
                                                                Canvas(
                                                                        modifier =
                                                                                Modifier.size(12.dp)
                                                                ) {
                                                                        drawCircle(
                                                                                color =
                                                                                        androidx.compose
                                                                                                .ui
                                                                                                .graphics
                                                                                                .Color
                                                                                                .Green
                                                                        )
                                                                }
                                                                Text(
                                                                        text = "Connected",
                                                                        style =
                                                                                MaterialTheme
                                                                                        .typography
                                                                                        .bodySmall,
                                                                        color =
                                                                                MaterialTheme
                                                                                        .colorScheme
                                                                                        .primary
                                                                )
                                                        }
                                                        false -> {
                                                                Canvas(
                                                                        modifier =
                                                                                Modifier.size(12.dp)
                                                                ) {
                                                                        drawCircle(
                                                                                color =
                                                                                        androidx.compose
                                                                                                .ui
                                                                                                .graphics
                                                                                                .Color
                                                                                                .Red
                                                                        )
                                                                }
                                                                Text(
                                                                        text = "Disconnected",
                                                                        style =
                                                                                MaterialTheme
                                                                                        .typography
                                                                                        .bodySmall,
                                                                        color =
                                                                                MaterialTheme
                                                                                        .colorScheme
                                                                                        .error
                                                                )
                                                        }
                                                        null -> {
                                                                CircularProgressIndicator(
                                                                        modifier =
                                                                                Modifier.size(
                                                                                        12.dp
                                                                                ),
                                                                        strokeWidth = 1.5.dp
                                                                )
                                                                Text(
                                                                        text = "Checking...",
                                                                        style =
                                                                                MaterialTheme
                                                                                        .typography
                                                                                        .bodySmall,
                                                                        color =
                                                                                MaterialTheme
                                                                                        .colorScheme
                                                                                        .onSurfaceVariant
                                                                )
                                                        }
                                                }
                                        }
                                }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Card(
                                modifier =
                                        Modifier.fillMaxWidth().clickable {
                                                Toast.makeText(
                                                                context,
                                                                "Sync initiated...",
                                                                Toast.LENGTH_SHORT
                                                        )
                                                        .show()
                                                SyncWorker.scheduleOneTimeSync(context, true)
                                        },
                                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                                Column {
                                        Row(
                                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                        ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                                text = "Sync Data",
                                                                style =
                                                                        MaterialTheme.typography
                                                                                .bodyMedium
                                                        )

                                                        val relativeTime =
                                                                if (lastSyncTime > 0) {
                                                                        android.text.format
                                                                                .DateUtils
                                                                                .getRelativeTimeSpanString(
                                                                                        lastSyncTime,
                                                                                        System.currentTimeMillis(),
                                                                                        android.text
                                                                                                .format
                                                                                                .DateUtils
                                                                                                .MINUTE_IN_MILLIS
                                                                                )
                                                                                .toString()
                                                                } else {
                                                                        "Never"
                                                                }

                                                        Text(
                                                                text = relativeTime,
                                                                style =
                                                                        MaterialTheme.typography
                                                                                .bodySmall,
                                                                color =
                                                                        MaterialTheme.colorScheme
                                                                                .onSurfaceVariant
                                                        )
                                                }

                                                TextButton(
                                                        onClick = {
                                                                scope.launch {
                                                                        val logs = withContext(Dispatchers.IO) {
                                                                                SyncLogger.getLogs(context)
                                                                        }
                                                                        logsText = logs
                                                                        showLogsDialog = true
                                                                }
                                                        }
                                                ) {
                                                        Text("View Logs")
                                                }
                                        }

                                        // Import Contacts section removed to run automatically

                                        HorizontalDivider()

                                        Text(
                                                text = "Sync Interval",
                                                style = MaterialTheme.typography.bodyMedium,
                                                modifier =
                                                        Modifier.padding(start = 16.dp, top = 8.dp)
                                        )

                                        Row(
                                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                                ThemeOption(
                                                        label = "15m",
                                                        selected = currentInterval == 15,
                                                        onClick = {
                                                                preferenceManager
                                                                        .syncIntervalMinutes = 15
                                                                currentInterval = 15
                                                                SyncWorker.schedulePeriodicSync(
                                                                        context,
                                                                        15
                                                                )
                                                        }
                                                )
                                                ThemeOption(
                                                        label = "30m",
                                                        selected = currentInterval == 30,
                                                        onClick = {
                                                                preferenceManager
                                                                        .syncIntervalMinutes = 30
                                                                currentInterval = 30
                                                                SyncWorker.schedulePeriodicSync(
                                                                        context,
                                                                        30
                                                                )
                                                        }
                                                )
                                                ThemeOption(
                                                        label = "1h",
                                                        selected = currentInterval == 60,
                                                        onClick = {
                                                                preferenceManager
                                                                        .syncIntervalMinutes = 60
                                                                currentInterval = 60
                                                                SyncWorker.schedulePeriodicSync(
                                                                        context,
                                                                        60
                                                                )
                                                        }
                                                )
                                        }
                                }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Battery optimization moved to Permissions area

                        // 3. Appearance (Font Size & Theme)
                        Card(
                                modifier = Modifier.fillMaxWidth(),
                                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                        Text(
                                                text = "Appearance",
                                                style = MaterialTheme.typography.titleMedium
                                        )
                                        Spacer(modifier = Modifier.height(12.dp))

                                        // Theme
                                        Text(
                                                text = "Theme",
                                                style = MaterialTheme.typography.bodyMedium
                                        )
                                        Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                                ThemeOption(
                                                        label = "System",
                                                        selected = currentTheme == "system",
                                                        onClick = {
                                                                preferenceManager.appTheme =
                                                                        "system"
                                                                currentTheme = "system"
                                                        }
                                                )
                                                ThemeOption(
                                                        label = "Light",
                                                        selected = currentTheme == "light",
                                                        onClick = {
                                                                preferenceManager.appTheme = "light"
                                                                currentTheme = "light"
                                                        }
                                                )
                                                ThemeOption(
                                                        label = "Dark",
                                                        selected = currentTheme == "dark",
                                                        onClick = {
                                                                preferenceManager.appTheme = "dark"
                                                                currentTheme = "dark"
                                                        }
                                                )
                                        }

                                        Spacer(modifier = Modifier.height(12.dp))

                                        // Font Size
                                        Text(
                                                text = "Font Size",
                                                style = MaterialTheme.typography.bodyMedium
                                        )
                                        Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                                ThemeOption(
                                                        label = "Small",
                                                        selected =
                                                                currentScale ==
                                                                        AppConfig.SCALE_SMALL,
                                                        onClick = {
                                                                preferenceManager.fontScale =
                                                                        AppConfig.SCALE_SMALL
                                                                currentScale = AppConfig.SCALE_SMALL
                                                        }
                                                )
                                                ThemeOption(
                                                        label = "Medium",
                                                        selected =
                                                                currentScale ==
                                                                        AppConfig.SCALE_MEDIUM,
                                                        onClick = {
                                                                preferenceManager.fontScale =
                                                                        AppConfig.SCALE_MEDIUM
                                                                currentScale =
                                                                        AppConfig.SCALE_MEDIUM
                                                        }
                                                )
                                                ThemeOption(
                                                        label = "Large",
                                                        selected =
                                                                currentScale ==
                                                                        AppConfig.SCALE_LARGE,
                                                        onClick = {
                                                                preferenceManager.fontScale =
                                                                        AppConfig.SCALE_LARGE
                                                                currentScale = AppConfig.SCALE_LARGE
                                                        }
                                                )
                                        }
                                }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // 4. Permissions (Compact)
                        Card(
                                modifier = Modifier.fillMaxWidth(),
                                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                        Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                        ) {
                                                Text(
                                                        text = "Permissions",
                                                        style = MaterialTheme.typography.titleMedium
                                                )
                                                TextButton(
                                                        onClick = {
                                                                try {
                                                                        val intent =
                                                                                android.content
                                                                                        .Intent(
                                                                                                android.provider
                                                                                                        .Settings
                                                                                                        .ACTION_APPLICATION_DETAILS_SETTINGS
                                                                                        )
                                                                                        .apply {
                                                                                                data =
                                                                                                        android.net
                                                                                                                .Uri
                                                                                                                .parse(
                                                                                                                        "package:${context.packageName}"
                                                                                                                )
                                                                                        }
                                                                        context.startActivity(
                                                                                intent
                                                                        )
                                                                } catch (e: Exception) {}
                                                        }
                                                ) { Text("Open Settings") }
                                        }

                                        Spacer(modifier = Modifier.height(8.dp))

                                        // Regular permissions
                                        permissions.forEach { (label, permission) ->
                                                val isGranted =
                                                        ContextCompat.checkSelfPermission(
                                                                context,
                                                                permission
                                                        ) == PackageManager.PERMISSION_GRANTED

                                                PermissionItem(
                                                        label = label,
                                                        isGranted = isGranted,
                                                        onRequestPermission = {
                                                                permissionLauncher.launch(
                                                                        arrayOf(permission)
                                                                )
                                                        }
                                                )
                                        }

                                        Spacer(modifier = Modifier.height(8.dp))

                                        // Battery Optimization Permission
                                        val powerManager = remember {
                                                context.getSystemService(Context.POWER_SERVICE) as
                                                        PowerManager
                                        }
                                        var isIgnoringBattery by remember {
                                                mutableStateOf(
                                                        powerManager.isIgnoringBatteryOptimizations(
                                                                context.packageName
                                                        )
                                                )
                                        }

                                        PermissionItem(
                                                label = "Battery Optimization",
                                                isGranted = isIgnoringBattery,
                                                onRequestPermission = {
                                                        try {
                                                                val intent =
                                                                        android.content.Intent(
                                                                                android.provider
                                                                                        .Settings
                                                                                        .ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                                                                        )
                                                                intent.data =
                                                                        Uri.parse(
                                                                                "package:${context.packageName}"
                                                                        )
                                                                context.startActivity(intent)
                                                                isIgnoringBattery =
                                                                        powerManager
                                                                                .isIgnoringBatteryOptimizations(
                                                                                        context.packageName
                                                                                )
                                                        } catch (e: Exception) {
                                                                Toast.makeText(
                                                                                context,
                                                                                "Could not open battery settings",
                                                                                android.widget.Toast
                                                                                        .LENGTH_SHORT
                                                                        )
                                                                        .show()
                                                        }
                                                }
                                        )
                                }
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        Button(
                                onClick = {
                                        scope.launch {
                                                // Clear only auth data — keep theme/font/sync
                                                // preferences
                                                preferenceManager.clearSession()
                                                ApiClient.setAuthToken(null)
                                                SyncWorker.cancelPeriodicSync(context)

                                                // Remove system account
                                                try {
                                                        val accountManager =
                                                                android.accounts.AccountManager.get(
                                                                        context
                                                                )
                                                        val account =
                                                                android.accounts.Account(
                                                                        com.ran.crm.CrmApplication
                                                                                .ACCOUNT_NAME,
                                                                        com.ran.crm.CrmApplication
                                                                                .ACCOUNT_TYPE
                                                                )
                                                        accountManager.removeAccountExplicitly(
                                                                account
                                                        )
                                                } catch (e: Exception) {
                                                        // Best-effort — account may not exist
                                                }

                                                navController.navigate("login") {
                                                        popUpTo(navController.graph.id) {
                                                                inclusive = true
                                                        }
                                                }
                                        }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors =
                                        ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.error
                                        )
                        ) { Text("Logout") }
                }
        }

        if (showLogsDialog) {
                LogViewerDialog(
                        onDismiss = { showLogsDialog = false },
                        logsText = logsText,
                        onClear = {
                                SyncLogger.clearLogs(context)
                                logsText = "Logs cleared."
                        },
                        onCopy = {
                                clipboardManager.setText(AnnotatedString(logsText))
                                Toast.makeText(context, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
                        },
                        onTestLog = {
                                SyncLogger.log("Testing SyncLogger from Settings UI")
                                // Refresh logs after brief delay for writing
                                scope.launch {
                                        kotlinx.coroutines.delay(200)
                                        logsText = withContext(Dispatchers.IO) {
                                                SyncLogger.getLogs(context)
                                        }
                                }
                        }
                )
        }
}

@Composable
fun LogViewerDialog(
        onDismiss: () -> Unit,
        logsText: String,
        onClear: () -> Unit,
        onCopy: () -> Unit,
        onTestLog: () -> Unit
) {
        AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Sync Logs") },
                text = {
                        Column {
                                Card(
                                        modifier = Modifier.fillMaxWidth().height(400.dp),
                                        colors = CardDefaults.cardColors(
                                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                                        )
                                ) {
                                        Box(
                                                modifier = Modifier
                                                        .padding(8.dp)
                                                        .verticalScroll(rememberScrollState())
                                        ) {
                                                Text(
                                                        text = logsText,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                        }
                                }
                        }
                },
                confirmButton = {
                        TextButton(onClick = onDismiss) { Text("Close") }
                },
                dismissButton = {
                        Row {
                                TextButton(onClick = onTestLog) {
                                        Text("Test Log")
                                }
                                TextButton(onClick = onClear) {
                                        Text("Clear", color = MaterialTheme.colorScheme.error)
                                }
                                TextButton(onClick = onCopy) {
                                        Text("Copy")
                                }
                        }
                }
        )
}

@Composable
private fun ThemeOption(label: String, selected: Boolean, onClick: () -> Unit) {
        Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable(onClick = onClick)
        ) {
                RadioButton(selected = selected, onClick = onClick)
                Text(
                        text = label,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 4.dp)
                )
        }
}

@Composable
private fun PermissionItem(label: String, isGranted: Boolean, onRequestPermission: () -> Unit) {
        Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
        ) {
                Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                ) {
                        if (isGranted) {
                                Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(text = label, style = MaterialTheme.typography.bodyMedium)
                }

                Button(
                        onClick = onRequestPermission,
                        enabled = !isGranted,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) { Text(if (isGranted) "Granted" else "Grant") }
        }
}
