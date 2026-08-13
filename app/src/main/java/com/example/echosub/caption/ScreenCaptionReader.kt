package com.example.echosub.caption

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.example.echosub.data.AppSettings
import com.example.echosub.stt.SentenceAssembler
import com.example.echosub.stt.TranscriptState
import com.example.echosub.translate.LanguagePair
import com.example.echosub.util.await
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "ScreenCaptionReader"

/** 화면을 읽는 주기. 실시간 자막은 단어 단위로 갱신되므로 이보다 촘촘할 이유가 없다. */
private const val OCR_INTERVAL_MS = 400L

/** 단어 시각이 없는 소스라 자막 하나가 커버할 수 있는 최대 구간을 여기서 자른다. */
private const val MAX_CUE_MS = 10_000L

/**
 * 화면의 지정 영역을 주기적으로 캡처해 OCR로 되읽고, 새로 나타난 텍스트를
 * 문장으로 조립해 번역 파이프라인에 넘기는 리더 — [com.example.echosub.stt.SttStreamingClient]의
 * 화면 자막판이다.
 *
 * 갤럭시 '실시간 자막'(시스템 Live Caption)이 이미 음성→텍스트를 하고 있다는 전제라
 * 이 앱은 소리를 인식하지 않는다. 흐름:
 *
 *   MediaProjection → VirtualDisplay(화면 미러) → ImageReader → 영역 crop →
 *   ML Kit OCR → [CaptionDiffer] (새 텍스트만) → [SentenceAssembler] (문장 조립) →
 *   onFinalResult (번역/자막 기록, STT 경로와 동일한 콜백 모양)
 *
 * 읽을 영역은 [AppSettings]의 captionRegion을 매 프레임 읽는다 — 사용자가
 * 영역 선택 오버레이로 조정하는 즉시 다음 프레임부터 반영된다.
 *
 * [MediaProjection]의 소유자는 서비스다 — 여기서는 VirtualDisplay만 만들고 지우며,
 * projection.stop()은 호출하지 않는다 (오디오 녹음이 같은 프로젝션을 쓰고 있다).
 */
