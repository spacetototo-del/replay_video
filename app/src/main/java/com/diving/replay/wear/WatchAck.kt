package com.diving.replay.wear

import android.content.Context
import android.util.Log
import com.diving.replay.Constants
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Phone -> watch acknowledgements after a save attempt (plan §4 Phase 4).
 * Fire-and-forget: the watch UI is optimistic, this just corrects it.
 */
class WatchAck(private val context: Context) {

    enum class Kind(val path: String) {
        SAVED(Constants.PATH_ACK_SAVED),
        PARTIAL(Constants.PATH_ACK_PARTIAL),
        ERROR(Constants.PATH_ACK_ERROR),
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val messageClient by lazy { Wearable.getMessageClient(context) }
    private val nodeClient by lazy { Wearable.getNodeClient(context) }

    fun send(kind: Kind, detail: String = "") {
        scope.launch {
            try {
                val payload = detail.toByteArray()
                nodeClient.connectedNodes.await().forEach { node ->
                    messageClient.sendMessage(node.id, kind.path, payload).await()
                }
            } catch (e: Exception) {
                Log.w(TAG, "ack ${kind.path} failed", e)
            }
        }
    }

    companion object {
        private const val TAG = "WatchAck"
    }
}
