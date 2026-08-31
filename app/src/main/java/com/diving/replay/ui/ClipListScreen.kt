package com.diving.replay.ui

import android.content.ContentUris
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
data class ClipRow(
    val uri: Uri,
    val name: String,
    val addedSec: Long,
    val sizeBytes: Long,
    val durationMs: Long,
)

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
            .onFailure { Toast.makeText(context, "이 영상을 열 앱이 없어요", Toast.LENGTH_SHORT).show() }
    }

    /**
     * Deletion is never silent: on A11+ the system shows its own confirmation for the whole
     * batch, which is why bulk cleanup is offered as one request rather than a background sweep.
     * Exported clips are the user's footage — the app warns, it doesn't tidy up behind them.
     */
    fun delete(rows: List<ClipRow>) {
        if (rows.isEmpty()) return
        scope.launch {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val pi = MediaStore.createDeleteRequest(context.contentResolver, rows.map { it.uri })
                deleteLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
            } else {
                withContext(Dispatchers.IO) {
                    rows.forEach { runCatching { context.contentResolver.delete(it.uri, null, null) } }
                }
                reloadKey++
            }
        }
    }

    /** Everything past the keep count — the list is newest-first, so that's the tail. */
    val surplus = clips.drop(Constants.EXPORTED_CLIP_KEEP_COUNT)

    ScreenScaffold {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBackIosNew, contentDescription = "뒤로", modifier = Modifier.padding(end = 4.dp))
                Text("라이브")
            }
            Text(
                "저장영상 ${clips.size}개",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(end = 12.dp),
            )
        }

        if (surplus.isNotEmpty()) {
            val mb = surplus.sumOf { it.sizeBytes } / (1024 * 1024)
            SettingCard(Modifier.padding(horizontal = 12.dp)) {
                Text(
                    "보관 한도(${Constants.EXPORTED_CLIP_KEEP_COUNT}개)를 넘었어요 — " +
                        "오래된 ${surplus.size}개가 ${mb}MB 차지 중.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { delete(surplus) }) {
                    Text("오래된 ${surplus.size}개 삭제")
                }
            }
        }

        LazyColumn(
            Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp).padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            items(clips, key = { it.uri.toString() }) { row ->
                var thumb by remember(row.uri) { mutableStateOf<Bitmap?>(null) }
                LaunchedEffect(row.uri) {
                    thumb = withContext(Dispatchers.IO) {
                        runCatching {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                context.contentResolver.loadThumbnail(row.uri, Size(320, 180), null)
                            } else {
                                null
                            }
                        }.getOrNull()
                    }
                }

                Surface(
                    Modifier.fillMaxWidth().clickable { open(row) },
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(DivingTokens.chipRadius),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Row(
                        Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            Modifier
                                .width(112.dp)
                                .height(63.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            val t = thumb
                            if (t != null) {
                                Image(
                                    bitmap = t.asImageBitmap(),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.width(112.dp).height(63.dp),
                                )
                            }
                            Icon(
                                Icons.Rounded.PlayArrow,
                                contentDescription = "재생",
                                tint = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.92f),
                            )
                            val durSec = (row.durationMs / 1000).toInt()
                            if (durSec > 0) {
                                Text(
                                    "%d:%02d".format(durSec / 60, durSec % 60),
                                    color = androidx.compose.ui.graphics.Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(4.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f))
                                        .padding(horizontal = 4.dp, vertical = 1.dp),
                                )
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                row.name,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 13.sp,
                                maxLines = 1,
                            )
                            Text(
                                "길이 ${(row.durationMs / 1000).toInt()}초  ·  ${row.sizeBytes / (1024 * 1024)}MB\n" +
                                    DateFormat.getDateTimeInstance().format(Date(row.addedSec * 1000)),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        TextButton(onClick = { delete(listOf(row)) }) { Text("삭제") }
                    }
                }
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
        MediaStore.Video.Media.DURATION,
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
        val durCol = c.getColumnIndex(MediaStore.Video.Media.DURATION)
        while (c.moveToNext()) {
            val id = c.getLong(idCol)
            out += ClipRow(
                uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id),
                name = c.getString(nameCol),
                addedSec = c.getLong(dateCol),
                sizeBytes = c.getLong(sizeCol),
                durationMs = if (durCol >= 0) c.getLong(durCol) else 0L,
            )
        }
    }
    return out
}
