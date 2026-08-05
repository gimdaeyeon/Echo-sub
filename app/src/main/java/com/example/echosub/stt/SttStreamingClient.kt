package com.example.echosub.stt

import android.content.Context
import android.util.Log
import com.google.cloud.speech.v1.RecognitionConfig
import com.google.cloud.speech.v1.SpeechGrpc
import com.google.cloud.speech.v1.StreamingRecognitionConfig
import com.google.cloud.speech.v1.StreamingRecognizeRequest
import com.google.cloud.speech.v1.StreamingRecognizeResponse
import com.google.protobuf.ByteString
import io.grpc.ManagedChannel
import io.grpc.Status
import io.grpc.android.AndroidChannelBuilder
import io.grpc.auth.MoreCallCredentials
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

private const val TAG = "SttStreamingClient"

private const val HOSTNAME = "speech.googleapis.com"
private const val PORT = 443

/**
 * 서버가 스트림을 닫기 전에 선제적으로 재연결하는 시점.
 * Google STT 스트리밍은 오디오 5분(약 305초) 제한이 있어, 여유를 두고 240초에 갱신한다.
 */
private const val STREAM_REFRESH_MS = 240_000L

/** 단어 시각을 못 받았을 때 자막 하나가 화면에 떠 있을 수 있는 최대 길이. */
private const val MAX_CUE_MS = 15_000L

/**
 * 장문·미디어용 인식 모델. 기본 모델(`default`)은 짧은 발화를 전제로 튜닝돼 있어
 * 강의·영상처럼 이어지는 말에서 눈에 띄게 더 틀린다 — 번역 품질은 인식 정확도를
 * 넘어설 수 없으므로 여기가 가장 앞단의 품질 손실 지점이다.
 *
 * 요금은 `default`와 같은 Standard로 매겨지므로 비용은 그대로다.
 * 다만 지원 언어가 기본 모델보다 좁아, 못 쓰는 언어에서는 [FALLBACK_MODEL]로 되돌린다.
 */
private const val LONG_FORM_MODEL = "latest_long"

/** 모델을 지정하지 않으면 서버가 기본 모델을 고른다 — 지원 언어가 가장 넓다. */
private val FALLBACK_MODEL: String? = null

/**
 * 문장이 아직 안 끝난 조각을 다음 조각과 합치려고 기다리는 시간.
 *
 * 이 시간을 늘리면 번역 품질은 오르지만 자막이 그만큼 늦게 뜬다. 조각이 문장 부호로
 * 끝나 있으면 기다리지 않고 바로 내보내므로, 실제로 이 지연을 무는 것은 문장 중간에
 * 끊긴 조각뿐이다.
 */
private const val SENTENCE_MERGE_WINDOW_MS = 900L

/** 아무리 문장이 안 닫혀도 이 길이를 넘으면 그냥 내보낸다 (자막 한 장이 지나치게 길어지지 않게). */
private const val MAX_MERGED_CHARS = 220

/**
 * 종결 부호가 끝내 안 나와도 문장을 시작한 지 이 시간이 지나면 무조건 내보낸다.
 *
 * [SENTENCE_MERGE_WINDOW_MS]/[MAX_MERGED_CHARS]만으로는 상한이 없었다 — 화자가 쉼표로만
 * 말을 이어가고 마침표를 오래 찍지 않으면(강의·설명형 발화) 문장이 닫히길 하염없이
 * 기다려, "번역되는 단위가 길어서 한참 뒤에 보인다"는 결과로 이어졌다. 문맥을 위해 조각을
 * 붙이는 이득은 유지하되, 그 대가로 무는 지연에는 반드시 한도를 둔다.
 */
private const val MAX_MERGE_WAIT_MS = 3_000L

/** 문장이 닫혔다고 볼 종결 부호. */
private const val SENTENCE_TERMINATORS = ".!?。！？…"

/** 종결 부호 뒤에 따라붙을 수 있는 닫는 문장부호 — 여기까지 걷어내고 마지막 글자를 본다. */
private const val TRAILING_MARKS = "\"'”’」』)]»"

