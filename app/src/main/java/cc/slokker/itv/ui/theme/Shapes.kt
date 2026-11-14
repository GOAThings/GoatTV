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
    /** O vidro novo (os mockups): cantos bem redondos, linhas limpas, o foco numa placa branca que sai do painel. */
    var glass by mutableStateOf(false)

    /** O vidro claro (véu branco); sem ele, o vidro novo é escuro, como o clássico (mesmas formas e linhas). */
    var light by mutableStateOf(false)
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
    private val GlassRow = RoundedCornerShape(24.dp)
}

/** Raio dos controlos (também o do brilho de foco, que tem de coincidir). */
val ControlRadius = 8.dp
