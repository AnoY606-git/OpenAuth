package dev.openauth.app.ui.graphics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale

private data class ServicePalette(val background: Color, val foreground: Color)

private val ServicePalettes = listOf(
    ServicePalette(Color(0xFFD2F1E5), Color(0xFF125640)),
    ServicePalette(Color(0xFFD9E8FA), Color(0xFF284D7C)),
    ServicePalette(Color(0xFFEADFF7), Color(0xFF654086)),
    ServicePalette(Color(0xFFFFE3D1), Color(0xFF813E1B)),
    ServicePalette(Color(0xFFF6DFEB), Color(0xFF803D5C)),
    ServicePalette(Color(0xFFE9EDCD), Color(0xFF4D5C1D)),
    ServicePalette(Color(0xFFD2EDF0), Color(0xFF1D5C68)),
    ServicePalette(Color(0xFFE3E5F5), Color(0xFF494D85)),
)

/** Локальный аватар; доступное имя задаёт содержащая его строка сервиса. */
@Composable
fun ServiceIcon(issuer: String, modifier: Modifier = Modifier) {
    val normalized = remember(issuer) { issuer.trim().lowercase(Locale.ROOT) }
    val palette = remember(normalized) {
        // Маска сохраняет неотрицательный индекс даже для Int.MIN_VALUE.
        ServicePalettes[(normalized.hashCode() and Int.MAX_VALUE) % ServicePalettes.size]
    }
    val initials = remember(issuer) { serviceInitials(issuer) }
    val darkSurface = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val background = if (darkSurface) {
        palette.background.copy(alpha = 0.14f).compositeOver(MaterialTheme.colorScheme.surfaceContainerHigh)
    } else {
        palette.background
    }
    val foreground = if (darkSurface) palette.background else palette.foreground

    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials,
            modifier = Modifier.clearAndSetSemantics {},
            color = foreground,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

private fun serviceInitials(issuer: String): String {
    val words = issuer.splitToSequence(' ', '\t', '\n', '.', '-', '_', '/', '@')
        .map { word -> word.codePoints().filter { Character.isLetterOrDigit(it) }.toArray() }
        .filter { it.isNotEmpty() }
        .take(2)
        .toList()
    if (words.isEmpty()) return "•"
    val points = if (words.size > 1) {
        intArrayOf(words[0][0], words[1][0])
    } else {
        words[0].take(2).toIntArray()
    }
    return buildString {
        points.forEach { appendCodePoint(Character.toUpperCase(it)) }
    }
}