private fun String.endsSentence(): Boolean {
    val last = trimEnd().trimEnd(*TRAILING_MARKS.toCharArray()).lastOrNull() ?: return false
    return last in SENTENCE_TERMINATORS
}

private fun com.google.protobuf.Duration.toMillis(): Long =
    seconds * 1_000L + nanos / 1_000_000L

/**
 * Google Cloud Speech-to-Text 스트리밍 인식 클라이언트.
 *
 * 스트리밍 인식은 REST가 아니라 gRPC 양방향 스트리밍으로만 제공된다.
 * gRPC 채널은 한 번만 만들어 재사용하고, 5분 제한에 걸리는 것은 "스트림 호출"이므로
 * 재연결 시에는 채널은 유지한 채 streamingRecognize() 호출만 새로 연다.
 *
 * [sendAudioChunk]는 캡처 루프(IO 스레드)에서 호출되고 재연결은 별도 코루틴에서 일어나므로
 * 스트림 교체 구간은 [lock]으로 직렬화한다 (gRPC StreamObserver는 thread-safe 하지 않다).
 */
class SttStreamingClient(
    context: Context,
    private val languageCode: String = "en-US",
    private val sampleRateHertz: Int = 16_000,
    /**
     * 지금까지 녹음 파일에 쌓인 오디오 길이(ms)를 알려준다. 서버가 주는 시간은 **현재 스트림**
     * 기준 상대값이라, 240초마다 스트림을 새로 열 때마다 0으로 리셋된다 — 스트림을 열 때
     * 이 값을 찍어두고 더해야 녹음 파일 전체 기준의 절대 시각이 나온다.
     */
    private val audioPositionMs: () -> Long = { 0L },
    /** 확정 결과가 나올 때마다 호출된다 (id는 [TranscriptState.appendFinal]이 발급한 값). */
    private val onFinalResult: (id: Long, text: String, startMs: Long, endMs: Long) -> Unit =
        { _, _, _, _ -> },
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    /** 문장 병합 상태 전용 락. 인식 콜백 스레드와 병합 마감 타이머가 함께 건드린다. */
    private val sentenceLock = Any()

    private var channel: ManagedChannel? = null
    private var stub: SpeechGrpc.SpeechStub? = null
    private var requestObserver: StreamObserver<StreamingRecognizeRequest>? = null
    private var refreshJob: Job? = null

    /** 스트림 세대 번호 — 낡은 스트림의 콜백이 새 스트림을 건드리지 못하게 하는 가드 */
    private var generation = 0

    /** 현재 스트림이 시작된 시점의 녹음 파일 내 위치(ms). 서버 상대시간에 이 값을 더한다. */
    @Volatile
    private var streamStartAudioMs: Long = 0L

    /** 단어 시작시각을 못 받았을 때 자막 시작점으로 쓸 직전 문장의 끝시각. */
    @Volatile
    private var lastFinalEndMs: Long = 0L

    @Volatile
    private var running = false

    /**
     * 이 스트림에서 서버가 문장 부호를 붙여준 적이 있는지.
     *
     * `enableAutomaticPunctuation`은 언어마다 지원 여부가 다르다. 부호가 아예 안 오는
     * 언어에서 "문장이 닫히길 기다리는" 병합을 켜면 **모든** 자막이 [SENTENCE_MERGE_WINDOW_MS]만큼
     * 늦어지기만 하고 얻는 게 없다. 그래서 부호를 한 번이라도 본 뒤에만 병합을 시작한다.
     */
    @Volatile
    private var punctuationSeen = false

    // --- 병합 중인 문장 (sentenceLock 아래에서만 건드린다) ---
    private var pendingId: Long = -1
    private var pendingText: String = ""
    private var pendingStartMs: Long = 0L
    private var pendingEndMs: Long = 0L

    /** 마감 타이머 세대 — 낡은 타이머가 이미 새로 시작된 문장을 끊지 못하게 하는 가드. */
    private var sealGeneration = 0

    /**
     * 실제로 요청에 실을 인식 모델. [LONG_FORM_MODEL]로 시작하고, 서버가 이 언어에서
     * 그 모델을 거부하면(INVALID_ARGUMENT) [FALLBACK_MODEL]로 한 번 내려간다.
     */
    @Volatile
    private var recognitionModel: String? = LONG_FORM_MODEL

    /**
     * 인증서 로드 + gRPC 채널 생성 + 첫 스트림 오픈. 네트워크 I/O를 하므로 IO 컨텍스트에서 호출해야 한다.
     */
    fun start() {
        if (running) {
            Log.w(TAG, "already started")
            return
        }
        running = true
        TranscriptState.update {
            TranscriptState.Status(connectionState = TranscriptState.ConnectionState.CONNECTING)
        }

        try {
            val credentials = loadSttCredentials(appContext)
            val newChannel = AndroidChannelBuilder.forAddress(HOSTNAME, PORT)
                .context(appContext)
                .build()
            channel = newChannel
            stub = SpeechGrpc.newStub(newChannel)
                .withCallCredentials(MoreCallCredentials.from(credentials))
        } catch (e: Exception) {
            failed("STT 초기화 실패: ${e.message}", e)
            return
        }

        synchronized(lock) { openStream() }
    }

    /**
     * PCM 16bit LE 청크를 현재 스트림으로 전송한다. 스트림이 교체 중이면 해당 청크는 버려진다
     * (재연결은 수백 ms 이내라 실사용에서 체감되지 않음).
     */
    fun sendAudioChunk(buffer: ByteArray, length: Int) {
        if (!running || length <= 0) return
        synchronized(lock) {
            val observer = requestObserver ?: return
            try {
                observer.onNext(
                    StreamingRecognizeRequest.newBuilder()
                        .setAudioContent(ByteString.copyFrom(buffer, 0, length))
                        .build()
                )
            } catch (e: Exception) {
                Log.w(TAG, "audio chunk 전송 실패 — 스트림 재연결 예약", e)
                requestObserver = null
                scheduleReconnect(immediate = true)
            }
        }
    }

    fun close() {
        running = false
        refreshJob?.cancel()
        refreshJob = null

        // 아직 문장이 닫히길 기다리던 조각을 흘려보내지 않는다 — 여기서 확정하지 않으면
        // 마지막 한 문장이 번역도 자막 파일 기록도 없이 사라진다.
        // scope.cancel()보다 먼저 해야 한다(마감 타이머가 그 scope에 있다).
        synchronized(sentenceLock) { sealPending() }

        synchronized(lock) {
            generation++
            try {
                requestObserver?.onCompleted()
            } catch (e: Exception) {
                Log.w(TAG, "requestObserver.onCompleted() 실패", e)
            }
            requestObserver = null
        }

        channel?.let { ch ->
            try {
                ch.shutdown().awaitTermination(2, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                Log.w(TAG, "채널 종료 대기 중 인터럽트", e)
            }
        }
        channel = null
        stub = null

        scope.cancel()
        TranscriptState.update {
            it.copy(connectionState = TranscriptState.ConnectionState.IDLE, interimText = "")
        }
        Log.i(TAG, "closed")
    }

    /** [lock]을 잡은 상태에서 호출해야 한다. */
    private fun openStream() {
        val currentStub = stub ?: return
        val myGeneration = ++generation
        // 이 스트림에 실릴 오디오는 지금 이 지점부터 시작한다 — 서버가 주는 상대시간의 기준점.
        streamStartAudioMs = audioPositionMs()

        val responseObserver = object : StreamObserver<StreamingRecognizeResponse> {
            override fun onNext(response: StreamingRecognizeResponse) {
                handleResponse(response)
            }

            override fun onError(t: Throwable) {
                if (myGeneration != generation) return // 이미 교체된 낡은 스트림
                // 이 언어에서 장문 모델을 못 쓰는 경우 — 사용자 눈에 오류로 보이기 전에
                // 기본 모델로 조용히 내려가 다시 연결한다.
                if (running && recognitionModel != null && Status.fromThrowable(t).code == Status.Code.INVALID_ARGUMENT) {
                    Log.w(TAG, "$recognitionModel 모델을 쓸 수 없는 요청 — 기본 모델로 재시도", t)
                    recognitionModel = FALLBACK_MODEL
                    scheduleReconnect(immediate = true)
                    return
                }
                Log.e(TAG, "스트림 오류", t)
                if (running) {
                    TranscriptState.update {
                        it.copy(
                            connectionState = TranscriptState.ConnectionState.ERROR,
                            errorMessage = "STT 스트림 오류: ${t.message}",
                        )
                    }
                    scheduleReconnect(immediate = false)
                }
            }

            override fun onCompleted() {
                if (myGeneration != generation) return
                Log.i(TAG, "서버가 스트림을 종료함")
                if (running) scheduleReconnect(immediate = true)
            }
        }

        val observer = currentStub.streamingRecognize(responseObserver)

        val recognitionConfig = RecognitionConfig.newBuilder()
            .setEncoding(RecognitionConfig.AudioEncoding.LINEAR16)
            .setSampleRateHertz(sampleRateHertz)
            .setLanguageCode(languageCode)
            .setEnableAutomaticPunctuation(true)
            // 문장이 "끝난" 시각(resultEndTime)만으로는 자막을 언제 띄울지 알 수 없다.
            // 단어별 시각까지 받아 첫 단어의 시작을 자막 시작점으로 쓴다.
            .setEnableWordTimeOffsets(true)
            .also { builder -> recognitionModel?.let { builder.model = it } }
            .build()

        observer.onNext(
            StreamingRecognizeRequest.newBuilder()
                .setStreamingConfig(
                    StreamingRecognitionConfig.newBuilder()
                        .setConfig(recognitionConfig)
                        .setInterimResults(true)
                        .setSingleUtterance(false)
                        .build()
                )
                .build()
        )

        requestObserver = observer
        TranscriptState.update {
            it.copy(connectionState = TranscriptState.ConnectionState.CONNECTED, errorMessage = null)
        }
        Log.i(TAG, "스트림 오픈 (gen=$myGeneration, lang=$languageCode, ${sampleRateHertz}Hz)")

        refreshJob?.cancel()
        refreshJob = scope.launch {
            delay(STREAM_REFRESH_MS)
            if (running) {
                Log.i(TAG, "5분 제한 도달 전 선제 재연결")
                reconnect()
            }
        }
    }

    private fun handleResponse(response: StreamingRecognizeResponse) {
        response.resultsList.forEach { result ->
            val alternative = result.alternativesList.firstOrNull() ?: return@forEach
            val transcript = alternative.transcript ?: return@forEach
            if (result.isFinal) {
                Log.i(TAG, "final: $transcript")

                val base = streamStartAudioMs
                val endMs = if (result.hasResultEndTime()) {
                    base + result.resultEndTime.toMillis()
                } else {
                    audioPositionMs()
                }
                // 단어 시각이 오면 첫 단어의 시작이 곧 자막이 떠야 할 시점이다.
                // 안 오면 직전 문장이 끝난 지점부터 이어 붙이되, 무음 구간이 길었을 때
                // 자막 하나가 몇 분씩 떠 있지 않도록 상한을 둔다.
                val firstWordStart = alternative.wordsList.firstOrNull()?.startTime
                val startMs = if (firstWordStart != null) {
                    base + firstWordStart.toMillis()
                } else {
                    lastFinalEndMs.coerceAtLeast(endMs - MAX_CUE_MS)
                }.coerceIn(0L, endMs)
                lastFinalEndMs = endMs

                acceptFinal(transcript.trim(), startMs, endMs)
            } else {
                TranscriptState.update { it.copy(interimText = transcript) }
            }
        }
    }

    /**
     * 확정 결과 한 조각을 받아, 문장이 닫힐 때까지 앞 조각과 합친다.
     *
     * 서버가 주는 확정 결과는 "문장"이 아니라 발화가 잠깐 끊긴 지점까지의 **조각**이다.
     * 조각마다 따로 번역하면 번역기가 반 토막 문장을 문맥 없이 옮기게 되고, 그게 이 앱
     * 번역이 어색했던 큰 원인이다. 여기서 문장으로 다시 붙여 한 번에 번역한다.
     */
    private fun acceptFinal(text: String, startMs: Long, endMs: Long) {
        if (text.isBlank()) return
        if (text.endsSentence()) punctuationSeen = true

        synchronized(sentenceLock) {
            if (pendingId >= 0) {
                pendingText = "$pendingText $text"
                pendingEndMs = endMs
                TranscriptState.extendFinal(pendingId, pendingText, pendingEndMs)
            } else {
                pendingId = TranscriptState.appendFinal(text, startMs, endMs)
                pendingText = text
                pendingStartMs = startMs
                pendingEndMs = endMs
                scheduleHardSeal(pendingId)
            }

            val shouldWait = punctuationSeen &&
                !pendingText.endsSentence() &&
                pendingText.length < MAX_MERGED_CHARS
            if (shouldWait) scheduleSeal() else sealPending()
        }
    }

    /**
     * 문장을 처음 시작했을 때 **딱 한 번** 걸어두는 상한 타이머.
     *
     * [scheduleSeal]의 900ms 창은 조각이 새로 올 때마다 다시 잡히는 "다음 조각을 기다리는"
     * 타이머라 문장이 계속 이어지는 한 리셋될 뿐 상한이 없다. 이 타이머는 리셋되지 않고
     * 문장 시작 시점부터 [MAX_MERGE_WAIT_MS]가 지나면 그 사이 몇 조각이 더 붙었든 무조건
     * 끊어 보낸다. [id]가 이미 봉인돼 다음 문장으로 넘어갔으면(불일치) 조용히 아무것도 안 한다.
     */
    private fun scheduleHardSeal(id: Long) {
        scope.launch {
            delay(MAX_MERGE_WAIT_MS)
            synchronized(sentenceLock) {
                if (pendingId == id) sealPending()
            }
        }
    }

    /**
     * 뒤에 이어질 조각을 잠깐 기다린다. 그 사이 새 조각이 오면 [acceptFinal]이 다시
     * 판단하고 타이머는 세대 번호로 무효가 된다.
     */
    private fun scheduleSeal() {
        val myGeneration = ++sealGeneration
        scope.launch {
            delay(SENTENCE_MERGE_WINDOW_MS)
            synchronized(sentenceLock) {
                if (myGeneration == sealGeneration) sealPending()
            }
        }
    }

    /** 모아둔 문장을 확정해 번역/자막 기록으로 넘긴다. [sentenceLock]을 잡고 호출해야 한다. */
    private fun sealPending() {
        val id = pendingId
        if (id < 0) return
        val text = pendingText
        val startMs = pendingStartMs
        val endMs = pendingEndMs

        pendingId = -1
        pendingText = ""
        sealGeneration++ // 대기 중인 타이머 무효화

        onFinalResult(id, text, startMs, endMs)
    }

    private fun scheduleReconnect(immediate: Boolean) {
        scope.launch {
            if (!immediate) delay(1_000)
            if (running) reconnect()
        }
    }

    private fun reconnect() {
        synchronized(lock) {
            if (!running) return
            try {
                requestObserver?.onCompleted()
            } catch (e: Exception) {
                Log.w(TAG, "재연결 중 이전 스트림 종료 실패", e)
            }
            requestObserver = null

            try {
                openStream()
                TranscriptState.update { it.copy(reconnectCount = it.reconnectCount + 1) }
            } catch (e: Exception) {
                failed("스트림 재연결 실패: ${e.message}", e)
            }
        }
    }

    private fun failed(message: String, cause: Throwable) {
        Log.e(TAG, message, cause)
        running = false
        TranscriptState.update {
            it.copy(
                connectionState = TranscriptState.ConnectionState.ERROR,
                errorMessage = message,
            )
        }
    }
}
