package com.picap.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.picap.mobile.ui.PiCapApp
import com.picap.mobile.ui.theme.PiCapTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PiCapTheme {
                PiCapApp()
            }
        }
    }
}
