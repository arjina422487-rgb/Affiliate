package com.ownerlink.agent.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.ownerlink.agent.OwnerLinkApp
import com.ownerlink.agent.affiliate.AffiliateAuthState
import com.ownerlink.agent.service.ConnectionService

/** Affiliate-only root. Legacy chat, contacts and video-call surfaces are not part of this product. */
@Composable
fun OwnerLinkAppUi() {
    val app = OwnerLinkApp.instance
    val auth by app.affiliateAccount.state.collectAsState()
    LaunchedEffect(Unit) {
        if (app.store.remoteAccessConsent && app.store.serviceWanted) ConnectionService.start(app)
    }
    Surface(Modifier.fillMaxSize().safeDrawingPadding(), color = MaterialTheme.colorScheme.background) {
        when (val state = auth) {
            AffiliateAuthState.Checking -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            AffiliateAuthState.SignedOut -> AffiliateAuth()
            is AffiliateAuthState.CompleteAccount -> AffiliateAuth(state.legacyName)
            is AffiliateAuthState.SignedIn -> AffiliateHome()
        }
    }
}
