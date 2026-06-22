package messina.androidapp

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import messina.App
import messina.settings.Theme

class MainActivity : ComponentActivity() {
    private val requiredPermissions: Array<String> = buildList {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { ensurePermissions() }

    private var hasRequestedPermissions = false
    private var permissionsDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val serviceIntent = Intent(this, ForegroundService::class.java)
        startForegroundService(serviceIntent)

        setContent {
            // White status bar icons when in dark mode and black when in light mode
            val darkMode = Theme.isDarkMode()
            LaunchedEffect(darkMode) {
                enableEdgeToEdge(
                    statusBarStyle = if (darkMode) {
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(
                            android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT,
                        )
                    }
                )
            }

            App()
        }
    }

    override fun onResume() {
        super.onResume()
        ensurePermissions()
    }

    override fun onPause() {
        super.onPause()
        permissionsDialog?.dismiss()
        permissionsDialog = null
    }

    private fun ensureBatteryExemption() {
        if (getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) return

        startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
        )
    }

    private fun ensurePermissions() {
        if (permissionsDialog != null) return

        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            ensureBatteryExemption()
            return
        }

        permissionsDialog = if (!hasRequestedPermissions) {
            AlertDialog.Builder(this)
                .setTitle("Accept all the permissions")
                .setMessage("Accept all the following permissions or the app won't work.")
                .setCancelable(false)
                .setPositiveButton("Continue") { _, _ ->
                    permissionsDialog = null
                    hasRequestedPermissions = true
                    permissionLauncher.launch(missing.toTypedArray())
                }
                .show()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Permissions Denied")
                .setMessage("Permissions were permanently denied. Click \"permissions\" in the settings to grant them.")
                .setCancelable(false)
                .setPositiveButton("Go to settings") { _, _ ->
                    permissionsDialog = null
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", packageName, null)
                        }
                    )
                }
                .show()
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}