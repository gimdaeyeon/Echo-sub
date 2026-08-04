package com.example.echosub.translate

import android.util.Log
import com.example.echosub.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private const val TAG = "DeepLTranslator"

private const val FREE_ENDPOINT = "https://api-free.deepl.com/v2/translate"
private const val PRO_ENDPOINT = "https://api.deepl.com/v2/translate"

/**
 * DeepL REST API 번역.
 *
 * 요청이 단순한 form POST 하나뿐이라 Retrofit/OkHttp를 새로 끌어오지 않고
 * [HttpURLConnection]으로 직접 호출한다 — Phase 2에서 gRPC 의존성 충돌을 이미 겪었기 때문에
 * 의존성을 더 늘리지 않는 쪽이 안전하다.
 */
class DeepLTranslator(
    private val pair: LanguagePair,
    private val apiKey: String = BuildConfig.DEEPL_API_KEY,
) : Translator {

    /** Free 키는 ":fx"로 끝나고 전용 엔드포인트를 쓴다. */
    private val endpoint: String
        get() = if (apiKey.endsWith(":fx")) FREE_ENDPOINT else PRO_ENDPOINT

    override suspend fun prepare() {
        if (apiKey.isBlank()) {
            throw TranslatorUnavailableException(
                "DeepL API 키가 설정되지 않았습니다. local.properties의 deepl.api.key를 채운 뒤 다시 빌드하세요."
            )
        }
        Log.i(TAG, "준비 완료: ${pair.label}, endpoint=$endpoint")
    }

    override suspend fun translate(text: String): String = withContext(Dispatchers.IO) {
        val body = buildString {
            append("text=").append(URLEncoder.encode(text, "UTF-8"))
            append("&source_lang=").append(pair.deepLSource)
            append("&target_lang=").append(pair.deepLTarget)
        }

        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "DeepL-Auth-Key $apiKey")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connectTimeout = 10_000
            readTimeout = 10_000
            doOutput = true
        }

        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                val error = connection.errorStream
                    ?.bufferedReader()
                    ?.use(BufferedReader::readText)
                    .orEmpty()
                throw TranslationFailedException(deepLErrorMessage(code, error))
            }

            val response = connection.inputStream.bufferedReader().use(BufferedReader::readText)
            parseTranslation(response)
        } catch (e: TranslationFailedException) {
            throw e
        } catch (e: Exception) {
            throw TranslationFailedException("DeepL 요청 실패: ${e.message}", e)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseTranslation(json: String): String {
        val translations = JSONObject(json).optJSONArray("translations")
            ?: throw TranslationFailedException("DeepL 응답 형식이 예상과 다릅니다: $json")
        if (translations.length() == 0) {
            throw TranslationFailedException("DeepL이 번역 결과를 반환하지 않았습니다")
        }
        return translations.getJSONObject(0).optString("text")
    }

    private fun deepLErrorMessage(code: Int, body: String): String = when (code) {
        401, 403 -> "DeepL 인증 실패 (키가 잘못되었거나 만료됨)"
        429 -> "DeepL 요청이 너무 잦습니다 (rate limit)"
        456 -> "DeepL 무료 사용량(월 50만자)을 모두 소진했습니다"
        else -> "DeepL 오류 HTTP $code: $body"
    }
}
