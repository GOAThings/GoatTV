package cc.slokker.itv.ui.mobile

import cc.slokker.itv.data.Prefs

/** Os separadores da barra de baixo. */
internal enum class PhoneTab { Tv, Guide, Favorites, Search, Menu }

/** O que o Voltar faz no ecrã principal do telemóvel (ver [PhoneNav.back]), pela ordem em que se decide. */
internal enum class PhoneBack {
    /** Fecha a folha do áudio, das legendas ou do temporizador. */
    CLOSE_OPTIONS,
    /** Sai do ecrã inteiro (volta ao alto). */
    LEAVE_FULLSCREEN,
    /** Fecha o guia do canal. */
    CLOSE_EPG,
    /** De outro separador, volta à TV. */
    TV_TAB,
    /** Da lista de um grupo (ou das gravações), volta ao ecrã inicial. */
    LEAVE_GROUP,
    /** Do canal, volta ao ecrã inicial (o canal continua no mini-leitor). */
    HOME,
}

/**
 * Onde se está no telemóvel: o separador, o ecrã inicial ou o canal em grande, o grupo aberto no início e se o
 * canal foi fechado. Só regras, sem Compose: cada toque é uma função que devolve o estado seguinte.
 * As janelas por cima (folhas, PIN) e a rotação ficam no ecrã, que diz ao [back] quais estão abertas.
 */
internal data class PhoneNav(
    val tab: PhoneTab = PhoneTab.Tv,
    /** No separador TV: o ecrã inicial (recentes e grupos) em vez do canal em grande. */
    val home: Boolean = true,
    /** O canal foi fechado (✕ no mini-leitor, ou ainda nenhum escolhido): não há vídeo nem mini-leitor. */
    val stopped: Boolean = true,
    /** O grupo aberto a partir do ecrã inicial (ou da procura). */
    val homeGroup: String? = null,
    /** As gravações abertas a partir do ecrã inicial (no lugar dele, como um grupo). */
    val recordings: Boolean = false,
) {
    /** Nas Definições o canal está fechado. */
    val settings: Boolean get() = tab == PhoneTab.Menu

    /** Há um canal a dar (em grande, em ecrã inteiro ou no mini-leitor). */
    val video: Boolean get() = !settings && !stopped

    /** O canal em grande, por cima da lista do separador TV. */
    val big: Boolean get() = video && tab == PhoneTab.Tv && !home

    /** Ecrã inteiro: na janela pequena, ou com o canal em grande e o telemóvel deitado. */
    fun fullscreen(pip: Boolean, rotated: Boolean): Boolean = video && (pip || (big && rotated))

    /** O mini-leitor por cima dos separadores: a dar, mas fora do canal em grande. */
    fun mini(fullscreen: Boolean): Boolean = video && !big && !fullscreen

    /** O que o Voltar faz agora; null quando não há nada a fechar (o sistema trata dele: sai da app). */
    fun back(optionsOpen: Boolean, fullscreen: Boolean, epgOpen: Boolean): PhoneBack? = when {
        optionsOpen -> PhoneBack.CLOSE_OPTIONS
        fullscreen -> PhoneBack.LEAVE_FULLSCREEN
        epgOpen -> PhoneBack.CLOSE_EPG
        tab != PhoneTab.Tv -> PhoneBack.TV_TAB
        homeGroup != null || recordings -> PhoneBack.LEAVE_GROUP
        !home -> PhoneBack.HOME
        else -> null
    }

    /** O estado depois de [back] (as folhas e a rotação mudam-se no ecrã). */
    fun after(back: PhoneBack): PhoneNav = when (back) {
        PhoneBack.TV_TAB -> copy(tab = PhoneTab.Tv)
        PhoneBack.LEAVE_GROUP -> copy(homeGroup = null, recordings = false)
        PhoneBack.HOME -> copy(home = true)
        PhoneBack.CLOSE_OPTIONS, PhoneBack.LEAVE_FULLSCREEN, PhoneBack.CLOSE_EPG -> this
    }

    /** Abriu-se um canal a partir de uma lista, do guia, da procura, do catch-up ou de um lembrete: vê-se em grande. */
    fun played(): PhoneNav = copy(stopped = false, home = false, homeGroup = null, recordings = false, tab = PhoneTab.Tv)

    /** Toque num separador; "TV" outra vez, já na TV, volta ao ecrã inicial. */
    fun tabTapped(tapped: PhoneTab): PhoneNav =
        if (tapped == PhoneTab.Tv && tab == PhoneTab.Tv) copy(tab = tapped, home = true, homeGroup = null, recordings = false) else copy(tab = tapped)

    /** Toque no mini-leitor: o canal em grande. */
    fun openPlayer(): PhoneNav = copy(tab = PhoneTab.Tv, home = false)

    /** ✕ no mini-leitor. */
    fun closePlayer(): PhoneNav = copy(stopped = true)

    fun openGroup(id: String): PhoneNav = copy(homeGroup = id)

    fun closeGroup(): PhoneNav = copy(homeGroup = null)

    /** As gravações, a partir do ecrã inicial. */
    fun openRecordings(): PhoneNav = copy(tab = PhoneTab.Tv, home = true, homeGroup = null, recordings = true)

    fun closeRecordings(): PhoneNav = copy(recordings = false)

    /** Um grupo escolhido na procura abre-se no ecrã inicial da TV. */
    fun groupFromSearch(id: String): PhoneNav = copy(tab = PhoneTab.Tv, home = true, homeGroup = id)

    /** Mudou o canal a dar sem ser por uma lista (ex.: trocar de lista nas Definições abre outro): volta à TV para o ver. */
    fun channelChanged(): PhoneNav = if (settings) copy(tab = PhoneTab.Tv) else this

    /** Para o rememberSaveable (sobrevive à rotação e à app ir para trás). */
    fun toList(): List<Any?> = listOf(tab.name, home, stopped, homeGroup, recordings)

    companion object {
        /** Ao abrir: o ecrã inicial sem nada a dar, ou já o último canal em grande (Definições → no arranque). */
        fun start(phoneStartup: String): PhoneNav {
            val home = phoneStartup == Prefs.PhoneStartup.HOME
            return PhoneNav(home = home, stopped = home)
        }

        fun fromList(list: List<Any?>): PhoneNav =
            PhoneNav(PhoneTab.valueOf(list[0] as String), list[1] as Boolean, list[2] as Boolean, list[3] as String?, list.getOrNull(4) as? Boolean ?: false)
    }
}
