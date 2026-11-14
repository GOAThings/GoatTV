package cc.slokker.itv.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp

/** A aparência dos painéis em uso (Definições → Aspeto → Aparência; só na TV). O vidro lê-a: mudar redesenha-o. */
object ItvStyle {
    /** Claro: um véu branco por cima do vídeo desfocado, em vez do escuro de sempre. As listas são as mesmas. */
    var light by mutableStateOf(false)
}

/** Cantos da interface: arredondados, mas discretos. Mudar aqui muda a app toda. */
object ItvShapes {
    /** Painéis laterais, guia, definições, cartões grandes (erro, ativação, HUD). */
    val Panel = RoundedCornerShape(12.dp)

    /** Cartões pequenos (troca de canal, avisos, toast). */
    val Card = RoundedCornerShape(10.dp)

    /** Linhas das listas (canais, grupos, definições): os mesmos cantos discretos dos controlos. */
    val Row = RoundedCornerShape(ControlRadius)

    /** Campos de texto, botões, chips e cápsulas de estado. */
    val Control = RoundedCornerShape(ControlRadius)

    /** Etiquetas, células do guia, teclas. */
    val Small = RoundedCornerShape(4.dp)
}

/** Raio dos controlos (também o do brilho de foco, que tem de coincidir). */
val ControlRadius = 8.dp
