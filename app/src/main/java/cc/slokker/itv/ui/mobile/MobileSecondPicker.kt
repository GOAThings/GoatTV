package cc.slokker.itv.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import cc.slokker.itv.data.Channel
import cc.slokker.itv.data.PlaylistContent
import cc.slokker.itv.ui.AppViewModel
import cc.slokker.itv.ui.common.ChannelLogo
import cc.slokker.itv.ui.i18n.LocalStrings
import cc.slokker.itv.ui.player.SecondPickerState
import cc.slokker.itv.ui.theme.ItvColors

/**
 * A escolha do segundo canal no telemóvel (lista → grupo → canal), com linhas de toque: as da TV
 * só respondem ao comando. A lógica é a mesma da TV ([SecondPickerState]).
 */
@Composable
internal fun PhoneSecondPicker(
    vm: AppViewModel,
    state: SecondPickerState,
    /** O lugar cujo canal se vai mudar (larga a sua ligação), ou null para um canal novo. */
    replacing: Int?,
    onPick: (PlaylistContent, Channel, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val main by vm.content.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val content = state.content
    val group = state.group
    Column(modifier.fillMaxWidth().clip(PhoneShapes.Card).background(PhoneColors.Sheet).padding(vertical = 16.dp)) {
        Text(
            group?.name ?: content?.playlist?.name ?: s.secondChannel,
            color = ItvColors.Text,
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Text(
            when {
                group != null -> s.secondPickChannel
                content != null -> s.secondPickGroup
                else -> s.secondPickPlaylist
            },
            color = ItvColors.TextFaint,
            fontSize = 14.sp,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        )
        when {
            state.loading -> Spinner(Modifier.align(Alignment.CenterHorizontally).padding(24.dp))

            content == null -> {
                val allowed = playlists.filter { vm.secondAllowed(it, replacing) }
                if (allowed.isEmpty()) Note(s.secondNeedsOther)
                LazyColumn {
                    // As listas permitidas primeiro; as outras ficam no fim, sem toque.
                    items(allowed + (playlists - allowed.toSet()), key = { it.id }) { p ->
                        val ok = p in allowed
                        MenuRow(
                            title = p.name,
                            value = null,
                            chevron = ok,
                            subtitle = when {
                                !ok -> s.secondOneConnection
                                p.id == main?.playlist?.id -> s.currentPlaylist
                                else -> null
                            },
                            onClick = { if (ok) state.open(vm, scope, p) },
                        )
                    }
                }
            }

            group == null -> {
                val groups = remember(content) { vm.groupsForSecond(content) }
                LazyColumn {
                    items(groups, key = { it.id }) { g ->
                        MenuRow(title = g.name, value = null, chevron = true, onClick = { state.group = g })
                    }
                }
            }

            else -> {
                val channels = remember(content, group) { vm.channelsForSecond(content, group.id) }
                if (channels.isEmpty()) Note(s.noChannelsInGroup)
                LazyColumn {
                    items(channels, key = { it.uid }) { ch ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(content, ch, group.id) }.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (prefs.showLogos) {
                                ChannelLogo(ch, 34.dp)
                                Spacer(Modifier.width(12.dp))
                            }
                            Text(
                                if (prefs.showNumbers) "${ch.num}  ${ch.name}" else ch.name,
                                color = ItvColors.Text,
                                fontSize = 16.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
