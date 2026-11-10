package cc.slokker.itv.ui.i18n

import androidx.core.os.ConfigurationCompat
import android.content.Context
import android.content.res.Configuration
import android.text.TextUtils
import android.view.View
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.os.LocaleListCompat
import cc.slokker.itv.R
import cc.slokker.itv.ui.common.ErrorInfo
import java.util.Locale

/**
 * Os textos da app. Estão em `strings_ui.xml`: o inglês em `res/values` e cada língua na sua pasta (`values-fr`…)
 * (as mesmas 15 do site). A língua é a escolhida nas Definições (por app, com o AppCompat) ou, em
 * "Automático", a do sistema; uma que não exista cai no inglês.
 */
class Strings(context: Context) {
    private val res = context.resources
    val locale: Locale = ConfigurationCompat.getLocales(res.configuration)[0] ?: Locale.getDefault()

    /** Línguas da direita para a esquerda (árabe): o Compose espelha o ecrã sozinho. */
    val rtl: Boolean = TextUtils.getLayoutDirectionFromLocale(locale) == View.LAYOUT_DIRECTION_RTL

    private fun s(id: Int): String = res.getString(id)
    private fun s(id: Int, vararg args: Any?): String = res.getString(id, *args)
    private fun p(id: Int, n: Int, vararg args: Any?): String = res.getQuantityString(id, n, *args)

    fun activateExpiresText(min: Long, sec: Long) = s(R.string.activate_expires_text, min, sec)
    fun streamsLimit(n: Int) = p(R.plurals.streams_limit, n, n)
    fun hiddenGroups(n: Int) = p(R.plurals.hidden_groups, n, n)
    private fun inParens(codec: String?) = codec?.let { " ($it)" }.orEmpty()

    // ---- Geral ----
    val settings get() = s(R.string.settings)
    val welcomeLocalPhone get() = s(R.string.welcome_local_phone)
    val welcomeLocalPhoneSub get() = s(R.string.welcome_local_phone_sub)
    val save get() = s(R.string.save)
    val automaticWord get() = s(R.string.automatic_word)
    val videoFormatWord get() = s(R.string.video_format_word)
    val retry get() = s(R.string.retry)
    val logout get() = s(R.string.logout)
    val logoutThisTv get() = s(R.string.logout_this_tv)
    val pressAgainToConfirm get() = s(R.string.press_again_to_confirm)
    val favorites get() = s(R.string.favorites)
    val favoritesStar get() = s(R.string.favorites_star)
    val all get() = s(R.string.all)
    val others get() = s(R.string.others)
    val groups get() = s(R.string.groups)
    val groupsHint get() = s(R.string.groups_hint)
    fun channelsCount(n: Int, playlist: String) = s(R.string.channels_count, n, playlist)
    fun channelName(id: String) = s(R.string.channel_name, id)

    // ---- Ativação ----
    val activateTitle get() = s(R.string.activate_title)
    val activateGoTo get() = s(R.string.activate_go_to)
    val activateEnterCode get() = s(R.string.activate_enter_code)
    fun activateExpires(min: Long, sec: Long) = activateExpiresText(min, sec)
    val activateRequesting get() = s(R.string.activate_requesting)
    val activateQr get() = s(R.string.activate_qr)
    val activateQrDescription get() = s(R.string.activate_qr_description)
    fun activateServerError(code: Int) = s(R.string.activate_server_error, code)
    val activateOffline get() = s(R.string.activate_offline)
    val activateBadResponse get() = s(R.string.activate_bad_response)

    // ---- Carregar ----
    val loadingSession get() = s(R.string.loading_session)
    val loadingPlaylists get() = s(R.string.loading_playlists)
    fun loadingPlaylist(name: String) = s(R.string.loading_playlist, name)
    fun playlistLoadFailed(name: String, reason: String) =
        s(R.string.playlist_load_failed, name, reason)
    val sessionEnded get() = s(R.string.session_ended)
    val connectAccountTitle get() = s(R.string.connect_account_title)
    val connectAccountBody get() = s(R.string.connect_account_body)
    val copyCode get() = s(R.string.copy_code)
    val copied get() = s(R.string.copied)
    val signInToApprove get() = s(R.string.sign_in_to_approve)

    // ---- Listas (erros das fontes) ----
    val m3uNoUrl get() = s(R.string.m3u_no_url)
    val m3uBadUrl get() = s(R.string.m3u_bad_url)
    fun m3uHttp(code: Int) = s(R.string.m3u_http, code)
    val xtreamBadLogin get() = s(R.string.xtream_bad_login)
    val stalkerBadLogin get() = s(R.string.stalker_bad_login)
    val stalkerSub get() = s(R.string.stalker_sub)
    val stalkerMacHint get() = s(R.string.stalker_mac_hint)
    val portalUrl get() = s(R.string.portal_url)
    val macAddress get() = s(R.string.mac_address)
    val missingPortal get() = s(R.string.missing_portal)
    val invalidMac get() = s(R.string.invalid_mac)
    fun xtreamInactive(status: String) = s(R.string.xtream_inactive, status)
    fun xtreamHttp(code: Int) = s(R.string.xtream_http, code)
    val xtreamBadResponse get() = s(R.string.xtream_bad_response)
    val xtreamBadServer get() = s(R.string.xtream_bad_server)

