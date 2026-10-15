package com.onionhost.app.ui.main

import android.os.Bundle
import android.content.Intent
import com.onionhost.app.chat.ChatInviteLinkStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.onionhost.app.ui.navigation.AppNavigation
import com.onionhost.app.ui.theme.OnionHostTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ChatInviteLinkStore.submit(intent?.dataString)
        setContent {
            OnionHostTheme {
                AppNavigation()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        ChatInviteLinkStore.submit(intent.dataString)
    }
}
