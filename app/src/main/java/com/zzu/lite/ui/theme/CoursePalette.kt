package com.zzu.lite.ui.theme

import androidx.compose.ui.graphics.Color

/** 课程色板：按课程名哈希取色，保证同一课程颜色稳定 */
private val PALETTE = listOf(
    Color(0xFF1B5E43),
    Color(0xFF1565C0),
    Color(0xFF6A1B9A),
    Color(0xFFC62828),
    Color(0xFFEF6C00),
    Color(0xFF00838F),
    Color(0xFFAD1457),
    Color(0xFF5D4037),
    Color(0xFF4527A0),
    Color(0xFF2E7D32)
)

fun courseColor(courseName: String): Color {
    if (courseName.isBlank()) return PALETTE[0]
    val idx = (courseName.fold(0) { acc, c -> acc * 31 + c.code } and 0x7FFFFFFF) % PALETTE.size
    return PALETTE[idx]
}