    // ---- Guia, pesquisa, catch-up, temporizador, lembretes ----
    val recents get() = s(R.string.recents)
    val tvGuide get() = s(R.string.tv_guide)
    val search get() = s(R.string.search)
    val searchHint get() = s(R.string.search_hint)
    val searchMinChars get() = s(R.string.search_min_chars)
    val searchNoResults get() = s(R.string.search_no_results)
    fun nowOn(channel: String) = s(R.string.now_on, channel)
    val addFavorite get() = s(R.string.add_favorite)
    val removeFavorite get() = s(R.string.remove_favorite)
    val startOver get() = s(R.string.start_over)
    val channelGone get() = s(R.string.channel_gone)
    val close get() = s(R.string.close)
    val secondChannel get() = s(R.string.second_channel)
    val secondPickPlaylist get() = s(R.string.second_pick_playlist)
    val secondPickGroup get() = s(R.string.second_pick_group)
    val secondPickChannel get() = s(R.string.second_pick_channel)
    val secondOneConnection get() = s(R.string.second_one_connection)
    val secondNeedsOther get() = s(R.string.second_needs_other)
    val currentPlaylist get() = s(R.string.current_playlist)
    val swapChannels get() = s(R.string.swap_channels)
    val splitScreen get() = s(R.string.split_screen)
    val pictureInPicture get() = s(R.string.picture_in_picture)
    val settingsPictureSound get() = s(R.string.settings_picture_sound)
    val settingsConnection get() = s(R.string.settings_connection)
    val settingsAdvanced get() = s(R.string.settings_advanced)
    val settingsAppearance get() = s(R.string.settings_appearance)
    val settingOn get() = s(R.string.setting_on)
    val settingOff get() = s(R.string.setting_off)
    val tabGuide get() = s(R.string.tab_guide)
    val guideNow get() = s(R.string.guide_now)
    val playerLive get() = s(R.string.player_live)
    val playerGoLive get() = s(R.string.player_go_live)
    val changeSecond get() = s(R.string.change_second)
    val closeSecond get() = s(R.string.close_second)
    val noSignal get() = s(R.string.no_signal)
    val loadingShort get() = s(R.string.loading_short)
    val secondClosedSameAccount get() = s(R.string.second_closed_same_account)
    fun updateAvailable(v: String) = s(R.string.update_available, v)
    fun updateTo(v: String) = s(R.string.update_to, v)
    fun downloading(p: Int) = s(R.string.downloading, p)
    val installing get() = s(R.string.installing)
    val updateReadyLabel get() = s(R.string.update_ready_label)
    fun updateWhatsNew(v: String) = s(R.string.update_whats_new, v)
    val updateHoldOk get() = s(R.string.update_hold_ok)
    val updateNow get() = s(R.string.update_now)
    val updateLater get() = s(R.string.update_later)

    // ---- Gravações ----
    val recordings get() = s(R.string.recordings)
    val record get() = s(R.string.record)
    val recordSeries get() = s(R.string.record_series)
    val stopRecording get() = s(R.string.stop_recording)
    val cancelRecording get() = s(R.string.cancel_recording)
    val cancelSeries get() = s(R.string.cancel_series)
    val recBadge get() = s(R.string.rec_badge)
    val recordingPill get() = s(R.string.recording_pill)
    fun recordingNow(title: String) = s(R.string.recording_now, title)
    fun recordingSaved(title: String) = s(R.string.recording_saved, title)
    val recordingStoppedSpace get() = s(R.string.recording_stopped_space)
    val recordingStoppedConnection get() = s(R.string.recording_stopped_connection)
    val recordingNoSpace get() = s(R.string.recording_no_space)
    val recordingNotRecordable get() = s(R.string.recording_not_recordable)
    val recordingOtherChannel get() = s(R.string.recording_other_channel)
    fun recordingSwitchTitle(title: String) = s(R.string.recording_switch_title, title)
    val recordingSwitchText get() = s(R.string.recording_switch_text)
    val recordingStopAndSwitch get() = s(R.string.recording_stop_and_switch)
    val recordingKeep get() = s(R.string.recording_keep)
    fun recordingSoon(channel: String) = s(R.string.recording_soon, channel)
    fun recordingSwitchTo(channel: String) = s(R.string.recording_switch_to, channel)
    val recordingSkip get() = s(R.string.recording_skip)
    fun recordingSwitchingIn(seconds: Int) = s(R.string.recording_switching_in, seconds)
    fun recordingScheduled(title: String) = s(R.string.recording_scheduled, title)
    fun recordingSeriesScheduled(title: String) = s(R.string.recording_series_scheduled, title)
    val recordingCancelled get() = s(R.string.recording_cancelled)
    fun recordingOverlap(title: String) = s(R.string.recording_overlap, title)
    val recordingHlsOnly get() = s(R.string.recording_hls_only)
    fun recordingSkipped(title: String) = s(R.string.recording_skipped, title)
    val recordingsEmpty get() = s(R.string.recordings_empty)
    val recordingsScheduled get() = s(R.string.recordings_scheduled)
    val recordingsRecorded get() = s(R.string.recordings_recorded)
    fun recordingsFree(size: String) = s(R.string.recordings_free, size)
    val recordingStateStopped get() = s(R.string.recording_state_stopped)
    val recordingStateFailed get() = s(R.string.recording_state_failed)
    val recordingDeleted get() = s(R.string.recording_deleted)
    val recordingDelete get() = s(R.string.recording_delete)
    val recordingPaddingBefore get() = s(R.string.recording_padding_before)
    val recordingPaddingAfter get() = s(R.string.recording_padding_after)
    val recordingPaddingSub get() = s(R.string.recording_padding_sub)
    val recordingExactAlarm get() = s(R.string.recording_exact_alarm)
    val recordingAllow get() = s(R.string.recording_allow)
    fun updateHidesIn(seconds: Int) = s(R.string.update_hides_in, seconds)
    val updateFailedTitle get() = s(R.string.update_failed_title)
    val checkUpdates get() = s(R.string.check_updates)
    val checking get() = s(R.string.checking)
    val upToDate get() = s(R.string.up_to_date)
    fun updateFailed(reason: String) = s(R.string.update_failed, reason)
    val sendReport get() = s(R.string.send_report)
    val sendReportSub get() = s(R.string.send_report_sub)
    val reportWhat get() = s(R.string.report_what)
    val reportSend get() = s(R.string.report_send)
    val reportSending get() = s(R.string.report_sending)
    fun reportSent(id: String) = s(R.string.report_sent, id)
    val reportTooMany get() = s(R.string.report_too_many)
    fun reportFailed(reason: String) = s(R.string.report_failed, reason)
    val allowInstalls get() = s(R.string.allow_installs)
    val updatesOnlyRelease get() = s(R.string.updates_only_release)
    fun onNow(until: String) = s(R.string.on_now, until)
    val uiScale get() = s(R.string.ui_scale)
    val noPreviousChannel get() = s(R.string.no_previous_channel)
    fun noChannelNumber(n: Int) = s(R.string.no_channel_number, n)
    val noGuideInfo get() = s(R.string.no_guide_info)
    val changeGroup get() = s(R.string.change_group)
    val watch get() = s(R.string.watch)
    val watchCatchup get() = s(R.string.watch_catchup)
    val noCatchup get() = s(R.string.no_catchup)
    val setReminder get() = s(R.string.set_reminder)
    val removeReminder get() = s(R.string.remove_reminder)
    val backToNow get() = s(R.string.back_to_now)
    val today get() = s(R.string.today)
    val yesterday get() = s(R.string.yesterday)
    val tomorrow get() = s(R.string.tomorrow)
    fun minutes(n: Int) = s(R.string.minutes, n)
    val catchupLabel get() = "CATCH-UP"
    val seekMinute get() = s(R.string.seek_minute)
    val pause get() = s(R.string.pause)
    val pauseLive get() = s(R.string.pause_live)
    val timeshiftSeek get() = s(R.string.timeshift_seek)
    fun timeshiftSaved(min: Int) = s(R.string.timeshift_saved, min)
    val resume get() = s(R.string.resume)
    val backKeyName get() = s(R.string.back_key_name)
    val backToLive get() = s(R.string.back_to_live)
    val sleepTimer get() = s(R.string.sleep_timer)
    fun sleepRemaining(min: Int) = s(R.string.sleep_remaining, min)
    fun minutesLeft(min: Int) = s(R.string.minutes_left, min)
    val homeScreen get() = s(R.string.home_screen)
    val homeContinue get() = s(R.string.home_continue)
    /** Bom dia / boa tarde / boa noite, pela [hour] (0–23). */
    fun greeting(hour: Int) = s(
        when (hour) {
            in 5..11 -> R.string.greeting_morning
            in 12..19 -> R.string.greeting_afternoon
            else -> R.string.greeting_evening
        },
    )
    fun groupChannels(count: Int) = if (count == 1) s(R.string.group_channels_one) else s(R.string.group_channels, count)
    val searchRecent get() = s(R.string.search_recent)
    val searchNow get() = s(R.string.search_now)
    val searchLater get() = s(R.string.search_later)
    val searchPast get() = s(R.string.search_past)
    val searchChannels get() = s(R.string.search_channels)
    val searchProgrammes get() = s(R.string.search_programmes)
    val favoritesReorder get() = s(R.string.favorites_reorder)
    val done get() = s(R.string.done)
    val favoritesHint get() = s(R.string.favorites_hint)
    val watchAgain get() = s(R.string.watch_again)

