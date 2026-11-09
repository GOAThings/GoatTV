package cc.slokker.itv.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.layout
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import cc.slokker.itv.ui.glass.ItvIcons
import cc.slokker.itv.ui.i18n.LocalStrings
import cc.slokker.itv.ui.theme.ItvColors
import cc.slokker.itv.ui.theme.LocalAccent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke


/** O canal está a abrir. */
@Composable
internal fun Spinner(modifier: Modifier = Modifier) {
    val turn by rememberInfiniteTransition(label = "spinner").animateFloat(
        0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "turn",
    )
    Canvas(modifier.size(28.dp)) {
        val stroke = 2.dp.toPx()
        drawArc(Color.White, turn, 270f, false, topLeft = Offset(stroke / 2, stroke / 2), size = Size(size.width - stroke, size.height - stroke), style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

@Composable
internal fun ControlButton(icon: ImageVector, modifier: Modifier = Modifier.size(44.dp), onClick: () -> Unit) {
    Box(
        modifier.clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(26.dp)) }
}

@Composable
internal fun Note(text: String) = Text(text, color = ItvColors.TextFaint, fontSize = 15.sp, modifier = Modifier.padding(16.dp))

@Composable
internal fun Field(hint: String, value: String, modifier: Modifier = Modifier, secret: Boolean = false, keyboard: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
    Box(modifier.fillMaxWidth().clip(PhoneShapes.Field).background(PhoneColors.Card).padding(14.dp)) {
        if (value.isEmpty()) Text(hint, color = ItvColors.TextFaint, fontSize = 16.sp)
        BasicTextField(
            value, onChange,
            singleLine = true,
            textStyle = TextStyle(color = ItvColors.Text, fontSize = 16.sp),
            cursorBrush = SolidColor(Color.White),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Quatro dígitos com o teclado do telefone. [check] devolve true quando o PIN serve; [rejected] diz porque não serviu. */
@Composable
internal fun PinDialog(title: String, onClose: (() -> Unit)?, rejected: () -> String = { "" }, check: (String) -> Boolean) {
    val s = LocalStrings.current
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(24.dp).clip(PhoneShapes.Card).background(PhoneColors.Sheet).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, color = ItvColors.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Field(s.pinHint, pin, secret = true, keyboard = KeyboardType.NumberPassword) { v ->
                pin = v.filter(Char::isDigit).take(4)
                wrong = null
                if (pin.length == 4 && !check(pin)) {
                    wrong = rejected().ifEmpty { s.wrongPin }
                    pin = ""
                }
            }
            wrong?.let { Text(it, color = ItvColors.Error, fontSize = 14.sp) }
            if (onClose != null) ControlButton(ItvIcons.Close, onClick = onClose)
        }
    }
}


@Composable
internal fun Section(title: String?, rows: @Composable () -> Unit) {
    Column {
        if (title != null) {
            Text(title.uppercase(), color = ItvColors.TextFaint, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp, modifier = Modifier.padding(start = 20.dp, bottom = 8.dp))
        }
        // Num cartão, como nas Definições da TV. A linha por baixo da última fila fica de fora.
        Column(
            Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(PhoneShapes.Section).background(PhoneColors.Section).layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height - 0.5.dp.roundToPx()) { placeable.place(0, 0) }
            },
        ) { rows() }
    }
}

/** O topo de uma página: voltar atrás e o título. */
@Composable
internal fun PageHeader(title: String, back: String, onBack: () -> Unit) {
    val accent = LocalAccent.current.start
    Box(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(Modifier.align(Alignment.CenterStart).clip(PhoneShapes.List).clickable(onClick = onBack).padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(ItvIcons.Back, null, tint = accent, modifier = Modifier.size(24.dp))
            Text(back, color = accent, fontSize = 16.sp)
        }
        Text(title, color = ItvColors.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.Center))
    }
}

@Composable
internal fun MenuRow(title: String, value: String?, icon: ImageVector? = null, chevron: Boolean = false, checked: Boolean = false, onClick: () -> Unit) {
    val accent = LocalAccent.current.start
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.07f)), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = ItvColors.TextDim, modifier = Modifier.size(19.dp))
                }
                Spacer(Modifier.width(14.dp))
            }
            Text(title, color = ItvColors.Text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (value != null) {
                Spacer(Modifier.width(8.dp))
                Text(value, color = ItvColors.TextFaint, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp))
            }
            if (checked) Icon(ItvIcons.Check, null, tint = accent, modifier = Modifier.size(22.dp))
            if (chevron) Icon(ItvIcons.Chevron, null, tint = ItvColors.TextFaint.copy(alpha = 0.42f), modifier = Modifier.size(22.dp))
        }
        Box(Modifier.padding(start = if (icon != null) 64.dp else 16.dp).fillMaxWidth().height(0.5.dp).background(PhoneColors.RowLine))
    }
}

@Composable
internal fun ToggleRow(title: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val accent = LocalAccent.current
    Column(Modifier.fillMaxWidth().clickable { onChange(!on) }) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = ItvColors.Text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Box(
                Modifier.size(44.dp, 26.dp).clip(CircleShape).then(if (on) Modifier.background(accent.brush) else Modifier.background(Color.White.copy(alpha = 0.18f))).padding(3.dp),
                contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
            ) { Box(Modifier.size(20.dp).clip(CircleShape).background(Color.White)) }
        }
        Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(0.5.dp).background(PhoneColors.RowLine))
    }
}
