package com.bluetier.flippercompanion.wear

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* result surfaced when the user next starts a scan */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        permissionLauncher.launch(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION) // Wear OS 3 (API 30)
            },
        )

        setContent { WearAppScreen(viewModel) }

        // Wear OS blanks the display seconds after the wrist drops, and sensor
        // delivery stops with it. Without this the remote goes deaf while the
        // app still looks open, which is indistinguishable from a gesture that
        // does not work.
        //
        // Only while it is listening: a watch running its IMU flat out with the
        // screen lit does not last an afternoon, so this is tied to the chip
        // the wearer pressed rather than to the app being open. Reading
        // `running` alone keeps the ~10 Hz bearing updates from touching the
        // window at all.
        lifecycleScope.launch {
            viewModel.remoteState
                .map { it.running }
                .distinctUntilChanged()
                .collect { running ->
                    if (running) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshConnection()
    }
}