    // ---- Ligação do canal (página Ligação, gráfico e aviso no vídeo) ----
    val connection get() = s(R.string.connection)
    val netMeasuring get() = s(R.string.net_measuring)
    val netMeasuringSub get() = s(R.string.net_measuring_sub)
    val netGood get() = s(R.string.net_good)
    fun netGoodSub(seconds: Int) = s(R.string.net_good_sub, seconds)
    val netSlowWifi get() = s(R.string.net_slow_wifi)
    val netSlow get() = s(R.string.net_slow)
    fun netSlowSub(needs: String, getting: String) = s(R.string.net_slow_sub, needs, getting)
    val netGaps get() = s(R.string.net_gaps)
    val netGapsSub get() = s(R.string.net_gaps_sub)
    val netSlowReply get() = s(R.string.net_slow_reply)
    fun netSlowReplySub(seconds: String) = s(R.string.net_slow_reply_sub, seconds)
    val netVlc get() = s(R.string.net_vlc)
    val netVlcSub get() = s(R.string.net_vlc_sub)
    fun netBarGetting(mbps: String) = s(R.string.net_bar_getting, mbps)
    fun netBarNeeds(mbps: String) = s(R.string.net_bar_needs, mbps)
    val netSpeed get() = s(R.string.net_speed)
    val netSpeedSub get() = s(R.string.net_speed_sub)
    val netNeeds get() = s(R.string.net_needs)
    val netAhead get() = s(R.string.net_ahead)
    val netAheadSub get() = s(R.string.net_ahead_sub)
    val netReply get() = s(R.string.net_reply)
    val netReplySub get() = s(R.string.net_reply_sub)
    val netStops get() = s(R.string.net_stops)
    val netStopsSub get() = s(R.string.net_stops_sub)
    val netRightNow get() = s(R.string.net_right_now)
    val netThisTv get() = s(R.string.net_this_tv)
    val netTry get() = s(R.string.net_try)
    val netOptions get() = s(R.string.net_options)
    val netNetwork get() = s(R.string.net_network)
    val netCable get() = s(R.string.net_cable)
    fun netWifi(band: String, signal: Int) = s(R.string.net_wifi, band, signal)
    val netWifiPlain get() = s(R.string.net_wifi_plain)
    val netUnknown get() = s(R.string.net_unknown)
    val netUseCable get() = s(R.string.net_use_cable)
    val netUseCableSub get() = s(R.string.net_use_cable_sub)
    val netBufferSub get() = s(R.string.net_buffer_sub)
    val netFormatSub get() = s(R.string.net_format_sub)
    val netGraph get() = s(R.string.net_graph)
    val netGraphSub get() = s(R.string.net_graph_sub)
    val netWarnings get() = s(R.string.net_warnings)
    val netWarningsSub get() = s(R.string.net_warnings_sub)
    val netShortGood get() = s(R.string.net_short_good)
    val netShortSlow get() = s(R.string.net_short_slow)
    val netShortGaps get() = s(R.string.net_short_gaps)
    val netShortReply get() = s(R.string.net_short_reply)
    val netShortMeasuring get() = s(R.string.net_short_measuring)
    val netHintTitle get() = s(R.string.net_hint_title)
    fun netHintSlow(stops: Int, getting: String, needs: String) = s(R.string.net_hint_slow, stops, getting, needs)
    fun netHintGaps(stops: Int) = s(R.string.net_hint_gaps, stops)
    val netBiggerBuffer get() = s(R.string.net_bigger_buffer)
    fun netTryFormat(format: String) = s(R.string.net_try_format, format)
    val netDetails get() = s(R.string.net_details)
    val netDontShow get() = s(R.string.net_dont_show)
    val netGetting get() = s(R.string.net_getting)
    fun netMbit(mbps: String) = s(R.string.net_mbit, mbps)
    val netLastMinute get() = s(R.string.net_last_minute)
    val sectionWatching get() = s(R.string.section_watching)
    val sectionInterface get() = s(R.string.section_interface)
    val sectionLibrary get() = s(R.string.section_library)
    val sectionLanguageText get() = s(R.string.section_language_text)
    val sectionChannelList get() = s(R.string.section_channel_list)
    val sectionPanelsColour get() = s(R.string.section_panels_colour)
    fun sleepSet(min: Int) = s(R.string.sleep_set, min)
    val sleepOff get() = s(R.string.sleep_off)
    fun sleepSoon(sec: Int) = s(R.string.sleep_soon, sec)
    val keepWatching get() = s(R.string.keep_watching)
    val startingNow get() = s(R.string.starting_now)
    val dismiss get() = s(R.string.dismiss)
    fun reminderSet(title: String) = s(R.string.reminder_set, title)
    val reminderRemoved get() = s(R.string.reminder_removed)
    val backKey get() = s(R.string.back_key)
    val backOpensGuide get() = s(R.string.back_opens_guide)
    val backExits get() = s(R.string.back_exits)
    val protectSettings get() = s(R.string.protect_settings)
    val protectSettingsSub get() = s(R.string.protect_settings_sub)

