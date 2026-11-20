package cc.slokker.itv.ui.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import cc.slokker.itv.playback.ListVerdict
import cc.slokker.itv.playback.ProbeVerdict
import cc.slokker.itv.playback.StreamProbe
import cc.slokker.itv.ui.AppViewModel
import cc.slokker.itv.ui.glass.ItvIcons
import cc.slokker.itv.ui.i18n.LocalStrings
import cc.slokker.itv.ui.player.probeListVerdict
import cc.slokker.itv.ui.player.probeRows
import cc.slokker.itv.ui.player.probeVerdictText

/** "Testar este canal" na folha de opções do player: começa sozinho ao abrir e mostra o veredicto e os números. */
@Composable
internal fun ChannelTestSection(vm: AppViewModel, player: Player?, title: String) {
    val s = LocalStrings.current
    val st by vm.channelProbe.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        vm.channelProbe.clear()
        vm.channelProbe.start(player)
    }
    Section(title) {
        val result = st.result
        when {
            st.unsupported -> Note(s.probeUnsupported)
            st.running -> Note(s.probeTesting(st.second))
            result != null -> {
                val line = StreamProbe.verdict(result)
                MenuRow(s.probeVerdictText(line), null, icon = if (line.verdict == ProbeVerdict.OK) ItvIcons.Check else ItvIcons.Warning) {}
                s.probeRows(result).forEach { (label, value) -> MenuRow(label, value) {} }
            }
        }
        if (!st.running) MenuRow(if (result == null) s.probeTestChannel else s.probeAgain, null) {
            vm.channelProbe.clear()
            vm.channelProbe.start(player)
        }
    }
}

/** As linhas de "Testar esta lista": uma lista e um guia, cada um com a resposta, o tempo, o tamanho e a contagem. */
@Composable
internal fun ListTestRows(vm: AppViewModel) {
    val s = LocalStrings.current
    val st by vm.listProbe.state.collectAsStateWithLifecycle()
    if (st.running && st.rows.isEmpty()) Note(s.probeListTesting)
    if (st.portal) Note(s.probeListPortal)
    st.rows.forEach { row ->
        val count = if (row.guide) s.probeListProgrammes(row.count) else s.probeListChannels(row.count)
        MenuRow(
            "${if (row.guide) s.probeListGuide else s.probeListPlaylist} · ${s.probeListVerdict(row)}", row.host.ifEmpty { null },
            icon = if (row.verdict == ListVerdict.OK) ItvIcons.Check else ItvIcons.Warning,
            subtitle = if (row.status >= 0) listOfNotNull(s.probeSeconds(StreamProbe.seconds(row.ms)), StreamProbe.size(row.bytes), count.takeIf { row.verdict != ListVerdict.REFUSED }).joinToString(" · ") else null,
        ) {}
    }
    if (st.noGuide) Note(s.probeListNoGuide)
}
