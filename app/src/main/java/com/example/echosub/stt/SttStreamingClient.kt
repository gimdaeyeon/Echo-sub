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

                val id = TranscriptState.appendFinal(transcript, startMs, endMs)
                onFinalResult(id, transcript, startMs, endMs)
            } else {
                TranscriptState.update { it.copy(interimText = transcript) }
            }
        }
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
