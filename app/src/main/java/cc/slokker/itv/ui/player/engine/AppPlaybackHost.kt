package cc.slokker.itv.ui.player.engine

import androidx.media3.common.PlaybackException
import cc.slokker.itv.data.Channel
import cc.slokker.itv.data.Languages
import cc.slokker.itv.data.PlaylistContent
import cc.slokker.itv.data.Prefs
import cc.slokker.itv.ui.AppViewModel
import cc.slokker.itv.ui.common.ErrorInfo
import cc.slokker.itv.ui.common.explainPlaybackError
import cc.slokker.itv.ui.common.isAccountBusy
import cc.slokker.itv.ui.i18n.I18n
import cc.slokker.itv.ui.i18n.Strings
import cc.slokker.itv.data.at

/** O canal principal (TV e telemóvel): as definições, o catch-up e o formato da conta são os do [vm]. */
internal class AppPlaybackHost(
    private val vm: AppViewModel,
    private val latestPrefs: () -> Prefs,
    private val latestData: () -> PlaylistContent,
) : PlaybackHost {
    override val prefs: Prefs get() = latestPrefs()
    override val streamsDisabled: Boolean get() = vm.streamsDisabled
    override fun onFormatWorked(format: String) = vm.onFormatWorked(format)
    override fun exitCatchup() = vm.exitCatchup()
    override fun seekCatchup(positionMs: Long) = vm.seekCatchup(positionMs)
    override fun onVlcFallback() = vm.showToast(I18n.s.usingVlc)
    override val appLanguage: String? get() = Languages.code(I18n.s.locale.language)
    override fun programmeStartAt(channel: Channel, wallMs: Long): Long? = vm.guideEntries(channel).at(wallMs)?.startMs
    override fun recordingUrl(channel: Channel): String? = vm.recordingRingUrl(channel)

    override suspend fun explain(error: PlaybackException, request: PlaybackRequest, format: String?): ErrorInfo {
        val data = latestData()
        // Pergunta ao servidor se a conta está mesmo ocupada antes de o dizer.
        val connections = if (error.errorCode in 2000..2999) vm.xtreamConnections() else null
        return if (!request.live && !isAccountBusy(data, connections)) {
            I18n.s.errCatchup.copy(detail = I18n.s.technical(error.errorCodeName))
        } else {
            explainPlaybackError(I18n.s, error, data, connections, latestPrefs(), format)
        }
    }
}

/**
 * O segundo canal: outra lista, sem catch-up nem cartão de erro (a etiqueta diz "sem sinal"). Não toca no formato
 * da conta principal; as definições lêem-se a cada tentativa (o User-Agent novo vale já na seguinte).
 */
internal class SecondPlaybackHost(private val vm: AppViewModel, private val latestPrefs: () -> Prefs) : PlaybackHost {
    override val prefs: Prefs get() = latestPrefs()
    override val streamsDisabled: Boolean get() = vm.streamsDisabled
    override fun onFormatWorked(format: String) = Unit
    override fun exitCatchup() = Unit
    override fun seekCatchup(positionMs: Long) = Unit
    override fun onVlcFallback() = Unit
    override val appLanguage: String? get() = Languages.code(I18n.s.locale.language)
    override fun programmeStartAt(channel: Channel, wallMs: Long): Long? = null
    override suspend fun explain(error: PlaybackException, request: PlaybackRequest, format: String?): ErrorInfo? = null
}

/** O título do cartão de erro: a explicação, ou "não foi possível abrir" enquanto se analisa. */
internal fun PlaybackFailure.titleCard(s: Strings): ErrorInfo = explained ?: ErrorInfo(s.cantOpenChannel.removeSuffix("."))

/** A linha de estado do cartão de erro: a analisar, a próxima tentativa, ou nada (OK tenta outra vez). */
internal fun PlaybackFailure.status(s: Strings): String? = when {
    analysing -> s.analysingError
    retry > 0 -> s.retryIn(retry, maxRetries, retrySeconds)
    else -> null
}
