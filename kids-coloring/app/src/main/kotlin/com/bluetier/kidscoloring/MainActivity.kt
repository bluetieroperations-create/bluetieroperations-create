package com.bluetier.kidscoloring

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.bluetier.kidscoloring.ui.ColoringScreen
import com.bluetier.kidscoloring.ui.ColoringViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: ColoringViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ColoringScreen(viewModel) }
    }
}
