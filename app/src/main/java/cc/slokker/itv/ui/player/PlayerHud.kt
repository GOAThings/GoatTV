package cc.slokker.itv.ui.player

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import cc.slokker.itv.data.Channel
import cc.slokker.itv.data.EpgEntry
import cc.slokker.itv.data.Prefs
import cc.slokker.itv.data.StreamFormat
import cc.slokker.itv.data.minutesLeft
import cc.slokker.itv.ui.AppViewModel
import cc.slokker.itv.ui.glass.GlassChipButton
import cc.slokker.itv.ui.glass.ItvIcons
import cc.slokker.itv.ui.glass.capsuleBar
import cc.slokker.itv.ui.i18n.LocalStrings
import cc.slokker.itv.ui.i18n.Strings
import cc.slokker.itv.ui.player.engine.ChannelPlayback

/** Os botões da barra completa que mexem no ecrã do player (abrir um painel, trocar os canais...). */
internal enum class HudAction { AUDIO, SUBTITLES, PAUSE_LIVE, RECORD, SECOND_CHANNEL, SWAP, LAYOUT, SEARCH, GUIDE, RECORDINGS, EPG_CHANNEL, BACKUP, SLEEP }

/**
 * Os botões da barra completa (OK no vídeo). O que é só pedir ao ViewModel (imagem, favorito, ver do início,
 * fechar o segundo canal) faz-se aqui; o que abre ou troca coisas no ecrã vai para [onAction].
 * [live]: o programa a dar; [pauseLive]: há pausa do direto; [second]: há segundo canal; [recording]: este canal está
 * a gravar (o botão pára), senão "Gravar" aparece com a pausa do direto (grava-se do anel dela).
 */
@Composable
internal fun HudButtons(
    vm: AppViewModel,
    channel: Channel,
    live: EpgEntry?,
    aspect: String,
    isFavorite: Boolean,
    sleepAt: Long?,
    pauseLive: Boolean,
    recording: Boolean,
    second: Boolean,
    /** A lista tem reserva e é direto: o botão "Canal de reserva". */
    backup: Boolean,
    dualLayout: DualLayout,
    firstFocus: FocusRequester,
    onAction: (HudAction) -> Unit,
) {
    val s = LocalStrings.current
    // ← → andam entre os botões (sem abrir a lista nem saltar de canal).
    val focusManager = LocalFocusManager.current
    Row(
        Modifier
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key.nativeKeyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> { focusManager.moveFocus(FocusDirection.Left); true }
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> { focusManager.moveFocus(FocusDirection.Right); true }
                    else -> false
                }
            }
            .capsuleBar()
            .horizontalScroll(rememberScrollState())
            // Margem para o botão com foco (cresce um pouco) não ficar cortado nas pontas.
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // Como num player: faixas e imagem primeiro, depois o resto.
        GlassChipButton(ItvIcons.Audio, s.audio, { onAction(HudAction.AUDIO) }, Modifier.focusRequester(firstFocus))
        GlassChipButton(ItvIcons.Subtitles, s.subtitles, { onAction(HudAction.SUBTITLES) })
        GlassChipButton(
            ItvIcons.Aspect,
            when (aspect) {
                Prefs.Aspect.ZOOM -> s.aspectZoom
                Prefs.Aspect.FILL -> s.aspectFill
                else -> s.aspectFit
            },
            {
                val next = when (aspect) {
                    Prefs.Aspect.FIT -> Prefs.Aspect.ZOOM
                    Prefs.Aspect.ZOOM -> Prefs.Aspect.FILL
                    else -> Prefs.Aspect.FIT
                }
                vm.setPref(Prefs.Key.ASPECT, next)
            },
        )
        // Pausar o direto (também com Play/Pausa no comando, para quem o tem).
        if (pauseLive) GlassChipButton(ItvIcons.Pause, s.pauseLive, { onAction(HudAction.PAUSE_LIVE) })
        // Um só botão (gravar ⇄ parar), para o foco ficar nele quando muda.
        if (recording || pauseLive && !second) {
            GlassChipButton(if (recording) ItvIcons.Stop else ItvIcons.Record, if (recording) s.stopRecording else s.record, { onAction(HudAction.RECORD) })
        }
        if (!second) {
            GlassChipButton(ItvIcons.Pip, s.secondChannel, { onAction(HudAction.SECOND_CHANNEL) })
        } else {
            GlassChipButton(ItvIcons.SwapPlaylist, s.swapChannels, { onAction(HudAction.SWAP) })
            GlassChipButton(
                if (dualLayout == DualLayout.PIP) ItvIcons.Split else ItvIcons.Pip,
                if (dualLayout == DualLayout.PIP) s.splitScreen else s.pictureInPicture,
                { onAction(HudAction.LAYOUT) },
            )
            GlassChipButton(ItvIcons.Pip, s.changeSecond, { onAction(HudAction.SECOND_CHANNEL) })
            GlassChipButton(ItvIcons.Close, s.closeSecond, { vm.closeSecond() })
        }
        GlassChipButton(ItvIcons.Search, s.search, { onAction(HudAction.SEARCH) })
        GlassChipButton(ItvIcons.Guide, s.tvGuide, { onAction(HudAction.GUIDE) })
        GlassChipButton(ItvIcons.Recordings, s.recordings, { onAction(HudAction.RECORDINGS) })
        // Trocar o guia deste canal por outro (só com conta; sem conta explica como ligar).
        GlassChipButton(ItvIcons.Guide, s.epgGuideChannel, {
            if (vm.epgPickerAvailable) {
                onAction(HudAction.EPG_CHANNEL)
            } else {
                vm.showToast(s.epgNeedsAccount)
            }
        })
        if (backup) GlassChipButton(ItvIcons.SwapPlaylist, s.backupChannel, { onAction(HudAction.BACKUP) })
        GlassChipButton(
            if (isFavorite) ItvIcons.Star else ItvIcons.StarOutline,
            if (isFavorite) s.removeFavorite else s.addFavorite,
            { vm.toggleFavorite(channel) },
        )
        if (live != null && vm.canCatchup(channel, live)) {
            GlassChipButton(ItvIcons.Replay, s.startOver, { vm.playCatchup(channel, live) })
        }
        GlassChipButton(
            ItvIcons.Sleep,
            sleepAt?.let { s.sleepRemaining(minutesLeft(it, System.currentTimeMillis())) } ?: s.sleepTimer,
            { onAction(HudAction.SLEEP) },
        )
    }
}

