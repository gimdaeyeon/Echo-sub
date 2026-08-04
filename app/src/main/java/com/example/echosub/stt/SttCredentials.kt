package com.example.echosub.stt

import android.content.Context
import com.example.echosub.R
import com.google.auth.oauth2.GoogleCredentials

private const val CLOUD_PLATFORM_SCOPE = "https://www.googleapis.com/auth/cloud-platform"

/**
 * res/raw/service_account.json에 포함된 서비스 계정 키로 [GoogleCredentials]를 만든다.
 *
 * 개인용 프로젝트라 키를 APK에 직접 넣는 방식을 택했다. 앱을 배포할 일이 생기면
 * 이 키는 추출 가능하므로 프록시 서버 경유로 바꿔야 한다.
 */
fun loadSttCredentials(context: Context): GoogleCredentials =
    context.resources.openRawResource(R.raw.service_account).use { stream ->
        GoogleCredentials.fromStream(stream).createScoped(listOf(CLOUD_PLATFORM_SCOPE))
    }
