# Replay Video — 다이빙 인스턴트 리플레이

다이빙장에서 **도약~입수 순간을 놓치지 않고 바로 다시 보기** 위한 안드로이드 앱.

폰(또는 태블릿)을 삼각대에 세워두면 카메라가 **최근 3분을 상시 롤링 버퍼**로 계속 녹화한다.
방금 뛴 다이빙이 마음에 걸리면 화면을 아래로 밀어 최근 3분을 스크럽해서 다시 보고, 원하는
구간만 골라 갤러리에 mp4로 저장한다. 상용 다이빙장 아이패드 앱과 비슷한 사용감을 목표로 한 개인 프로젝트.

> REC를 눌러야 녹화가 "시작"되는 방식이 아니다. 카메라는 앱이 켜져 있는 동안 **절대 멈추지 않고**,
> REC/STOP은 "저장할 구간의 시작/끝 표시"일 뿐이라 이미 지나간 장면도 건질 수 있다.

---

## 주요 기능

| 기능 | 설명 |
|---|---|
| 상시 롤링 버퍼 | 15초 세그먼트로 끊김 없이 녹화, 최근 3분 + 안전마진 1분만 디스크에 유지(자동 삭제) |
| 라이브 프리뷰 | 화면에 항상 카메라가 떠 있음 |
| 리와인드 스크럽 | 아래로 스와이프 → 타임라인 바 드래그로 프레임 미리보기, 손을 떼면 그 지점부터 재생돼 위치 확인 |
| 구간 저장 | 리와인드에서 시작점·끝점을 찍고 그 구간만 병합·트림해서 갤러리(`Movies/DivingReplay`)로 저장 |
| 핀치 줌 + 잠금 | 두 손가락으로 카메라 줌(녹화분에도 반영). 세션 중 실수 방지를 위한 줌 잠금(해제는 2초 롱프레스) |
| 터치 기반 화면 디밍 | 15초 무터치 → 화면 디밍(배터리 절약), 아무 터치나 하면 즉시 풀밝기 + 타이머 리셋 |
| 갤럭시 워치 연동 | 워치 REC/STOP 버튼으로 폰의 저장 구간 마커를 원격 표시 (Wear Data Layer) |
| 클립 목록 | 저장된 클립 목록, 탭하면 외부 플레이어로 재생, 개별 삭제 |
| 설정 | 해상도(720p/1080p/4K), 비트레이트, 버퍼 길이, 대기 화면 모드 |

## 동작 방식

```
[카메라] ──CameraX VideoCapture──▶ filesDir/buffer/seg_*.mp4  (15초 링, 오래된 건 자동 삭제)
                                          │
   리와인드: 세그먼트들을 ExoPlayer 플레이리스트로 "이어보기" (파일 병합 X, seek만)
   저장    : 시작~끝 마커 구간의 세그먼트들 → Media3 Transformer로 병합·트림 → MediaStore
   워치    : MessageClient 로 /mark_start · /mark_end 만 전달, 카메라 세션은 폰이 단독 소유
```

- 카메라·버퍼는 포그라운드 서비스(`RecordingService`)가 소유하므로 앱을 백그라운드로 보내도 녹화가 계속된다.
- 재생/스크럽은 실제 파일을 합치지 않고 플레이리스트 seek 로 처리해 가볍다. 물리적 병합은 "저장"할 때만.
- 자세한 설계 배경은 [`docs/diving_replay_app_plan.md`](docs/diving_replay_app_plan.md) 참고.

## 기술 스택

Kotlin 1.9.23 · Jetpack Compose (Material 3) · CameraX 1.3.4 · Media3 (ExoPlayer + Transformer) 1.3.1 ·
DataStore · play-services-wearable 18.2.0 · Wear Compose
빌드: AGP 8.3.2 / Gradle 8.6 / JDK 17 / compileSdk 34 / minSdk 26(폰)·30(워치)

## 빌드

```bash
git clone https://github.com/spacetototo-del/replay_video.git
cd replay_video
# local.properties 에 Android SDK 경로 지정 (Android Studio로 열면 자동 생성)
#   sdk.dir=/path/to/Android/Sdk
./gradlew :app:assembleDebug :wear:assembleDebug
```

또는 Android Studio에서 폴더를 열고 Sync → Run.

APK 출력: `app/build/outputs/apk/debug/app-debug.apk`, `wear/build/outputs/apk/debug/wear-debug.apk`
첫 빌드는 의존성(CameraX/Media3/Compose 등)을 네트워크에서 받는다.

## 설치

### 폰 / 태블릿
`app-debug.apk` 를 기기로 옮겨 파일 관리자에서 탭 → "출처를 알 수 없는 앱" 허용 → 설치.
또는 `adb install -r app-debug.apk`.
첫 실행 시 카메라·마이크·알림 권한을 허용해야 버퍼가 시작된다.

### 갤럭시 워치 (Wear OS)
워치는 파일을 직접 넣을 수 없어 ADB 로 설치한다.
- **PC**: 워치 설정 → 개발자 옵션 → 무선 디버깅 → `adb pair` → `adb connect` → `adb install -r wear-debug.apk`
- **폰만**: 워치에서 "블루투스로 디버깅" 켜고, 폰의 Bugjaeger 같은 ADB 앱으로 `wear-debug.apk` 설치

폰·워치 앱의 `applicationId` 가 같아야(`com.diving.replay`) Data Layer 페어링이 된다.

## 설정 (앱 내)

- **Resolution / Bitrate**: 변경 시 버퍼가 초기화된다(다른 인코딩의 세그먼트는 이어붙일 수 없어서).
- **Buffer length**: 2 / 3 / 5분.
- **Idle screen**: `Dim when idle`(기본) / `Always bright` / `Screen off when idle`.
  녹화는 어느 모드에서도 멈추지 않으며, 화면만 바뀐다.

## 알려진 제한 / TODO

- 세그먼트 경계에서 수백 ms 프레임 누락 가능(트레이드오프).
- 앱 재시작 시 이전 세션 버퍼는 버리고 새로 시작한다(시간 공백 때문).
- 워치 라이브 프리뷰는 미구현(보류).
- RECORD_AUDIO 권한 흐름 점검 필요 — 현재 일부 상황에서 오디오가 무음으로 녹음될 수 있음.
- 아이콘/스플래시 등 리소스 정리, 배터리 실측.

## 라이선스

개인 프로젝트. 별도 라이선스 명시 전까지 All rights reserved.
