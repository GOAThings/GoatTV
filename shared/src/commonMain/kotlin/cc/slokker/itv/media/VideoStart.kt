package cc.slokker.itv.media

/**
 * Por onde o vídeo pode entrar num canal (ao abrir, ou depois de faltarem dados): só numa imagem-chave e sem as imagens
 * que, a seguir a ela, se mostram antes dela. Num open GOP (muitos canais de TV em H.264, e o HEVC com CRA), essas
 * imagens B apontam para imagens de antes da imagem-chave, que o descodificador nunca viu: no da Apple dão erro
 * (os borrões), noutros imagens estragadas. Da imagem-chave seguinte em diante já passa tudo.
 */
class VideoStart {
    private var waiting = true
    private var floor = NONE

    /** Esta imagem (com [pts] no tempo seguido, ou -1) pode ir para o descodificador? */
    fun pass(keyframe: Boolean, pts: Long): Boolean {
        if (waiting) {
            if (!keyframe) return false
            waiting = false
            floor = pts
            return true
        }
        if (keyframe) {
            floor = NONE
            return true
        }
        return floor == NONE || pts < 0 || pts >= floor
    }

    /** Volta a esperar por uma imagem-chave (outra ligação, ou faltaram dados). */
    fun restart() {
        waiting = true
        floor = NONE
    }

    private companion object {
        const val NONE = Long.MIN_VALUE
    }
}
