package com.bluetier.flippercompanion.mobile

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bluetier.flippercompanion.mobile.nfc.NfcMessages
import com.bluetier.flippercompanion.mobile.nfc.NfcReader
import com.bluetier.flippercompanion.mobile.nfc.NfcWriteResult
import com.bluetier.flippercompanion.mobile.ui.CompanionScreen
import com.bluetier.flippercompanion.mobile.ui.MainViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private lateinit var nfcReader: NfcReader

    /** Action to run once the BLE permissions requested below are granted. */
    private var pendingScanAction: (() -> Unit)? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val action = pendingScanAction
        pendingScanAction = null
        if (grants.values.all { it }) action?.invoke()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfcReader = NfcReader(this)

        // Forward reader-mode tag reads into the ViewModel.
        lifecycleScope.launch {
            nfcReader.lastTag.collect { tag -> tag?.let(viewModel::onNfcTag) }
        }
        // Mirror NFC write arm-state and results into the ViewModel for the UI.
        lifecycleScope.launch {
            nfcReader.armed.collect(viewModel::setNfcArmed)
        }
        lifecycleScope.launch {
            nfcReader.writeResult.collect { result ->
                viewModel.onNfcWriteStatus(
                    when (result) {
                        is NfcWriteResult.Success -> "Wrote ${result.bytesWritten} B to the tag"
                        is NfcWriteResult.Failure -> "Write failed: ${result.reason}"
                    },
                )
            }
        }

        // Opt in on every API level so the UI's safe-drawing padding behaves the
        // same on older devices as on Android 15+, where it is enforced.
        enableEdgeToEdge()
        setContent {
            CompanionScreen(
                viewModel = viewModel,
                nfcSupported = nfcReader.isSupported,
                nfcEnabled = nfcEnabled,
                onToggleScan = ::onToggleScan,
                onFind = { withScanPermission(viewModel::enterFinder) },
                onSweep = { withScanPermission(viewModel::enterSweep) },
                onProbe = viewModel::enterProbe,
                onArmTextWrite = { text -> nfcReader.armWrite(NfcMessages.text(text)) },
                onArmUriWrite = { uri -> nfcReader.armWrite(NfcMessages.uri(uri)) },
                onCancelNfcWrite = { nfcReader.disarmWrite() },
            )
        }
    }

    /** Re-read on resume so returning from NFC settings updates the status. */
    private var nfcEnabled by mutableStateOf(false)

    private fun onToggleScan() {
        if (viewModel.scanning.value) viewModel.stopBleScan() else withScanPermission(viewModel::startBleScan)
    }

    /** Run [action] if the BLE permissions are held, otherwise request them first. */
    private fun withScanPermission(action: () -> Unit) {
        val needed = requiredBlePermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) {
            action()
        } else {
            pendingScanAction = action
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
        nfcEnabled = nfcReader.isEnabled
        nfcReader.enable()
    }

    override fun onPause() {
        nfcReader.disable()
        super.onPause()
    }
}
