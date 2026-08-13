import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// DeepL 키는 소스에 넣지 않고 local.properties(=.gitignore 대상)에서 읽어 BuildConfig로 노출한다.
val deepLApiKey: String = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}.getProperty("deepl.api.key", "")

android {
    namespace = "com.example.echosub"
    compileSdk = 34
    // AGP 7.4.2 predates official API 34 support and would otherwise try to resolve
    // its own default build-tools version (not installed locally). Pin explicitly to
    // the build-tools revision that IS installed on this machine.
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "com.example.echosub"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1-phase1"

        vectorDrawables {
            useSupportLibrary = true
        }

        buildConfigField("String", "DEEPL_API_KEY", "\"$deepLApiKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.10"
    }

    packagingOptions {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // gRPC/Google API 의존성들이 각자 동일 경로의 메타데이터 파일을 들고 있어 충돌한다.
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/notice.txt",
                "META-INF/*.kotlin_module",
                "META-INF/io.netty.versions.properties",
            )
        }
    }
}

// 이 애노테이션 전용 jar들은 최신 클래스 파일 버전으로 빌드되어 AGP 7.4.2 번들 D8이
// 파싱하지 못한다(Error while dexing / NullPointerException). @Retention(CLASS) 애노테이션이라
// 런타임에는 필요 없으므로 제외해도 동작에 영향이 없다.
configurations.all {
    exclude(group = "com.google.errorprone", module = "error_prone_annotations")
    exclude(group = "com.google.j2objc", module = "j2objc-annotations")
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    val composeBom = platform("androidx.compose:compose-bom:2024.02.02")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    val grpcVersion = "1.73.0"
    implementation("io.grpc:grpc-android:$grpcVersion")
    implementation("io.grpc:grpc-okhttp:$grpcVersion")
    implementation("io.grpc:grpc-stub:$grpcVersion")
    implementation("io.grpc:grpc-auth:$grpcVersion")

    implementation("com.google.api.grpc:grpc-google-cloud-speech-v1:4.88.0")
    implementation("com.google.auth:google-auth-library-oauth2-http:1.48.0") {
        exclude(group = "org.apache.httpcomponents")
    }
    implementation("javax.annotation:javax.annotation-api:1.3.2")

    // 온디바이스 번역 (모델은 최초 사용 시 런타임 다운로드, 언어당 약 30MB)
    implementation("com.google.mlkit:translate:17.0.3")

    // 화면 자막 읽기(OCR) — 갤럭시 '실시간 자막' 창을 캡처해 텍스트로 되읽는다.
    // 모델이 APK에 번들된다(문자계별 약 4MB). 키릴·그리스 문자는 ML Kit이 지원하지 않아
    // 해당 언어에서는 화면 자막 모드를 막는다 (CaptionOcr 참고).
    implementation("com.google.mlkit:text-recognition:16.0.0")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.0")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // JVM 단위 테스트 — 순수 로직(리샘플러, SRT 직렬화, WAV 헤더, 문장 병합)만 대상이라
    // 기기/에뮬레이터 없이 gradlew testDebugUnitTest로 돈다.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}
