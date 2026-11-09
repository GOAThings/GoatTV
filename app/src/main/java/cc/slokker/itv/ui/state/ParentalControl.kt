package cc.slokker.itv.ui.state

import cc.slokker.itv.data.PinAttempts
import cc.slokker.itv.data.Prefs
import cc.slokker.itv.ui.i18n.I18n
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * O controlo parental desta sessão: conferir o PIN (com as tentativas erradas, que contam em todos os sítios que o
 * pedem), os grupos desbloqueados até a app fechar, as Definições desbloqueadas, e mudar o PIN e os grupos bloqueados
 * (que ficam nas definições da conta, via [setPref]). [onLocksChanged] avisa quem mostra canais fora da sessão (o ecrã
 * inicial).
 */
class ParentalControl(
    private val prefs: StateFlow<Prefs>,
    private val setPref: (String, Any?) -> Unit,
    attempts: () -> PinAttempts,
    private val onLocksChanged: () -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** As tentativas erradas contam em todos os sítios que pedem o PIN, e continuam depois de fechar a app. */
    private val pinAttempts by lazy(attempts)

    /** Grupos bloqueados já desbloqueados com o PIN nesta sessão ("playlistId:groupId"). */
    private val _unlocked = MutableStateFlow<Set<String>>(emptySet())
    val unlocked: StateFlow<Set<String>> = _unlocked.asStateFlow()

    /** As Definições já foram desbloqueadas com o PIN nesta sessão. */
    private var settingsUnlocked = false

    fun checkPin(pin: String): Boolean {
        val hash = prefs.value.pinHash ?: return true
        return pinAttempts.check(now(), Prefs.hashPin(pin) == hash)
    }

    /** O que dizer quando o PIN não entrou: [wrong], ou quanto falta para se poder tentar outra vez. */
    fun pinRejected(wrong: String): String {
        val wait = pinAttempts.waitMs(now())
        return when {
            wait <= 0 -> wrong
            wait < 60_000 -> I18n.s.pinWait("${(wait + 999) / 1000} s")
            else -> I18n.s.pinWait("${(wait + 59_999) / 60_000} min")
        }
    }

    /** Terminar sessão pede o PIN, quando há um (é por aí que se saía para as listas desta TV sem ele). */
    fun logoutNeedsPin(): Boolean = prefs.value.pinHash != null

    /** Confere o PIN; se estiver certo, desbloqueia [groupId] de [playlistId] até a app fechar. */
    fun unlockGroup(playlistId: String?, groupId: String, pin: String): Boolean {
        if (prefs.value.pinHash == null || !checkPin(pin)) return false
        playlistId ?: return true
        _unlocked.value = _unlocked.value + "$playlistId:$groupId"
        return true
    }

    /** Define (ou remove, com null) o PIN parental. Sem PIN não há grupos bloqueados. */
    fun setPin(pin: String?) {
        setPref(Prefs.Key.PIN_HASH, pin?.let { Prefs.hashPin(it) })
        if (pin == null) setPref(Prefs.Key.LOCKED_GROUPS, emptyList<String>())
        _unlocked.value = emptySet()
        pinAttempts.reset()
        onLocksChanged()
    }

    fun toggleGroupLock(playlistId: String, groupId: String) {
        val key = "$playlistId:$groupId"
        val current = prefs.value.lockedGroups
        setPref(Prefs.Key.LOCKED_GROUPS, (if (key in current) current - key else current + key).toList())
        onLocksChanged()
    }

    fun settingsNeedPin(): Boolean = prefs.value.settingsPin && prefs.value.pinHash != null && !settingsUnlocked

    fun unlockSettings(pin: String): Boolean {
        if (!checkPin(pin)) return false
        settingsUnlocked = true
        return true
    }

    /** Outro perfil ou outra sessão: tudo volta a estar bloqueado. */
    fun reset() {
        _unlocked.value = emptySet()
        settingsUnlocked = false
    }
}
