package com.example.echosub.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import com.example.echosub.R
import com.example.echosub.data.AppSettings

private const val TAG = "SubtitleOverlay"

/** 번역 히스토리 영역이 늘어날 수 있는 최대 높이 — 이보다 길어지면 스크롤로 넘겨본다. */
private const val HISTORY_MAX_HEIGHT_DP = 200f

/**
 * 히스토리 최대 높이의 화면 비례 상한. [HISTORY_MAX_HEIGHT_DP]는 세로 화면 기준이라
 * 가로로 눕힌 화면(높이가 절반 이하)에서는 그것만으로 화면 대부분을 덮는다 —
 * 어느 쪽이든 화면 높이의 이 비율을 넘지 않게 한 번 더 자른다.
 * (비례 계산은 MaxHeightScrollView가 측정 시점마다 하므로 회전에도 맞는 값이 된다.)
 */
private const val HISTORY_MAX_SCREEN_FRACTION = 0.30f

/** 상단 원문 영역의 최대 높이 — 넘치면 스크롤이 되고, 항상 꼬리(지금 들리는 말)를 따라간다. */
private const val SOURCE_MAX_HEIGHT_DP = 110f

/**
 * 원문 영역 최대 높이의 화면 비례 상한. 글자 수 상한([MAX_SOURCE_CHARS]/[MAX_INTERIM_CHARS])만으로는
 * 좁은 박스에서 줄 수가 얼마든지 늘어난다 — 가로 화면에서 원문이 쌓이며 박스를 밀어
 * 아래 번역 영역이 화면 밖으로 잘리는 것을 높이로 직접 막는다.
 */
private const val SOURCE_MAX_SCREEN_FRACTION = 0.22f

/*
 * 높이 예산 검산 (가로 화면 높이 ~360dp 기준):
 * 손잡이 ~34 + 원문 min(110, 22%≈79) + 구분선 ~17 + 히스토리 min(200, 30%≈108) + 패딩 20
 * ≈ 258dp — 하단 여백(8%)을 더해도 화면 높이를 넘지 않는다.
 */

/**
 * 인식 중(interim) 텍스트로 상단에 보여줄 최대 글자 수. 서버가 주는 interim은 발화가
 * 이어지는 동안 상한 없이 길어진다 — 그대로 두면 원문 영역이 화면을 밀고 내려가
 * 번역 영역이 잘린다. 지금 들리는 말은 꼬리 쪽이므로 앞을 버리고 꼬리만 남긴다.
 */
private const val MAX_INTERIM_CHARS = 110

/** 스크롤이 바닥에서 이 거리(dp) 안에 있으면 "바닥에 붙어 있다"고 본다. */
private const val STICK_TO_BOTTOM_SLOP_DP = 24f

/** 상단 원문 영역에 함께 남겨둘 최근 확정 문장 수 (인식 중 텍스트는 별도로 추가). */
private const val SOURCE_HISTORY = 2

/**
 * 상단 원문 영역의 총 글자 상한. 넘치면 오래된 줄부터 버린다 —
 * 여러 줄 TextView에서는 `ellipsize="start"`가 동작하지 않아 코드에서 자른다.
 */
private const val MAX_SOURCE_CHARS = 160

/** 번역 글자 크기 대비 원문 글자 크기 비율 — 주인공은 번역이다. */
private const val SOURCE_TEXT_SCALE = 0.72f

/** 인식 중(interim) 텍스트 색 — 아직 확정이 아니라는 것을 옅기로 말한다. */
private const val INTERIM_COLOR = 0x99FFFFFF.toInt()

/** 번역이 실패한 문장을 표시할 때 쓰는 옅은 빨강 — 원문은 보이되 뭔가 걸렸다는 티만 낸다. */
private const val ERROR_COLOR = 0xB3FFCDD2.toInt()

/** 가장 최근 번역의 강조색 — 히스토리 속에서 "지금 이 말"을 훑지 않고 찾게 한다. */
private const val LATEST_TRANSLATION_COLOR = 0xFF8AB4F8.toInt()

