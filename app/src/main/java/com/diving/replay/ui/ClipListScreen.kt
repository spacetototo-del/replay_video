package com.diving.replay.ui

import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.diving.replay.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * Lists exported clips from MediaStore, newest first (plan §4 Phase 9, §10).
 * Retention decision (see 의사결정.md): keep newest [Constants.EXPORTED_CLIP_KEEP_COUNT];
 * anything past that is flagged here and deleted only on explicit tap — never automatically.
 */
data class ClipRow(val uri: Uri, val name: String, val addedSec: Long, val sizeBytes: Long)

@Composable
fun ClipListScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var clips by remember { mutableStateOf<List<ClipRow>>(emptyList()) }
    var reloadKey by remember { mutableIntStateOf(0) }

    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { reloadKey++ }

    androidx.compose.runtime.LaunchedEffect(reloadKey) {
        clips = withContext(Dispatchers.IO) { queryClips(context) }
    }

    fun open(row: ClipRow) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(row.uri, "video/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { Toast.makeText(context, "No app to open this video", Toast.LENGTH_SHORT).show() }
    }

    fun delete(row: ClipRow) {
        scope.launch {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val pi = MediaStore.createDeleteRequest(context.contentResolver, listOf(row.uri))
                deleteLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
            } else {
                withContext(Dispatchers.IO) {
                    runCatching { context.contentResolver.delete(row.uri, null, null) }
                }
                reloadKey++
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onBack) { Text("Back to live") }
            Text("${clips.size} clips")
        }

        if (clips.size > Constants.EXPORTED_CLIP_KEEP_COUNT) {
            Text(
                "Over the keep limit of ${Constants.EXPORTED_CLIP_KEEP_COUNT} — consider deleting old clips.",
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }

        LazyColumn(Modifier.fillMaxWidth()) {
            items(clips, key = { it.uri.toString() }) { row ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { open(row) }
                        .padding(vertical = 8.dp),
                ) {
                    Text(row.name)
                    Text(
                        "${DateFormat.getDateTimeInstance().format(Date(row.addedSec * 1000))}  ·  " +
                            "${row.sizeBytes / (1024 * 1024)} MB  ·  tap to play",
                    )
                    OutlinedButton(
                        onClick = { delete(row) },
                        modifier = Modifier.padding(top = 4.dp),
                    ) { Text("Delete") }
                }
                HorizontalDivider()
            }
        }
    }
}

private fun queryClips(context: android.content.Context): List<ClipRow> {
    val out = mutableListOf<ClipRow>()
    val projection = arrayOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DISPLAY_NAME,
        MediaStore.Video.Media.DATE_ADDED,
        MediaStore.Video.Media.SIZE,
    )
    val (selection, args) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?" to arrayOf("%${Constants.EXPORT_RELATIVE_DIR}%")
    } else {
        "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?" to arrayOf("diving_%")
    }
    context.contentResolver.query(
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        projection,
        selection,
        args,
        "${MediaStore.Video.Media.DATE_ADDED} DESC",
    )?.use { c ->
        val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
        val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
        val dateCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
        val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
        while (c.moveToNext()) {
            val id = c.getLong(idCol)
            out += ClipRow(
                uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id),
                name = c.getString(nameCol),
                addedSec = c.getLong(dateCol),
                sizeBytes = c.getLong(sizeCol),
            )
        }
    }
    return out
}
