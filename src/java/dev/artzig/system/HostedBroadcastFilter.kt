package dev.artzig.system

import android.content.IntentFilter

/**
 * AOSP BroadcastFilter's intent/export metadata for the hosted broadcast router.
 * Receiver lifetime and caller identity remain in HostedBroadcastQueue. The stock
 * BroadcastFilter requires ReceiverList and the Android AMS process graph.
 */
class HostedBroadcastFilter(filter: IntentFilter, @JvmField val exported: Boolean) : IntentFilter(filter)