/**
 * 다른 앱 위에 뜨는 번역 자막 창.
 *
 * 2단 구성이다 — **위에 원문(확정 + 인식 중), 아래에 번역 히스토리.** 원문은 말하는
 * 속도로, 번역은 그보다 1~2초 늦게 도착하므로 두 흐름을 한 칸에 섞으면 번역이 올 때마다
 * 원문 읽던 자리가 밀린다. 칸을 나누면 각자 자기 속도로 흐르고, 번역이 오기 전에도
 * 상단에서 글자가 실시간으로 움직이므로 "느리다"는 인상이 사라진다.
 * 원문이 필요 없는 사용자를 위해 상단 영역은 손잡이 줄의 토글로 숨길 수 있다
 * ("번역만 보기") — 상태는 [AppSettings.setOverlayShowSource]로 저장돼 다음에도 유지된다.
 *
 * Compose 대신 일반 View를 쓴다 — ComposeView를 WindowManager에 직접 붙이려면
 * ViewTreeLifecycleOwner/SavedStateRegistryOwner/ViewModelStoreOwner를 손수 심어줘야 하는데,
 * 이 오버레이는 TextView 두 개와 아이콘 두 개가 전부라 그 배선을 감당할 이유가 없다.
 *
 * 호출 규칙: [show]/[setContent]/[setTextSize]/[setBoxWidth]/[setPaused]/[hide] 모두
 * 메인 스레드에서 호출해야 한다 (WindowManager 요구사항).
 * 호출자([com.example.echosub.service.AudioCaptureService])가 Dispatchers.Main에서 부른다.
 *
 * [onOpenApp]/[onTogglePause]는 다른 앱을 보는 동안 EchoSub를 따로 찾아 열지 않고
 * 오버레이에서 바로 앱으로 돌아가거나 인식을 멈출 수 있게 하기 위한 것이다.
 * [onAdjustRegion]은 화면 자막 읽기 모드에서만 넘어온다(null이면 버튼 숨김) —
 * 실시간 자막 창을 옮겼을 때 읽기 영역을 다시 맞추는 진입점이다.
 */
