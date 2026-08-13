package com.example.echosub.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.echosub.MainActivity
import com.example.echosub.R
import com.example.echosub.caption.RegionSelectOverlay
import com.example.echosub.caption.ScreenCaptionReader
import com.example.echosub.caption.SubtitleSource
import com.example.echosub.capture.CaptureFormat
import com.example.echosub.capture.PcmDownsampler
import com.example.echosub.capture.PlaybackAudioRecorder
import com.example.echosub.capture.WavFileWriter
import com.example.echosub.capture.bytesPerFrame
import com.example.echosub.capture.computeRms16BitLe
import com.example.echosub.data.AppSettings
import com.example.echosub.device.AppForegroundState
import com.example.echosub.overlay.OverlaySettings
import com.example.echosub.overlay.SubtitleOverlay
import com.example.echosub.stt.SttStreamingClient
import com.example.echosub.subtitle.SubtitleRecorder
import com.example.echosub.subtitle.subtitleFileFor
import com.example.echosub.stt.TranscriptState
import com.example.echosub.translate.DeepLTranslator
import com.example.echosub.translate.LanguagePair
import com.example.echosub.translate.MlKitTranslator
import com.example.echosub.translate.TranslationEngine
import com.example.echosub.translate.TranslationFailedException
import com.example.echosub.translate.Translator
import com.example.echosub.translate.TranslatorUnavailableException
import com.example.echosub.util.enumByName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Phase 1 파이프라인 전체를 orchestration 하는 Foreground Service.
 *
 * Android 14+(API 34) 요구사항 때문에 순서가 매우 중요하다:
 *   1) startForeground()를 먼저 호출해 FGS 상태를 확정
 *   2) 그 다음에만 MediaProjectionManager.getMediaProjection() 호출 가능
 *   3) MediaProjection.Callback 등록 필수 (미등록 시 캡처 중 예외)
 *   4) 프로젝션 토큰은 1회용 — stop 후 재시작하려면 동의 다이얼로그를 다시 띄워야 함 → START_NOT_STICKY
 */
class AudioCaptureService : Service() {

