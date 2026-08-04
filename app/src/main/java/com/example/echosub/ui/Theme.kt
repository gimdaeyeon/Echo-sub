package com.example.echosub.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 자막을 오래 들여다보는 앱이라 채도를 낮춘 인디고 계열로 잡았다.
// 배경은 순백/순흑 대신 살짝 눌러 카드와 배경이 구분되게 한다.
private val LightScheme = lightColorScheme(
    primary = Color(0xFF2A5BD7),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE3FF),
    onPrimaryContainer = Color(0xFF00164E),
    secondary = Color(0xFF585E71),
    tertiary = Color(0xFF7A5900),
    background = Color(0xFFF7F8FC),
    onBackground = Color(0xFF1A1B21),
    surface = Color(0xFFF7F8FC),
    onSurface = Color(0xFF1A1B21),
    surfaceVariant = Color(0xFFE2E2EC),
    onSurfaceVariant = Color(0xFF45464F),
    outline = Color(0xFF767680),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFB3C5FF),
    onPrimary = Color(0xFF00297A),
    primaryContainer = Color(0xFF0B3EA8),
    onPrimaryContainer = Color(0xFFDCE3FF),
    secondary = Color(0xFFC0C6DC),
    tertiary = Color(0xFFF2BF48),
    background = Color(0xFF121318),
    onBackground = Color(0xFFE3E2E9),
    surface = Color(0xFF121318),
    onSurface = Color(0xFFE3E2E9),
    surfaceVariant = Color(0xFF44464F),
    onSurfaceVariant = Color(0xFFC5C6D0),
    outline = Color(0xFF8F909A),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

@Composable
fun EchoSubTheme(content: @Composable () -> Unit) {
    val colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme
    MaterialTheme(colorScheme = colorScheme, content = content)
}
