package cc.slokker.itv.playback

import cc.slokker.itv.data.Prefs

/**
 * O aspeto das legendas (Definições → Legendas): o tamanho, a cor e o sítio, iguais no Android e na Apple. Cada
 * plataforma desenha à sua maneira (o SubtitleView do Media3, a UILabel da Apple); aqui fica só a conta.
 *
 * As legendas em texto (teletexto, WebVTT, CEA-608/708) mudam de tamanho, de cor e de sítio. As imagens DVB só mudam
 * de tamanho e de sítio ([place]): são desenhadas pelo emissor, a cor vem com elas.
 */
object SubtitleLook {
    /** A distância do fundo do vídeo ao fundo das legendas, em fração da altura (a de sempre nas duas plataformas). */
    const val NORMAL_GAP = 0.08f

    /** Mais acima: por cima da barra de informação e dos botões do player. */
    const val RAISED_GAP = 0.28f

    /** O tamanho em relação ao normal. */
    fun scale(size: String): Float = when (size) {
        Prefs.SubtitleSize.SMALL -> 0.75f
        Prefs.SubtitleSize.LARGE -> 1.3f
        Prefs.SubtitleSize.EXTRA_LARGE -> 1.6f
        else -> 1f
    }

    /** A distância do fundo do vídeo ao fundo das legendas em texto ([NORMAL_GAP] ou [RAISED_GAP]). */
    fun gap(position: String): Float = if (position == Prefs.SubtitlePosition.RAISED) RAISED_GAP else NORMAL_GAP

    /** O texto numa caixa preta (senão, sem fundo e com sombra). */
    fun boxed(style: String): Boolean = style == Prefs.SubtitleStyle.WHITE_BOX || style == Prefs.SubtitleStyle.YELLOW_BOX

    /** A cor do texto, em ARGB. */
    fun textColor(style: String): Long =
        if (style == Prefs.SubtitleStyle.YELLOW_SHADOW || style == Prefs.SubtitleStyle.YELLOW_BOX) YELLOW else WHITE

    const val WHITE = 0xFFFFFFFFL
    const val YELLOW = 0xFFFFFF00L

    /**
     * Onde fica uma imagem DVB ([x], [y], [width], [height] em fração do ecrã do emissor, 0–1) com o tamanho [size] e
     * o sítio [position]. Cresce a partir do centro e da margem mais perto (de baixo, se a imagem estiver na metade de
     * baixo; de cima, se estiver na de cima), por isso as várias imagens de uma legenda continuam juntas. Mais acima
     * só sobe as de baixo. Nunca sai do vídeo. No normal e no tamanho normal fica tal e qual.
     */
    fun place(x: Float, y: Float, width: Float, height: Float, size: String, position: String): SubtitleRect {
        val s = scale(size)
        if (s == 1f && gap(position) == NORMAL_GAP) return SubtitleRect(x, y, width, height)
        val bottomHalf = y + height / 2 > 0.5f
        val anchor = if (bottomHalf) 1f else 0f
        val w = width * s
        val h = height * s
        var nx = 0.5f + (x - 0.5f) * s
        var ny = anchor + (y - anchor) * s
        if (bottomHalf) ny -= gap(position) - NORMAL_GAP
        nx = nx.coerceIn(0f, (1f - w).coerceAtLeast(0f))
        ny = ny.coerceIn(0f, (1f - h).coerceAtLeast(0f))
        return SubtitleRect(nx, ny, w, h)
    }
}

/** Um retângulo em fração do vídeo (0–1), a partir do canto de cima à esquerda. */
class SubtitleRect(val x: Float, val y: Float, val width: Float, val height: Float) {
    override fun equals(other: Any?) =
        other is SubtitleRect && other.x == x && other.y == y && other.width == width && other.height == height

    override fun hashCode() = ((x.hashCode() * 31 + y.hashCode()) * 31 + width.hashCode()) * 31 + height.hashCode()

    override fun toString() = "SubtitleRect($x, $y, $width, $height)"
}