    val errCatchup get() = ErrorInfo(
        s(R.string.err_catchup),
        s(R.string.err_catchup_2),
        listOf(
            s(R.string.err_catchup_3),
            s(R.string.err_catchup_4),
        ),
    )

    // ---- Erros explicados (o que foi, porquê, como resolver) ----
    val howToFix get() = s(R.string.how_to_fix)
    val analysingError get() = s(R.string.analysing_error)
    fun retryIn(n: Int, max: Int, seconds: Int) = s(R.string.retry_in, n, max, seconds)
    val changeChannel get() = s(R.string.change_channel)
    val channelListHint get() = s(R.string.channel_list_hint)
    fun technical(detail: String) = s(R.string.technical, detail)
    // Em árabe (da direita para a esquerda) o caminho lê-se ao contrário: a seta aponta para a esquerda.
    private fun path(vararg parts: String) = (listOf(settings) + parts).joinToString(if (rtl) " ← " else " → ")
    private fun editPath(name: String) = path(playlists, name, edit)

    fun errNoPlaylists() = ErrorInfo(
        s(R.string.err_no_playlists),
        s(R.string.err_no_playlists_2),
        listOf(
            s(R.string.err_no_playlists_3),
            s(R.string.err_no_playlists_4),
        ),
    )

    fun errBackend(code: Int) = ErrorInfo(
        s(R.string.err_backend),
        s(R.string.err_backend_2),
        listOf(
            s(R.string.err_backend_3),
            s(R.string.err_backend_4),
        ),
        technical("HTTP $code"),
    )

    fun errBackendOffline() = ErrorInfo(
        s(R.string.err_backend_offline),
        s(R.string.err_backend_offline_2),
        listOf(
            s(R.string.err_backend_offline_3),
            s(R.string.err_backend_offline_4),
            s(R.string.err_backend_offline_5),
        ),
    )

    fun errPlaylistBadLogin(name: String) = ErrorInfo(
        s(R.string.err_playlist_bad_login),
        s(R.string.err_playlist_bad_login_2, name),
        listOf(
            s(R.string.err_playlist_bad_login_3, editPath(name)),
            s(R.string.err_playlist_bad_login_4),
        ),
    )

    fun errPlaylistInactive(name: String, status: String) = ErrorInfo(
        s(R.string.err_playlist_inactive),
        s(R.string.err_playlist_inactive_2, name, status),
        listOf(
            s(R.string.err_playlist_inactive_3),
            s(R.string.err_playlist_inactive_4, path(playlists)),
        ),
    )

    fun errPlaylistHttp(name: String, code: Int) = when (code) {
        401, 403 -> ErrorInfo(
            s(R.string.err_playlist_http),
            s(R.string.err_playlist_http_2, name),
            listOf(
                s(R.string.err_playlist_http_3),
                s(R.string.err_playlist_http_4),
                s(R.string.err_playlist_http_5, path(general, userAgent)),
            ),
            technical("HTTP $code"),
        )
        404 -> ErrorInfo(
            s(R.string.err_playlist_http_6),
            s(R.string.err_playlist_http_7, name),
            listOf(
                s(R.string.err_playlist_http_8, editPath(name)),
                s(R.string.err_playlist_http_9),
            ),
            technical("HTTP 404"),
        )
        429 -> ErrorInfo(
            s(R.string.err_playlist_http_10),
            s(R.string.err_playlist_http_11, name),
            listOf(
                s(R.string.err_playlist_http_12),
                s(R.string.err_playlist_http_13),
            ),
            technical("HTTP 429"),
        )
        in 500..599 -> ErrorInfo(
            s(R.string.err_playlist_http_14),
            s(R.string.err_playlist_http_15, name),
            listOf(
                s(R.string.err_playlist_http_16),
                s(R.string.err_playlist_http_17),
            ),
            technical("HTTP $code"),
        )
        else -> ErrorInfo(
            s(R.string.err_playlist_http_18, name),
            s(R.string.err_playlist_http_19),
            listOf(
                s(R.string.err_playlist_http_20),
                s(R.string.err_playlist_http_21, editPath(name)),
            ),
            technical("HTTP $code"),
        )
    }

    fun errPlaylistBadResponse(name: String) = ErrorInfo(
        s(R.string.err_playlist_bad_response),
        s(R.string.err_playlist_bad_response_2, name),
        listOf(
            s(R.string.err_playlist_bad_response_3, editPath(name)),
            s(R.string.err_playlist_bad_response_4),
        ),
    )

    fun errPlaylistBadUrl(name: String) = ErrorInfo(
        s(R.string.err_playlist_bad_url),
        s(R.string.err_playlist_bad_url_2, name),
        listOf(s(R.string.err_playlist_bad_url_3, editPath(name))),
    )

    fun errPlaylistUnknownHost(name: String) = ErrorInfo(
        s(R.string.err_playlist_unknown_host),
        s(R.string.err_playlist_unknown_host_2, name),
        listOf(
            s(R.string.err_playlist_unknown_host_3, editPath(name)),
            s(R.string.err_playlist_unknown_host_4),
            s(R.string.err_playlist_unknown_host_5),
        ),
    )

    fun errPlaylistUnreachable(name: String) = ErrorInfo(
        s(R.string.err_playlist_unreachable),
        s(R.string.err_playlist_unreachable_2, name),
        listOf(
            s(R.string.err_playlist_unreachable_3),
            s(R.string.err_playlist_unreachable_4),
            s(R.string.err_playlist_unreachable_5, path(playlists)),
        ),
    )

    fun errPlaylistSsl(name: String) = ErrorInfo(
        s(R.string.err_playlist_ssl),
        s(R.string.err_playlist_ssl_2, name),
        listOf(
            s(R.string.err_playlist_ssl_3),
            s(R.string.err_playlist_ssl_4, editPath(name)),
        ),
    )

    fun errPlaylistOther(name: String) = ErrorInfo(
        s(R.string.err_playlist_other, name),
        s(R.string.err_playlist_other_2),
        listOf(
            s(R.string.err_playlist_other_3),
            s(R.string.err_playlist_other_4, editPath(name)),
        ),
    )

    fun errAllLocked(name: String) = ErrorInfo(
        s(R.string.err_all_locked),
        s(R.string.err_all_locked_2, name),
        listOf(s(R.string.err_all_locked_3)),
    )

    fun errPlaylistEmpty(name: String) = ErrorInfo(
        s(R.string.err_playlist_empty),
        s(R.string.err_playlist_empty_2, name),
        listOf(
            s(R.string.err_playlist_empty_3),
            s(R.string.err_playlist_empty_4, editPath(name)),
            s(R.string.err_playlist_empty_5, path(playlists)),
        ),
    )

