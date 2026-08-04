package com.example.echosub

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.echosub.data.AppSettings
import com.example.echosub.device.AppForegroundState
import com.example.echosub.device.DeviceState
import com.example.echosub.service.AudioCaptureService
import com.example.echosub.service.CaptureState
import com.example.echosub.stt.TranscriptState
import com.example.echosub.ui.EchoSubApp
import com.example.echosub.ui.EchoSubTheme

/**
 * 권한 요청 → MediaProjection 동의 → 서비스 기동까지의 진입점.
 *
 * 캡처 자체의 상태/로직은 [AudioCaptureService]가 전담하고, 이 액티비티는
 * [CaptureState]를 구독해 화면에 반영하기만 한다 (화면 회전/백그라운드 전환에도
 * 캡처가 끊기지 않도록). 사용자 설정은 [AppSettings]가 디스크에 들고 있으므로
 * 액티비티가 죽었다 살아나도 그대로다.
 */
class MainActivity : ComponentActivity() {

    private var pendingStart: AppSettings.Values? = null

    // OS 설정에서 직접 바꾸고 돌아올 수도 있으므로 onResume마다 DeviceState를 다시 채운다.
    private val batteryOptimizationLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            DeviceState.refreshBatteryOptimizationExempt(this)
        }

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results.values.all { it }) {
                proceedAfterRuntimePermissions()
            } else {
                Toast.makeText(this, "마이크/알림 권한이 있어야 캡처를 시작할 수 있습니다", Toast.LENGTH_LONG).show()
                pendingStart = null
            }
        }

    // ACTION_MANAGE_OVERLAY_PERMISSION은 resultCode를 주지 않으므로 돌아온 뒤 직접 다시 확인해야 한다.
    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Settings.canDrawOverlays(this)) {
                launchProjectionConsent()
            } else {
                Toast.makeText(this, "오버레이 권한이 없으면 자막을 다른 앱 위에 띄울 수 없습니다", Toast.LENGTH_LONG).show()
                pendingStart = null
            }
        }

    private val projectionConsentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val start = pendingStart
            pendingStart = null
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null && start != null) {
                val serviceIntent = AudioCaptureService.buildStartIntent(
                    context = this,
                    resultCode = result.resultCode,
                    data = data,
                    format = start.captureFormat,
                    save16kCompanion = start.save16kCompanion,
                    sttEnabled = start.sttEnabled,
                    languagePair = start.languagePair,
                    translationEngine = start.translationEngine,
                    overlayEnabled = start.overlayEnabled,
                )
                ContextCompat.startForegroundService(this, serviceIntent)
            } else {
                Toast.makeText(this, "화면/오디오 캡처 동의가 거부되었습니다", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppSettings.init(this)
        DeviceState.refreshBatteryOptimizationExempt(this)

        setContent {
            EchoSubTheme {
                EchoSubApp(
                    onStartClick = { requestPermissionsThenStart() },
                    onStopClick = { stopCapture() },
                    onToggleSttPause = { toggleSttPause() },
                    onRequestBatteryExemption = { requestBatteryExemption() },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        DeviceState.refreshBatteryOptimizationExempt(this)
    }

    // 앱 안에서 이미 자막을 보고 있는 동안에는 오버레이가 그 위에 겹쳐 뜰 이유가 없다.
    // 서비스가 이 값을 구독해 오버레이를 숨겼다/보였다 한다.
    override fun onStart() {
        super.onStart()
        AppForegroundState.setForeground(true)
    }

    override fun onStop() {
        super.onStop()
        AppForegroundState.setForeground(false)
    }

    /**
     * 화면을 끄거나 다른 앱을 오래 쓰는 동안 OS가 캡처 Foreground Service를 죽이지 않도록
     * 배터리 최적화 대상에서 빼달라고 직접 요청한다. 마이크/오버레이 권한과 달리 캡처 기능
     * 자체에 필수는 아니라서(그냥 안 하면 가끔 끊길 수 있다는 정도) 시작 흐름을 막지 않고
     * 설정 화면에서 별도로 요청한다.
     */
    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName"),
        )
        batteryOptimizationLauncher.launch(intent)
    }

    private fun toggleSttPause() {
        val paused = TranscriptState.status.value.connectionState == TranscriptState.ConnectionState.PAUSED
        val intent = if (paused) {
            AudioCaptureService.buildResumeSttIntent(this)
        } else {
            AudioCaptureService.buildPauseSttIntent(this)
        }
        startService(intent)
    }

    private fun requestPermissionsThenStart() {
        pendingStart = AppSettings.values.value

        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            proceedAfterRuntimePermissions()
        } else {
            requestPermissionsLauncher.launch(missing.toTypedArray())
        }
    }

    /** 마이크/알림 런타임 권한 확보 후 다음 단계 — 오버레이가 필요하면 그 권한부터, 아니면 바로 MediaProjection 동의로. */
    private fun proceedAfterRuntimePermissions() {
        val start = pendingStart ?: return
        if (start.needsOverlayPermission && !Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            )
            overlayPermissionLauncher.launch(intent)
        } else {
            launchProjectionConsent()
        }
    }

    private fun requiredPermissions(): List<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun launchProjectionConsent() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionConsentLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun stopCapture() {
        // 서비스가 이미 foreground 상태로 떠 있으므로 startForegroundService 재호출 없이
        // 일반 startService로 ACTION_STOP을 전달하면 된다.
        startService(AudioCaptureService.buildStopIntent(this))
    }
}
