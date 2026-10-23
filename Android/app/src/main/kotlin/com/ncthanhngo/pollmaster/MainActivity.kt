package com.ncthanhngo.pollmaster

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import com.ncthanhngo.pollmaster.ui.AppNav
import com.ncthanhngo.pollmaster.ui.theme.PollMasterTheme

class MainActivity : ComponentActivity() {
    private val container get() = (application as PollMasterApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CompositionLocalProvider(LocalContainer provides container) {
                PollMasterTheme { AppNav() }
            }
        }
    }

    override fun onStop() {
        container.records.flushAll()
        super.onStop()
    }
}