    // Player
    fun errAccountBusy(limit: Int?, sure: Boolean) = ErrorInfo(
        if (sure) s(R.string.err_account_busy)
        else s(R.string.err_account_busy_2),
        if (limit != null) {
            streamsLimit(limit)
        } else {
            s(R.string.err_account_busy_3)
        },
        listOf(
            s(R.string.err_account_busy_4),
            s(R.string.err_account_busy_5),
            s(R.string.err_account_busy_6),
        ),
    )

    fun errChannelRefused(code: Int) = ErrorInfo(
        s(R.string.err_channel_refused),
        s(R.string.err_channel_refused_2),
        listOf(
            s(R.string.err_channel_refused_3),
            s(R.string.err_channel_refused_4),
            s(R.string.err_channel_refused_5),
        ),
        technical("HTTP $code"),
    )

    val errChannelGone get() = ErrorInfo(
        s(R.string.err_channel_gone),
        s(R.string.err_channel_gone_2),
        listOf(
            s(R.string.err_channel_gone_3, path(playlists), reloadChannels),
            s(R.string.err_channel_gone_4),
        ),
        technical("HTTP 404"),
    )

    val errChannelRateLimited get() = ErrorInfo(
        s(R.string.err_channel_rate_limited),
        s(R.string.err_channel_rate_limited_2),
        listOf(
            s(R.string.err_channel_rate_limited_3),
            s(R.string.err_channel_rate_limited_4),
        ),
        technical("HTTP 429"),
    )

    fun errProviderServer(code: Int) = ErrorInfo(
        s(R.string.err_provider_server),
        s(R.string.err_provider_server_2),
        listOf(
            s(R.string.err_provider_server_3),
            s(R.string.err_provider_server_4),
        ),
        technical("HTTP $code"),
    )

    val errChannelUnreachable get() = ErrorInfo(
        s(R.string.err_channel_unreachable),
        s(R.string.err_channel_unreachable_2),
        listOf(
            s(R.string.err_channel_unreachable_3),
            s(R.string.err_channel_unreachable_4),
        ),
    )

    fun errStreamFormat(format: String?) = ErrorInfo(
        s(R.string.err_stream_format),
        s(R.string.err_stream_format_2),
        listOf(
            s(R.string.err_stream_format_3),
            s(R.string.err_stream_format_4, path(playback, streamFormat), format ?: automaticWord),
            s(R.string.err_stream_format_5),
        ),
    )

    fun errVideoDecoder(codec: String?, software: Boolean) = ErrorInfo(
        s(R.string.err_video_decoder),
        if (software) {
            s(R.string.err_video_decoder_2, codec ?: videoFormatWord)
        } else {
            s(R.string.err_video_decoder_3, codec ?: videoFormatWord)
        },
        listOf(
            if (software) s(R.string.err_video_decoder_4, path(playback, decoder))
            else s(R.string.err_video_decoder_5, path(playback, decoder)),
            s(R.string.err_video_decoder_6),
        ),
    )

    fun errAudio(codec: String?, passthroughOn: Boolean, software: Boolean) = ErrorInfo(
        s(R.string.err_audio),
        s(R.string.err_audio_2, inParens(codec)),
        listOfNotNull(
            if (passthroughOn) s(R.string.err_audio_3, passthrough, path(playback), audioSection) else null,
            if (software) s(R.string.err_audio_4, audioDecoder, path(playback), audioSection)
            else s(R.string.err_audio_5, audioDecoder, path(playback), audioSection),
            s(R.string.err_audio_6, path(currentChannel, audioSection)),
        ),
    )

    val errCantPlay get() = ErrorInfo(
        s(R.string.err_cant_play),
        s(R.string.err_cant_play_2),
        listOf(
            s(R.string.err_cant_play_3),
            s(R.string.err_cant_play_4),
            s(R.string.err_cant_play_5),
        ),
    )

    // ---- Conta / gravação ----
    fun favoriteAdded(name: String) = s(R.string.favorite_added, name)
    fun favoriteRemoved(name: String) = s(R.string.favorite_removed, name)
    fun saveFailed(code: Int) = s(R.string.save_failed, code)
    val saveOffline get() = s(R.string.save_offline)
    val epgRefreshed get() = s(R.string.epg_refreshed)

    // ---- Player ----
    val pressBackToExit get() = s(R.string.press_back_to_exit)
    val tuning get() = s(R.string.tuning)
    val reconnecting get() = s(R.string.reconnecting)
    fun reconnectAttempt(channel: String, n: Int) = s(R.string.reconnect_attempt, channel, n)
    val cantOpenChannel get() = s(R.string.cant_open_channel)
    fun groupLocked(name: String) = s(R.string.group_locked, name)
    val wrongPinRetry get() = s(R.string.wrong_pin_retry)
    val noFavoritesYet get() = s(R.string.no_favorites_yet)
    val noChannelsInGroup get() = s(R.string.no_channels_in_group)
    val seeGroups get() = s(R.string.see_groups)
    val watchingNow get() = s(R.string.watching_now)

    // ---- Guia ----
    val noGuide get() = s(R.string.no_guide)
    val now get() = s(R.string.now)
    fun next(time: String) = s(R.string.next, time)

    // ---- PIN ----
    val pinHint get() = s(R.string.pin_hint)
    val enterPin get() = s(R.string.enter_pin)
    val choosePin get() = s(R.string.choose_pin)
    val wrongPin get() = s(R.string.wrong_pin)
    fun pinWait(time: String) = s(R.string.pin_wait, time)
    val pinSet get() = s(R.string.pin_set)
    val parentalOffToast get() = s(R.string.parental_off_toast)

    // ---- Definições: páginas ----
    val currentChannel get() = s(R.string.current_channel)
    val audio get() = s(R.string.audio)
    val subtitles get() = s(R.string.subtitles)
    val streamInfo get() = s(R.string.stream_info)
    val playback get() = s(R.string.playback)
    val zapping get() = s(R.string.zapping)
    val appearance get() = s(R.string.appearance)
    val guide get() = s(R.string.guide)
    val parental get() = s(R.string.parental)
    val newPin get() = s(R.string.new_pin)
    val lockedGroups get() = s(R.string.locked_groups)
    val playlists get() = s(R.string.playlists)
    val account get() = s(R.string.account)

    // ---- Definições: resumos da página inicial ----
    val playbackSummary get() = s(R.string.playback_summary)
    val zappingSummary get() = s(R.string.zapping_summary)
    val appearanceSummary get() = s(R.string.appearance_summary)
    val guideSummary get() = s(R.string.guide_summary)
    val on get() = s(R.string.on)
    val off get() = s(R.string.off)

