package com.diving.replay.wear

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Native-camera-style REC / STOP (plan §4 Phase 4). REC drops a start marker on the phone and
 * shows a running timer; STOP drops the end marker and shows "saving…" until the phone acks
 * (`/ack_saved`, `/ack_partial`, `/ack_error`). The camera itself never starts/stops here (§8).
 */
class MainActivity : ComponentActivity() {

    private lateinit var sender: WatchMessageSender

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sender = WatchMessageSender(this)
        setContent { WatchScreen(sender) { block -> lifecycleScope.launch { block() } } }
    }
}

private enum class RecState { IDLE, RECORDING, SAVING, DONE, PARTIAL, ERROR }

@Composable
private fun WatchScreen(sender: WatchMessageSender, launch: (suspend () -> Unit) -> Unit) {
    val context = LocalContext.current
    var state by remember { mutableStateOf(RecState.IDLE) }
    var startedElapsed by remember { mutableLongStateOf(0L) }
    var tick by remember { mutableLongStateOf(0L) }
    var phoneConnected by remember { mutableStateOf(false) }
    var ackDetail by remember { mutableStateOf("") }

    // running timer while recording
    LaunchedEffect(state) {
        while (state == RecState.RECORDING) {
            tick = SystemClock.elapsedRealtime()
            delay(500)
        }
    }

    // poll phone connection
    LaunchedEffect(Unit) {
        while (true) {
            phoneConnected = runCatching { Wearable.getNodeClient(context).connectedNodes.await().isNotEmpty() }
                .getOrDefault(false)
            delay(4000)
        }
    }

    // listen for phone acks
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val client = Wearable.getMessageClient(context)
        val listener = MessageClient.OnMessageReceivedListener { event ->
            ackDetail = String(event.data)
            state = when (event.path) {
                "/ack_saved" -> RecState.DONE
                "/ack_partial" -> RecState.PARTIAL
                "/ack_error" -> RecState.ERROR
                else -> state
            }
        }
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> client.addListener(listener)
                Lifecycle.Event.ON_PAUSE -> client.removeListener(listener)
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
            client.removeListener(listener)
        }
    }

    // auto-return to idle after a terminal state
    LaunchedEffect(state) {
        if (state in listOf(RecState.DONE, RecState.PARTIAL, RecState.ERROR)) {
            delay(2500)
            state = RecState.IDLE
        }
    }

    MaterialTheme {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(if (phoneConnected) "phone ✓" else "phone ✗", color = if (phoneConnected) Color.Green else Color.Red)

                when (state) {
                    RecState.IDLE -> {
                        Text("Ready", modifier = Modifier.padding(4.dp))
                        Button(
                            onClick = {
                                state = RecState.RECORDING
                                startedElapsed = SystemClock.elapsedRealtime()
                                launch { sender.sendMarkStart() }
                            },
                            colors = ButtonDefaults.primaryButtonColors(backgroundColor = Color.Red),
                            modifier = Modifier.size(72.dp).clip(CircleShape).padding(top = 4.dp),
                        ) { Text("REC") }
                    }
                    RecState.RECORDING -> {
                        val secs = ((tick - startedElapsed).coerceAtLeast(0)) / 1000
                        Text("● %02d:%02d".format(secs / 60, secs % 60), modifier = Modifier.padding(4.dp))
                        Button(
                            onClick = {
                                state = RecState.SAVING
                                launch { sender.sendMarkEnd() }
                            },
                            colors = ButtonDefaults.primaryButtonColors(),
                            modifier = Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)).padding(top = 4.dp),
                        ) { Text("STOP") }
                    }
                    RecState.SAVING -> Text("Saving…")
                    RecState.DONE -> Text("Saved $ackDetail", color = Color.Green)
                    RecState.PARTIAL -> Text("Partial $ackDetail", color = Color(0xFFFFA000))
                    RecState.ERROR -> Text("Failed", color = Color.Red)
                }
            }
        }
    }
}
