package cc.slokker.itv.ui.theme

import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.Immutable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.LocalTextStyle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme
import cc.slokker.itv.R

object ItvColors {
    val Sky = Color(0xFF38BDF8)
    val Indigo = Color(0xFF818CF8)
    val Background = Color(0xFF05070D)

    // Cores de texto ao estilo Apple (label / secondaryLabel / tertiaryLabel).
    val Text = Color(0xFFF5F5F7)
    val TextDim = Color(0xD9EBEBF5)
    val TextFaint = Color(0x99EBEBF5)

    /** Elemento com foco: vidro claro, texto branco e contorno no gradiente da marca. */
    val Focus = Color(0x33FFFFFF)
    val OnFocus = Text
    val Pressed = Color(0x4DFFFFFF)

    val Error = Color(0xFFFF6961)

    val Accent = Brush.linearGradient(listOf(Sky, Indigo))
}

/** Cor de destaque (contorno de foco, interruptores, progresso). Escolhe-se em Aspeto → Cor de destaque. */
@Immutable
data class Accent(val id: String, val start: Color, val end: Color) {
    val brush: Brush get() = Brush.linearGradient(listOf(start, end))

    companion object {
        val ALL = listOf(
            Accent("itv", ItvColors.Sky, ItvColors.Indigo),
            Accent("violet", Color(0xFFA78BFA), Color(0xFFF472B6)),
            Accent("mint", Color(0xFF34D399), Color(0xFF22D3EE)),
            Accent("sunset", Color(0xFFFB923C), Color(0xFFF43F5E)),
            Accent("gold", Color(0xFFFDE68A), Color(0xFFF59E0B)),
            Accent("white", Color(0xFFFFFFFF), Color(0xFFCBD5E1)),
        )

        fun byId(id: String) = ALL.firstOrNull { it.id == id } ?: ALL.first()
    }
}

val LocalAccent = compositionLocalOf { Accent.ALL.first() }

/** Margens de segurança de TV (overscan). */
val TvSafePadding = PaddingValues(horizontal = 48.dp, vertical = 27.dp)

/** Adwaita Sans (derivada do Inter, OFL): a fonte livre mais próxima do SF Pro. */
private fun weight(w: Int) = Font(
    R.font.adwaita_sans,
    weight = FontWeight(w),
    variationSettings = FontVariation.Settings(FontVariation.weight(w)),
)

val ItvFont = FontFamily(weight(400), weight(500), weight(600), weight(700), weight(800))

private fun TextStyle.apple(size: Int? = null, w: Int? = null, tracking: Float? = null) = copy(
    fontFamily = ItvFont,
    fontSize = size?.sp ?: fontSize,
    fontWeight = w?.let { FontWeight(it) } ?: fontWeight,
    letterSpacing = tracking?.sp ?: letterSpacing,
)

private val ItvTypography = Typography().run {
    copy(
        displayLarge = displayLarge.apple(w = 700, tracking = -1f),
        displayMedium = displayMedium.apple(w = 700, tracking = -0.8f),
        displaySmall = displaySmall.apple(w = 700, tracking = -0.5f),
        headlineLarge = headlineLarge.apple(w = 700, tracking = -0.5f),
        headlineMedium = headlineMedium.apple(w = 700, tracking = -0.4f),
        headlineSmall = headlineSmall.apple(w = 600, tracking = -0.3f),
        titleLarge = titleLarge.apple(w = 600, tracking = -0.2f),
        titleMedium = titleMedium.apple(w = 600, tracking = 0f),
        titleSmall = titleSmall.apple(w = 600, tracking = 0f),
        bodyLarge = bodyLarge.apple(w = 400, tracking = 0f),
        bodyMedium = bodyMedium.apple(w = 400, tracking = 0f),
        bodySmall = bodySmall.apple(w = 400, tracking = 0f),
        labelLarge = labelLarge.apple(w = 600, tracking = 0f),
        labelMedium = labelMedium.apple(w = 600, tracking = 0.2f),
        labelSmall = labelSmall.apple(w = 600, tracking = 0.4f),
    )
}

@Composable
fun ItvTheme(content: @Composable () -> Unit) {
    // Em árabe, o espaçamento entre letras parte as ligações da escrita: tira-se de toda a tipografia.
    val typography = if (LocalLayoutDirection.current == LayoutDirection.Rtl) ItvTypography.withoutTracking() else ItvTypography
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = ItvColors.Sky,
            onPrimary = ItvColors.OnFocus,
            secondary = ItvColors.Indigo,
            onSecondary = ItvColors.OnFocus,
            background = ItvColors.Background,
            onBackground = ItvColors.Text,
            surface = Color.Transparent,
            onSurface = ItvColors.Text,
            surfaceVariant = Color(0x1FFFFFFF),
            onSurfaceVariant = ItvColors.TextDim,
            inverseSurface = ItvColors.Focus,
            inverseOnSurface = ItvColors.OnFocus,
            error = ItvColors.Error,
            border = Color(0x33FFFFFF),
        ),
        typography = typography,
    ) {
        // Sem isto, o Text do tv-material fora de um Surface fica preto.
        CompositionLocalProvider(
            LocalContentColor provides ItvColors.Text,
            LocalTextStyle provides typography.bodyLarge,
            content = content,
        )
    }
}

private fun Typography.withoutTracking() = copy(
    displayLarge = displayLarge.copy(letterSpacing = 0.sp), displayMedium = displayMedium.copy(letterSpacing = 0.sp),
    displaySmall = displaySmall.copy(letterSpacing = 0.sp), headlineLarge = headlineLarge.copy(letterSpacing = 0.sp),
    headlineMedium = headlineMedium.copy(letterSpacing = 0.sp), headlineSmall = headlineSmall.copy(letterSpacing = 0.sp),
    titleLarge = titleLarge.copy(letterSpacing = 0.sp), titleMedium = titleMedium.copy(letterSpacing = 0.sp),
    titleSmall = titleSmall.copy(letterSpacing = 0.sp), labelLarge = labelLarge.copy(letterSpacing = 0.sp),
    labelMedium = labelMedium.copy(letterSpacing = 0.sp), labelSmall = labelSmall.copy(letterSpacing = 0.sp),
)