/**
 * O que o aviso de paragens oferece: um buffer maior, o outro formato (quando é mesmo outro endereço), os
 * detalhes da ligação e não voltar a avisar. Cada ação fecha o aviso ([onDismiss]).
 */
internal fun netHintActions(
    s: Strings,
    vm: AppViewModel,
    prefs: Prefs,
    playback: ChannelPlayback,
    catchup: Boolean,
    onDismiss: () -> Unit,
    onDetails: () -> Unit,
): List<NetHintAction> = buildList {
    // Buffer maior (o player recomeça com ele).
    val bigger = when (prefs.buffer) {
        Prefs.Buffer.LIVE -> Prefs.Buffer.SMALL
        Prefs.Buffer.SMALL -> Prefs.Buffer.NORMAL
        Prefs.Buffer.NORMAL -> Prefs.Buffer.LARGE
        else -> null
    }
    if (bigger != null) add(NetHintAction(ItvIcons.Buffer, s.netBiggerBuffer) { onDismiss(); vm.setPref(Prefs.Key.BUFFER, bigger) })
    // O outro formato (TS ⇄ HLS), quando é mesmo outro endereço (Xtream, ou .ts ⇄ .m3u8); num link sem
    // extensão o "outro" é o mesmo URL, que já falhou nesse formato.
    val candidates = playback.candidates
    val playingNow = playback.currentCandidate
    val other = candidates.indices.firstOrNull { i ->
        val c = candidates[i]
        i != playback.attempt && c.format != playingNow?.format && c.url != playingNow?.url
    }
    if (other != null && !catchup) {
        val format = candidates[other].format
        add(NetHintAction(ItvIcons.SwapPlaylist, s.netTryFormat(if (format == StreamFormat.HLS) "HLS" else "TS")) {
            onDismiss()
            vm.setStreamFormat(format)
            playback.switchTo(other)
        })
    }
    add(NetHintAction(ItvIcons.Info, s.netDetails) { onDismiss(); onDetails() })
    add(NetHintAction(ItvIcons.Close, s.netDontShow) { onDismiss(); vm.setPref(Prefs.Key.NET_WARNINGS, false) })
}
