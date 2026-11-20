package cc.slokker.itv.media

import cc.slokker.itv.data.Prefs

/**
 * O que o leitor de HLS pode ler numa playlist principal: começa em [start] e a medição da rede (o ABR) só anda entre
 * [lowest] e [highest] (índices de [HlsQuality.ordered]). Com os dois iguais, a qualidade fica fixa.
 */
class HlsQualityPlan(val start: Int, val lowest: Int, val highest: Int) {
    val fixed: Boolean get() = lowest == highest
}

/** Uma linha do menu "Qualidade": o índice em [HlsQuality.ordered] e o nome ("1080p · 6.2 Mb/s"). */
class HlsQualityOption(val index: Int, val label: String)

/**
 * A escolha da qualidade de um HLS com várias, igual no Android e na Apple: a definição (Prefs.hls_quality) e a
 * escolha à mão no leitor (que ganha até se mudar de canal) dão um [HlsQualityPlan]; os nomes das qualidades são os
 * mesmos nos dois.
 */
object HlsQuality {
    /** Poupar dados: até 720p; quando a playlist não diz a altura, até ~3 Mb/s. */
    const val SAVER_HEIGHT = 720
    const val SAVER_BANDWIDTH = 3_300_000L

    /** Os prefixos (CODECS) que são vídeo. */
    private val VIDEO_CODECS = listOf("avc1", "avc3", "hvc1", "hev1", "dvh1", "dvhe", "mp4v", "av01", "vp09", "vp9")

    /**
     * As qualidades por onde o leitor anda, da mais baixa à mais alta (pelo débito). As só de som (CODECS sem vídeo e
     * sem altura) saem, quando há alguma com vídeo: no menu e no ABR não serviam de nada.
     */
    fun ordered(variants: List<HlsVariant>): List<HlsVariant> {
        val sorted = variants.sortedBy { it.bandwidth }
        val video = sorted.filter { !audioOnly(it) }
        return video.ifEmpty { sorted }
    }

    /** Só som: diz os codecs e nenhum é de vídeo (e não diz altura). */
    fun audioOnly(v: HlsVariant): Boolean {
        if (v.height > 0) return false
        val codecs = v.codecs?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() } ?: return false
        return codecs.isNotEmpty() && codecs.none { c -> VIDEO_CODECS.any { c.startsWith(it) } }
    }

    /**
     * O plano para [ordered] (ver [ordered]) com a definição [preference] (Prefs.HlsQuality) e a escolha à mão
     * [pinned] (-1 = nenhuma). Automático começa na mais alta (é uma TV, ou um telemóvel na Wi-Fi de casa) e desce e
     * sobe com a rede; Poupar dados faz o mesmo até ao teto.
     */
    fun plan(ordered: List<HlsVariant>, preference: String, pinned: Int): HlsQualityPlan {
        val last = ordered.lastIndex
        if (last < 0) return HlsQualityPlan(0, 0, 0)
        if (pinned in 0..last) return HlsQualityPlan(pinned, pinned, pinned)
        return when (preference) {
            Prefs.HlsQuality.BEST -> HlsQualityPlan(last, last, last)
            Prefs.HlsQuality.LOWEST -> HlsQualityPlan(0, 0, 0)
            Prefs.HlsQuality.DATA_SAVER -> {
                val cap = ordered.indexOfLast(::fitsSaver).coerceAtLeast(0)
                HlsQualityPlan(cap, 0, cap)
            }
            else -> HlsQualityPlan(last, 0, last)
        }
    }

    /**
     * A definição que conta agora: com dados móveis ([cellular]) e `mobile_data_quality` = auto (só nos telemóveis),
     * qualquer uma menos "a mais baixa" conta como Poupar dados; na Wi-Fi ou com "same", a de sempre.
     */
    fun effectivePreference(preference: String, mobileData: String, cellular: Boolean): String =
        if (cellular && mobileData == Prefs.MobileDataQuality.AUTO && preference != Prefs.HlsQuality.LOWEST) Prefs.HlsQuality.DATA_SAVER else preference

    /** Cabe em Poupar dados: até 720p, ou (sem altura) até ~3 Mb/s. */
    fun fitsSaver(v: HlsVariant): Boolean =
        if (v.height > 0) v.height <= SAVER_HEIGHT else v.bandwidth in 1..SAVER_BANDWIDTH

    /**
     * O nome de uma qualidade: a altura (com a cadência quando passa de 30: "1080p50") e o débito ("6.2 Mb/s"). Sem
     * nenhum dos dois, o número dela ([index] + 1).
     */
    fun label(v: HlsVariant, index: Int): String {
        val fps = v.frameRate.takeIf { it > 30.5 }?.let { kotlin.math.round(it).toInt() }
        val height = v.height.takeIf { it > 0 }?.let { h -> "${h}p" + (fps?.toString() ?: "") }
        val parts = listOfNotNull(height, bitrate(v.bandwidth))
        return if (parts.isEmpty()) "${index + 1}" else parts.joinToString(" · ")
    }

    /** "6.2 Mb/s" ou "800 kb/s"; null sem débito. */
    fun bitrate(bps: Long): String? = when {
        bps <= 0 -> null
        bps < 1_000_000 -> "${(bps + 500) / 1000} kb/s"
        else -> {
            val tenths = (bps + 50_000) / 100_000
            "${tenths / 10}.${tenths % 10} Mb/s"
        }
    }

    /**
     * O menu "Qualidade" (além do Automático): da mais alta à mais baixa, sem repetir nomes (as playlists com
     * endereços de reserva trazem a mesma qualidade duas vezes; fica a primeira). Vazio com menos de duas.
     */
    fun menu(ordered: List<HlsVariant>): List<HlsQualityOption> {
        val seen = HashSet<String>()
        val out = ArrayList<HlsQualityOption>()
        for (i in ordered.indices.reversed()) {
            val label = label(ordered[i], i)
            if (seen.add(label)) out += HlsQualityOption(i, label)
        }
        return if (out.size < 2) emptyList() else out
    }
}
