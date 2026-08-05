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
 * 있으면 고품질 모델을 쓰고 없으면 알아서 기존 모델로 떨어지는 값.
 * (`quality_optimized`는 못 쓰는 계정에서 요청 자체가 거부되지만 `prefer_`는 그렇지 않다.)
 */
private const val QUALITY_MODEL_TYPE = "prefer_quality_optimized"

/**
 * 문맥으로 딸려 보낼 앞 문장들의 최대 길이.
 *
 * 문맥은 과금 대상이 아니지만(DeepL 문서 명시) 길수록 요청이 무거워지고 초점이 흐려진다.
 * 자막 두어 줄 분량이면 대명사와 말투를 맞추는 데 충분하다.
 */
internal const val MAX_CONTEXT_CHARS = 500

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

    /**
     * 고품질 모델을 요청에 실을지. 계정/플랜에 따라 이 파라미터 자체가 거부될 수 있어
     * (문서에 플랜별 지원 여부가 명시돼 있지 않다) 한 번 거부당하면 끄고 다시 붙이지 않는다.
     * 번역이 통째로 안 되는 것보다 모델 한 단계 낮춰 번역되는 쪽이 낫다.
     */
    @Volatile
    private var useQualityModel = true

    override suspend fun prepare() {
        if (apiKey.isBlank()) {
            throw TranslatorUnavailableException(
                "DeepL API 키가 설정되지 않았습니다. local.properties의 deepl.api.key를 채운 뒤 다시 빌드하세요."
            )
        }
        Log.i(TAG, "준비 완료: ${pair.label}, endpoint=$endpoint")
    }

    override suspend fun translate(text: String, precedingContext: String?): String =
        withContext(Dispatchers.IO) {
            try {
                request(text, precedingContext, withQualityModel = useQualityModel)
            } catch (e: UnsupportedParameterException) {
                // 이 계정에서 model_type을 받지 않는다 — 빼고 한 번만 다시 보낸다.
                Log.i(TAG, "model_type 미지원 계정으로 판단 — 이후 요청에서 제외한다")
                useQualityModel = false
                request(text, precedingContext, withQualityModel = false)
            }
        }

    private fun request(text: String, precedingContext: String?, withQualityModel: Boolean): String {
        val body = buildString {
            append("text=").append(URLEncoder.encode(text, "UTF-8"))
            append("&source_lang=").append(pair.deepLSource)
            append("&target_lang=").append(pair.deepLTarget)
            // 자막은 원문 줄바꿈/공백이 의미를 갖는 일이 거의 없지만, 켜두면 DeepL이
            // 임의로 문장을 재구성하지 않아 타이밍이 어긋날 여지가 줄어든다.
            append("&preserve_formatting=1")
            if (withQualityModel) {
                append("&model_type=").append(QUALITY_MODEL_TYPE)
            }
            precedingContext
                ?.takeLast(MAX_CONTEXT_CHARS)
                ?.takeIf { it.isNotBlank() }
                ?.let { append("&context=").append(URLEncoder.encode(it, "UTF-8")) }
        }

        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "DeepL-Auth-Key $apiKey")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connectTimeout = 10_000
            readTimeout = 10_000
            doOutput = true
        }

        return try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                val error = connection.errorStream
                    ?.bufferedReader()
                    ?.use(BufferedReader::readText)
                    .orEmpty()
                // 400은 "요청이 이상하다"는 뜻이라, 우리가 새로 붙인 선택 파라미터가
                // 원인일 수 있다. 그것부터 떼어내고 다시 시도해 본다.
                if (code == HttpURLConnection.HTTP_BAD_REQUEST && withQualityModel) {
                    throw UnsupportedParameterException(error)
                }
                throw TranslationFailedException(deepLErrorMessage(code, error))
            }

            val response = connection.inputStream.bufferedReader().use(BufferedReader::readText)
            parseTranslation(response)
        } catch (e: UnsupportedParameterException) {
            throw e
        } catch (e: TranslationFailedException) {
            throw e
        } catch (e: Exception) {
            throw TranslationFailedException("DeepL 요청 실패: ${e.message}", e)
        } finally {
            connection.disconnect()
        }
    }

    /** 선택 파라미터 때문에 거부당했다는 내부 신호 — 밖으로 새지 않고 재시도로 흡수된다. */
    private class UnsupportedParameterException(body: String) : Exception(body)

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
