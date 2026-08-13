package com.example.echosub.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// 자막을 오래 들여다보는 앱이라 채도를 낮춘 인디고 계열로 잡았다.
// 라이트는 순백 대신 살짝 인디고 쪽으로 눌러 카드와 배경이 층으로 구분되게 하고,
// surfaceContainer 단계를 직접 지정해 "카드 = 한 단계 밝은/어두운 면"이 일관되게 한다.
private val LightScheme = lightColorScheme(
    primary = Color(0xFF3B5BDB),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDEE4FF),
    onPrimaryContainer = Color(0xFF001459),
    secondary = Color(0xFF585E71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDDE1F9),
    onSecondaryContainer = Color(0xFF151B2C),
    tertiary = Color(0xFF7A5900),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDEA6),
    onTertiaryContainer = Color(0xFF261900),
    background = Color(0xFFF3F4FB),
    onBackground = Color(0xFF1A1B23),
    surface = Color(0xFFF3F4FB),
    onSurface = Color(0xFF1A1B23),
    surfaceVariant = Color(0xFFE2E2EC),
    onSurfaceVariant = Color(0xFF45464F),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAFAFF),
    surfaceContainer = Color(0xFFEFF0F8),
    surfaceContainerHigh = Color(0xFFE9EAF2),
    surfaceContainerHighest = Color(0xFFE3E4ED),
    outline = Color(0xFF767680),
    outlineVariant = Color(0xFFC6C6D0),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

// 다크는 순검정 기반이다 — 밤에 불을 끄고 보는 사용 패턴이라, 인디고 톤을 깐 어두운
// 회색조차 OLED에서는 화면 전체가 빛나는 판이 된다. 배경/최하층은 #000000으로 내리고
// 카드·바 등 "떠 있는 면"만 아주 낮은 회색 단계로 남겨 층 구분을 유지한다.
// 글자색(onX)은 그대로 둔다 — 바탕이 검어질수록 대비는 오히려 올라간다.
private val DarkScheme = darkColorScheme(
    primary = Color(0xFFB9C3FF),
    onPrimary = Color(0xFF00297A),
    // 최신 자막 카드의 바탕 — 검은 화면에서 원색 인디고는 광원처럼 떠서 한 단계 눌렀다.
    primaryContainer = Color(0xFF1B3384),
    onPrimaryContainer = Color(0xFFDEE4FF),
    secondary = Color(0xFFC0C6DC),
    onSecondary = Color(0xFF2A3042),
    secondaryContainer = Color(0xFF343A4B),
    onSecondaryContainer = Color(0xFFDDE1F9),
    tertiary = Color(0xFFF2BF48),
    onTertiary = Color(0xFF402D00),
    tertiaryContainer = Color(0xFF4A3700),
    onTertiaryContainer = Color(0xFFFFDEA6),
    background = Color(0xFF000000),
    onBackground = Color(0xFFE3E2EB),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFE3E2EB),
    surfaceVariant = Color(0xFF2E3038),
    onSurfaceVariant = Color(0xFFC5C6D0),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF0B0C11),
    surfaceContainer = Color(0xFF101118),
    surfaceContainerHigh = Color(0xFF181A22),
    surfaceContainerHighest = Color(0xFF22242E),
    outline = Color(0xFF8F909A),
    outlineVariant = Color(0xFF3A3C45),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

// 기본 Material 타입 스케일에서 제목 계열만 손봤다 — 이 앱의 본문은 자막이라
// 번역문(titleMedium/Large)이 조금 더 단단하고 촘촘하게 보여야 화면이 잡힌다.
private val EchoSubTypography = Typography().let { base ->
    base.copy(
        headlineSmall = base.headlineSmall.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.2).sp,
        ),
        titleLarge = base.titleLarge.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.2).sp,
            lineHeight = 30.sp,
        ),
        titleMedium = base.titleMedium.copy(
            letterSpacing = 0.sp,
            lineHeight = 26.sp,
        ),
        titleSmall = base.titleSmall.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.1.sp,
        ),
        labelMedium = base.labelMedium.copy(
            fontWeight = FontWeight.Medium,
        ),
    )
}

// 카드류는 모서리를 넉넉히 굴려 기본 Material 룩과 거리를 둔다.
private val EchoSubShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun EchoSubTheme(content: @Composable () -> Unit) {
    val colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = EchoSubTypography,
        shapes = EchoSubShapes,
        content = content,
    )
}
