package cc.slokker.itv.data

import android.content.Context
import cc.slokker.itv.playback.ChannelMemory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Onde fica a [ChannelMemory] (o áudio, as legendas e a proporção escolhidos à mão em cada canal): só neste aparelho,
 * fora da conta. Usa-se na thread principal (o player e o ecrã).
 */
class ChannelMemoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("channel_memory", Context.MODE_PRIVATE)

    val memory: ChannelMemory by lazy { ChannelMemory.decode(prefs.getString(KEY, null)) }

    private val _changes = MutableStateFlow(0)

    /** Muda a cada gravação: o ecrã volta a ler a proporção do canal. */
    val changes: StateFlow<Int> = _changes

    /** A proporção do canal: a escolhida à mão nele ou, sem nenhuma, a das definições ([global]). */
    fun aspect(playlistId: String, channelKey: String, global: String): String = memory.aspect(playlistId, channelKey).ifEmpty { global }

    /** A pessoa escolheu a proporção [aspect] no canal: guarda-se se for diferente da das definições ([global]). */
    fun setAspect(playlistId: String, channelKey: String, aspect: String, global: String) =
        saveIf(memory.setAspect(playlistId, channelKey, aspect, global))

    /** Grava se [changed] (o que os `set…` e o `touch` da [ChannelMemory] devolvem). */
    fun saveIf(changed: Boolean) {
        if (!changed) return
        prefs.edit().putString(KEY, memory.encode()).apply()
        _changes.value++
    }

    private companion object {
        const val KEY = "channels"
    }
}
