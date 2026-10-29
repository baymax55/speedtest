package com.example.speedtest

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.speedtest.ui.SpeedTestScreen
import com.example.speedtest.ui.SpeedTestViewModel
import com.example.speedtest.ui.theme.SpeedtestTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SpeedtestTheme {
                val viewModel: SpeedTestViewModel = viewModel()
                SpeedTestScreen(viewModel)
            }
        }
    }
}
