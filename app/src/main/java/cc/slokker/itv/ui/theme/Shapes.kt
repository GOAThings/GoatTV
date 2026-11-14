package cc.slokker.itv.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp

/**
 * O estilo dos painéis em uso (Definições → Aspeto → Estilo). As formas e o vidro leem-no: mudar de estilo redesenha
 * o que os usa (é estado do Compose). No telemóvel fica sempre o clássico.
 */
object ItvStyle {
    /** O vidro claro: véu branco sobre o vídeo desfocado, cantos bem redondos, foco numa placa branca maior. */
    var glass by mutableStateOf(false)

    /**
     * Onde está (na janela) a linha com foco: as outras linhas da mesma coluna apagam-se com a distância a ela, como
     * numa roda (ver GlassRow). Lido só no desenho.
     */
    var focusBand by mutableStateOf(androidx.compose.ui.geometry.Rect.Zero)

    /** A opacidade de uma linha em [row] (na janela) com o foco em [focusBand]: a 1 linha 0.55, a 2 0.38, a 3 0.26… */
    fun wheelAlpha(row: androidx.compose.ui.geometry.Rect): Float {
        val band = focusBand
        if (band.isEmpty || row.isEmpty) return 1f
        // Noutra coluna (o menu ao lado das opções), fica a meio.
        if (row.right <= band.left || row.left >= band.right) return 0.6f
        val steps = kotlin.math.abs(row.center.y - band.center.y) / band.height.coerceAtLeast(1f)
        if (steps < 0.5f) return 1f
        return (0.55f * Math.pow(0.69, (steps - 1f).toDouble().coerceAtLeast(0.0)).toFloat()).coerceIn(0.1f, 0.55f)
    }
}

/** Cantos da interface: arredondados, mas discretos. Mudar aqui muda a app toda. */
object ItvShapes {
    /** Painéis laterais, guia, definições, cartões grandes (erro, ativação, HUD). */
    val Panel: RoundedCornerShape get() = if (ItvStyle.glass) GlassPanel else ClassicPanel

    /** Cartões pequenos (troca de canal, avisos, toast). */
    val Card: RoundedCornerShape get() = if (ItvStyle.glass) GlassCard else ClassicCard

    /** Linhas das listas (canais, grupos, definições): os mesmos cantos discretos dos controlos. */
    val Row: RoundedCornerShape get() = if (ItvStyle.glass) GlassRow else ClassicRow

    /** Campos de texto, botões, chips e cápsulas de estado. */
    val Control = RoundedCornerShape(ControlRadius)

    /** Etiquetas, células do guia, teclas. */
    val Small = RoundedCornerShape(4.dp)

    private val ClassicPanel = RoundedCornerShape(12.dp)
    private val ClassicCard = RoundedCornerShape(10.dp)
    private val ClassicRow = RoundedCornerShape(ControlRadius)
    private val GlassPanel = RoundedCornerShape(28.dp)
    private val GlassCard = RoundedCornerShape(22.dp)
    private val GlassRow = RoundedCornerShape(18.dp)
}

/** Raio dos controlos (também o do brilho de foco, que tem de coincidir). */
val ControlRadius = 8.dp

/** No vidro claro as listas andam com o foco ao centro (a roda do mockup); nas pontas param, como sempre. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
val CenteredScroll = object : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        offset - (containerSize - size) / 2f
}
