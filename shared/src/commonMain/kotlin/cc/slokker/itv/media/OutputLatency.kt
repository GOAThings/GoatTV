package cc.slokker.itv.media

/**
 * A latência da saída de som (o tempo entre entregar uma amostra ao sistema e ela ouvir-se): uns ms no HDMI, 100 a
 * 300 ms em auscultadores e colunas Bluetooth. A imagem tem de esperar por ela, senão os lábios não acertam.
 *
 * O motor mede-a de vez em quando e dá aqui o que conseguiu saber, por ordem de confiança:
 *  - [measure] `timestampUs`: a diferença entre o que já entregou e o que o sistema diz que está a sair agora (o
 *    carimbo do AudioTrack), a medida mais certa;
 *  - `reportedUs`: o que a saída declara (o getLatency escondido do AudioTrack, sem o buffer da própria faixa);
 *  - sem nenhuma, numa saída Bluetooth, [BLUETOOTH_DEFAULT_US] (melhor do que zero); noutra saída, zero.
 *
 * O valor fica dentro de 0…[MAX_US] (um disparate do aparelho não estraga a sincronia) e alisado: as medidas tremem
 * uns ms e a imagem não deve tremer com elas; uma mudança a sério (outra saída, ver [reset]) vale ao fim de
 * [JUMP_SAMPLES] medidas seguidas a dizer o mesmo. Sem estado de plataforma: igual no Android e na Apple.
 */
class OutputLatency {
    /** A latência a descontar (µs), 0…[MAX_US]. Lê-se de qualquer thread. */
    var latencyUs: Long = 0
        private set

    /** Já houve alguma medida (ou um valor de partida) desde o último [reset]. */
    var known: Boolean = false
        private set

    private var jumpUs = 0L
    private var jumps = 0

    /** A saída mudou (ou a faixa é nova): esquece o que sabia. [startUs]: o que esta saída deu da última vez, ou -1. */
    fun reset(startUs: Long = -1) {
        jumps = 0
        known = startUs >= 0
        latencyUs = if (known) startUs.coerceIn(0, MAX_US) else 0
    }

    /** Uma medida nova (cada valor em µs, ou -1 se não se sabe); devolve [latencyUs] já com ela. */
    fun measure(timestampUs: Long, reportedUs: Long, bluetooth: Boolean): Long {
        val picked = pick(timestampUs, reportedUs, bluetooth)
        if (picked < 0) return latencyUs
        if (!known) {
            known = true
            latencyUs = picked
            return picked
        }
        val diff = picked - latencyUs
        if (diff in -JUMP_US..JUMP_US) {
            jumps = 0
            latencyUs += diff / 4
        } else {
            // Longe do que se tinha: só vale se as seguintes disserem o mesmo (uma medida solta não mexe na imagem).
            if (jumps > 0 && picked - jumpUs in -JUMP_US..JUMP_US) jumps++ else jumps = 1
            jumpUs = picked
            if (jumps >= JUMP_SAMPLES) {
                jumps = 0
                latencyUs = picked
            }
        }
        return latencyUs
    }

    companion object {
        /** O máximo que se desconta: acima disto já não é uma saída de som normal, e o acerto à mão continua a existir. */
        const val MAX_US = 500_000L
        /** Acima disto o valor é um erro do aparelho (não uma saída lenta): não se usa. */
        const val IMPLAUSIBLE_US = 2 * MAX_US
        /** Uma saída Bluetooth que não diz nada de si: o habitual do A2DP com SBC/AAC. */
        const val BLUETOOTH_DEFAULT_US = 150_000L
        private const val JUMP_US = 40_000L
        private const val JUMP_SAMPLES = 3

        /** O valor a usar desta medida (µs, 0…[MAX_US]), ou -1 se nada se sabe. Ver a ordem em [OutputLatency]. */
        fun pick(timestampUs: Long, reportedUs: Long, bluetooth: Boolean): Long {
            val value = when {
                plausible(timestampUs) -> timestampUs
                plausible(reportedUs) -> reportedUs
                bluetooth -> BLUETOOTH_DEFAULT_US
                else -> return -1
            }
            return value.coerceAtMost(MAX_US)
        }

        private fun plausible(us: Long) = us in 0..IMPLAUSIBLE_US

        /**
         * O que a saída declara, a partir do getLatency do AudioTrack ([trackLatencyMs], que inclui o buffer da faixa)
         * e do tamanho desse buffer ([bufferFrames] a [sampleRate]; em passthrough o sistema conta-o em bytes, e o
         * getLatency também): µs, ou -1 se os números não fazem sentido.
         */
        fun reported(trackLatencyMs: Int, bufferFrames: Int, sampleRate: Int): Long {
            if (trackLatencyMs <= 0 || bufferFrames < 0 || sampleRate <= 0) return -1
            val us = trackLatencyMs * 1000L - bufferFrames * 1_000_000L / sampleRate
            return if (plausible(us)) us else -1
        }
    }
}