    // ---- Canal atual ----
    val favorite get() = s(R.string.favorite)
    val aspect get() = s(R.string.aspect)
    val aspectFit get() = s(R.string.aspect_fit)
    val aspectZoom get() = s(R.string.aspect_zoom)
    val aspectFill get() = s(R.string.aspect_fill)

    // ---- Reprodução ----
    val streamFormat get() = s(R.string.stream_format)
    val auto get() = s(R.string.auto)
    val buffer get() = s(R.string.buffer)
    val bufferLive get() = s(R.string.buffer_live)
    val bufferSmall get() = s(R.string.buffer_small)
    val bufferNormal get() = s(R.string.buffer_normal)
    val bufferLarge get() = s(R.string.buffer_large)
    val engine get() = s(R.string.engine)
    val engineExo get() = s(R.string.engine_exo)
    val engineVlc get() = s(R.string.engine_vlc)
    val usingVlc get() = s(R.string.using_vlc)
    val decoder get() = s(R.string.decoder)
    val deinterlace get() = s(R.string.deinterlace)
    val deinterlaceSub get() = s(R.string.deinterlace_sub)
    val deinterlaceSmooth get() = s(R.string.deinterlace_smooth)
    val deinterlaceNormal get() = s(R.string.deinterlace_normal)
    val decoderHw get() = s(R.string.decoder_hw)
    val decoderSw get() = s(R.string.decoder_sw)
    val freezeFrame get() = s(R.string.freeze_frame)
    val freezeFrameSub get() = s(R.string.freeze_frame_sub)
    val timeshift get() = s(R.string.timeshift)
    val timeshiftSub get() = s(R.string.timeshift_sub)
    val protectPaused get() = s(R.string.protect_paused)
    val protectPausedSub get() = s(R.string.protect_paused_sub)
    val behindSwitchTitle get() = s(R.string.behind_switch_title)
    val behindSwitchText get() = s(R.string.behind_switch_text)
    val behindSwitchGo get() = s(R.string.behind_switch_go)
    val behindSwitchStay get() = s(R.string.behind_switch_stay)
    val reconnect get() = s(R.string.reconnect)
    val no get() = s(R.string.no)
    fun afterSeconds(n: Int) = s(R.string.after_seconds, n)

    // ---- Mudança de canal ----
    val upDownKeys get() = s(R.string.up_down_keys)
    val upDownZap get() = s(R.string.up_down_zap)
    val upDownList get() = s(R.string.up_down_list)
    val reverseUpDown get() = s(R.string.reverse_up_down)
    val reverseUpDownSub get() = s(R.string.reverse_up_down_sub)
    val infoOnZap get() = s(R.string.info_on_zap)
    val dontShow get() = s(R.string.dont_show)
    fun seconds(n: Int) = s(R.string.seconds, n)
    fun secondsShort(n: Int) = s(R.string.seconds_short, n)
    val zapDelay get() = s(R.string.zap_delay)
    val zapDelaySub get() = s(R.string.zap_delay_sub)
    val onStartup get() = s(R.string.on_startup)
    val startupLast get() = s(R.string.startup_last)
    val startupList get() = s(R.string.startup_list)

    // ---- Aspeto ----
    val language get() = s(R.string.language)
    val languageAuto get() = s(R.string.language_auto)
    val channelNumbers get() = s(R.string.channel_numbers)
    val channelLogos get() = s(R.string.channel_logos)
    val outlineColor get() = s(R.string.outline_color)
    fun outlineColorName(id: String) = s(
        when (id) {
            "accent" -> R.string.outline_accent
            "blue" -> R.string.color_blue
            "violet" -> R.string.color_violet
            "pink" -> R.string.color_pink
            "red" -> R.string.color_red
            "orange" -> R.string.color_orange
            "yellow" -> R.string.color_yellow
            "green" -> R.string.color_green
            else -> R.string.color_white
        },
    )
    val epgExtra get() = s(R.string.epg_extra)
    val epgExtraSub get() = s(R.string.epg_extra_sub)
    val epgExtraNone get() = s(R.string.epg_extra_none)
    val epgExtraRemove get() = s(R.string.epg_extra_remove)
    val logoSource get() = s(R.string.logo_source)
    val logoFromPlaylist get() = s(R.string.logo_from_playlist)
    val logoFromGuide get() = s(R.string.logo_from_guide)
    val logoFromGuideSub get() = s(R.string.logo_from_guide_sub)
    val logoByCountry get() = s(R.string.logo_by_country)
    val channelLogo get() = s(R.string.channel_logo)
    val logoAutomatic get() = s(R.string.logo_automatic)
    val logoChosen get() = s(R.string.logo_chosen)
    fun logoCountryApply(country: String) = s(R.string.logo_country_apply, country)
    val logoCountryApplySub get() = s(R.string.logo_country_apply_sub)
    val sortChannels get() = s(R.string.sort_channels)
    val sortNumber get() = s(R.string.sort_number)
    val sortName get() = s(R.string.sort_name)
    val panelWidth get() = s(R.string.panel_width)
    val widthNarrow get() = s(R.string.width_narrow)
    val widthNormal get() = s(R.string.width_normal)
    val widthWide get() = s(R.string.width_wide)
    val widthExtraWide get() = s(R.string.width_extra_wide)
    val textSize get() = s(R.string.text_size)
    val sizeSmall get() = s(R.string.size_small)
    val sizeNormal get() = s(R.string.size_normal)
    val sizeLarge get() = s(R.string.size_large)
    val autoClosePanels get() = s(R.string.auto_close_panels)
    val never get() = s(R.string.never)

    // ---- Guia ----
    val timeOffset get() = s(R.string.time_offset)
    val noOffset get() = s(R.string.no_offset)
    val catchupOffset get() = s(R.string.catchup_offset)
    val catchupOffsetSub get() = s(R.string.catchup_offset_sub)
    val nowInList get() = s(R.string.now_in_list)
    val nowInListSub get() = s(R.string.now_in_list_sub)

    // ---- Controlo parental ----
    val setPin get() = s(R.string.set_pin)
    val setPinSub get() = s(R.string.set_pin_sub)
    val changePin get() = s(R.string.change_pin)
    val parentalOff get() = s(R.string.parental_off)
    val parentalOffSub get() = s(R.string.parental_off_sub)
    val lockedGroupsHeader get() = s(R.string.locked_groups_header)

