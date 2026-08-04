package com.example.echosub.translate

import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import com.google.mlkit.nl.translate.Translator as MlKitNativeTranslator

private const val TAG = "MlKitTranslator"

/**
 * ML Kit 온디바이스 번역.
 *
 * 모델을 한 번 받아두면 이후로는 네트워크 없이, 수십 ms 수준으로 번역한다.
 * 다만 ML Kit은 영어를 중간 언어로 사용하므로 일본어→한국어는 사실상 ja→en→ko
 * 2단계를 거친다 (품질 저하 요인). 이 프로젝트에서 DeepL과 나란히 두고 비교하는 이유.
 */
class MlKitTranslator(private val pair: LanguagePair) : Translator {

    private var delegate: MlKitNativeTranslator? = null

    override suspend fun prepare() {
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(pair.mlKitSource)
            .setTargetLanguage(pair.mlKitTarget)
            .build()
        val client = Translation.getClient(options)

        try {
            // 조건을 걸지 않으면 셀룰러에서도 받는다. 실사용 편의를 위해 그대로 둔다(모델당 약 30MB).
            client.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
        } catch (e: Exception) {
            client.close()
            throw TranslatorUnavailableException(
                "ML Kit 번역 모델 다운로드 실패 (네트워크 확인 필요): ${e.message}", e
            )
        }

        delegate = client
        Log.i(TAG, "모델 준비 완료: ${pair.label}")
    }

    override suspend fun translate(text: String): String {
        val client = delegate
            ?: throw TranslationFailedException("prepare()가 먼저 호출되어야 합니다")
        return try {
            client.translate(text).await()
        } catch (e: Exception) {
            throw TranslationFailedException("ML Kit 번역 실패: ${e.message}", e)
        }
    }

    override fun close() {
        delegate?.close()
        delegate = null
    }
}

/**
 * Play Services [Task]를 코루틴으로 감싼다.
 * kotlinx-coroutines-play-services 의존성을 추가하지 않기 위해 직접 구현했다.
 */
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { result -> continuation.resume(result) }
    addOnFailureListener { error -> continuation.resumeWithException(error) }
    addOnCanceledListener { continuation.cancel() }
}
