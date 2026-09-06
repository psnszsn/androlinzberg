package dev.artzig.systemtest

import android.app.Service
import android.content.Intent
import android.net.INetworkRecommendationProvider
import android.net.NetworkKey
import android.net.NetworkScoreManager
import android.net.RssiCurve
import android.net.ScoredNetwork
import android.os.Binder

/** Test APK service bound by the stock NetworkScoreService. */
class TestNetworkRecommendationService : Service() {
    override fun onBind(intent: Intent) = object : INetworkRecommendationProvider.Stub() {
        override fun requestScores(networks: Array<NetworkKey>?) {
            val identity = Binder.clearCallingIdentity()
            try {
                getSystemService(NetworkScoreManager::class.java).updateScores(
                    networks.orEmpty().map { ScoredNetwork(it, RssiCurve(-100, 10, byteArrayOf(1, 2, 3))) }
                        .toTypedArray())
            } finally { Binder.restoreCallingIdentity(identity) }
        }
    }
}
