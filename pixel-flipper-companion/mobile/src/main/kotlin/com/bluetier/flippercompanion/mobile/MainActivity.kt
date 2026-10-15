package com.bluetier.flippercompanion.mobile

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bluetier.flippercompanion.mobile.nfc.NfcReader
import com.bluetier.flippercompanion.mobile.ui.CompanionScreen
import com.bluetier.flippercompanion.mobile.ui.MainViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private lateinit var nfcReader: NfcReader

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.all { it }) viewModel.startBleScan()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfcReader = NfcReader(this)

        // Forward reader-mode tag reads into the ViewModel.
        lifecycleScope.launch {
            nfcReader.lastTag.collect { tag -> tag?.let(viewModel::onNfcTag) }
        }

        setContent {
            CompanionScreen(
                viewModel = viewModel,
                nfcSupported = nfcReader.isSupported,
                nfcEnabled = nfcReader.isEnabled,
                onToggleScan = ::onToggleScan,
            )
        }
    }

    private fun onToggleScan() {
        if (viewModel.scanning.value) {
            viewModel.stopBleScan()
            return
        }
        val needed = requiredBlePermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) {
            viewModel.startBleScan()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun requiredBlePermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    override fun onResume() {
        super.onResume()
        nfcReader.enable()
    }

    override fun onPause() {
        nfcReader.disable()
        super.onPause()
    }
}
