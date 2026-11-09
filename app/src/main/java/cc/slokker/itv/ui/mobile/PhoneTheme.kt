package cc.slokker.itv.ui.mobile

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// O desenho do telemóvel tem cores e formas próprias, separadas das da TV (ItvColors/ItvShapes, que a Apple TV
// espelha): mudar uma não mexe na outra. Os valores são os que os ecrãs já usavam, agora com nome.

/** As cores do telemóvel (os brancos translúcidos são por cima do fundo escuro). */
internal object PhoneColors {
    /** Folhas que sobem de baixo (guia do canal, áudio/legendas/temporizador) e a janela do PIN. */
    val Sheet = Color(0xFF14161E)
    /** A folha da versão nova. */
    val UpdateSheet = Color(0xFF151821)
    /** O aviso de um lembrete, no topo. */
    val Reminder = Color(0xFF20232E)
    /** O mini-leitor por cima dos separadores. */
    val MiniPlayer = Color(0xFF1A1E2A)
    /** A barra dos separadores. */
    val TabBar = Color(0xFF0C0E13)
    /** O gradiente dos cartões em destaque: "Continuar" no início e a lista em uso nas Definições. */
    val HeroTop = Color(0xFF1D2236)
    val HeroBottom = Color(0xFF0E1018)
    val PlaylistCardBottom = Color(0xFF12151D)
    val FavoriteGold = Color(0xFFF5C04A)
    /** O ponto do "Em direto". */
    val LiveRed = Color(0xFFE53935)

    /** Fundo de cartões, campos e botões em pílula. */
    val Card = Color.White.copy(alpha = 0.08f)
    /** Fundo dos mosaicos (grupos, favoritos) e do botão de procurar. */
    val Tile = Color.White.copy(alpha = 0.06f)
    /** O cartão de uma secção das Definições. */
    val Section = Color.White.copy(alpha = 0.05f)
    /** O traço entre as linhas de uma secção. */
    val RowLine = Color.White.copy(alpha = 0.08f)
    /** O fundo de uma barra de progresso fina, e a parte já vista quando o canal não é o que está a dar. */
    val ProgressTrack = Color.White.copy(alpha = 0.08f)
    val ProgressDim = Color.White.copy(alpha = 0.34f)
    /** Os botões redondos por cima do vídeo. */
    val HudButton = Color.White.copy(alpha = 0.14f)
    /** O escuro por trás de uma folha aberta. */
    val Scrim = Color.Black.copy(alpha = 0.5f)
    /** A cor de destaque por trás do canal (ou grupo) que está a dar. */
    const val SELECTED_ALPHA = 0.13f
}

/** As formas do telemóvel. */
internal object PhoneShapes {
    /** Cartões grandes: escolhas do início, o aviso de lembrete, o PIN, o botão Gravar. */
    val Card = RoundedCornerShape(18.dp)
    /** Linhas de lista, pílulas de grupos e o "A seguir". */
    val List = RoundedCornerShape(12.dp)
    val Section = RoundedCornerShape(16.dp)
    val Field = RoundedCornerShape(14.dp)
    val Tile = RoundedCornerShape(16.dp)
    val Hero = RoundedCornerShape(20.dp)
    val MiniPlayer = RoundedCornerShape(16.dp)
    /** O topo das folhas que sobem de baixo. */
    val Sheet = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
}
