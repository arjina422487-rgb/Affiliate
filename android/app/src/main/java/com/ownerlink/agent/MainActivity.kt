package com.ownerlink.agent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.ownerlink.agent.ui.OwnerLinkAppUi
import com.ownerlink.agent.ui.theme.PrivatChatTheme
import com.ownerlink.agent.service.ConnectionService

class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        val app = application as OwnerLinkApp
        if (app.store.remoteAccessConsent && app.store.serviceWanted) ConnectionService.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val appearance by (application as OwnerLinkApp).appearance.collectAsState()
            PrivatChatTheme(appearance) {
                OwnerLinkAppUi()
            }
        }
    }
}
