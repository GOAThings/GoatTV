package cc.slokker.itv.ui.player.goatcore

import cc.slokker.itv.data.Prefs
import cc.slokker.itv.media.HlsQuality
import cc.slokker.itv.media.HlsQualityOption
import cc.slokker.itv.media.HlsQualityPlan
import cc.slokker.itv.media.HlsVariant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A qualidade do HLS do canal principal, entre o leitor ([HlsInputStream]) e o menu: a definição ([preference]), a
 * escolha à mão ([pin], só para a playlist [pinnedUrl] e até se mudar de canal) e o que está a dar ([now], para o
 * menu e a informação do stream). O segundo canal e as gravações seguem só a definição.
 */
object HlsQualityControl {
    /** O que o leitor do canal principal está a ler: as qualidades ([HlsQuality.ordered]), a atual e o menu. */
    class Now(val url: String, val variants: List<HlsVariant>, val level: Int, val pinned: Int, internal val owner: Any) {
        val options: List<HlsQualityOption> get() = HlsQuality.menu(variants)
        val label: String? get() = variants.getOrNull(level)?.let { HlsQuality.label(it, level) }
        /** O nome da escolha à mão; null = Automático (a definição). */
        val pinnedLabel: String? get() = variants.getOrNull(pinned)?.let { HlsQuality.label(it, pinned) }
        /** A linha do menu que está escolhida: a da escolha à mão (pelo nome, que o menu não repete); -1 = Automático. */
        val selected: Int get() = pinnedLabel?.let { l -> options.firstOrNull { it.label == l }?.index } ?: -1
    }

    private val lock = Any()
    private var pinnedUrl: String? = null
    private var pinned = -1

    @Volatile var preference: String = Prefs.HlsQuality.AUTO
        set(value) {
            if (field == value) return
            field = value
            version++
        }

    /** Muda sempre que a definição ou a escolha à mão mudam: o leitor volta a fazer o plano. */
    @Volatile var version = 0
        private set

    private val _now = MutableStateFlow<Now?>(null)
    val now: StateFlow<Now?> = _now

    /** O plano para [url] (as qualidades já em [HlsQuality.ordered]); [main]: o canal principal (o que tem menu). */
    fun plan(url: String, variants: List<HlsVariant>, main: Boolean): HlsQualityPlan {
        val pin = if (main) synchronized(lock) { if (pinnedUrl == url) pinned else -1 } else -1
        return HlsQuality.plan(variants, preference, pin)
    }

    /** Fixa a qualidade [index] (de [Now.variants]) no que está a dar; -1 volta ao Automático. */
    fun pin(index: Int) {
        val n = _now.value ?: return
        synchronized(lock) {
            pinnedUrl = n.url.takeIf { index >= 0 }
            pinned = index
        }
        version++
        _now.value = Now(n.url, n.variants, n.level, index, n.owner)
    }

    /** Mudou-se de canal: a escolha à mão acaba. */
    fun clearPin() {
        val had = synchronized(lock) {
            val had = pinned >= 0
            pinnedUrl = null
            pinned = -1
            had
        }
        if (had) version++
    }

    internal fun publish(owner: Any, url: String, variants: List<HlsVariant>, level: Int) {
        val pin = synchronized(lock) { if (pinnedUrl == url) pinned else -1 }
        _now.value = Now(url, variants, level, pin, owner)
    }

    internal fun closed(owner: Any) {
        val n = _now.value
        if (n?.owner === owner) _now.compareAndSet(n, null)
    }
}
