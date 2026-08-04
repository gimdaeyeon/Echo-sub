package com.example.echosub.capture

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.util.Log

private const val TAG = "PlaybackAudioRecorder"

/**
 * MediaProjection 세션을 이용해 다른 앱의 재생 오디오(USAGE_MEDIA/GAME/UNKNOWN)를
 * PCM 16bit로 캡처하는 래퍼.
 *
 * 주의: [projection]은 [android.media.projection.MediaProjection.Callback]이 등록된 뒤,
 * Foreground Service가 이미 시작된 상태에서 생성된 것이어야 한다 (Android 14+ 요구사항).
 * 이 클래스 자체는 그 순서를 강제하지 않으므로 호출부(AudioCaptureService)가 책임진다.
 */
class PlaybackAudioRecorder(
    private val projection: MediaProjection,
    val format: CaptureFormat,
) {
    private var audioRecord: AudioRecord? = null

    /** 약 100ms 분량의 프레임 수 — Phase 2 STT 전송 청크 크기와 동일하게 맞춤 */
    val chunkBytes: Int = (format.sampleRate / 10) * format.bytesPerFrame()

    /**
     * AudioRecord를 생성하고 녹음을 시작한다.
     * @throws IllegalStateException 시스템이 캡처 설정을 초기화하지 못한 경우
     */
    fun start() {
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(format.sampleRate)
            .setChannelMask(format.channelMask)
            .build()

        val minBufferSize = AudioRecord.getMinBufferSize(
            format.sampleRate,
            format.channelMask,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBufferSize <= 0) {
            throw IllegalStateException("AudioRecord.getMinBufferSize failed: $minBufferSize")
        }
        val bufferSizeInBytes = maxOf(minBufferSize * 2, chunkBytes * 4)

        val record = AudioRecord.Builder()
            .setAudioFormat(audioFormat)
            .setAudioPlaybackCaptureConfig(captureConfig)
            .setBufferSizeInBytes(bufferSizeInBytes)
            .build()

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("AudioRecord failed to initialize (state=${record.state})")
        }

        record.startRecording()
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            record.release()
            throw IllegalStateException("AudioRecord.startRecording() did not enter RECORDING state")
        }

        audioRecord = record
        Log.i(TAG, "started: ${format.label}, bufferSize=$bufferSizeInBytes, chunkBytes=$chunkBytes")
    }

    /**
     * 블로킹 read. 호출자가 IO 디스패처 등 별도 스레드/코루틴에서 루프를 돌려야 한다.
     * @return 읽은 바이트 수, 음수면 [AudioRecord] 에러 코드
     */
    fun read(buffer: ByteArray): Int {
        val record = audioRecord ?: return -1
        return record.read(buffer, 0, buffer.size)
    }

    fun stop() {
        val record = audioRecord ?: return
        audioRecord = null
        try {
            record.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "stop() called on non-recording AudioRecord", e)
        }
        record.release()
    }
}