    // ---- Listas ----
    val inUse get() = s(R.string.in_use)
    val refreshEpg get() = s(R.string.refresh_epg)
    val refreshEpgSub get() = s(R.string.refresh_epg_sub)
    val usePlaylist get() = s(R.string.use_playlist)
    val reloadChannels get() = s(R.string.reload_channels)
    val reloadChannelsSub get() = s(R.string.reload_channels_sub)
    val edit get() = s(R.string.edit)
    val thisTv get() = s(R.string.this_tv)
    val playlistUpdated get() = s(R.string.playlist_updated)
    val playlistRefresh get() = s(R.string.playlist_refresh)
    val epgRefresh get() = s(R.string.epg_refresh)
    val updates get() = s(R.string.updates)
    val onAppOpen get() = s(R.string.on_app_open)
    fun everyHours(n: Int) = s(R.string.every_hours, n)
    fun refreshLabel(hours: Int) = if (hours <= 0) onAppOpen else everyHours(hours)
    val epgRefreshing get() = s(R.string.epg_refreshing)
    val epgRefreshFailed get() = s(R.string.epg_refresh_failed)
    val type get() = s(R.string.type)
    val name get() = s(R.string.name)
    val server get() = s(R.string.server)
    val username get() = s(R.string.username)
    val password get() = s(R.string.password)
    val epgUrl get() = s(R.string.epg_url)
    val missingServer get() = s(R.string.missing_server)
    val missingUsername get() = s(R.string.missing_username)
    val missingPassword get() = s(R.string.missing_password)
    val missingUrl get() = s(R.string.missing_url)
    val newPlaylist get() = s(R.string.new_playlist)
    val editPlaylist get() = s(R.string.edit_playlist)
    val addPlaylist get() = s(R.string.add_playlist)
    val addPlaylistSub get() = s(R.string.add_playlist_sub)
    val removePlaylist get() = s(R.string.remove_playlist)
    val removePlaylistConfirm get() = s(R.string.remove_playlist_confirm)
    val removePlaylistSub get() = s(R.string.remove_playlist_sub)
    val playlistRemoved get() = s(R.string.playlist_removed)
    val playlistSaved get() = s(R.string.playlist_saved)
    val optional get() = s(R.string.optional)
    val playlistUrl get() = s(R.string.playlist_url)
    val playlistOnlyHere get() = s(R.string.playlist_only_here)
    val playlistAccountHint get() = s(R.string.playlist_account_hint)
    val playlistsSiteTip get() = s(R.string.playlists_site_tip)
    val removePlaylistAccountSub get() = s(R.string.remove_playlist_account_sub)
    val playlistRejected get() = s(R.string.playlist_rejected)
    val playlistForbidden get() = s(R.string.playlist_forbidden)
    val playlistServerInvalid get() = s(R.string.playlist_server_invalid)
    val playlistUrlInvalid get() = s(R.string.playlist_url_invalid)
    val playlistEpgInvalid get() = s(R.string.playlist_epg_invalid)
    val playlistNameTooLong get() = s(R.string.playlist_name_too_long)
    val playlistTypeChange get() = s(R.string.playlist_type_change)
    val playlistAddressBlocked get() = s(R.string.playlist_address_blocked)
    fun playlistLimit(max: Int) = s(R.string.playlist_limit, max)
    val playlistGone get() = s(R.string.playlist_gone)
    val tooManyRequests get() = s(R.string.too_many_requests)
    val xtreamSub get() = s(R.string.xtream_sub)
    val m3uPlaylist get() = s(R.string.m3u_playlist)
    val m3uSub get() = s(R.string.m3u_sub)

    // ---- Boas-vindas e sem conta ----
    val welcomeTitle get() = s(R.string.welcome_title)
    val welcomeSub get() = s(R.string.welcome_sub)
    val welcomeAccount get() = s(R.string.welcome_account)
    val welcomeAccountSub get() = s(R.string.welcome_account_sub)
    val recommended get() = s(R.string.recommended)
    val welcomeLocal get() = s(R.string.welcome_local)
    val welcomeLocalSub get() = s(R.string.welcome_local_sub)
    val useWithoutAccount get() = s(R.string.use_without_account)
    val noAccount get() = s(R.string.no_account)
    val connectAccount get() = s(R.string.connect_account)
    val connectAccountSub get() = s(R.string.connect_account_sub)
    val localProfileMoved get() = s(R.string.local_profile_moved)

    // ---- Guia da conta (trocar o guia de um canal) ----
    val epgGuideChannel get() = s(R.string.epg_guide_channel)
    val epgFromPlaylist get() = s(R.string.epg_from_playlist)
    fun epgUsingNow(guide: String) = s(R.string.epg_using_now, guide)
    val epgSuggestions get() = s(R.string.epg_suggestions)
    fun epgCountriesFor(query: String) = s(R.string.epg_countries_for, query)
    val epgSearchOther get() = s(R.string.epg_search_other)
    fun epgAllCountry(country: String) = s(R.string.epg_all_country, country)
    fun epgNoResults(query: String) = s(R.string.epg_no_results, query)
    fun epgMapped(channel: String, guide: String) = s(R.string.epg_mapped, channel, guide)
    fun epgUnmapped(channel: String) = s(R.string.epg_unmapped, channel)
    fun epgFailed(code: String) = s(R.string.epg_failed, code)
    val epgOffline get() = s(R.string.epg_offline)
    val epgNeedsAccount get() = s(R.string.epg_needs_account)
    val epgCountryGuide get() = s(R.string.epg_country_guide)
    val epgCountryGuideSub get() = s(R.string.epg_country_guide_sub)
    fun epgCountryApply(country: String) = s(R.string.epg_country_apply, country)
    fun epgCountryApplying(country: String) = s(R.string.epg_country_applying, country)
    fun epgCountryDone(count: Int, country: String) = s(R.string.epg_country_done, count.toString(), country)
    fun epgCountryNone(country: String) = s(R.string.epg_country_none, country)
    val epgResetAll get() = s(R.string.epg_reset_all)
    val epgResetAllSub get() = s(R.string.epg_reset_all_sub)
    val epgResetDone get() = s(R.string.epg_reset_done)
    val typeHint get() = s(R.string.type_hint)

    // ---- Geral ----
    val general get() = s(R.string.general)
    val generalSummary get() = s(R.string.general_summary)
    val autoStart get() = s(R.string.auto_start)
    val autoStartSub get() = s(R.string.auto_start_sub)
    val overlayNeeded get() = s(R.string.overlay_needed)
    val exitConfirm get() = s(R.string.exit_confirm)
    val userAgent get() = s(R.string.user_agent)
    val userAgentDefault get() = s(R.string.user_agent_default)
    val userAgentSub get() = s(R.string.user_agent_sub)

