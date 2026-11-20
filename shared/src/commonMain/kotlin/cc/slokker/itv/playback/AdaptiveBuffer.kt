package cc.slokker.itv.playback

import cc.slokker.itv.data.Prefs

/**
 * O buffer automático do direto (Prefs.Buffer.AUTO), igual no GoatCore do Android e da Apple. O motor mostra a
 * primeira imagem logo (o arranque rápido não muda); o que se adapta é quanto vídeo à frente ([targetMs]) ele junta
 * depois de uma paragem antes de voltar a dar: com mais à frente, as quebras seguintes da ligação já não se veem.
 *
 * - Uma paragem ([onStall]) sobe um nível (Ao vivo → Pequeno → Normal → Grande, nunca acima do Grande).
 * - A ligação a dar menos do que o canal gasta (o que está à frente a cair muito, ver [onPlaying]) também sobe um
 *   nível, antes de parar, no máximo um a cada [DIP_COOLDOWN_MS].
 * - [STABLE_MS] seguidos sem nada disto descem um nível, devagar, até ao Ao vivo. Ao descer, o que está à frente a
 *   mais gasta-se a tocar um pouco mais depressa ([rate], [DRAIN_RATE], o som sem mudar de tom) até ao alvo novo;
 *   onde o motor não pode acelerar (o som em bruto para o recetor), fica para o canal seguinte.
 *
 * O nível aprendido guarda-se por fornecedor ([AdaptiveBufferMemory], pela [providerKey]): o canal seguinte de um
 * fornecedor que falha já começa no nível certo. Não é seguro entre threads (quem usa sincroniza). O relógio (ms) é
 * o de quem chama, sempre o mesmo e a andar para a frente. Sem tipos aninhados nem Int?, para chegar ao Swift.
 */
class AdaptiveBuffer(startLevel: Int = START_LEVEL) {
    /** O nível agora ([LIVE] a [LARGE]). */
    var level: Int = startLevel.coerceIn(LIVE, LARGE)
        private set

    private var stableSince = Long.MIN_VALUE
    private var lastGrowAt = Long.MIN_VALUE
    private var peakLeadMs = 0L
    private var draining = false

    /** Quanto vídeo à frente juntar depois de uma paragem antes de voltar a dar (ms). */
    fun targetMs(): Long = targetMs(level)

    /** Uma paragem a meio (o que estava à frente acabou): sobe um nível. Devolve true se mudou de nível. */
    fun onStall(nowMs: Long): Boolean {
        val grew = grow(nowMs)
        // Depois de parar, o que estava à frente recomeça do zero.
        peakLeadMs = 0L
        return grew
    }

    /**
     * A dar à velocidade normal, com [leadMs] à frente. [dips]: o que está à frente diz bem se a ligação acompanha (num
     * HLS chega aos bocados, um segmento de cada vez, e sobe e desce sempre: aí só as paragens contam). Devolve true
     * se mudou de nível.
     */
    fun onPlaying(nowMs: Long, leadMs: Long, dips: Boolean): Boolean {
        if (stableSince == Long.MIN_VALUE) stableSince = nowMs
        val lead = leadMs.coerceAtLeast(0)
        if (lead > peakLeadMs) peakLeadMs = lead
        // A cair muito desde o máximo e já abaixo de metade do alvo: a ligação não está a dar o que o canal gasta.
        if (dips && lead < targetMs() / 2 && peakLeadMs - lead >= DIP_DROP_MS &&
            (lastGrowAt == Long.MIN_VALUE || nowMs - lastGrowAt >= DIP_COOLDOWN_MS)
        ) {
            val grew = grow(nowMs)
            peakLeadMs = lead
            return grew
        }
        if (nowMs - stableSince >= STABLE_MS && level > LIVE) {
            level--
            stableSince = nowMs
            draining = true
            peakLeadMs = lead
            return true
        }
        return false
    }

