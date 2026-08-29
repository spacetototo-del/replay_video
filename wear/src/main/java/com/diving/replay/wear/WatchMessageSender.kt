package com.diving.replay.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

/**
 * Sends REC / STOP to every connected phone node (plan §3, §4 Phase 4).
 * Paths mirror the phone's Constants: "/mark_start", "/mark_end".
 */
class WatchMessageSender(private val context: Context) {

    private val messageClient by lazy { Wearable.getMessageClient(context) }
    private val nodeClient by lazy { Wearable.getNodeClient(context) }

    suspend fun sendMarkStart() = sendToAll(PATH_MARK_START)
    suspend fun sendMarkEnd() = sendToAll(PATH_MARK_END)

    private suspend fun sendToAll(path: String): Boolean {
        return try {
            val nodes = nodeClient.connectedNodes.await()
            if (nodes.isEmpty()) return false
            nodes.forEach { node ->
                messageClient.sendMessage(node.id, path, ByteArray(0)).await()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "send $path failed", e)
            false
        }
    }

    companion object {
        private const val TAG = "WatchMessageSender"
        const val PATH_MARK_START = "/mark_start"
        const val PATH_MARK_END = "/mark_end"
    }
}