    // ---- Reprodução: AFR e áudio ----
    val afr get() = s(R.string.afr)
    val afrSub get() = s(R.string.afr_sub)
    val afrOnStart get() = s(R.string.afr_on_start)
    val afrAlways get() = s(R.string.afr_always)
    val afrFallback get() = s(R.string.afr_fallback)
    val afrFallbackSub get() = s(R.string.afr_fallback_sub)
    val hdr get() = "HDR"
    val hdrAuto get() = s(R.string.hdr_auto)
    val hdrSdr get() = s(R.string.hdr_sdr)
    val hdrTvNone get() = s(R.string.hdr_tv_none)
    fun hdrTvSupports(types: String) = s(R.string.hdr_tv_supports, types)
    val audioSection get() = s(R.string.audio_section)
    val passthrough get() = s(R.string.passthrough)
    val passthroughSub get() = s(R.string.passthrough_sub)
    val surroundDefault get() = s(R.string.surround_default)
    val surroundDefaultSub get() = s(R.string.surround_default_sub)
    val audioLanguage get() = s(R.string.audio_language)
    val audioLanguageSub get() = s(R.string.audio_language_sub)
    val subtitleLanguage get() = s(R.string.subtitle_language)
    val subtitleLanguageSub get() = s(R.string.subtitle_language_sub)
    val audioDecoder get() = s(R.string.audio_decoder)
    val audioDelay get() = s(R.string.audio_delay)
    val audioDelaySub get() = s(R.string.audio_delay_sub)

    // ---- Aspeto: vidro e grupos ----
    val accent get() = s(R.string.accent)
    fun accentName(id: String) = when (id) {
        "violet" -> s(R.string.accent_name)
        "mint" -> s(R.string.accent_name_2)
        "sunset" -> s(R.string.accent_name_3)
        "gold" -> s(R.string.accent_name_4)
        "white" -> s(R.string.accent_name_5)
        else -> s(R.string.accent_name_6)
    }
    val glassOpacity get() = s(R.string.glass_opacity)
    fun percent(n: Int) = "$n %"
    val manageGroups get() = s(R.string.manage_groups)
    fun manageGroupsSub(hidden: Int) = if (hidden == 0) s(R.string.manage_groups_sub) else hiddenGroups(hidden)
    val visibleGroups get() = s(R.string.visible_groups)
    val reorderGroups get() = s(R.string.reorder_groups)
    val reorderGroupsSub get() = s(R.string.reorder_groups_sub)
    val moving get() = s(R.string.moving)
    val organize get() = s(R.string.organize)
    val organizeUseFirst get() = s(R.string.organize_use_first)
    val manageChannels get() = s(R.string.manage_channels)
    val manageChannelsSub get() = s(R.string.manage_channels_sub)
    val reorderChannels get() = s(R.string.reorder_channels)
    val reorderChannelsSub get() = s(R.string.reorder_channels_sub)
    val visibleChannels get() = s(R.string.visible_channels)
    val resetOrder get() = s(R.string.reset_order)
    val resetOrderSub get() = s(R.string.reset_order_sub)

    // ---- Conta ----
    val version get() = s(R.string.version)
    val licenses get() = s(R.string.licenses)
    val sourceCode get() = s(R.string.source_code)

    // ---- Faixas e stream ----
    val none get() = s(R.string.none)
    val subtitlesOff get() = s(R.string.subtitles_off)
    val noTracks get() = s(R.string.no_tracks)
    fun track(n: Int) = s(R.string.track, n)
    val stereo get() = s(R.string.stereo)
    fun channelsAudio(n: Int) = s(R.string.channels_audio, n)
    val resolution get() = s(R.string.resolution)
    val dynamicRange get() = s(R.string.dynamic_range)
    val video get() = s(R.string.video)
    val preferredFormat get() = s(R.string.preferred_format)

    companion object {
        /** As línguas da app, iguais às do site, pela ordem do seletor (o nome de cada uma na própria língua). */
        val LANGUAGES = listOf(
            "en" to "English", "es" to "Español", "pt-PT" to "Português (PT)", "pt-BR" to "Português (BR)",
            "fr" to "Français", "de" to "Deutsch", "it" to "Italiano", "ru" to "Русский", "tr" to "Türkçe",
            "id" to "Indonesia", "hi" to "हिन्दी", "ar" to "العربية", "zh-CN" to "简体中文", "ja" to "日本語", "ko" to "한국어",
        )

        /** "auto" ou um código de [LANGUAGES]; o antigo "pt" passa a pt-PT e o resto desconhecido a "auto". */
        fun normalize(code: String?): String = when {
            code == "pt" -> "pt-PT"
            code != null && LANGUAGES.any { it.first == code } -> code
            else -> "auto"
        }

        /** Aplica a língua ("auto" segue o sistema). O AppCompat guarda-a e recria as Activities. */
        fun apply(code: String) {
            val lang = normalize(code)
            val target = if (lang == "auto") LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(lang)
            if (AppCompatDelegate.getApplicationLocales().toLanguageTags() != target.toLanguageTags()) {
                AppCompatDelegate.setApplicationLocales(target)
            }
        }

        @Volatile
        private var cache: Pair<String, Strings>? = null

        /**
         * Os textos na língua da app, para código fora das Activities: antes do Android 13 o contexto da app
         * não muda de língua sozinho, por isso cria-se um com a língua escolhida.
         */
        fun of(context: Context): Strings {
            val app = context.applicationContext
            val chosen = AppCompatDelegate.getApplicationLocales()
            val key = chosen.toLanguageTags() + "|" + Locale.getDefault().toLanguageTag()
            cache?.let { if (it.first == key) return it.second }
            val ctx = if (chosen.isEmpty) {
                app
            } else {
                app.createConfigurationContext(
                    Configuration(app.resources.configuration).also { ConfigurationCompat.setLocales(it, chosen) },
                )
            }
            return Strings(ctx).also { cache = key to it }
        }
    }
}

/** Textos atuais, para código fora do Compose (ViewModel, fontes de listas). Iniciado no ItvApp. */
object I18n {
    @Volatile
    lateinit var s: Strings
}

val LocalStrings = staticCompositionLocalOf { I18n.s }

/**
 * Email mascarado para o ecrã ("a••••••@exemplo.com"): não aparece por inteiro em gravações ou fotos da TV.
 * O Android 11 da SHIELD não tem desfocagem real, por isso esconde-se o nome.
 */
fun maskEmail(email: String): String {
    val at = email.indexOf('@')
    if (at <= 0) return email
    return email.first() + "••••••" + email.substring(at)
}
