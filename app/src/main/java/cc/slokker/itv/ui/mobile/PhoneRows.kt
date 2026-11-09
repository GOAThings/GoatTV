package cc.slokker.itv.ui.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.slokker.itv.data.Channel
import cc.slokker.itv.data.EpgEntry
import cc.slokker.itv.data.Prefs
import cc.slokker.itv.data.Reminder
import cc.slokker.itv.ui.AppViewModel

/**
 * O que as linhas das listas do telemóvel mostram além do canal: logótipos, números, o programa a dar, a estrela e o
 * sino. A lista lê-o uma vez ([rememberRowState]) e passa-o às linhas; assim cada linha volta a desenhar-se quando
 * muda um favorito, um lembrete ou uma definição, sem ler o ViewModel linha a linha.
 */
internal class RowState(
    val showLogos: Boolean,
    val showNumbers: Boolean,
    val epgInList: Boolean,
    private val favorites: Set<String>,
    private val reminders: Set<Pair<String, Long>>,
) {
    fun isFavorite(channel: Channel): Boolean = channel.key in favorites

    /** Há lembrete para [entry] de [channel]. */
    fun reminded(channel: Channel, entry: EpgEntry): Boolean = (channel.key to entry.startMs) in reminders

    companion object {
        /** Os favoritos e os lembretes da lista aberta [playlistId] (os das outras listas não contam). */
        fun of(prefs: Prefs, favorites: Map<String, List<String>>, reminders: List<Reminder>, playlistId: String?) = RowState(
            showLogos = prefs.showLogos,
            showNumbers = prefs.showNumbers,
            epgInList = prefs.epgInList,
            favorites = playlistId?.let { favorites[it] }.orEmpty().toSet(),
            reminders = reminders.filter { it.playlistId == playlistId }.map { it.channelKey to it.startMs }.toSet(),
        )
    }
}

@Composable
internal fun rememberRowState(vm: AppViewModel): RowState {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val reminders by vm.reminders.collectAsStateWithLifecycle()
    val content by vm.content.collectAsStateWithLifecycle()
    val playlistId = content?.playlist?.id
    return remember(prefs, favorites, reminders, playlistId) { RowState.of(prefs, favorites, reminders, playlistId) }
}
