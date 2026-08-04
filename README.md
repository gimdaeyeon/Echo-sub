# EchoSub

다른 앱에서 재생 중인 소리를 실시간으로 받아 번역 자막으로 띄우고, 동시에 원본 음질로 녹음해 두었다가
나중에 자막과 함께 다시 들을 수 있는 개인용 안드로이드 앱.

유튜브·강의 영상을 보면서 앱을 전환하지 않고 번역을 읽는 것이 1차 목적이고,
그때 흘려보낸 내용을 나중에 다시 확인할 수 있게 하는 것이 2차 목적이다.

> 개인 사용 목적의 사이드로드 APK다. Play Store 배포를 전제로 만들지 않았다 (→ [보안상 알려진 제약](#보안상-알려진-제약)).

---

## 무엇을 하는가

| | |
|---|---|
| **실시간 번역 자막** | 다른 앱의 재생 오디오를 캡처 → Google STT 스트리밍 인식 → 번역 → 화면 위 떠 있는 자막으로 표시 |
| **원음 녹음** | 번역과 **동시에** 44.1kHz 스테레오 WAV로 저장. 번역 때문에 음질을 포기하지 않아도 된다 |
| **자막 파일 저장** | 녹음과 짝이 되는 `.srt` 사이드카 파일을 오디오 기준 타임스탬프로 함께 저장 |
| **동기화 재생** | 저장된 녹음을 재생하면 그 시점의 자막이 크게 뜨고, 자막 목록에서 하이라이트 + 자동 스크롤 |
| **기록 관리** | 파일 목록/길이/용량 확인, 이름 바꾸기, 길게 눌러 다중 선택 삭제 |

지원 언어쌍은 **영어 → 한국어**, **일본어 → 한국어**.
번역 엔진은 **ML Kit**(온디바이스·무료·오프라인)와 **DeepL**(클라우드·고품질) 중 선택.

---

## 파이프라인

```
[다른 앱의 재생 오디오]
        │  MediaProjection + AudioPlaybackCaptureConfiguration
        ▼
  PlaybackAudioRecorder ──────────────► WavFileWriter (원본 그대로 저장)
        │                                    44.1kHz 스테레오
        │  PcmDownsampler (44.1k 스테레오 → 16k 모노)
        ▼
  SttStreamingClient (gRPC 양방향 스트리밍)
        │  Google Cloud Speech-to-Text v1
        ▼
   확정 문장 + 오디오 기준 시각(startMs/endMs)
        ├──────────────► SubtitleRecorder ──► .srt 사이드카 파일
        ▼
    Translator (ML Kit | DeepL)
        │
        ├──► TranscriptState ──► 앱 내 자막 화면
        └──► SubtitleOverlay ──► 다른 앱 위 떠 있는 자막
```

핵심은 **한 번 캡처한 오디오가 두 갈래로 갈라진다**는 점이다.
원본은 그대로 파일로 가고, 복제본만 16kHz로 변환돼 STT로 간다.
덕분에 "원음으로 녹음하면 번역을 못 한다"는 초기 제약을 없앴다.

---

## 화면 구성

- **자막 탭** — 실시간 번역 결과. 번역문을 본문 크기로, 원문을 각주처럼 아래에 둔다 (이 앱을 쓰는 이유는 번역이므로)
- **기록 탭** — 저장된 녹음 목록. `날짜 · 길이 · 용량 · 자막 유무`
- **설정 탭** — 언어/엔진, 오버레이 크기, 고급(원음 녹음 여부)
- **재생 화면** — 기록에서 파일을 누르면 전체 화면으로. 현재 자막 크게 표시 + 목록 하이라이트/자동 스크롤

캡처 중에는 화면 하단에 **[번역 정지] [저장하고 종료]** 두 버튼이 나란히 뜬다.

---

## 구현하면서 부딪힌 문제들

이 프로젝트에서 실제로 시간이 든 곳들. 대부분 "되는 줄 알았는데 안 되는" 플랫폼 제약이었다.

### 1. Android 14+ MediaProjection 호출 순서

API 34부터 순서를 어기면 바로 예외가 난다.

```
1) startForeground(FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) 먼저
2) 그 다음에만 getMediaProjection() 호출 가능
3) MediaProjection.Callback 등록 필수 (미등록 시 캡처 중 예외)
4) 토큰은 1회용 — 중지 후 재시작하려면 동의 다이얼로그를 다시 띄워야 함
```

4번 때문에 서비스는 `START_NOT_STICKY`다. 시스템이 서비스를 되살려도 토큰이 없어 재개할 수 없으므로,
되살아나 봐야 좀비 서비스만 남는다.

### 2. Google STT의 5분 스트리밍 제한

스트리밍 인식은 오디오 약 305초에서 서버가 스트림을 끊는다.
여유를 두고 **240초마다 선제적으로 재연결**한다. 이때 두 가지를 지켰다.

- **채널은 유지하고 스트림만 새로 연다** — 제한에 걸리는 것은 gRPC 채널이 아니라 `streamingRecognize()` 호출이다.
  채널까지 다시 만들면 TLS 핸드셰이크 비용을 매번 낸다.
- **세대 번호(generation) 가드** — 낡은 스트림의 `onError`/`onCompleted` 콜백이 뒤늦게 도착해
  이미 살아 있는 새 스트림을 끊어버리는 일을 막는다.

### 3. 자막 타임스탬프 — 벽시계로는 절대 못 맞춘다

가장 까다로웠던 부분. "나중에 재생할 때 자막이 제 시점에 뜨게" 하려면
자막에 **녹음 파일 기준 몇 초 지점인지**를 붙여야 하는데:

- STT 결과는 실제 발화보다 **1~2초 늦게** 도착한다 → `System.currentTimeMillis()`를 쓰면 그만큼 밀린다
- 서버가 주는 `resultEndTime`은 **현재 스트림 기준 상대값**이다 → 240초마다 0으로 리셋된다

그래서 **오디오 프레임 카운터를 시계로 쓴다.**

```kotlin
// 캡처 루프(IO 스레드)가 쓰고, STT 콜백 스레드가 읽는다
private val capturedFrames = AtomicLong(0)
private fun audioPositionMs(): Long = capturedFrames.get() * 1_000L / captureSampleRate
```

스트림을 열 때마다 그 시점의 `audioPositionMs()`를 `streamStartAudioMs`로 찍어두고,
서버가 주는 상대 시각에 더해 절대 시각을 만든다. 재연결이 몇 번 일어나든 타임라인이 이어진다.

추가로 `enableWordTimeOffsets`를 켜서 **첫 단어의 시작 시각**을 자막 시작점으로 쓴다.
문장이 끝난 시각(`resultEndTime`)만으로는 자막을 *언제 띄울지* 알 수 없기 때문이다.

### 4. WAV 헤더 — 쓸 때도, 읽을 때도

WAV 헤더에는 전체 데이터 길이가 들어가는데 스트리밍 중에는 그 값을 알 수 없다.
44바이트 자리를 0으로 채워 먼저 써두고, `close()`에서 `RandomAccessFile`로 되돌아가 패치한다.

> `close()`가 호출되지 않으면(강제 종료 등) 크기 필드가 0으로 남아 재생 불가 파일이 된다.
> 정상 종료 경로를 반드시 타야 한다.

기록 탭의 **재생 길이 표시**도 같은 헤더를 거꾸로 읽어서 계산한다 (`WavDuration.kt`).
`MediaMetadataRetriever`를 쓰지 않은 이유는, 이미 우리가 쓴 포맷을 알고 있는데
파일마다 리트리버를 열 이유가 없기 때문. 녹음 중인 파일은 헤더가 아직 0이라 자연스럽게 길이가 안 나온다.

### 5. 오버레이는 Compose가 아니라 일반 View로

`ComposeView`를 `WindowManager`에 직접 붙이려면 `ViewTreeLifecycleOwner`,
`ViewTreeSavedStateRegistryOwner`, `ViewTreeViewModelStoreOwner`를 **손수 심어줘야 하고, 빠뜨리면 런타임 크래시**다.
이 오버레이는 사실상 `TextView` 하나 + 아이콘 두 개라 그 배선을 감당할 이유가 없었다.
앱 화면은 Compose 그대로 두고 오버레이만 일반 View로 만들었다.

그 밖에 지켜야 했던 것들:

- `FLAG_NOT_FOCUSABLE` **필수** — 없으면 오버레이가 포커스를 가로채 아래 앱의 뒤로가기/키보드가 죽는다
- `FLAG_NOT_TOUCHABLE`은 **쓰지 않는다** — 드래그로 위치를 옮겨야 하므로
- `addView`/`updateViewLayout`/`removeView`는 **메인 스레드 전용** — STT/번역 결과는 IO 코루틴에서 오므로 `Dispatchers.Main`으로 넘긴다
- 정리 경로에서 `removeView` 누락 시 서비스가 죽어도 창이 남는다

### 6. 앱 안에 있을 때는 오버레이를 숨긴다

앱 화면에 이미 자막이 보이는데 그 위에 또 띄울 이유가 없다.
`MainActivity.onStart/onStop` → `AppForegroundState` → 서비스가 구독 → `overlay.setVisible()`.

이때 **`removeView`가 아니라 `visibility = GONE` + `FLAG_NOT_TOUCHABLE`**로 처리한다.
창을 제거해 버리면 사용자가 드래그로 잡아둔 위치와 핀치로 맞춘 폭이 날아가기 때문.

### 7. 화면 회전 — 개별 화면을 고치는 대신 원인을 없앴다

회전할 때마다 보고 있던 것이 초기화되는 문제.
각 화면에 `rememberSaveable`을 흩뿌리는 대신 **Activity 재생성 자체를 막았다.**

```xml
android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden"
```

방향별 리소스가 따로 없고 Compose가 알아서 다시 배치하므로 재생성해서 얻는 게 없는 반면,
재생성되면 재생 중이던 `MediaPlayer`와 화면 상태가 전부 날아간다.
`rememberSaveable`은 그 위에 프로세스 사망 대비로만 남겼다.

### 8. 가로 모드 — 셸에서 탈출시킨 화면

가로로 눕히면 세로 높이가 절반 이하가 된다. 두 가지를 따로 처리했다.

- **재생 화면**은 아예 `Scaffold` 바깥에서 전체 화면으로 그린다.
  상단바 + 하단탭 + FAB에 자리를 내주면 재생 버튼이 화면 밖으로 잘렸다. 내부 패딩으로는 되찾을 수 없는 높이였다.
- **나머지 탭**은 가로일 때만 오른쪽에 96dp 여백을 준다.
  세로에서는 FAB이 콘텐츠 아래 여백 위에 뜨지만, 가로에서는 화면 한복판을 가렸다.

### 9. 깜빡이는 안내 문구 → 고정된 자리의 색으로

"입력 레벨이 거의 0입니다" 배너가 말이 끊길 때마다 나타났다 사라지며 레이아웃 전체를 흔들었다.
텍스트 배너를 없애고, 상단에 **항상 같은 자리에 있는** 레벨 바의 **색**으로만 알리도록 바꿨다.
자리는 고정, 색만 변화 — 정보량은 같은데 화면이 흔들리지 않는다.

### 10. 의존성 충돌 (gRPC + Google API)

gRPC/Google API 라이브러리들이 각자 같은 경로의 메타데이터를 들고 있어 병합 시 충돌한다.
`packagingOptions`에서 `META-INF/INDEX.LIST`, `DEPENDENCIES`, `LICENSE` 등을 제외했다.

또 `error_prone_annotations`와 `j2objc-annotations`는 최신 클래스 파일 버전으로 빌드되어
AGP 7.4.2 번들 D8이 파싱하지 못한다 (`Error while dexing`).
`@Retention(CLASS)` 애노테이션이라 런타임에 필요 없으므로 아예 제외했다.

같은 이유로 **DeepL 호출은 Retrofit/OkHttp가 아니라 `HttpURLConnection`**으로 직접 했다.
form POST 하나 때문에 의존성을 더 늘릴 이유가 없었다.

### 11. 종료 버튼의 의미가 모호했던 문제

"중지"가 일시정지인지 진짜 종료인지 헷갈린다는 피드백.
실제로는 파일을 확정 저장하고 끝내는 **되돌릴 수 없는** 동작이라, 그렇게 말하도록 바꿨다.

- 버튼 문구: `중지` → **`저장하고 종료`**
- 확인 다이얼로그 추가 — 무엇이 일어나는지 + "잠깐 멈추고 싶었던 것"이라면 대신 뭘 써야 하는지 안내
- STT 일시정지 버튼도 `일시정지` → **`번역 정지`** (멈추는 건 번역뿐, 녹음은 계속된다)
- 상태 표시도 `일시정지` → `녹음 중 · 번역 정지` (경과 시간이 계속 도는 이유가 읽혀야 하므로)

### 12. 늦게 도착하는 번역

캡처를 중지하는 시점에 마지막 문장들은 아직 번역 중이다.
`SubtitleRecorder` 참조를 `finish()` 직후 `null`로 지우면 그 번역이 파일에 못 들어간다.
참조를 살려두고, `setTranslation`이 그때마다 SRT 전체를 다시 쓰게 해서 늦게 와도 반영되도록 했다.

### 13. 빌드 환경 (이 머신 한정)

이 개발 머신의 JDK 17 설치본은 Windows에서 `java.nio` Selector 구현이 깨져 있어
(`Selector.open()`이 AF_UNIX 루프백 연결 실패) Gradle 클라이언트↔데몬 소켓이 아예 동작하지 않는다.
프로젝트 코드와 무관한 환경 문제라, JDK 11로 고정하는 래퍼 스크립트를 따로 뒀다.

```bash
./gradlew-jdk11.bat assembleDebug
```

---

## 설계 메모

**싱글턴 `StateFlow` 패턴** — 백그라운드(서비스/gRPC 콜백) → UI 상태 전달을 전부 같은 모양으로 통일했다.

| 객체 | 담당 |
|---|---|
| `CaptureState` | 캡처 실행 여부, 경과 시간, 입력 레벨, 출력 경로 |
| `TranscriptState` | 연결 상태, 확정 문장(최근 50개), interim 텍스트 |
| `AppSettings` | 사용자 설정 (SharedPreferences write-through) |
| `OverlaySettings` | 오버레이 글자 크기 / 박스 폭 |
| `DeviceState` | 배터리 최적화 예외 여부 |
| `AppForegroundState` | 앱이 화면에 보이는 중인지 |

반대로 **화면 안에서만 쓰는 일회성 상태는 로컬 `remember`로 둔다.**
기록 탭의 파일 목록, 다중 선택 상태, 재생 위치 등은 공유할 이유가 없어 StateFlow로 올리지 않았다.

**글자 크기와 박스 폭의 분리** — 예전에는 오버레이를 두 손가락으로 집으면 글자 크기가 바뀌어서,
박스만 넓히려 해도 글자까지 커졌다. 지금은 핀치가 `boxWidthDp`만 바꾸고 글자 크기는 설정 슬라이더 전담이다.

**`TranscriptState`(최근 50개)와 `SubtitleRecorder`(전량)의 분리** — 화면은 최근 것만 보면 되지만
파일에는 전부 남아야 한다. 긴 녹음에서 앞부분이 잘리는 것을 막기 위해 두 경로를 나눴다.

---

## 빌드

### 요구사항

- Android Studio / Gradle 7.5.1, AGP 7.4.2, Kotlin 1.9.22
- JDK 11 (위 [13번](#13-빌드-환경-이-머신-한정) 참고)
- minSdk 29 / targetSdk 34
- 실기기 필요 — 에뮬레이터는 `AudioPlaybackCapture` 동작이 보장되지 않는다

### 1. Google Cloud STT 서비스 계정 키

Speech-to-Text API가 활성화된 프로젝트에서 서비스 계정 키(JSON)를 발급받아 다음 경로에 둔다.

```
app/src/main/res/raw/service_account.json
```

이 파일은 `.gitignore`에 등록돼 있다 (커밋 금지).

### 2. DeepL API 키 (선택)

DeepL 엔진을 쓸 경우에만 필요하다. ML Kit만 쓸 거면 건너뛰어도 된다.

```properties
# local.properties
deepl.api.key=your-key-here
```

`local.properties` 역시 `.gitignore` 대상이며, 키는 `BuildConfig.DEEPL_API_KEY`로 노출된다.
`:fx`로 끝나는 무료 키는 전용 엔드포인트로 자동 분기한다.

### 3. 빌드 & 설치

```bash
./gradlew-jdk11.bat assembleDebug
```

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 4. 첫 실행

1. 설정 탭에서 언어쌍 / 엔진 / 오버레이 여부 확인
2. **캡처 시작** → 오버레이 권한 허용 → MediaProjection 동의에서 **"전체 화면 공유"** 선택
3. 다른 앱에서 소리 재생

> 안정적인 백그라운드 동작을 위해 설정 탭에서 **배터리 최적화 예외**를 등록하는 것을 권장한다.
> 등록하지 않으면 화면을 끄거나 다른 앱을 오래 쓸 때 OS가 캡처 서비스를 중단시킬 수 있다.

---

## 저장 위치

```
Android/data/com.example.echosub/files/captures/
├── capture_20260804_123456_44k_stereo.wav   # 녹음 (원음 모드)
├── capture_20260804_123456_44k_stereo.srt   # 짝이 되는 자막
└── capture_20260804_123456_16k_mono.wav     # 리샘플러 검증용 (선택)
```

자막은 확장자만 다른 같은 이름으로 짝을 찾으므로, 이름을 바꿀 때는 **둘 다 함께** 바뀐다.
삭제도 마찬가지로 짝을 함께 지운다.

SRT는 **번역문이 먼저, 원문이 둘째 줄**로 들어간다.

```
1
00:00:03,120 --> 00:00:07,480
좋아, 그래서 여기 우리는 코끼리 앞에 있습니다.
All right, so here we are in front of the elephants.
```

---

## 보안상 알려진 제약

**서비스 계정 키가 APK 안에 들어간다.** 개인용 사이드로드를 전제로 한 선택이다.
APK에서 키를 추출하는 것은 어렵지 않으므로, 이 앱을 누군가에게 배포할 일이 생기면
**반드시 프록시 서버 경유 방식으로 바꿔야 한다.**

`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`도 마찬가지다. Play Store 정책상 제한적으로만 허용되는 권한이며,
개인용 APK이기 때문에 쓰고 있다.

---

## 아직 하지 않은 것

- **압축 저장** — 현재는 무압축 WAV라 원음 모드에서 분당 약 10MB. AAC/Opus 인코딩은 넣지 않았다
- **자막 편집** — 인식/번역이 틀렸을 때 고칠 방법이 없다
- **언어쌍 확장** — 영어·일본어 → 한국어만
- **오버레이 위치/크기 영구 저장** — 크기는 저장되지만 위치는 앱 재시작 시 초기화된다
- **자동화 테스트** — 테스트 프레임워크 없이 실기기 검증으로만 확인했다
  (Galaxy S22 Ultra / Android 16에서 전 기능 종단 검증)

---

## 기술 스택

Kotlin · Jetpack Compose (Material 3) · Coroutines/StateFlow ·
MediaProjection + AudioPlaybackCapture · Google Cloud Speech-to-Text v1 (gRPC 스트리밍) ·
ML Kit Translation (온디바이스) · DeepL REST API