class ScreenCaptionReader(
    context: Context,
    private val projection: MediaProjection,
    private val languagePair: LanguagePair,
    /** 녹음 파일 기준 현재 위치(ms) — 자막(SRT) 타임스탬프의 기준 시계. */
    private val audioPositionMs: () -> Long = { 0L },
    /** 문장이 닫힐 때마다 호출 — [com.example.echosub.stt.SttStreamingClient]와 같은 규약. */
    private val onFinalResult: (id: Long, text: String, startMs: Long, endMs: Long) -> Unit =
        { _, _, _, _ -> },
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val assembler = SentenceAssembler(
        scope = scope,
        onOpened = { text, startMs, endMs -> TranscriptState.appendFinal(text, startMs, endMs) },
        onExtended = { id, mergedText, endMs -> TranscriptState.extendFinal(id, mergedText, endMs) },
        onSealed = { id, text, startMs, endMs -> onFinalResult(id, text, startMs, endMs) },
    )
    private val differ = CaptionDiffer()

    private var recognizer: TextRecognizer? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    /** 캡처 해상도 — VirtualDisplay를 만든 시점의 화면 크기로 고정된다. */
    private var displayWidth = 0
    private var displayHeight = 0

    /** 직전에 내보낸 조각의 끝 시각 — 다음 조각의 시작점으로 이어 붙인다. */
    private var lastFragmentEndMs = 0L

    /**
     * 전체 화면 프레임을 받는 재사용 비트맵. 매 프레임 새로 만들면 화면 크기(약 10MB)를
     * 초당 두어 번 할당하게 된다 — 캡처 루프 한 스레드만 만지므로 재사용해도 안전하다.
     */
    private var fullFrameBitmap: Bitmap? = null

    @Volatile
    private var running = false

    fun start() {
        if (running) return

        val rec = CaptionOcr.recognizerFor(languagePair)
        if (rec == null) {
            TranscriptState.update {
                it.copy(
                    connectionState = TranscriptState.ConnectionState.ERROR,
                    errorMessage = CaptionOcr.unsupportedMessage(languagePair),
                )
            }
            return
        }
        recognizer = rec
        running = true
        TranscriptState.update {
            TranscriptState.Status(connectionState = TranscriptState.ConnectionState.CONNECTING)
        }

        // 회전까지 따라가려면 디스플레이 리스너가 필요하지만, 자막을 읽는 동안은
        // 화면 방향이 고정된 사용(영상 시청)이라 시작 시점 크기로 고정한다.
        val metrics = DisplayMetrics()
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        displayWidth = metrics.widthPixels
        displayHeight = metrics.heightPixels

        val reader = ImageReader.newInstance(
            displayWidth,
            displayHeight,
            PixelFormat.RGBA_8888,
            2,
        )
        imageReader = reader

        virtualDisplay = try {
            projection.createVirtualDisplay(
                "echosub-caption",
                displayWidth,
                displayHeight,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                null,
            )
        } catch (e: Exception) {
            Log.e(TAG, "VirtualDisplay 생성 실패", e)
            TranscriptState.update {
                it.copy(
                    connectionState = TranscriptState.ConnectionState.ERROR,
                    errorMessage = "화면 캡처 시작 실패: ${e.message}",
                )
            }
            running = false
            return
        }

        TranscriptState.update {
            it.copy(connectionState = TranscriptState.ConnectionState.CONNECTED, errorMessage = null)
        }
        Log.i(TAG, "화면 자막 읽기 시작 (${displayWidth}x${displayHeight}, lang=${languagePair.label})")

        scope.launch { captureLoop() }
    }

    private suspend fun captureLoop() {
        while (scope.isActive && running) {
            delay(OCR_INTERVAL_MS)
            val frame = grabRegionBitmap() ?: continue
            val text = try {
                recognizer?.let { ocr(it, frame) } ?: break
            } catch (e: Exception) {
                Log.w(TAG, "OCR 실패 — 다음 프레임에서 재시도", e)
                continue
            } finally {
                frame.recycle()
            }
            handleVisibleText(text)
        }
    }

    /** 최신 프레임을 얻어 지정 영역만 잘라낸다. 프레임이 아직 없으면 null. */
    private fun grabRegionBitmap(): Bitmap? {
        val reader = imageReader ?: return null
        val image = reader.acquireLatestImage() ?: return null
        return try {
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowPadding = plane.rowStride - pixelStride * displayWidth
            // rowStride 패딩 때문에 비트맵 폭을 패딩 포함으로 잡고 crop에서 걷어낸다.
            val paddedWidth = displayWidth + rowPadding / pixelStride
            val full = fullFrameBitmap
                ?.takeIf { it.width == paddedWidth && it.height == displayHeight }
                ?: Bitmap.createBitmap(paddedWidth, displayHeight, Bitmap.Config.ARGB_8888)
                    .also { fullFrameBitmap = it }
            full.copyPixelsFromBuffer(plane.buffer)

            val rect = regionRect()
            // 크롭은 새 비트맵이다 — OCR 후 호출자가 recycle 한다. full은 재사용하므로 남긴다.
            // (영역이 전체 화면과 일치하면 createBitmap이 원본을 그대로 돌려준다 —
            //  그 경우 소유권을 호출자에게 넘기고 재사용 캐시를 비운다.)
            val cropped = Bitmap.createBitmap(full, rect.left, rect.top, rect.width(), rect.height())
            if (cropped === full) fullFrameBitmap = null
            cropped
        } catch (e: Exception) {
            Log.w(TAG, "프레임 변환 실패", e)
            null
        } finally {
            image.close()
        }
    }

    /** 설정의 비율 영역을 현재 캡처 해상도의 픽셀 사각형으로 바꾼다 (최소 크기 보장). */
    private fun regionRect(): Rect {
        val region = AppSettings.values.value.captionRegion.sanitized()
        val left = (region.left * displayWidth).toInt().coerceIn(0, displayWidth - 1)
        val top = (region.top * displayHeight).toInt().coerceIn(0, displayHeight - 1)
        val right = (region.right * displayWidth).toInt().coerceIn(left + 1, displayWidth)
        val bottom = (region.bottom * displayHeight).toInt().coerceIn(top + 1, displayHeight)
        return Rect(left, top, right, bottom)
    }

    private suspend fun ocr(recognizer: TextRecognizer, bitmap: Bitmap): String {
        val result: Text = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        // 블록 순서는 보장되지 않는다 — 줄을 위→아래, 왼→오른쪽으로 다시 세운다.
        return result.textBlocks
            .flatMap { it.lines }
            .sortedWith(compareBy({ it.boundingBox?.top ?: 0 }, { it.boundingBox?.left ?: 0 }))
            .joinToString(" ") { it.text }
    }

    private fun handleVisibleText(visibleText: String) {
        val result = differ.process(visibleText)

        result.newText?.let { fragment ->
            val now = audioPositionMs()
            val startMs = lastFragmentEndMs.coerceAtLeast(now - MAX_CUE_MS).coerceAtMost(now)
            lastFragmentEndMs = now
            assembler.accept(fragment, startMs, now)
        }

        // 화면에 보이지만 아직 확정하지 않은 꼬리 — STT의 인식 중(interim) 텍스트와 같은 자리.
        TranscriptState.update { it.copy(interimText = result.interimText) }
    }

    fun close() {
        if (!running) {
            // start()가 인식기 미지원으로 일찍 반환한 경우에도 정리는 되어야 한다.
            recognizer?.close()
            recognizer = null
            scope.cancel()
            return
        }
        running = false

        // 유보 중이던 꼬리 → 조립 중이던 문장 순서로 마감해야 마지막 말이 안 사라진다.
        differ.flush()?.let { fragment ->
            val now = audioPositionMs()
            assembler.accept(fragment, lastFragmentEndMs.coerceAtMost(now), now)
        }
        assembler.flush()

        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        recognizer?.close()
        recognizer = null
        // recycle하지 않는다 — 캡처 루프(다른 스레드)가 마지막 프레임을 아직 만지고
        // 있을 수 있다. O 이후 비트맵 픽셀은 자바 힙이라 참조만 끊으면 GC가 거둔다.
        fullFrameBitmap = null

        scope.cancel()
        TranscriptState.update {
            it.copy(connectionState = TranscriptState.ConnectionState.IDLE, interimText = "")
        }
        Log.i(TAG, "화면 자막 읽기 종료")
    }
}
