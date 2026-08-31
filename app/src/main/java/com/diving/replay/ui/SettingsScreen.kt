package com.diving.replay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diving.replay.Constants
import com.diving.replay.data.CAPTURE_FPS_OPTIONS
import com.diving.replay.data.CaptureSettingsRepository
import com.diving.replay.data.DIM_AFTER_OPTIONS_SEC
import com.diving.replay.data.IdleScreenMode
import com.diving.replay.data.REPLAY_DELAY_OPTIONS_MS
import com.diving.replay.data.TargetResolution
import kotlinx.coroutines.launch

/**
 * Capture + playback preferences. Changing resolution / bitrate / fps makes RecordingService
 * wipe + restart the buffer (segments with different encoder settings can't be concatenated).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { CaptureSettingsRepository(context) }
    val scope = rememberCoroutineScope()
    val settings by repo.settings.collectAsState(initial = null)
    val s = settings

    ScreenScaffold {
        ScreenHeader("설정", onBack)

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val currentRes = s?.resolution ?: TargetResolution.FHD_1080P
            SettingCard {
                SectionLabel("해상도")
                ChipRow {
                    TargetResolution.entries.forEach { res ->
                        ChoiceChip(res.label, res == currentRes) { scope.launch { repo.setResolution(res) } }
                    }
                }
                Hint("높을수록 선명하지만 파일이 커지고 발열이 늘어요. 바꾸면 현재 버퍼가 초기화됩니다.")
            }

            val mbps = (s?.bitRateBps ?: currentRes.defaultBitRateBps) / 1_000_000
            SettingCard {
                SectionLabel("비트레이트 · ${mbps} Mbps")
                Slider(
                    value = mbps.toFloat(),
                    valueRange = 2f..40f,
                    steps = 37,
                    onValueChange = { scope.launch { repo.setBitRate(it.toInt() * 1_000_000) } },
                )
                Hint("같은 해상도라도 높이면 화질이 좋아지고 용량이 늘어요.")
            }

            val currentFps = s?.captureFps ?: 60
            SettingCard {
                SectionLabel("촬영 프레임레이트")
                ChipRow {
                    CAPTURE_FPS_OPTIONS.forEach { fps ->
                        ChoiceChip("${fps}fps", fps == currentFps) { scope.launch { repo.setCaptureFps(fps) } }
                    }
                }
                Hint("60fps면 0.5배속 슬로모션이 부드러워요. 120은 실험적이라 기기가 무시할 수 있어요. 바꾸면 버퍼가 초기화됩니다.")
            }

            val currentBuffer = s?.bufferRetentionMs ?: Constants.BUFFER_RETENTION_MS
            SettingCard {
                SectionLabel("버퍼 길이")
                ChipRow {
                    Constants.BUFFER_PRESETS_MS.forEach { preset ->
                        ChoiceChip("${preset / 60_000}분", preset == currentBuffer) {
                            scope.launch { repo.setBufferRetentionMs(preset) }
                        }
                    }
                }
                Hint("최근 이만큼을 되감을 수 있어요. 지난 영상은 자동으로 지워집니다.")
            }

            val currentDelay = s?.replayDelayMs ?: 25_000L
            SettingCard {
                SectionLabel("지연 재생 시간")
                ChipRow {
                    REPLAY_DELAY_OPTIONS_MS.forEach { option ->
                        ChoiceChip("${option / 1000}초", option == currentDelay) {
                            scope.launch { repo.setReplayDelayMs(option) }
                        }
                    }
                }
                Hint("지연 재생 화면이 몇 초 전 장면을 보여줄지. 물에서 나와 걸어올 시간만큼. 버퍼 길이보다 짧아야 해요.")
            }

            val currentIdle = s?.idleScreen ?: IdleScreenMode.DIM
            SettingCard {
                SectionLabel("대기 화면")
                ChipRow {
                    IdleScreenMode.entries.forEach { mode ->
                        ChoiceChip(mode.koLabel(), mode == currentIdle) { scope.launch { repo.setIdleScreen(mode) } }
                    }
                }
                Hint("녹화는 멈추지 않고 화면만 바뀝니다. 화면을 터치하거나 워치 REC 신호가 오면 다시 밝아져요.")
            }

            val currentDim = s?.dimAfterSec ?: 30
            SettingCard {
                SectionLabel("화면 어두워지기까지")
                ChipRow {
                    DIM_AFTER_OPTIONS_SEC.forEach { sec ->
                        ChoiceChip("${sec}초", sec == currentDim) { scope.launch { repo.setDimAfterSec(sec) } }
                    }
                }
                Hint("이 시간 동안 화면을 안 건드리면 어두워집니다. (‘대기 화면’이 항상 밝게면 적용 안 됨)")
            }
        }
    }
}

@Composable
private fun ScreenHeader(title: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onBack) {
            Icon(Icons.Rounded.ArrowBackIosNew, contentDescription = "뒤로", modifier = Modifier.padding(end = 4.dp))
            Text("라이브")
        }
        Text(
            title,
            modifier = Modifier.padding(start = 4.dp),
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun Hint(text: String) {
    Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun IdleScreenMode.koLabel(): String = when (this) {
    IdleScreenMode.ALWAYS_BRIGHT -> "항상 밝게"
    IdleScreenMode.DIM -> "대기 시 어둡게"
    IdleScreenMode.SCREEN_OFF -> "대기 시 화면 끄기"
}
