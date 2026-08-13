package com.example.echosub.util

import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Play Services [Task]를 코루틴으로 감싼다.
 * kotlinx-coroutines-play-services 의존성을 추가하지 않기 위해 직접 구현했다.
 * ML Kit 번역([com.example.echosub.translate.MlKitTranslator])과
 * OCR([com.example.echosub.caption.ScreenCaptionReader])이 함께 쓴다.
 */
internal suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { result -> continuation.resume(result) }
    addOnFailureListener { error -> continuation.resumeWithException(error) }
    addOnCanceledListener { continuation.cancel() }
}
