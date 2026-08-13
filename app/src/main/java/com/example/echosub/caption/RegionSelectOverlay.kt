package com.example.echosub.caption

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import com.example.echosub.R
import com.example.echosub.data.AppSettings

private const val TAG = "RegionSelectOverlay"

/** 상자를 이보다 작게 줄일 수 없다 — 자막 한 줄도 안 들어가는 영역은 의미가 없다. */
private const val MIN_WIDTH_DP = 140f
private const val MIN_HEIGHT_DP = 56f

/**
 * 화면 자막을 읽어올 영역을 지정하는 반투명 상자 오버레이.
 *
 * 창 자체가 곧 영역이다 — 상자 내부를 드래그해 옮기고, 오른쪽 아래 손잡이로
 * 크기를 조절하고, 체크 버튼으로 확정한다. 위치/크기가 바뀔 때마다(손을 뗀 순간)
 * [AppSettings]에 비율로 저장하므로, [ScreenCaptionReader]는 조정을 따라 즉시
 * 다른 영역을 읽기 시작한다 — 확정 버튼은 상자를 닫는 것뿐이다.
 *
 * 좌표는 실제 화면 크기(realMetrics) 기준으로 정규화한다 — 캡처(VirtualDisplay)가
 * 상태 바를 포함한 전체 화면을 미러링하므로 같은 기준을 써야 어긋나지 않는다.
 *
 * [com.example.echosub.overlay.SubtitleOverlay]와 같은 호출 규칙: [show]/[hide]는
 * 메인 스레드에서 호출해야 한다.
 */
class RegionSelectOverlay(
    private val context: Context,
    private val onConfirmed: () -> Unit,
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var rootView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    fun show() {
        if (rootView != null) return

        val (screenW, screenH) = realScreenSize()
        val region = AppSettings.values.value.captionRegion.sanitized()

        val view = LayoutInflater.from(context).inflate(R.layout.overlay_region_select, null)

        val params = WindowManager.LayoutParams(
            ((region.right - region.left) * screenW).toInt(),
            ((region.bottom - region.top) * screenH).toInt(),
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (region.left * screenW).toInt()
            y = (region.top * screenH).toInt()
        }

        attachDragToMove(view, params)
        attachDragToResize(view.findViewById(R.id.region_handle_resize), view, params)
        view.findViewById<ImageView>(R.id.region_btn_confirm).setOnClickListener {
            saveRegion()
            hide()
            onConfirmed()
        }

        try {
            windowManager.addView(view, params)
        } catch (e: Exception) {
            Log.e(TAG, "영역 선택 오버레이 추가 실패", e)
            return
        }
        rootView = view
        layoutParams = params
        Log.i(TAG, "영역 선택 표시")
    }

    fun hide() {
        val view = rootView ?: return
        try {
            windowManager.removeView(view)
        } catch (e: Exception) {
            Log.w(TAG, "영역 선택 오버레이 제거 실패", e)
        }
        rootView = null
        layoutParams = null
    }

    val isShowing: Boolean get() = rootView != null

    /** 상자 내부 아무 데나 드래그 = 이동. 손을 떼면 저장한다. */
    private fun attachDragToMove(root: View, params: WindowManager.LayoutParams) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f

        root.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val (screenW, screenH) = realScreenSize()
                    params.x = (startX + (event.rawX - touchX).toInt())
                        .coerceIn(0, (screenW - params.width).coerceAtLeast(0))
                    params.y = (startY + (event.rawY - touchY).toInt())
                        .coerceIn(0, (screenH - params.height).coerceAtLeast(0))
                    updateLayout(root, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    saveRegion()
                    true
                }
                else -> false
            }
        }
    }

    /** 오른쪽 아래 손잡이 드래그 = 크기 조절. 손을 떼면 저장한다. */
    private fun attachDragToResize(handle: View, root: View, params: WindowManager.LayoutParams) {
        var startW = 0
        var startH = 0
        var touchX = 0f
        var touchY = 0f
        val minW = dpToPx(MIN_WIDTH_DP)
        val minH = dpToPx(MIN_HEIGHT_DP)

        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startW = params.width
                    startH = params.height
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val (screenW, screenH) = realScreenSize()
                    params.width = (startW + (event.rawX - touchX).toInt())
                        .coerceIn(minW, screenW - params.x)
                    params.height = (startH + (event.rawY - touchY).toInt())
                        .coerceIn(minH, screenH - params.y)
                    updateLayout(root, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    saveRegion()
                    true
                }
                else -> false
            }
        }
    }

    private fun updateLayout(root: View, params: WindowManager.LayoutParams) {
        try {
            windowManager.updateViewLayout(root, params)
        } catch (e: Exception) {
            Log.w(TAG, "영역 조절 중 창 갱신 실패", e)
        }
    }

    /** 현재 상자의 위치/크기를 화면 비율로 저장한다 — 리더가 다음 프레임부터 이 값을 읽는다. */
    private fun saveRegion() {
        val params = layoutParams ?: return
        val (screenW, screenH) = realScreenSize()
        val w = screenW.toFloat()
        val h = screenH.toFloat()
        AppSettings.setCaptionRegion(
            CaptionRegion(
                left = params.x / w,
                top = params.y / h,
                right = (params.x + params.width) / w,
                bottom = (params.y + params.height) / h,
            ).sanitized(),
        )
    }

    private fun realScreenSize(): Pair<Int, Int> {
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }

    private fun dpToPx(dp: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        dp,
        context.resources.displayMetrics,
    ).toInt()
}