    /**
     * A velocidade a tocar com [leadMs] à frente: [DRAIN_RATE] enquanto há a mais depois de descer de nível, senão 1.
     * [canDrain]: o motor pode acelerar sem se notar (o som PCM com o tom certo, à velocidade normal, no direto).
     */
    fun rate(leadMs: Long, canDrain: Boolean): Float {
        val over = draining && leadMs > targetMs() + DRAIN_SLACK_MS
        if (!over) {
            // Chegou ao alvo (ou já lá estava): acabou a descida.
            if (draining && leadMs <= targetMs() + DRAIN_SLACK_MS) draining = false
            return 1f
        }
        return if (canDrain) DRAIN_RATE else 1f
    }

    private fun grow(nowMs: Long): Boolean {
        stableSince = nowMs
        lastGrowAt = nowMs
        draining = false
        if (level >= LARGE) return false
        level++
        return true
    }

    companion object {
        const val LIVE = 0
        const val SMALL = 1
        const val NORMAL = 2
        const val LARGE = 3

        /** Um fornecedor de que não se sabe nada começa no Normal (o padrão de antes). */
        const val START_LEVEL = NORMAL

        /** Sem paragens nem quebras durante isto (10 min), desce um nível. */
        const val STABLE_MS = 600_000L

        /** Uma quebra é o que está à frente cair isto desde o máximo. */
        const val DIP_DROP_MS = 1_500L

        /** Uma subida por quebra no máximo a cada isto (as paragens sobem sempre). */
        const val DIP_COOLDOWN_MS = 30_000L

        /** A velocidade para gastar o que está à frente a mais (2 %: não se nota, e 6 s gastam-se em 5 min). */
        const val DRAIN_RATE = 1.02f

        /** Acima do alvo até isto, já não se acelera. */
        const val DRAIN_SLACK_MS = 500L

        /** O alvo de cada nível (ms): os do VLC na Apple TV (Prefs.Buffer.milliseconds). */
        fun targetMs(level: Int): Long = when (level.coerceIn(LIVE, LARGE)) {
            LIVE -> 500L
            SMALL -> 1_000L
            NORMAL -> 2_000L
            else -> 4_000L
        }

        /** O nível de uma opção fixa do buffer (o automático e o resto: o Normal). */
        fun levelOf(buffer: String): Int = when (buffer) {
            Prefs.Buffer.LIVE -> LIVE
            Prefs.Buffer.SMALL -> SMALL
            Prefs.Buffer.LARGE -> LARGE
            else -> NORMAL
        }

        /**
         * O fornecedor de [url], para a memória: o servidor e a porta (sem o utilizador nem a palavra-passe que
         * algumas listas põem no endereço), em minúsculas; "" se não tiver servidor (um ficheiro, o anel).
         */
        fun providerKey(url: String): String {
            val at = url.indexOf("://")
            if (at <= 0) return ""
            val authority = url.substring(at + 3).substringBefore('/').substringBefore('?').substringBefore('#')
            return authority.substringAfterLast('@').lowercase()
        }
    }
}

/**
 * O nível que cada fornecedor aprendeu ([AdaptiveBuffer.providerKey] → nível), em memória até a app fechar; guarda os
 * últimos [MAX] fornecedores. Não é seguro entre threads (quem usa sincroniza).
 */
class AdaptiveBufferMemory {
    private val levels = LinkedHashMap<String, Int>()

    /** O nível com que [key] começa: o aprendido ou [AdaptiveBuffer.START_LEVEL]. */
    fun startLevel(key: String): Int = levels[key] ?: AdaptiveBuffer.START_LEVEL

    /** [key] ficou em [level]. */
    fun remember(key: String, level: Int) {
        if (key.isEmpty()) return
        levels.remove(key)
        levels[key] = level.coerceIn(AdaptiveBuffer.LIVE, AdaptiveBuffer.LARGE)
        while (levels.size > MAX) levels.remove(levels.keys.first())
    }

    fun clear() = levels.clear()

    companion object {
        const val MAX = 64
    }
}