class SubtitleOverlay(
    private val context: Context,
    private val onOpenApp: () -> Unit,
    private val onTogglePause: () -> Unit,
    private val onAdjustRegion: (() -> Unit)? = null,
) {

    /** 히스토리 한 줄. 원문은 상단 영역에, 번역(또는 실패 표시)은 하단 영역에 쓰인다. */
    data class Entry(
        val sourceText: String,
        val translatedText: String?,
        val translationError: String? = null,
    )

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var rootView: View? = null
    private var sourceView: TextView? = null
    private var sourceScrollView: MaxHeightScrollView? = null
    private var textView: TextView? = null
    private var dividerView: View? = null
    private var scrollView: MaxHeightScrollView? = null
    private var pauseButton: ImageView? = null
    private var sourceToggleButton: ImageView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isPaused = false

    /** 상단 원문 영역을 보일지 — 손잡이 줄의 토글 버튼이 바꾸고 [AppSettings]에 저장된다. */
    private var showSource = true

    /** 토글로 다시 그릴 수 있도록 마지막 내용을 들고 있는다. */
    private var lastEntries: List<Entry> = emptyList()
    private var lastInterim = ""

    /**
     * 사용자가 위로 스크롤해서 지난 문장을 보고 있는 동안에는 새 문장이 와도 끌어내리지
     * 않는다. 바닥 근처로 돌아오면(±[STICK_TO_BOTTOM_SLOP_DP]) 자동으로 다시 붙는다 —
     * 별도 "따라가기" 버튼 없이 스크롤 위치 자체가 그 의사 표시가 되게 했다.
     */
    private var stickToBottom = true

    fun show() {
        if (rootView != null) return

        val view = LayoutInflater.from(context)
            .inflate(R.layout.overlay_subtitle, null) as PinchToResizeLayout
        val handle = view.findViewById<View>(R.id.overlay_handle)
        val source = view.findViewById<TextView>(R.id.overlay_source)
        val sourceScroll = view.findViewById<MaxHeightScrollView>(R.id.overlay_source_scroll)
        val divider = view.findViewById<View>(R.id.overlay_divider)
        val text = view.findViewById<TextView>(R.id.overlay_text)
        val scroll = view.findViewById<MaxHeightScrollView>(R.id.overlay_scroll)

        // 두 영역 모두 고정 상한 + 화면 비례 상한을 함께 건다. 비례 쪽은 측정 시점마다
        // 다시 계산되므로(MaxHeightScrollView), 오버레이가 떠 있는 채로 화면을 돌려도
        // 그 순간의 화면 높이에 맞는 상한이 적용된다.
        scroll.maxHeightPx = dpToPx(HISTORY_MAX_HEIGHT_DP)
        scroll.maxScreenHeightFraction = HISTORY_MAX_SCREEN_FRACTION
        sourceScroll.maxHeightPx = dpToPx(SOURCE_MAX_HEIGHT_DP)
        sourceScroll.maxScreenHeightFraction = SOURCE_MAX_SCREEN_FRACTION

        val screenHeightPx = context.resources.displayMetrics.heightPixels
        val slopPx = dpToPx(STICK_TO_BOTTOM_SLOP_DP)
        scroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            // 리스너 파라미터는 View 타입이라 getChildAt이 없다 — ScrollView인 scroll을 직접 쓴다.
            val content = scroll.getChildAt(0)
            val maxScroll = ((content?.height ?: 0) - scroll.height).coerceAtLeast(0)
            stickToBottom = maxScroll - scrollY <= slopPx
        }

        val openAppButton = view.findViewById<ImageView>(R.id.overlay_btn_open_app)
        openAppButton.setOnClickListener { onOpenApp() }

        showSource = AppSettings.values.value.overlayShowSource
        val sourceToggle = view.findViewById<ImageView>(R.id.overlay_btn_source)
        sourceToggle.setOnClickListener { toggleSourceVisible() }
        sourceToggleButton = sourceToggle
        applySourceToggleAppearance(sourceToggle)

        val regionButton = view.findViewById<ImageView>(R.id.overlay_btn_region)
        val adjustRegion = onAdjustRegion
        if (adjustRegion != null) {
            regionButton.visibility = View.VISIBLE
            regionButton.setOnClickListener { adjustRegion() }
        }

        val pauseBtn = view.findViewById<ImageView>(R.id.overlay_btn_toggle_pause)
        pauseBtn.setOnClickListener { onTogglePause() }
        pauseButton = pauseBtn
        applyPausedAppearance(pauseBtn, isPaused)

        val params = WindowManager.LayoutParams(
            widthPxFor(OverlaySettings.boxWidthDp.value),
            WindowManager.LayoutParams.WRAP_CONTENT,
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            },
            // NOT_FOCUSABLE 없으면 오버레이가 포커스를 가로채 아래 앱의 뒤로가기/키보드가 죽는다.
            // TOUCHABLE은 유지 — 손잡이로 옮길 수 있어야 한다.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // 아래 모서리를 기준으로 앵커한다 (y = 화면 아래에서 창 아래까지의 거리).
            // TOP 기준이었을 때는 내용이 길어지면 창이 아래로 자라 화면 밖으로 밀려나
            // 정작 가장 최근 번역(맨 아래)이 잘렸다 — BOTTOM 기준이면 위로 자란다.
            gravity = Gravity.BOTTOM or Gravity.START
            x = 0
            y = (screenHeightPx * 0.08f).toInt()
        }

        // 드래그는 손잡이 줄에서만 받는다 — 본문은 자체 스크롤을 가지므로 박스 전체를
        // 드래그 대상으로 두면 문장을 넘겨보려는 손짓과 창을 옮기려는 손짓이 같은
        // 제스처(세로 드래그)라 구분할 수 없다. 핀치(두 손가락)는 손가락 수로 구분되므로
        // 루트(PinchToResizeLayout)가 박스 어디서든 받는다.
        attachTouchHandlers(root = view, handle = handle, params = params)

        try {
            windowManager.addView(view, params)
        } catch (e: Exception) {
            // 권한 미허용 상태에서 호출된 경우 등 — 여기서 죽으면 서비스 전체가 죽으므로 반드시 잡는다.
            Log.e(TAG, "오버레이 창 추가 실패", e)
            return
        }

        rootView = view
        sourceView = source
        sourceScrollView = sourceScroll
        textView = text
        dividerView = divider
        scrollView = scroll
        layoutParams = params
        render() // 아직 내용이 없으면 대기 문구가 (토글 상태에 맞는 자리에) 뜬다.
        Log.i(TAG, "오버레이 표시 시작")
    }

    /**
     * 화면을 통째로 갱신한다 — 상단 원문(최근 확정 [SOURCE_HISTORY]개 + 인식 중 텍스트),
     * 하단 번역 히스토리(가장 최근 번역만 강조색).
     *
     * 인식 중(interim) 텍스트를 상단에 실시간으로 흘리는 것이 핵심이다. 말이 끝나
     * 확정되고 번역이 돌아오기까지 1~2초가 비는데, 그동안에도 글자가 움직이고 있으면
     * 기다림이 "느림"으로 읽히지 않는다.
     */
    fun setContent(entries: List<Entry>, interimText: String) {
        lastEntries = entries
        lastInterim = interimText
        render()
    }

    /** "번역만 보기" ↔ "원본도 같이 보기" 토글 — 마지막 내용으로 즉시 다시 그린다. */
    private fun toggleSourceVisible() {
        showSource = !showSource
        AppSettings.setOverlayShowSource(showSource)
        sourceToggleButton?.let { applySourceToggleAppearance(it) }
        render()
    }

    private fun applySourceToggleAppearance(button: ImageView) {
        // 꺼진 상태를 알파로 말한다 — 아이콘을 바꾸는 것보다 "같은 기능의 on/off"로 읽힌다.
        button.imageAlpha = if (showSource) 255 else 110
        button.contentDescription = context.getString(
            if (showSource) R.string.overlay_source_toggle_hide else R.string.overlay_source_toggle_show,
        )
    }

    /** [lastEntries]/[lastInterim]과 [showSource] 상태로 두 영역을 그린다. */
    private fun render() {
        val source = sourceView ?: return
        val text = textView ?: return
        val entries = lastEntries

        // ---- 상단: 원문 (토글로 통째로 숨길 수 있다) ----
        if (showSource) {
            var sources = entries.takeLast(SOURCE_HISTORY).map { it.sourceText }
            // 여러 줄 TextView는 ellipsize="start"가 동작하지 않는다 — 오래된 줄부터 코드로 버린다.
            while (sources.size > 1 && sources.sumOf { it.length } > MAX_SOURCE_CHARS) {
                sources = sources.drop(1)
            }
            val top = SpannableStringBuilder()
            sources.forEach { line ->
                if (top.isNotEmpty()) top.append("\n")
                top.append(line)
            }
            if (lastInterim.isNotBlank()) {
                // interim은 발화가 이어지는 동안 상한 없이 길어진다 — 꼬리만 남겨 원문
                // 영역이 박스를 화면 밖까지 밀어내지 않게 한다.
                val interim = if (lastInterim.length > MAX_INTERIM_CHARS) {
                    "…" + lastInterim.takeLast(MAX_INTERIM_CHARS)
                } else {
                    lastInterim
                }
                if (top.isNotEmpty()) top.append("\n")
                top.appendSpanned(
                    interim,
                    ForegroundColorSpan(INTERIM_COLOR),
                    StyleSpan(Typeface.ITALIC),
                )
            }
            source.text = top.ifEmpty { context.getString(R.string.overlay_waiting) }
            sourceScrollView?.visibility = View.VISIBLE
            // 원문 영역은 항상 꼬리(지금 들리는 말)를 따라간다 — 높이 상한에 걸려 스크롤이
            // 생기는 순간에도 새 글자가 보여야 한다. 히스토리와 달리 위로 올려 읽는 용도가
            // 아니므로 조건 없이 바닥에 붙인다.
            sourceScrollView?.let { it.post { it.fullScroll(View.FOCUS_DOWN) } }
        } else {
            sourceScrollView?.visibility = View.GONE
        }

        // ---- 하단: 번역 히스토리 ----
        val bottom = SpannableStringBuilder()
        // 번역이 오갔거나(성공) 실패한 것만 줄이 된다 — 아직 번역 중인 문장은 상단
        // 원문 영역에 이미 보이고 있으므로 여기 자리를 만들지 않는다.
        val lines = entries.filter { it.translatedText != null || it.translationError != null }
        val lastTranslated = lines.indexOfLast { it.translatedText != null }
        lines.forEachIndexed { index, entry ->
            if (bottom.isNotEmpty()) bottom.append("\n")
            when {
                entry.translatedText == null -> bottom.appendSpanned(
                    entry.sourceText,
                    ForegroundColorSpan(ERROR_COLOR),
                    StyleSpan(Typeface.ITALIC),
                )
                index == lastTranslated -> bottom.appendSpanned(
                    entry.translatedText,
                    ForegroundColorSpan(LATEST_TRANSLATION_COLOR),
                )
                else -> bottom.append(entry.translatedText)
            }
        }

        val hasLines = bottom.isNotEmpty()
        dividerView?.visibility = if (showSource && hasLines) View.VISIBLE else View.GONE
        when {
            hasLines -> {
                scrollView?.visibility = View.VISIBLE
                text.text = bottom
                autoScrollIfStuck()
            }
            !showSource -> {
                // 원본을 숨긴 상태에서 번역까지 없으면 아이콘 줄만 남는다 —
                // 대기 문구를 번역 칸에 대신 띄운다.
                scrollView?.visibility = View.VISIBLE
                text.text = context.getString(R.string.overlay_waiting)
            }
            else -> scrollView?.visibility = View.GONE
        }
    }

    private fun SpannableStringBuilder.appendSpanned(text: CharSequence, vararg spans: Any) {
        val start = length
        append(text)
        spans.forEach { setSpan(it, start, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE) }
    }

    /** 사용자가 위로 스크롤해 지난 내용을 보는 중이 아니면 새 내용이 보이도록 바닥까지 내린다. */
    private fun autoScrollIfStuck() {
        val scroll = scrollView ?: return
        if (stickToBottom) {
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    /** 사용자 설정은 번역 크기 기준이다 — 원문은 그에 비례해 한 단계 작게 따라간다. */
    fun setTextSize(sp: Float) {
        textView?.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        sourceView?.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * SOURCE_TEXT_SCALE)

        // 두 영역의 높이 상한도 글자 크기에 비례시킨다 — 핀치로 키울 때 상하로도
        // 함께 자라야 "비율을 유지한 확대"로 느껴진다. 화면 비례 상한(측정 시점 계산)은
        // 그대로 남아 있어 아무리 키워도 화면을 넘지는 않는다.
        val scale = sp / OverlaySettings.DEFAULT_TEXT_SIZE_SP
        scrollView?.let {
            it.maxHeightPx = dpToPx(HISTORY_MAX_HEIGHT_DP * scale)
            it.requestLayout()
        }
        sourceScrollView?.let {
            it.maxHeightPx = dpToPx(SOURCE_MAX_HEIGHT_DP * scale)
            it.requestLayout()
        }
    }

    /** 박스 폭만 바꾼다 — 글자 크기는 [setTextSize]가 따로 담당한다. */
    fun setBoxWidth(dp: Float) {
        val view = rootView ?: return
        val params = layoutParams ?: return
        val widthPx = widthPxFor(dp)
        if (params.width == widthPx) return
        params.width = widthPx
        try {
            windowManager.updateViewLayout(view, params)
        } catch (e: Exception) {
            Log.w(TAG, "박스 폭 적용 실패", e)
        }
    }

    /**
     * 앱 안에 들어와 있는 동안에는 오버레이를 숨긴다 — 이미 화면에 자막이 보이는데
     * 그 위에 또 띄울 이유가 없다. 드래그로 잡아둔 위치/폭을 잃지 않도록 창을
     * 제거(removeView)하는 대신 안 보이게+터치 통과로만 바꾼다.
     */
    fun setVisible(visible: Boolean) {
        val view = rootView ?: return
        val params = layoutParams ?: return
        view.visibility = if (visible) View.VISIBLE else View.GONE
        val newFlags = if (visible) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        if (params.flags == newFlags) return
        params.flags = newFlags
        try {
            windowManager.updateViewLayout(view, params)
        } catch (e: Exception) {
            Log.w(TAG, "표시 상태 갱신 실패", e)
        }
    }

    /** 일시정지 버튼의 아이콘/설명을 갱신한다. [show] 이전에 호출돼도 다음 [show]에 반영된다. */
    fun setPaused(paused: Boolean) {
        isPaused = paused
        pauseButton?.let { applyPausedAppearance(it, paused) }
    }

    private fun applyPausedAppearance(button: ImageView, paused: Boolean) {
        button.setImageResource(
            if (paused) R.drawable.ic_overlay_play else R.drawable.ic_overlay_pause,
        )
        button.contentDescription = context.getString(
            if (paused) R.string.overlay_resume_description else R.string.overlay_pause_description,
        )
    }

    private fun dpToPx(dp: Float): Int {
        val metrics = context.resources.displayMetrics
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, metrics).toInt()
    }

    /** dp를 픽셀로 바꾸되 화면 밖으로 나가지 않게 자른다 (OverlaySettings의 상한은 기기와 무관한 값이라). */
    private fun widthPxFor(dp: Float): Int {
        val metrics = context.resources.displayMetrics
        return dpToPx(dp).coerceAtMost(metrics.widthPixels)
    }

    fun hide() {
        val view = rootView ?: return
        try {
            windowManager.removeView(view)
        } catch (e: Exception) {
            Log.w(TAG, "오버레이 창 제거 실패 (이미 제거됐을 수 있음)", e)
        }
        rootView = null
        sourceView = null
        sourceScrollView = null
        textView = null
        dividerView = null
        scrollView = null
        pauseButton = null
        sourceToggleButton = null
        layoutParams = null
        Log.i(TAG, "오버레이 숨김")
    }

    /**
     * 손잡이([handle])에서 한 손가락 드래그로 이동, 박스 **어디서든** 두 손가락 핀치로
     * 크기 조절.
     *
     * 핀치를 손잡이 줄에서만 받던 시절에는 28dp 띠에 두 손가락을 올려야 해서 사실상
     * 확대/축소가 안 됐다 — 지금은 [PinchToResizeLayout]인 루트가 포인터가 둘이 되는
     * 순간 제스처를 가로채므로, 스크롤/버튼과 충돌 없이 박스 전체가 핀치 대상이 된다.
     *
     * 핀치는 PPT에서 모서리를 잡아 늘리듯 **비율을 유지한 채** 조절한다 — 폭과 글자
     * 크기를 같은 배율로 함께 바꾸고, 글자 크기가 커지면 [setTextSize]가 높이 상한도
     * 비례해 키우므로 상하로도 같이 자란다. (폭만 바꾸던 시절에는 "상하 조절이 안 된다"로
     * 느껴졌다.) 실제 반영은 두 값을 구독하는 서비스 쪽 코루틴이 처리하므로
     * 여기서는 상태만 바꾸면 된다.
     */
    private fun attachTouchHandlers(
        root: PinchToResizeLayout,
        handle: View,
        params: WindowManager.LayoutParams,
    ) {
        root.scaleDetector = ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    OverlaySettings.setBoxWidth(
                        OverlaySettings.boxWidthDp.value * detector.scaleFactor,
                    )
                    OverlaySettings.setTextSize(
                        OverlaySettings.textSizeSp.value * detector.scaleFactor,
                    )
                    return true
                }
            },
        )

        var startX = 0
        var startY = 0
        var startTouchX = 0f
        var startTouchY = 0f

        // 손잡이는 한 손가락 드래그 전담이다 — 두 번째 손가락이 닿으면 루트가 제스처를
        // 가로채고 여기는 CANCEL을 받으므로, 핀치와 드래그가 섞여 창이 튈 일이 없다.
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    startTouchX = event.rawX
                    startTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - startTouchX).toInt()
                    // BOTTOM 앵커라 y는 "화면 아래에서의 거리"다 — 손가락이 내려가면 줄어든다.
                    params.y = startY - (event.rawY - startTouchY).toInt()
                    try {
                        windowManager.updateViewLayout(root, params)
                    } catch (e: Exception) {
                        Log.w(TAG, "드래그 중 창 갱신 실패", e)
                    }
                    true
                }
                else -> false
            }
        }
    }
}
