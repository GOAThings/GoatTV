package cc.slokker.itv.ui.common

import cc.slokker.itv.ui.theme.ItvShapes
import cc.slokker.itv.ui.theme.LocalAccent
import cc.slokker.itv.ui.i18n.LocalStrings
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import cc.slokker.itv.ui.theme.ItvColors

/**
 * Entrada de PIN de 4 dígitos: números do comando, ou ↑/↓ para mudar o dígito e →/OK para avançar.
 * [onDone] devolve null para limpar e pedir de novo (ex.: PIN errado).
 */
@Composable
fun PinPad(title: String, message: String?, onDone: (String) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val digits = remember { mutableStateListOf(0, 0, 0, 0) }
    var index by remember { mutableIntStateOf(0) }
    var error by remember(message) { mutableStateOf(message) }
    val focus = remember { FocusRequester() }

    fun reset() {
        for (i in digits.indices) digits[i] = 0
        index = 0
    }

    fun advance() {
        if (index < digits.lastIndex) {
            index++
        } else {
            val pin = digits.joinToString("")
            reset()
            onDone(pin)
        }
    }

    LaunchedEffect(Unit) {
        repeat(6) {
            withFrameNanos { }
            if (runCatching { focus.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }

    Column(
        modifier
            .focusRequester(focus)
            .focusable(enabled)
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                val code = e.key.nativeKeyCode
                when (code) {
                    in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9 -> {
                        digits[index] = code - AndroidKeyEvent.KEYCODE_0
                        error = null
                        advance()
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_UP -> { digits[index] = (digits[index] + 1) % 10; true }
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> { digits[index] = (digits[index] + 9) % 10; true }
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT, AndroidKeyEvent.KEYCODE_DPAD_CENTER,
                    AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        error = null
                        advance()
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (index > 0) index--
                        true
                    }
                    else -> false
                }
            }
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            digits.forEachIndexed { i, d ->
                val active = i == index
                Box(
                    Modifier
                        .size(width = 52.dp, height = 64.dp)
                        .clip(ItvShapes.Control)
                        .background(if (active) Color.White else Color.White.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        // Os dígitos já passados ficam escondidos.
                        if (i < index) "•" else d.toString(),
                        style = MaterialTheme.typography.headlineMedium,
                        color = if (active) Color.Black else ItvColors.Text,
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            error ?: LocalStrings.current.pinHint,
            style = MaterialTheme.typography.bodySmall,
            color = if (error != null) ItvColors.Error else ItvColors.TextFaint,
            textAlign = TextAlign.Center,
        )
    }
}
