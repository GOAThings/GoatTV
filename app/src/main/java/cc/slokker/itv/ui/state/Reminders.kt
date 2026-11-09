package cc.slokker.itv.ui.state

import cc.slokker.itv.data.CacheStore
import cc.slokker.itv.data.Channel
import cc.slokker.itv.data.EpgEntry
import cc.slokker.itv.data.Reminder
import cc.slokker.itv.ui.i18n.I18n
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Lembretes de programas (só nesta TV, na cache), da lista aberta ([playlistId]). */
class Reminders(
    private val scope: CoroutineScope,
    private val cache: CacheStore,
    private val playlistId: () -> String?,
    private val toast: (String) -> Unit,
) {
    private val _reminders = MutableStateFlow<List<Reminder>>(emptyList())
    val reminders: StateFlow<List<Reminder>> = _reminders.asStateFlow()

    /** Lê os lembretes guardados, sem os que já passaram há mais de [GRACE_MS]. */
    suspend fun load(now: Long = System.currentTimeMillis()) {
        _reminders.value = cache.loadReminders().filter { it.startMs > now - GRACE_MS }
    }

    fun has(channel: Channel, entry: EpgEntry): Boolean {
        val playlistId = playlistId() ?: return false
        return _reminders.value.any { it.playlistId == playlistId && it.channelKey == channel.key && it.startMs == entry.startMs }
    }

    /** Marca/desmarca o lembrete de um programa que ainda vai começar. */
    fun toggle(channel: Channel, entry: EpgEntry) {
        val playlistId = playlistId() ?: return
        val existing = _reminders.value.firstOrNull { it.playlistId == playlistId && it.channelKey == channel.key && it.startMs == entry.startMs }
        _reminders.value = if (existing != null) {
            toast(I18n.s.reminderRemoved)
            _reminders.value - existing
        } else {
            toast(I18n.s.reminderSet(entry.title))
            (_reminders.value + Reminder(playlistId, channel.key, channel.name, entry.title, entry.startMs)).sortedBy { it.startMs }
        }
        scope.launch { cache.saveReminders(_reminders.value) }
    }

    /** Lembrete a mostrar agora (o programa começa dentro de 1 min, ou começou há pouco). */
    fun due(now: Long): Reminder? {
        val playlistId = playlistId() ?: return null
        return _reminders.value.firstOrNull {
            it.playlistId == playlistId && it.startMs <= now + AHEAD_MS && it.startMs > now - GRACE_MS
        }
    }

    /** O lembrete já foi mostrado: sai da lista. */
    fun consume(reminder: Reminder) {
        _reminders.value = _reminders.value - reminder
        scope.launch { cache.saveReminders(_reminders.value) }
    }

    companion object {
        const val AHEAD_MS = 60_000L
        const val GRACE_MS = 10 * 60_000L
    }
}