    companion object {
        private const val TAG = "AudioCaptureService"
        private const val NOTIFICATION_CHANNEL_ID = "capture_channel"
        private const val NOTIFICATION_ID = 1001

        /** Google STT 스트리밍에 보내는 오디오 포맷 — 캡처 포맷과 무관하게 항상 이 값이다. */
        private const val STT_SAMPLE_RATE = 16_000

        /** 번역기에 문맥으로 함께 넘길 직전 문장 개수. */
        private const val CONTEXT_SENTENCES = 3

        /** 오버레이 히스토리에 함께 보여줄 최근 문장 개수. */
        private const val OVERLAY_HISTORY_SIZE = 6

        const val ACTION_START = "com.example.echosub.action.START"
        const val ACTION_STOP = "com.example.echosub.action.STOP"
        const val ACTION_PAUSE_STT = "com.example.echosub.action.PAUSE_STT"
        const val ACTION_RESUME_STT = "com.example.echosub.action.RESUME_STT"

        private const val EXTRA_RESULT_CODE = "extra_result_code"
        private const val EXTRA_DATA = "extra_data"
        private const val EXTRA_FORMAT = "extra_format"
        private const val EXTRA_SAVE_16K_COMPANION = "extra_save_16k_companion"
        private const val EXTRA_STT_ENABLED = "extra_stt_enabled"
        private const val EXTRA_LANGUAGE_PAIR = "extra_language_pair"
        private const val EXTRA_TRANSLATION_ENGINE = "extra_translation_engine"
        private const val EXTRA_OVERLAY_ENABLED = "extra_overlay_enabled"
        private const val EXTRA_SUBTITLE_SOURCE = "extra_subtitle_source"

        fun buildStartIntent(
            context: Context,
            resultCode: Int,
            data: Intent,
            format: CaptureFormat,
            save16kCompanion: Boolean,
            sttEnabled: Boolean,
            languagePair: LanguagePair,
            translationEngine: TranslationEngine,
            overlayEnabled: Boolean,
            subtitleSource: SubtitleSource,
        ): Intent = Intent(context, AudioCaptureService::class.java).apply {
            action = ACTION_START
            putExtra(EXTRA_RESULT_CODE, resultCode)
            putExtra(EXTRA_DATA, data)
            putExtra(EXTRA_FORMAT, format.name)
            putExtra(EXTRA_SAVE_16K_COMPANION, save16kCompanion)
            putExtra(EXTRA_STT_ENABLED, sttEnabled)
            putExtra(EXTRA_LANGUAGE_PAIR, languagePair.name)
            putExtra(EXTRA_TRANSLATION_ENGINE, translationEngine.name)
            putExtra(EXTRA_OVERLAY_ENABLED, overlayEnabled)
            putExtra(EXTRA_SUBTITLE_SOURCE, subtitleSource.name)
        }

        fun buildStopIntent(context: Context): Intent =
            Intent(context, AudioCaptureService::class.java).apply { action = ACTION_STOP }

        /** STT 스트림만 끊는다 — 오디오 캡처/녹화는 계속된다. 무음 구간에서 STT 사용료를 아끼기 위한 것. */
        fun buildPauseSttIntent(context: Context): Intent =
            Intent(context, AudioCaptureService::class.java).apply { action = ACTION_PAUSE_STT }

        fun buildResumeSttIntent(context: Context): Intent =
            Intent(context, AudioCaptureService::class.java).apply { action = ACTION_RESUME_STT }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var captureJob: Job? = null

    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var recorder: PlaybackAudioRecorder? = null

    private var primaryWriter: WavFileWriter? = null
    private var companionWriter: WavFileWriter? = null
    private var downsampler: PcmDownsampler? = null

    @Volatile
    private var subtitleRecorder: SubtitleRecorder? = null

    /**
     * 지금까지 캡처한 프레임 수 = 녹음 파일의 재생 시각. 캡처 루프(IO)가 쓰고
     * STT 콜백 스레드가 읽으므로 원자적으로 다룬다.
     */
    private val capturedFrames = AtomicLong(0)
    private var captureBytesPerFrame = 2
    private var captureSampleRate = STT_SAMPLE_RATE

    /** 녹음 파일 기준 현재 위치(ms). 자막에 붙일 시각의 기준. */
    private fun audioPositionMs(): Long =
        capturedFrames.get() * 1_000L / captureSampleRate

    @Volatile
    private var sttClient: SttStreamingClient? = null

    /** 화면 자막 읽기 모드의 원문 소스 — [sttClient]와 동시에 존재하지 않는다. */
    @Volatile
    private var captionReader: ScreenCaptionReader? = null

    @Volatile
    private var translator: Translator? = null

    // 일시정지 후 재개할 때 같은 구성으로 소스를 다시 만들기 위해 기억해둔다.
    private var activeLanguagePair: LanguagePair? = null
    private var activeSubtitleSource: SubtitleSource = SubtitleSource.AUDIO_STT

    private var overlay: SubtitleOverlay? = null
    private var regionOverlay: RegionSelectOverlay? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 저장된 자막 크기를 OverlaySettings에 실어두기 위한 것. 이미 초기화됐으면 no-op.
        AppSettings.init(this)
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> handleStop()
            ACTION_PAUSE_STT -> handlePauseStt()
            ACTION_RESUME_STT -> handleResumeStt()
            else -> Log.w(TAG, "unknown or null action: ${intent?.action}")
        }
        // MediaProjection 토큰은 1회용이므로 시스템이 서비스를 재생성해도 재개할 수 없다.
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        if (recorder != null) {
            Log.w(TAG, "capture already running, ignoring duplicate ACTION_START")
            return
        }

        createNotificationChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_DATA)
        }
        if (data == null) {
            failAndStop("MediaProjection data intent가 없습니다")
            return
        }

        val format = enumByName(intent.getStringExtra(EXTRA_FORMAT), CaptureFormat.HIFI)
        val save16kCompanion = intent.getBooleanExtra(EXTRA_SAVE_16K_COMPANION, false) &&
            format == CaptureFormat.HIFI

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = try {
            mpm.getMediaProjection(resultCode, data)
        } catch (e: SecurityException) {
            failAndStop("MediaProjection 획득 실패: ${e.message}")
            return
        }
        projection = proj

        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "MediaProjection.Callback.onStop() — 캡처 세션 종료됨")
                stopCaptureAndSelf()
            }
        }
        proj.registerCallback(callback, Handler(Looper.getMainLooper()))
        projectionCallback = callback

        val newRecorder = PlaybackAudioRecorder(proj, format)
        try {
            newRecorder.start()
        } catch (e: IllegalStateException) {
            failAndStop("오디오 캡처 시작 실패: ${e.message}")
            return
        }
        recorder = newRecorder

        val sttEnabled = intent.getBooleanExtra(EXTRA_STT_ENABLED, false)

        val outputDir = File(getExternalFilesDir(null), "captures").apply { mkdirs() }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(java.util.Date())
        val outputPaths = mutableListOf<String>()

        val primaryFile = File(outputDir, "capture_${timestamp}_${format.sampleRate / 1000}k_${channelLabel(format)}.wav")
        primaryWriter = WavFileWriter(primaryFile, format.sampleRate, format.channelCount)
        outputPaths += primaryFile.absolutePath

        // STT는 16kHz 모노만 받는다. 원본이 그보다 크면 실시간으로 변환해서 보내고
        // 파일에는 원본을 그대로 쓴다 — 덕분에 원음 녹음과 번역을 동시에 할 수 있다.
        // 같은 변환 결과를 16k 동반 파일 저장도 함께 쓴다 (두 번 변환할 이유가 없다).
        if (format != CaptureFormat.STT_READY && (sttEnabled || save16kCompanion)) {
            downsampler = PcmDownsampler(inputSampleRate = format.sampleRate, outputSampleRate = STT_SAMPLE_RATE)
        }

        if (save16kCompanion) {
            val companionFile = File(outputDir, "capture_${timestamp}_16k_mono.wav")
            companionWriter = WavFileWriter(companionFile, STT_SAMPLE_RATE, 1)
            outputPaths += companionFile.absolutePath
        }

        // 자막을 재생 시점에 맞춰 띄우려면 "녹음 파일 기준 몇 초 지점인지"가 필요하다.
        // 캡처한 프레임 수가 곧 그 시계다 (벽시계는 STT 응답 지연 때문에 못 쓴다).
        capturedFrames.set(0)
        captureBytesPerFrame = format.bytesPerFrame()
        captureSampleRate = format.sampleRate

        CaptureState.update {
            CaptureState.Status(
                isRunning = true,
                format = format,
                outputPaths = outputPaths,
            )
        }

        if (sttEnabled) {
            val languagePair = enumByName(intent.getStringExtra(EXTRA_LANGUAGE_PAIR), LanguagePair.EN_TO_KO)
            val engine = enumByName(intent.getStringExtra(EXTRA_TRANSLATION_ENGINE), TranslationEngine.ML_KIT)
            val subtitleSource =
                enumByName(intent.getStringExtra(EXTRA_SUBTITLE_SOURCE), SubtitleSource.AUDIO_STT)

            activeLanguagePair = languagePair
            activeSubtitleSource = subtitleSource

            // 자막은 화면 표시용 TranscriptState와 별개로 여기에 전부 모아 파일로 남긴다
            // (TranscriptState는 최근 50문장만 유지하므로 긴 녹음이면 앞부분이 잘린다).
            subtitleRecorder = SubtitleRecorder(subtitleFileFor(primaryFile))

            TranscriptState.reset()

            // 번역기 준비(모델 다운로드/키 검증)는 STT 연결과 별개로 병렬로 진행한다 —
            // 어느 쪽이 실패해도 다른 쪽은 계속 동작해야 한다 (번역 실패해도 인식 결과는 봐야 함).
            serviceScope.launch(Dispatchers.IO) {
                val newTranslator: Translator = when (engine) {
                    TranslationEngine.ML_KIT -> MlKitTranslator(languagePair)
                    TranslationEngine.DEEPL -> DeepLTranslator(languagePair)
                }
                try {
                    newTranslator.prepare()
                    translator = newTranslator
                } catch (e: TranslatorUnavailableException) {
                    Log.w(TAG, "번역기 준비 실패 — 인식 결과만 표시", e)
                    TranscriptState.update { it.copy(translatorMessage = e.message) }
                }
            }

            startSubtitleSource()

            val overlayEnabled = intent.getBooleanExtra(EXTRA_OVERLAY_ENABLED, false)
            if (overlayEnabled && Settings.canDrawOverlays(this)) {
                showAndBindOverlay()
            }
            // 화면 자막 모드는 시작하자마자 읽기 영역부터 맞추게 한다 — 기본 영역이
            // 실시간 자막 창과 어긋나 있으면 아무것도 읽히지 않는데, 그 이유가
            // 화면에 보이지 않으면 "고장"으로 읽힌다.
            if (subtitleSource == SubtitleSource.SCREEN_CAPTION && Settings.canDrawOverlays(this)) {
                showRegionOverlay()
            }
        }

        val startedAt = System.currentTimeMillis()
        captureJob = serviceScope.launch(Dispatchers.IO) {
            runCaptureLoop(newRecorder, startedAt)
        }
    }

    private suspend fun CoroutineScope.runCaptureLoop(recorder: PlaybackAudioRecorder, startedAt: Long) {
        val buffer = ByteArray(recorder.chunkBytes)
        var totalBytes = 0L

        while (isActive) {
            val n = recorder.read(buffer)
            if (n > 0) {
                primaryWriter?.write(buffer, 0, n)

                // 파일에 쓴 만큼 오디오 시계를 먼저 진행시킨다 — STT가 결과에 붙이는
                // 시각의 기준점이라 청크를 보내기 전에 갱신돼 있어야 한다.
                capturedFrames.addAndGet((n / captureBytesPerFrame).toLong())

                val ds = downsampler
                if (ds == null) {
                    // 이미 16kHz 모노로 캡처 중 — 변환 없이 그대로 보낸다.
                    sttClient?.sendAudioChunk(buffer, n)
                } else {
                    val converted = ds.process(buffer, n)
                    if (converted.isNotEmpty()) {
                        sttClient?.sendAudioChunk(converted, converted.size)
                        companionWriter?.write(converted, 0, converted.size)
                    }
                }

                totalBytes += n
                val rms = computeRms16BitLe(buffer, n)
                CaptureState.update {
                    it.copy(
                        elapsedMs = System.currentTimeMillis() - startedAt,
                        bytesWritten = totalBytes,
                        rmsLevel = rms,
                    )
                }
            } else if (n < 0) {
                Log.e(TAG, "AudioRecord.read returned error code $n")
                CaptureState.update { it.copy(errorMessage = "AudioRecord 읽기 오류 (code=$n)") }
                break
            }
        }
    }

    /**
     * STT 확정 결과 하나를 번역한다. 번역기가 아직 준비되지 않았거나 준비에 실패했으면
     * 조용히 건너뛴다 — 원문(인식 결과)은 이미 화면에 나가 있으므로 번역만 비는 정도로 그친다.
     */
    private fun translateAndUpdate(id: Long, text: String) {
        val current = translator ?: return
        // 문맥은 지금(번역을 거는 시점) 기준으로 뽑는다 — 번역은 병렬로 도는데 코루틴
        // 안에서 다시 읽으면 나중 문장까지 섞여 들어온다.
        val precedingContext = precedingContextFor(id)
        serviceScope.launch(Dispatchers.IO) {
            try {
                val translated = current.translate(text, precedingContext)
                TranscriptState.setTranslation(id, translated)
                subtitleRecorder?.setTranslation(id, translated)
            } catch (e: TranslationFailedException) {
                Log.w(TAG, "번역 실패: ${e.message}")
                TranscriptState.setTranslationError(id, e.message ?: "번역 실패")
            }
        }
    }

    /**
     * [id] 문장 바로 앞에 나온 원문 몇 개.
     *
     * 자막 한 줄은 짧아서 그 줄만 보면 무엇을 가리키는지 알 수 없는 경우가 많다
     * ("그건 아니야"의 "그건"이 무엇인지 등). 앞 문장을 함께 넘기면 번역기가
     * 대명사·말투·용어를 앞뒤 맞게 고른다. 번역 결과에는 포함되지 않는다.
     */
    private fun precedingContextFor(id: Long): String =
        TranscriptState.status.value.finalEntries
            .filter { it.id < id }
            .takeLast(CONTEXT_SENTENCES)
            .joinToString(" ") { it.sourceText }

    private fun channelLabel(format: CaptureFormat): String =
        if (format.channelCount == 2) "stereo" else "mono"

    private fun handleStop() {
        stopCaptureAndSelf()
    }

    /**
     * 오버레이 창을 띄우고 상태 Flow들을 배선한다.
     *
     * handleStart()는 onStartCommand()를 통해 항상 메인 스레드에서 호출되므로 show()를
     * 바로 불러도 안전하다. 이후 갱신은 Dispatchers.Main으로 명시해야 한다 —
     * WindowManager 호출은 메인 스레드 전용이고, 아래 Flow들은 IO 코루틴에서도 값을 emit한다.
     */
    private fun showAndBindOverlay() {
        val ov = SubtitleOverlay(
            context = this,
            onOpenApp = { openMainActivity() },
            onTogglePause = { toggleSttFromOverlay() },
            // 화면 자막 모드에서만 영역 재지정 버튼이 생긴다 — 실시간 자막 창을
            // 옮기면 읽기 영역도 따라 옮겨야 하기 때문.
            onAdjustRegion = if (activeSubtitleSource == SubtitleSource.SCREEN_CAPTION) {
                { showRegionOverlay() }
            } else {
                null
            },
        )
        ov.show()
        overlay = ov

        serviceScope.launch(Dispatchers.Main) {
            OverlaySettings.textSizeSp.collect { ov.setTextSize(it) }
        }
        serviceScope.launch(Dispatchers.Main) {
            OverlaySettings.boxWidthDp.collect { ov.setBoxWidth(it) }
        }
        serviceScope.launch(Dispatchers.Main) {
            // 앱 화면에 이미 자막이 보이는 동안에는 오버레이를 숨긴다.
            AppForegroundState.isForeground.collect { inForeground -> ov.setVisible(!inForeground) }
        }
        serviceScope.launch(Dispatchers.Main) {
            // 최근 몇 개만 넘긴다 — 오버레이는 화면 한구석의 작은 창이라
            // TranscriptState 전량(최근 50개)을 다 그릴 이유가 없다.
            // interim(인식 중 텍스트)도 함께 흘린다 — 말이 확정되고 번역이
            // 돌아오기 전에도 상단 원문 영역에서 글자가 실시간으로 움직여야
            // 기다림이 "느림"으로 읽히지 않는다.
            TranscriptState.status
                .map { status ->
                    status.finalEntries.takeLast(OVERLAY_HISTORY_SIZE).map { e ->
                        SubtitleOverlay.Entry(e.sourceText, e.translatedText, e.translationError)
                    } to status.interimText
                }
                .distinctUntilChanged()
                .collect { (entries, interim) -> ov.setContent(entries, interim) }
        }
        serviceScope.launch(Dispatchers.Main) {
            TranscriptState.status
                .map { it.connectionState == TranscriptState.ConnectionState.PAUSED }
                .distinctUntilChanged()
                .collect { ov.setPaused(it) }
        }
    }

    /** 오버레이의 "앱으로 돌아가기" 버튼 — 다른 앱을 보다가 EchoSub를 따로 찾아 열 필요 없이 바로 전면으로. */
    private fun openMainActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        startActivity(intent)
    }

    /** 화면 자막 읽기 영역 선택 상자를 띄운다 (이미 떠 있으면 무시). 메인 스레드 전용. */
    private fun showRegionOverlay() {
        val existing = regionOverlay
        if (existing?.isShowing == true) return
        val ov = existing ?: RegionSelectOverlay(
            context = this,
            onConfirmed = { /* 영역은 조절 즉시 저장된다 — 확정은 상자를 닫을 뿐 */ },
        ).also { regionOverlay = it }
        ov.show()
    }

    /** 오버레이의 일시정지/재개 버튼 — Intent 왕복 없이 서비스 안에서 바로 처리한다. */
    private fun toggleSttFromOverlay() {
        if (sttClient != null || captionReader != null) handlePauseStt() else handleResumeStt()
    }

    /**
     * 원문 소스만 끊는다 (오디오 캡처/WAV 저장은 계속). 무음·대사 없는 구간에서
     * 사용자가 직접 눌러 STT 사용료나 OCR 배터리를 아끼기 위한 것 — [runCaptureLoop]의
     * `sttClient?.sendAudioChunk(...)` 호출은 sttClient가 null이면 그냥 건너뛴다.
     */
    private fun handlePauseStt() {
        val client = sttClient
        val reader = captionReader
        if (client == null && reader == null) return
        sttClient = null
        captionReader = null
        client?.close()
        reader?.close()
        TranscriptState.update {
            it.copy(connectionState = TranscriptState.ConnectionState.PAUSED, interimText = "")
        }
        Log.i(TAG, "원문 소스 일시정지 (사용자 요청)")
    }

    private fun handleResumeStt() {
        if (sttClient != null || captionReader != null) return
        if (activeLanguagePair == null) return
        startSubtitleSource()
        Log.i(TAG, "원문 소스 재개 (사용자 요청)")
    }

    /** 최초 시작과 일시정지 후 재개가 똑같이 "소스 새로 열기"라서 공통으로 뺐다. */
    private fun startSubtitleSource() {
        val languagePair = activeLanguagePair ?: return
        when (activeSubtitleSource) {
            SubtitleSource.AUDIO_STT -> startSttClient(languagePair)
            SubtitleSource.SCREEN_CAPTION -> startCaptionReader(languagePair)
        }
    }

    /**
     * STT 스트림을 연다. 캡처 포맷과 무관하게 STT로 가는 오디오는 항상 16kHz 모노다 —
     * 원본이 그보다 크면 [runCaptureLoop]가 변환해서 보낸다.
     */
    private fun startSttClient(languagePair: LanguagePair) {
        serviceScope.launch(Dispatchers.IO) {
            val client = SttStreamingClient(
                applicationContext,
                languageCode = languagePair.sttLanguageCode,
                sampleRateHertz = STT_SAMPLE_RATE,
                audioPositionMs = { audioPositionMs() },
                onFinalResult = { id, text, startMs, endMs ->
                    subtitleRecorder?.add(id, startMs, endMs, text)
                    translateAndUpdate(id, text)
                },
            )
            client.start()
            sttClient = client
        }
    }

    /**
     * 화면 자막 읽기(OCR)를 연다 — 문장이 닫힐 때의 콜백 규약이 STT와 같아서
     * 번역/자막 기록 이후 경로는 두 소스가 완전히 공유한다.
     */
    private fun startCaptionReader(languagePair: LanguagePair) {
        val proj = projection ?: return
        val reader = ScreenCaptionReader(
            context = this,
            projection = proj,
            languagePair = languagePair,
            audioPositionMs = { audioPositionMs() },
            onFinalResult = { id, text, startMs, endMs ->
                subtitleRecorder?.add(id, startMs, endMs, text)
                translateAndUpdate(id, text)
            },
        )
        reader.start()
        captionReader = reader
    }

    private fun failAndStop(message: String) {
        Log.e(TAG, message)
        CaptureState.update { it.copy(isRunning = false, errorMessage = message) }
        stopCaptureAndSelf()
    }

    private fun stopCaptureAndSelf() {
        captureJob?.cancel()
        captureJob = null

        recorder?.stop()
        recorder = null

        sttClient?.close()
        sttClient = null
        captionReader?.close()
        captionReader = null
        activeLanguagePair = null

        translator?.close()
        translator = null

        // stopCaptureAndSelf()는 항상 메인 스레드에서 호출된다(onStartCommand/MediaProjection.Callback
        // 모두 메인 루퍼) — hide()의 WindowManager 호출을 여기서 바로 해도 안전하다.
        overlay?.hide()
        overlay = null
        regionOverlay?.hide()
        regionOverlay = null

        primaryWriter?.close()
        primaryWriter = null
        companionWriter?.close()
        companionWriter = null
        downsampler = null

        // 일부러 null로 지우지 않는다 — 중지 시점에 아직 번역 중이던 마지막 문장들이
        // 조금 뒤에 도착하는데, 참조를 끊어버리면 그 번역이 파일에 못 들어간다.
        // setTranslation은 그때마다 파일을 다시 쓰므로 늦게 와도 반영된다.
        subtitleRecorder?.finish()

        projectionCallback?.let { cb -> projection?.unregisterCallback(cb) }
        projectionCallback = null
        projection?.stop()
        projection = null

        CaptureState.update { it.copy(isRunning = false) }

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        // 강제 종료 등 비정상 경로 대비 — 정상 경로에서는 이미 정리된 상태라 no-op에 가깝다.
        stopCaptureAndSelf()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val stopIntent = buildStopIntent(this)
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .addAction(0, getString(R.string.notification_stop_action), stopPendingIntent)
            .build()
    }
}
