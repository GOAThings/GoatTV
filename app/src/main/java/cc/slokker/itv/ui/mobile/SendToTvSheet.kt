package cc.slokker.itv.ui.mobile

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.slokker.itv.data.SendToTv
import cc.slokker.itv.data.TvFinder
import cc.slokker.itv.data.deviceName
import cc.slokker.itv.ui.AppViewModel
import cc.slokker.itv.ui.glass.ItvIcons
import cc.slokker.itv.ui.i18n.LocalStrings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * "Enviar para a TV": as TVs com a GoatTV na rede de casa (a última usada primeiro). Um toque manda o canal a dar
 * (só ids e nome, ver [SendToTv]); a dar na TV, [onSent] para o canal no telemóvel (larga a ligação ao fornecedor).
 */
@Composable
internal fun SendToTvSection(vm: AppViewModel, onSent: () -> Unit, onClose: () -> Unit) {
    val s = LocalStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val finder = remember { TvFinder(context.applicationContext) }
    DisposableEffect(finder) {
        finder.start(scope)
        onDispose { finder.stop() }
    }
    val tvs by finder.tvs.collectAsStateWithLifecycle()
    var searching by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(SEARCH_MS)
        searching = false
    }
    val store = remember { context.getSharedPreferences(STORE, Context.MODE_PRIVATE) }
    var last by remember { mutableStateOf(store.getString(LAST, null)) }
    var sending by remember { mutableStateOf<String?>(null) }
    val ordered = SendToTv.order(tvs.map { it.name }, last).map { tvs[it] }
    Section(s.sendToTv) {
        ordered.forEach { tv ->
            MenuRow(tv.name, if (sending == tv.host) "…" else null, icon = ItvIcons.Tv) {
                val channel = vm.playing.value
                val content = vm.content.value
                if (sending != null || channel == null || content == null) return@MenuRow
                sending = tv.host
                val body = SendToTv.body(content.playlist.id, channel.key, channel.name, vm.prefs.value.proLineup.orEmpty(), deviceName(context))
                scope.launch {
                    val (status, _) = TvFinder.send(tv, body)
                    sending = null
                    when (SendToTv.outcome(status)) {
                        SendToTv.PLAYING -> {
                            last = tv.name
                            store.edit().putString(LAST, tv.name).apply()
                            vm.showToast(s.sendPlaying(tv.name))
                            onSent()
                            onClose()
                        }
                        SendToTv.MISSING -> vm.showToast(s.sendMissing(tv.name))
                        SendToTv.CLOSED -> vm.showToast(s.sendClosed(tv.name))
                        else -> vm.showToast(s.sendFailed(tv.name))
                    }
                }
            }
        }
        // A procurar enquanto não aparece nada (e mais um pouco); sem TVs, o que verificar.
        if (searching) Note(s.sendSearching) else if (ordered.isEmpty()) Note(s.sendEmpty)
    }
}

/** O mDNS pode levar uns segundos a responder numa rede com muitos aparelhos. */
private const val SEARCH_MS = 6_000L
private const val STORE = "send_to_tv"
private const val LAST = "last"
