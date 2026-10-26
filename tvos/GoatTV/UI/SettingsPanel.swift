import GoatCore
import SwiftUI

/// As Definições, com as mesmas páginas do GoatTV Android, num painel à direita. Ficam na conta: valem para todas
/// as TVs.
struct SettingsPanel: View {
    @Environment(AppModel.self) private var model

    enum Page: Hashable {
        case root
        case current
        case audio
        case subtitles
        case streamInfo
        case general
        case playback
        case zapping
        case appearance
        case guide
        case settingsGate
        case parentalGate
        case parental
        case newPin
        case lockedGroups
        case lists
        case playlist(String)
        case groups
        case channelGroups
        case channels(String)
        case account
        case licenses
        case topShelf
        /// Escolher o valor de uma definição (a chave JSON).
        case pick(String)
        case userAgent
        case extraEpg(String)
        /// Escolher o guia (ou, com logo, o logótipo) de um canal: sugestões e pesquisa pelo nome.
        case epgPick(String, Bool, String)
        /// Os canais de guia de um país (os da pesquisa, ou todos com nil).
        case epgCountry(String, Bool, String, String, [EpgGuideChannel]?)
        case epgSearchOther(String, Bool)
        case logoSource
        case epgCountries
        case groupsOrder
        case channelsOrder(String)
        /// Lista nova, primeiro passo: Xtream Codes ou lista M3U.
        case newPlaylistType
        /// Criar (nil) ou editar uma lista.
        case playlistEdit(String?)
        /// Escrever um campo do formulário da lista.
        case formField(String)
        /// Enviar um relatório: o que aconteceu (opcional).
        case report
    }

    /// O que veio do servidor do guia para a página aberta.
    enum Remote {
        case pick(AppModel.EpgResult<[EpgGuideChannel]>, AppModel.EpgResult<[EpgCountryResult]>)
        case countries(AppModel.EpgResult<[EpgCountryInfo]>)
        case country(AppModel.EpgResult<EpgCountryResult>)
    }

    /// Uma linha de uma página (como o `Item` do Android).
    enum Item: Identifiable {
        case nav(String, String, value: String? = nil, subtitle: String? = nil, to: Page)
        case toggle(String, String, Bool, subtitle: String? = nil, (Bool) -> Void)
        /// Mostrar/esconder: olho (visível) ou olho riscado e esbatido (escondido); OK troca.
        case eye(String, String, Bool, (Bool) -> Void)
        case check(String, String, Bool, subtitle: String? = nil, swatch: AnyShapeStyle? = nil, () -> Void)
        /// Uma escolha com o logótipo à esquerda.
        case logoCheck(String, String, Bool, subtitle: String?, logo: String?, () -> Void)
        case action(String, String, subtitle: String? = nil, () -> Void)
        case info(String, String, String)
        case header(String, String)

        var id: String {
            switch self {
            case .nav(let id, _, _, _, _), .toggle(let id, _, _, _, _), .eye(let id, _, _, _), .check(let id, _, _, _, _, _),
                 .logoCheck(let id, _, _, _, _, _), .action(let id, _, _, _), .info(let id, _, _), .header(let id, _):
                return id
            }
        }

        var focusable: Bool {
            if case .header = self { return false }
            return true
        }
    }

    let start: Page
    let onClose: () -> Void
    @Environment(\.panelActivity) private var onActivity

    init(start: Page, onClose: @escaping () -> Void) {
        self.start = start
        self.onClose = onClose
    }

    @State private var stack: [Page] = []
    /// A linha que abriu cada página, para o foco voltar a ela ao recuar.
    @State private var openedFrom: [String] = []
    @State private var pendingFocus: String?
    @State private var confirm: String?
    @State private var text = ""
    @State private var remote: Remote?
    /// O formulário da lista a criar ou editar (fica enquanto o painel está aberto).
    @State private var form = PlaylistForm()
    @State private var saving = false
    /// Na página de reordenar: o item "na mão" (↑/↓ movem-no).
    @State private var moving: String?
    @FocusState private var focus: String?

    private var page: Page { stack.last ?? start }
    private var s: Strings { model.strings }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(title(page))
                .font(Fonts.panelTitle)
                .lineLimit(1)
                .padding(.horizontal, 24)
                .padding(.bottom, 6)
            content
        }
        .padding(.vertical, 28)
        .padding(.horizontal, 16)
        .frame(width: model.panelPoints(model.prefs.panelWidth + 40))
        .frame(maxHeight: .infinity, alignment: .top)
        .glass()
        .focusSection()
        .onAppear {
            guard stack.isEmpty else { return }
            stack = [start == .root && model.settingsNeedPin ? .settingsGate : start]
        }
        .onExitCommand { back() }
        .onChange(of: focus) { _, now in onActivity(now == "field") }
        .onChange(of: stack) { _, _ in onActivity(focus == "field") }
        .onChange(of: text) { _, _ in onActivity(focus == "field") }
        .task(id: stack) {
            confirm = nil
            moving = nil
            remote = nil
            try? await Task.sleep(for: .milliseconds(60))
            focus = pendingFocus ?? firstFocus
            pendingFocus = nil
            await loadRemote(page)
            if focus == nil || focus == "loading" { focus = firstFocus }
        }
    }

    // MARK: Navegação

    /// Enviar um relatório, com o estado do último envio por baixo.
    private var reportItem: Item {
        let status: String
        switch model.report {
        case .idle: status = s.r("send_report_sub")
        case .sending: status = s.r("report_sending")
        case .sent(let id): status = s.r("report_sent", id)
        case .failed(let why): status = why
        }
        return .action("report", s.r("send_report"), subtitle: status) {
            if model.report != .sending { push(.report, from: "report") }
        }
    }

    private func push(_ next: Page, from row: String) {
        if case .playlistEdit(let id?) = next, let p = model.playlists.first(where: { $0.id == id }) { form = PlaylistForm(p) }
        openedFrom.append(row)
        stack.append(next)
    }

    private func back() {
        if stack.count > 1 {
            stack.removeLast()
            pendingFocus = openedFrom.popLast()
        } else {
            onClose()
        }
    }

    /// Volta à página "Canal atual" (depois de escolher o guia ou o logótipo); se não houver, fecha.
    private func popToCurrent() {
        guard let i = stack.lastIndex(of: .current) else {
            onClose()
            return
        }
        pendingFocus = openedFrom.count > i ? openedFrom[i] : nil
        stack = Array(stack.prefix(i + 1))
        openedFrom = Array(openedFrom.prefix(i))
    }

    private func loadRemote(_ p: Page) async {
        switch p {
        case .epgPick(let key, _, let query):
            guard let ch = model.content?.byKey[key] else { return }
            let suggest = await model.epgSuggest(ch)
            let search = await model.epgSearch(query)
            if page == p { remote = .pick(suggest, search) }
        case .logoSource:
            guard model.hasAccount else { return }
            let r = await model.epgCountries()
            if page == p { remote = .countries(r) }
        case .epgCountries:
            let r = await model.epgCountries()
            if page == p { remote = .countries(r) }
        case .epgCountry(_, _, let country, _, nil):
            let r = await model.epgCountryChannels(country)
            if page == p { remote = .country(r) }
        default:
            break
        }
    }

    private func replace(_ next: Page) {
        if stack.isEmpty { stack = [next] } else { stack[stack.count - 1] = next }
    }

    private var firstFocus: String? {
        switch page {
        case .groupsOrder: return model.allGroups.first.map { "o:" + $0.id }
        case .channelsOrder(let g): return model.groupChannels(g, includeHidden: true).first.map { "o:" + $0.key }
        case .epgSearchOther, .formField: return "field"
        default: break
        }
        let list = items(page)
        if case .pick = page, let checked = list.first(where: { if case .check(_, _, true, _, _, _) = $0 { return true } else { return false } }) {
            return checked.id
        }
        return list.first(where: \.focusable)?.id
    }

    // MARK: Conteúdo

    @ViewBuilder
    private var content: some View {
        switch page {
        case .settingsGate:
            PinPad(title: s.t("Enter the PIN", "Escreve o PIN"), subtitle: s.t("Settings are protected", "As Definições estão protegidas")) { pin in
                guard model.unlockSettings(pin) else { return false }
                replace(.root)
                return true
            }
        case .parentalGate:
            PinPad(title: s.t("Enter the PIN", "Escreve o PIN")) { pin in
                guard model.checkPin(pin) else { return false }
                replace(.parental)
                return true
            }
        case .newPin:
            PinPad(title: s.t("New PIN", "PIN novo"), subtitle: s.t("4 digits", "4 algarismos")) { pin in
                model.setPin(pin)
                model.showToast(s.t("PIN saved", "PIN guardado"))
                back()
                return true
            }
        case .userAgent:
            textPage(
                placeholder: s.t("Default", "Predefinido"),
                hint: s.t("For providers that block the default one. Empty resets it.", "Para fornecedores que bloqueiam o predefinido. Vazio repõe."),
                initial: model.prefs.userAgent
            ) { value in
                let v = value.trimmingCharacters(in: .whitespacesAndNewlines)
                model.setPref("user_agent", v.isEmpty ? nil : .string(v))
            }
        case .epgSearchOther(let key, let logo):
            textPage(placeholder: model.content?.byKey[key]?.name ?? "", hint: s.t("The name to look for in the guide", "O nome a procurar no guia"),
                     initial: "") { value in
                let q = value.trimmingCharacters(in: .whitespaces)
                guard !q.isEmpty, stack.count >= 2 else { return }
                stack[stack.count - 2] = .epgPick(key, logo, q)
            }
        case .formField(let key):
            textPage(placeholder: key == "server" ? "http://servidor:8080" : (key == "url" || key == "epg" ? "http://…" : ""),
                     hint: "", initial: fieldValue(key), secure: key == "pass") { value in
                setField(key, value)
            }
        case .report:
            textPage(placeholder: s.r("report_what"), hint: s.r("send_report_sub"), initial: "", action: s.r("report_send")) { value in
                model.sendReport(value)
            }
        case .groupsOrder:
            orderList(model.allGroups.map { ($0.id, $0.name) }) { id, delta in model.moveGroup(id, delta) }
        case .channelsOrder(let g):
            orderList(model.groupChannels(g, includeHidden: true).map { ($0.key, $0.name) }) { key, delta in model.moveChannel(g, key, delta) }
        case .extraEpg(let id):
            textPage(
                placeholder: "https://…/guide.xml.gz",
                hint: s.t("An XMLTV guide for the channels this playlist's guide doesn't have.", "Um guia XMLTV para os canais que o guia da lista não tem."),
                initial: model.prefs.extraEpg[id] ?? ""
            ) { value in
                model.setExtraEpg(id, value.isEmpty ? nil : value)
            }
        default:
            ScrollView(.vertical, showsIndicators: false) {
                VStack(alignment: .leading, spacing: 4) {
                    ForEach(items(page)) { item in
                        row(item).accessibilityIdentifier(item.id)
                    }
                }
                .padding(.vertical, 8)
                .padding(.horizontal, 8)
            }
        }
    }

    /// Reordenar: OK pega/larga um item; com ele "na mão", ↑/↓ movem-no (os outros ficam sem foco).
    private func orderList(_ list: [(String, String)], move: @escaping (String, Int) -> Void) -> some View {
        ScrollViewReader { proxy in
            ScrollView(.vertical, showsIndicators: false) {
                VStack(alignment: .leading, spacing: 4) {
                    ForEach(list, id: \.0) { id, name in
                        let isMoving = moving == id
                        Button { moving = isMoving ? nil : id } label: {
                            RowContent(title: name) {
                                Text(isMoving ? s.t("Moving", "A mover") : "↕")
                                    .font(Fonts.rowValue)
                                    .foregroundStyle(isMoving ? AnyShapeStyle(Theme.sky) : AnyShapeStyle(Color.secondary))
                            }
                        }
                        .buttonStyle(FillRowStyle(selected: isMoving))
                        .focused($focus, equals: "o:" + id)
                        .disabled(moving != nil && !isMoving)
                        .id(id)
                    }
                }
                .padding(.vertical, 8)
                .padding(.horizontal, 8)
            }
            .onMoveCommand { direction in
                guard let id = moving else { return }
                if direction == .up { move(id, -1) }
                if direction == .down { move(id, 1) }
                proxy.scrollTo(id, anchor: .center)
            }
            .onAppear { if focus == nil, let first = list.first { focus = "o:" + first.0 } }
        }
    }

    private func textPage(placeholder: String, hint: String, initial: String, secure: Bool = false, action: String? = nil,
                          save: @escaping (String) -> Void) -> some View {
        VStack(alignment: .leading, spacing: 20) {
            Group {
                if secure {
                    SecureField(placeholder, text: $text)
                } else {
                    TextField(placeholder, text: $text)
                }
            }
                .font(Fonts.rowTitle)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .focused($focus, equals: "field")
                .onSubmit {
                    save(text)
                    back()
                }
            Text(hint).font(Fonts.rowSubtitle).foregroundStyle(Theme.textDim)
            Button {
                save(text)
                back()
            } label: {
                RowContent(title: action ?? s.t("Save", "Guardar"))
            }
            .buttonStyle(FillRowStyle())
            .focused($focus, equals: "save")
        }
        .padding(.horizontal, 8)
        .onAppear { text = initial }
    }

    @ViewBuilder
    private func row(_ item: Item) -> some View {
        switch item {
        case .header(_, let text):
            Text(text.uppercased())
                .font(Fonts.section)
                .foregroundStyle(Theme.textFaint)
                .padding(.horizontal, 24)
                .padding(.top, 18)
                .padding(.bottom, 4)
        case .nav(let id, let title, let value, let subtitle, let next):
            Button { push(next, from: id) } label: {
                RowContent(title: title, subtitle: subtitle) {
                    if let value, !value.isEmpty {
                        Text(value).font(Fonts.rowValue).opacity(0.82).lineLimit(1)
                    }
                    Image(systemName: "chevron.right").font(.system(size: 22, weight: .semibold)).opacity(0.6)
                }
            }
            .buttonStyle(FillRowStyle())
            .focused($focus, equals: id)
        case .toggle(let id, let title, let on, let subtitle, let change):
            Button { change(!on) } label: {
                RowContent(title: title, subtitle: subtitle) { SwitchMark(on: on) }
            }
            .buttonStyle(FillRowStyle())
            .focused($focus, equals: id)
        case .eye(let id, let title, let visible, let change):
            Button { change(!visible) } label: {
                RowContent(title: title, subtitle: nil) { EyeMark(visible: visible) }
            }
            .buttonStyle(FillRowStyle())
            .focused($focus, equals: id)
        case .check(let id, let title, let checked, let subtitle, let swatch, let pick):
            Button(action: pick) {
                HStack(spacing: 18) {
                    if let swatch {
                        Circle().fill(swatch).frame(width: 30, height: 30)
                    }
                    RowContent(title: title, subtitle: subtitle) {
                        if checked { Text("✓").font(Fonts.rowTitle).foregroundStyle(Theme.sky) }
                    }
                }
            }
            .buttonStyle(FillRowStyle())
            .focused($focus, equals: id)
        case .logoCheck(let id, let title, let checked, let subtitle, let logo, let pick):
            Button(action: pick) {
                HStack(spacing: 18) {
                    ChannelLogo(url: logo.flatMap(URL.init(string:)), name: title, size: CGSize(width: 72, height: 46))
                    RowContent(title: title, subtitle: subtitle) {
                        if checked { Text("✓").font(Fonts.rowTitle).foregroundStyle(Theme.sky) }
                    }
                }
            }
            .buttonStyle(FillRowStyle())
            .focused($focus, equals: id)
        case .action(let id, let title, let subtitle, let perform):
            Button(action: perform) {
                RowContent(title: title, subtitle: subtitle)
            }
            .buttonStyle(FillRowStyle())
            .focused($focus, equals: id)
        case .info(let id, let title, let value):
            Button {} label: {
                RowContent(title: title) {
                    Text(value).font(Fonts.rowValue).opacity(0.82).lineLimit(1)
                }
            }
            .buttonStyle(FillRowStyle())
            .focused($focus, equals: id)
        }
    }

    // MARK: Títulos

    private func title(_ p: Page) -> String {
        switch p {
        case .root, .settingsGate: return s.t("Settings", "Definições")
        case .current: return s.t("Current channel", "Canal atual")
        case .audio: return s.t("Audio", "Áudio")
        case .subtitles: return s.t("Subtitles", "Legendas")
        case .streamInfo: return s.t("Stream info", "Informação do stream")
        case .general: return s.t("General", "Geral")
        case .playback: return s.t("Playback", "Reprodução")
        case .zapping: return s.t("Channel switching", "Mudança de canal")
        case .appearance: return s.t("Appearance", "Aspeto")
        case .guide: return s.t("TV guide", "Guia de programação")
        case .parentalGate, .parental: return s.t("Parental controls", "Controlo parental")
        case .newPin: return s.t("New PIN", "PIN novo")
        case .lockedGroups: return s.t("Locked groups", "Grupos bloqueados")
        case .lists: return s.playlists
        case .playlist(let id): return model.playlists.first { $0.id == id }?.name ?? s.playlists
        case .groups: return s.groups
        case .channelGroups: return s.t("Channels", "Canais")
        case .channels(let g): return model.allGroups.first { $0.id == g }?.name ?? s.t("Channels", "Canais")
        case .account: return s.account
        case .licenses: return s.t("Licences", "Licenças")
        case .topShelf: return "Top Shelf"
        case .pick(let key): return spec(key).title
        case .userAgent: return "User-Agent"
        case .epgPick(_, let logo, _): return logo ? s.t("Channel logo", "Logótipo do canal") : s.t("Guide channel", "Canal do guia")
        case .epgCountry(_, _, _, let name, _): return name
        case .epgSearchOther: return s.t("Search another name", "Procurar outro nome")
        case .logoSource: return s.t("Logo source", "Origem dos logótipos")
        case .epgCountries: return s.t("Guide by country", "Guia por país")
        case .groupsOrder: return s.t("Reorder groups", "Reordenar grupos")
        case .newPlaylistType: return s.t("New playlist", "Lista nova")
        case .playlistEdit(let id): return id == nil ? s.t("New playlist", "Lista nova") : s.t("Edit playlist", "Editar lista")
        case .formField(let key): return fieldTitle(key)
        case .report: return s.r("send_report")
        case .channelsOrder(let g): return model.allGroups.first { $0.id == g }?.name ?? s.t("Reorder channels", "Reordenar canais")
        case .extraEpg: return s.t("Extra guide", "Guia extra")
        }
    }

    // MARK: Páginas

    private func items(_ p: Page) -> [Item] {
        let prefs = model.prefs
        switch p {
        case .root:
            var list: [Item] = []
            if let version = model.updateVersion {
                list.append(.action("update", s.r("update_to", version),
                                    subtitle: s.t("Download GoatTV.ipa from github.com/GOAThings/GoatTV and install it again with your signing service",
                                                  "Descarrega o GoatTV.ipa em github.com/GOAThings/GoatTV e volta a instalá-lo com o teu serviço de assinatura")) {})
            }
            if let channel = model.current {
                list.append(.nav("current", s.t("Current channel", "Canal atual"), subtitle: channel.name, to: .current))
            }
            list += [
                .nav("general", s.t("General", "Geral"), subtitle: s.t("Exit, User-Agent", "Saída, User-Agent"), to: .general),
                .nav("playback", s.t("Playback", "Reprodução"), subtitle: s.t("Format, buffer, player, picture, HDR", "Formato, buffer, player, imagem, HDR"), to: .playback),
                .nav("zapping", s.t("Channel switching", "Mudança de canal"), subtitle: s.t("↑/↓ keys, info, startup", "Teclas ↑/↓, informação, arranque"), to: .zapping),
                .nav("appearance", s.t("Appearance", "Aspeto"), subtitle: s.t("Language, panels, numbers, logos", "Idioma, painéis, números, logos"), to: .appearance),
                .nav("guide", s.t("TV guide", "Guia de programação"), subtitle: s.t("Updates, time offset", "Atualização, desvio horário"), to: .guide),
                .nav("parental", s.t("Parental controls", "Controlo parental"), subtitle: prefs.pinHash != nil ? s.t("On", "Ativo") : s.t("Off", "Desligado"),
                     to: prefs.pinHash != nil ? .parentalGate : .parental),
                .nav("lists", s.playlists, subtitle: model.content?.playlist.name, to: .lists),
                .nav("account", s.account, subtitle: model.hasAccount ? model.email.map(Self.maskEmail) : s.t("No account (this TV only)", "Sem conta (só nesta TV)"), to: .account),
            ]
            return list

        case .current:
            guard let channel = model.current else { return [] }
            let player = model.player
            var list: [Item] = [
                .toggle("fav", s.t("Favourite", "Favorito"), model.isFavorite(channel), subtitle: channel.name) { _ in model.toggleFavorite(channel) },
                .nav("audio", s.t("Audio", "Áudio"), value: trackName(player.audioTracks, player.currentAudio), to: .audio),
                .nav("subs", s.t("Subtitles", "Legendas"), value: trackName(player.subtitleTracks, player.currentSubtitle), to: .subtitles),
                pickItem("aspect"),
                .nav("info", s.t("Stream info", "Informação do stream"), to: .streamInfo),
            ]
            list += guideItems(channel)
            return list

        case .audio:
            let tracks = model.player.audioTracks
            if tracks.isEmpty { return [.info("none", s.t("No audio tracks", "Sem faixas de áudio"), "")] }
            return tracks.map { (track: PlayerEngine.Track) -> Item in
                .check("a\(track.id)", track.name, track.id == model.player.currentAudio) {
                    model.player.selectAudio(track.id)
                    back()
                }
            }

        case .subtitles:
            let tracks = model.player.subtitleTracks
            if tracks.isEmpty { return [.info("none", s.t("No subtitles", "Sem legendas"), "")] }
            return tracks.map { (track: PlayerEngine.Track) -> Item in
                .check("s\(track.id)", track.id < 0 ? s.t("Off", "Desligadas") : track.name, track.id == model.player.currentSubtitle) {
                    model.player.selectSubtitle(track.id)
                    back()
                }
            }

        case .streamInfo:
            guard let channel = model.current else { return [] }
            let group = model.content?.categoryName(channel.categoryId) ?? channel.categoryId
            var list: [Item] = [
                .info("res", s.t("Resolution", "Resolução"), model.player.resolution ?? "—"),
                .info("fmt", s.t("Format", "Formato"), model.player.format.map { $0 == StreamFormat.hls ? "HLS (m3u8)" : "MPEG-TS" } ?? "—"),
                .info("num", s.t("Number", "Número"), "\(channel.num)"),
                .info("grp", s.groups, group == PlaylistContent.others ? s.others : group),
                .info("player", s.t("Player", "Player"), model.player.backendName),
            ]
            for (i, detail) in model.player.streamDetails.enumerated() {
                let title = detail.0 == "video" ? s.t("Video", "Vídeo") : (detail.0 == "audio" ? s.t("Audio", "Áudio") : "Bitrate")
                list.append(.info("d\(i)", title, detail.1))
            }
            return list

        case .general:
            return [
                .toggle("exit_confirm", s.t("Confirm exit with a second Back", "Confirmar saída com 2.º Voltar"), prefs.exitConfirm) {
                    model.setPref("exit_confirm", .bool($0))
                },
                .nav("ua", "User-Agent", value: prefs.userAgent.isEmpty ? s.t("Default", "Predefinido") : prefs.userAgent,
                     subtitle: s.t("For providers that block the default one", "Para fornecedores que bloqueiam o predefinido"), to: .userAgent),
            ]

        case .playback:
            return [
                pickItem("stream_format"), pickItem("buffer"), pickItem("engine"), pickItem("decoder"), pickItem("aspect"),
                pickItem("deinterlace"), pickItem("reconnect_seconds"),
                .toggle("afr", s.t("Match frame rate", "Igualar a frame rate"), prefs.afr,
                        subtitle: s.t("The TV switches to the channel's rate (needs Match Content on the Apple TV)",
                                      "A TV muda para a cadência do canal (precisa de Igualar conteúdo no Apple TV)")) {
                    model.setPref("afr", .bool($0))
                },
                pickItem("hdr"),
                .toggle("freeze_frame", s.r("freeze_frame"), prefs.freezeFrame, subtitle: s.r("freeze_frame_sub")) {
                    model.setPref("freeze_frame", .bool($0))
                },
                .header("audio", s.t("Audio", "Áudio")),
                .toggle("audio_passthrough", s.t("Audio passthrough", "Passthrough de áudio"), prefs.audioPassthrough,
                        subtitle: s.t("Dolby and DTS go straight to the receiver or TV", "Dolby e DTS seguem para o recetor ou a TV")) {
                    model.setPref("audio_passthrough", .bool($0))
                },
                .toggle("surround_default", s.r("surround_default"), prefs.surroundDefault, subtitle: s.r("surround_default_sub")) {
                    model.setPref("surround_default", .bool($0))
                },
            ]

        case .zapping:
            return [
                pickItem("up_down"),
                .toggle("reverse_zap", s.t("Reverse ↑/↓", "Inverter ↑/↓"), prefs.reverseZap,
                        subtitle: s.t("↑ goes to the next channel", "↑ passa para o canal seguinte")) { model.setPref("reverse_zap", .bool($0)) },
                pickItem("info_seconds"),
                pickItem("startup"),
                pickItem("back_action"),
            ]

        case .appearance:
            return [
                pickItem("language"),
                .toggle("show_numbers", s.t("Channel numbers", "Números dos canais"), prefs.showNumbers) { model.setPref("show_numbers", .bool($0)) },
                .toggle("show_logos", s.t("Channel logos", "Logos dos canais"), prefs.showLogos) { model.setPref("show_logos", .bool($0)) },
                .nav("logo_source", s.t("Logo source", "Origem dos logótipos"), value: logoSourceLabel(prefs.logoSource), to: .logoSource),
                pickItem("sort"),
                pickItem("panel_width"),
                pickItem("text_size"),
                pickItem("ui_scale"),
                pickItem("accent"),
                pickItem("outline_color"),
                pickItem("glass_opacity"),
                pickItem("panel_timeout"),
            ]

        case .guide:
            return [
                pickItem("epg_refresh_hours"),
                pickItem("epg_offset_min"),
                .toggle("epg_in_list", s.t("Current show in the list", "Programa atual na lista"), prefs.epgInList,
                        subtitle: s.t("What's on, on every channel", "O que está a dar em cada canal")) { model.setPref("epg_in_list", .bool($0)) },
            ]

        case .parental:
            if prefs.pinHash == nil {
                return [.action("setpin", s.t("Set a PIN", "Definir PIN"),
                                subtitle: s.t("Lock groups and protect the settings", "Bloquear grupos e proteger as Definições")) { push(.newPin, from: "setpin") }]
            }
            return [
                .nav("locked", s.t("Locked groups", "Grupos bloqueados"), value: "\(prefs.lockedGroups.filter { $0.hasPrefix((model.content?.playlist.id ?? "") + ":") }.count)", to: .lockedGroups),
                .toggle("settings_pin", s.t("Protect settings", "Proteger as Definições"), prefs.settingsPin,
                        subtitle: s.t("Ask for the PIN to open them", "Pedir o PIN para as abrir")) { model.setPref("settings_pin", .bool($0)) },
                .action("changepin", s.t("Change PIN", "Mudar o PIN")) { push(.newPin, from: "changepin") },
                .action("removepin", confirm == "removepin" ? s.t("Press again to confirm", "Carrega outra vez para confirmar") : s.t("Remove PIN", "Tirar o PIN"),
                        subtitle: s.t("Unlocks every group", "Desbloqueia todos os grupos")) {
                    if confirm == "removepin" {
                        model.setPin(nil)
                        confirm = nil
                    } else {
                        confirm = "removepin"
                    }
                },
            ]

        case .lockedGroups:
            var list: [Item] = [.header("h", s.t("Ask for the PIN to open", "Pedir o PIN para abrir"))]
            list += model.allGroups.map { (g: ChannelGroup) -> Item in
                .toggle("g:\(g.id)", g.name, model.isGroupLockable(g.id)) { _ in model.toggleGroupLock(g.id) }
            }
            return list

        case .lists:
            var list: [Item] = model.playlists.map { (p: Playlist) -> Item in
                .nav("p:\(p.id)", p.name, value: p.id == model.content?.playlist.id ? s.t("In use", "Em uso") : nil,
                     subtitle: p.type == .xtream ? "Xtream Codes" : "M3U", to: .playlist(p.id))
            }
            list.append(.nav("add", s.t("Add playlist", "Juntar lista"), subtitle: "Xtream Codes, M3U", to: .newPlaylistType))
            if model.hasAccount {
                list.append(.info("site", s.t("Also at", "Também em"), "thegoat.systems"))
            }
            list += [
                .action("epg", s.t("Update TV guide now", "Atualizar o guia agora")) { model.refreshEpg() },
                .header("hr", s.t("Updates", "Atualizações")),
                pickItem("playlist_refresh_hours"),
                pickItem("epg_refresh_hours"),
            ]
            return list

        case .playlist(let id):
            guard let playlist = model.playlists.first(where: { $0.id == id }) else { return [] }
            let isCurrent = playlist.id == model.content?.playlist.id
            var list: [Item] = []
            if !isCurrent {
                list.append(.action("use", s.t("Use this playlist", "Usar esta lista")) {
                    model.switchPlaylist(playlist)
                    onClose()
                })
            }
            list.append(.action("reload", s.t("Reload channels", "Recarregar canais"),
                                subtitle: s.t("Download the playlist again", "Voltar a descarregar a lista")) {
                if isCurrent { model.reloadPlaylist() } else { model.switchPlaylist(playlist) }
                onClose()
            })
            list.append(.header("horg", s.t("Organise", "Organizar")))
            if isCurrent {
                let hidden = prefs.hiddenGroups.filter { $0.hasPrefix(id + ":") }.count
                list.append(.nav("groups", s.groups, subtitle: hidden == 0 ? s.t("All visible", "Todos visíveis") : s.t("%@ hidden", "%@ escondidos", "\(hidden)"), to: .groups))
                list.append(.nav("channels", s.t("Channels", "Canais"), subtitle: s.t("Hide channels", "Esconder canais"), to: .channelGroups))
            } else {
                list.append(.info("orgfirst", s.t("Use this playlist to organise it", "Usa esta lista para a organizar"), ""))
            }
            list.append(.header("hepg", s.t("TV guide", "Guia de programação")))
            let extra = prefs.extraEpg[id]
            list.append(.nav("epgextra", s.t("Extra guide", "Guia extra"), value: extra.flatMap { URL(string: $0)?.host } ?? s.t("None", "Nenhum"),
                             subtitle: s.t("For the channels this playlist's guide doesn't have", "Para os canais que o guia da lista não tem"), to: .extraEpg(id)))
            if extra != nil {
                list.append(.action("epgextraoff", s.t("Remove extra guide", "Tirar o guia extra")) { model.setExtraEpg(id, nil) })
            }
            list.append(.nav("edit", s.t("Edit", "Editar"), to: .playlistEdit(id)))
            list.append(.action("delete", confirm == "delete" ? s.t("Press again to delete", "Carrega outra vez para apagar") : s.t("Delete playlist", "Apagar lista"),
                                subtitle: model.hasAccount ? s.t("From the account, on every TV", "Da conta, em todas as TVs") : s.t("From this TV", "Desta TV")) {
                if confirm == "delete" {
                    confirm = nil
                    Task {
                        if let error = await model.deletePlaylist(playlist) {
                            model.showToast(error)
                        } else {
                            model.showToast(s.t("Playlist deleted", "Lista apagada"))
                            back()
                        }
                    }
                } else {
                    confirm = "delete"
                }
            })
            if isCurrent, model.hasAccount {
                list.append(.nav("epgcountry", s.t("Guide by country", "Guia por país"),
                                 subtitle: s.t("Channels with the same name use that country's guide", "Os canais com o mesmo nome passam a usar o guia desse país"),
                                 to: .epgCountries))
                if !model.epgMappings.isEmpty {
                    list.append(.action("epgreset", confirm == "epgreset" ? s.t("Press again to confirm", "Carrega outra vez para confirmar")
                                            : s.t("Use the playlist's guide on every channel", "Usar o guia da lista em todos os canais"),
                                        subtitle: s.t("Undoes every guide change in this playlist", "Desfaz todas as trocas de guia desta lista")) {
                        if confirm == "epgreset" {
                            model.resetEpgMappings()
                            confirm = nil
                        } else {
                            confirm = "epgreset"
                        }
                    })
                }
            }
            return list

        case .groups:
            var list: [Item] = [
                .nav("reorder", s.t("Reorder groups", "Reordenar grupos"),
                     subtitle: s.t("OK to pick up a group, ↑/↓ to move it, OK to drop it", "OK para pegar num grupo, ↑/↓ para o mover, OK para largar"), to: .groupsOrder),
                .action("resetorder", s.t("Restore the playlist's order", "Repor a ordem da lista"),
                        subtitle: s.t("Back to the provider's order", "Volta à ordem do fornecedor")) { model.resetGroupOrder() },
                .header("h", s.r("visible_groups")),
            ]
            list.append(.eye("show_favorites_group", s.favorites, prefs.showFavoritesGroup) { model.setPref("show_favorites_group", .bool($0)) })
            list.append(.eye("show_all_group", s.all, prefs.showAllGroup) { model.setPref("show_all_group", .bool($0)) })
            list.append(.eye("show_recent_group", s.recents, prefs.showRecentGroup) { model.setPref("show_recent_group", .bool($0)) })
            list += model.allGroups.map { (g: ChannelGroup) -> Item in
                .eye("g:\(g.id)", g.name, !model.isGroupHidden(g.id)) { visible in model.setGroupHidden(g.id, !visible) }
            }
            return list

        case .channelGroups:
            return model.allGroups.map { (g: ChannelGroup) -> Item in
                let all = model.groupChannels(g.id, includeHidden: true)
                let visible = all.filter { !model.isChannelHidden($0) }.count
                return .nav("g:\(g.id)", g.name, value: visible == all.count ? "\(all.count)" : "\(visible) / \(all.count)", to: .channels(g.id))
            }

        case .channels(let g):
            var list: [Item] = [
                .nav("reorder", s.t("Reorder channels", "Reordenar canais"),
                     subtitle: s.t("OK to pick up a channel, ↑/↓ to move it, OK to drop it", "OK para pegar num canal, ↑/↓ para o mover, OK para largar"), to: .channelsOrder(g)),
                .action("resetorder", s.t("Restore the playlist's order", "Repor a ordem da lista"),
                        subtitle: s.t("Back to the provider's order", "Volta à ordem do fornecedor")) { model.resetChannelOrder(g) },
                .header("h", s.r("visible_channels")),
            ]
            list += model.groupChannels(g, includeHidden: true).map { (ch: Channel) -> Item in
                .eye("c:\(ch.key)", ch.name, !model.isChannelHidden(ch)) { visible in model.setChannelHidden(ch, !visible) }
            }
            return list

        case .account where !model.hasAccount:
            var list: [Item] = [
                .info("email", s.account, s.t("No account (this TV only)", "Sem conta (só nesta TV)")),
                .action("connect", s.t("Connect a GOAT account", "Ligar uma conta GOAT"),
                        subtitle: s.t("Playlists and settings on every device", "Listas e definições em todos os aparelhos")) { model.connectAccount() },
                .info("version", s.t("Version", "Versão"), model.appVersion),
                .nav("licenses", s.t("Licences", "Licenças"), value: "GPL-3.0", to: .licenses),
                reportItem,
            ]
            if model.topShelfStatus != nil { list.append(.nav("topshelf", "Top Shelf", to: .topShelf)) }
            return list

        case .account:
            var list: [Item] = [
                .info("email", s.account, model.email.map(Self.maskEmail) ?? "—"),
                .info("device", s.t("This TV", "Esta TV"), model.deviceName ?? "—"),
                .action("logout", confirm == "logout" ? s.t("Press again to confirm", "Carrega outra vez para confirmar") : s.t("Sign out of this TV", "Terminar sessão nesta TV")) {
                    if confirm == "logout" { model.signOut() } else { confirm = "logout" }
                },
                .info("version", s.t("Version", "Versão"), model.appVersion),
                .nav("licenses", s.t("Licences", "Licenças"), value: "GPL-3.0", to: .licenses),
                reportItem,
            ]
            if model.topShelfStatus != nil { list.append(.nav("topshelf", "Top Shelf", to: .topShelf)) }
            return list

        case .topShelf:
            guard let st = model.topShelfStatus else { return [] }
            func groups(_ list: [String]?) -> String {
                guard let list else { return s.t("No profile", "Sem perfil") }
                return list.isEmpty ? s.t("None", "Nenhum") : list.joined(separator: ", ")
            }
            return [
                .info("ts-state", s.t("State", "Estado"), st.shared == nil ? s.t("No shared folder", "Sem pasta partilhada") : s.t("Shared folder OK", "Pasta partilhada OK")),
                .info("ts-shared", "App Group", st.shared ?? "—"),
                .info("ts-app", s.t("App signature", "Assinatura da app"), groups(st.appGroups)),
                .info("ts-ext", s.t("Extension signature", "Assinatura da extensão"), groups(st.extensionGroups)),
                .info("ts-run", s.t("Extension last ran", "A extensão correu"), st.lastRun ?? s.t("Never", "Nunca")),
            ]

        case .licenses:
            return [
                .info("goattv", "GoatTV", "GPL-3.0"),
                .info("vlc", "VLC (TVVLCKit)", "LGPL-2.1"),
            ]

        case .pick(let key):
            let sp = spec(key)
            return sp.options.indices.map { (i: Int) -> Item in
                let option = sp.options[i]
                return .check("o\(i)", option.label, option.value == sp.current, swatch: option.swatch) {
                    model.setPref(key, option.value)
                    back()
                }
            }

        case .epgPick, .epgCountry, .logoSource, .epgCountries:
            return remoteItems(p)

        case .newPlaylistType:
            return [
                .action("xtream", "Xtream Codes", subtitle: s.t("Server, username and password", "Servidor, utilizador e password")) {
                    form = PlaylistForm(type: .xtream)
                    replace(.playlistEdit(nil))
                },
                .action("m3u", s.t("M3U playlist", "Lista M3U"), subtitle: s.t("A link to an .m3u file", "Um endereço de um ficheiro .m3u")) {
                    form = PlaylistForm(type: .m3u)
                    replace(.playlistEdit(nil))
                },
            ]

        case .playlistEdit(let id):
            func field(_ key: String, optional: Bool = false) -> Item {
                let value = fieldValue(key)
                let shown = value.isEmpty ? (optional ? s.t("Optional", "Opcional") : "—") : (key == "pass" ? String(repeating: "•", count: min(8, value.count)) : value)
                return .nav(key, fieldTitle(key), value: shown, to: .formField(key))
            }
            var list: [Item] = []
            if id != nil, model.hasAccount {
                list.append(.info("type", s.t("Type", "Tipo"), form.type == .xtream ? "Xtream Codes" : s.t("M3U playlist", "Lista M3U")))
            } else {
                list.append(.action("type", s.t("Type", "Tipo"), subtitle: form.type == .xtream ? "Xtream Codes" : s.t("M3U playlist", "Lista M3U")) {
                    form.type = form.type == .xtream ? .m3u : .xtream
                })
            }
            list.append(field("name", optional: true))
            if form.type == .xtream {
                list += [field("server"), field("user"), field("pass")]
            } else {
                list.append(field("url"))
            }
            list.append(field("epg", optional: true))
            let hint = model.hasAccount ? s.t("Saved in the account (on every TV)", "Fica na conta (em todas as TVs)") : s.t("Only on this TV", "Só nesta TV")
            list.append(.action("save", saving ? s.t("Saving…", "A gravar…") : s.t("Save", "Guardar"), subtitle: hint) {
                guard !saving else { return }
                saving = true
                Task {
                    let error = await model.savePlaylist(id: id, form: form)
                    saving = false
                    if let error {
                        model.showToast(error)
                    } else {
                        model.showToast(s.t("Playlist saved", "Lista guardada"))
                        onClose()
                    }
                }
            })
            return list

        case .settingsGate, .parentalGate, .newPin, .userAgent, .extraEpg, .epgSearchOther, .groupsOrder, .channelsOrder, .formField:
            return []
        }
    }

    /// "Canal do guia" e "Logótipo do canal" (só com conta), como no Android.
    private func guideItems(_ channel: Channel) -> [Item] {
        guard model.hasAccount else {
            return [.action("epg", s.t("Guide channel", "Canal do guia"), subtitle: s.t("Needs a GOAT account", "Precisa de conta GOAT")) {}]
        }
        let mapping = model.epgMappings[channel.key]
        let choice = model.channelLogoChoice(channel)
        let logoValue = choice == nil ? s.t("Automatic", "Automático") : (choice!.isEmpty ? s.t("From the playlist", "Da lista") : s.t("Chosen", "Escolhido"))
        return [
            .nav("epg", s.t("Guide channel", "Canal do guia"), value: mapping.map(guideLabel) ?? s.t("Playlist's guide", "Guia da lista"),
                 to: .epgPick(channel.key, false, channel.name)),
            .nav("logo", s.t("Channel logo", "Logótipo do canal"), value: logoValue, to: .epgPick(channel.key, true, channel.name)),
        ]
    }

    private func guideLabel(_ m: EpgMapping) -> String {
        let name = m.guideName ?? m.channelId ?? ""
        guard let c = m.country, !c.isEmpty else { return name }
        return "\(name) (\(c.uppercased()))"
    }

    private func logoSourceLabel(_ source: String) -> String {
        switch source {
        case Prefs.LogoSource.playlist: return s.t("From the playlist", "Da lista")
        case Prefs.LogoSource.guide: return s.t("From each channel's guide", "Do guia de cada canal")
        default: return countryName(source)
        }
    }

    private func countryName(_ code: String) -> String {
        s.locale.localizedString(forRegionCode: code.uppercased()) ?? code.uppercased()
    }

    private var loadingItem: Item { .info("loading", s.t("Loading…", "A carregar…"), "") }

    /// As páginas que dependem do servidor do guia.
    private func remoteItems(_ p: Page) -> [Item] {
        let prefs = model.prefs
        switch p {
        case .epgPick(let key, let logo, let query):
            guard let ch = model.content?.byKey[key] else { return [] }
            let mapping = model.epgMappings[key]
            let choice = model.channelLogoChoice(ch)
            func usable(_ g: EpgGuideChannel) -> Bool { !logo || g.icon != nil }
            func chosen(_ country: String?, _ g: EpgGuideChannel) -> Bool {
                logo ? choice == g.icon : (mapping?.country == country && mapping?.channelId == g.id)
            }
            func choose(_ country: String, _ g: EpgGuideChannel) {
                if logo {
                    model.setChannelLogo(ch, g.icon)
                    popToCurrent()
                } else {
                    Task { if await model.setEpgMapping(ch, country: country, guide: g) { popToCurrent() } }
                }
            }
            var list: [Item] = []
            if logo {
                list.append(.check("auto", s.t("Automatic", "Automático"), choice == nil, subtitle: logoSourceLabel(prefs.logoSource)) {
                    model.setChannelLogo(ch, nil)
                    popToCurrent()
                })
                list.append(.logoCheck("playlist", s.t("From the playlist", "Da lista"), choice == "", subtitle: nil, logo: ch.logo) {
                    model.setChannelLogo(ch, "")
                    popToCurrent()
                })
            } else {
                list.append(.check("playlist", s.t("Playlist's guide", "Guia da lista"), mapping == nil,
                                   subtitle: mapping.map { s.t("Using %@", "A usar %@", "\(guideLabel($0))") }) {
                    if mapping != nil { Task { if await model.removeEpgMapping(ch) { popToCurrent() } } }
                })
            }
            guard case .pick(let suggest, let search)? = remote else { return list + [loadingItem] }
            if case .ok(let suggestions) = suggest {
                let usableSuggestions = suggestions.filter { $0.id != nil && $0.country != nil && usable($0) }
                if !usableSuggestions.isEmpty {
                    list.append(.header("hs", s.t("Suggestions", "Sugestões")))
                    for g in usableSuggestions {
                        let subtitle = [g.countryName, g.now].compactMap { $0 }.joined(separator: " · ")
                        let id = "s:\(g.country ?? ""):\(g.id ?? "")"
                        if logo {
                            list.append(.logoCheck(id, g.name ?? g.id ?? "", chosen(g.country, g), subtitle: subtitle, logo: g.icon) { choose(g.country!, g) })
                        } else {
                            list.append(.check(id, g.name ?? g.id ?? "", chosen(g.country, g), subtitle: subtitle) { choose(g.country!, g) })
                        }
                    }
                }
            }
            list.append(.header("hc", s.t("Countries with “%@”", "Países com “%@”", "\(query)")))
            switch search {
            case .error(let message):
                list.append(.info("err", message, ""))
            case .ok(let results):
                let countries = results.compactMap { r -> EpgCountryResult? in
                    var c = r
                    c.channels = r.channels.filter(usable)
                    return c.country != nil && !c.channels.isEmpty ? c : nil
                }
                if countries.isEmpty { list.append(.info("none", s.t("Nothing found for “%@”", "Nada encontrado para “%@”", "\(query)"), "")) }
                for c in countries {
                    let code = c.country!
                    let name = c.countryName ?? code.uppercased()
                    list.append(.nav("c:\(code)", name, value: "\(c.channels.count)", to: .epgCountry(key, logo, code, name, c.channels)))
                }
            }
            list.append(.nav("other", s.t("Search another name", "Procurar outro nome"), to: .epgSearchOther(key, logo)))
            return list

        case .epgCountry(let key, let logo, let country, let name, let matches):
            guard let ch = model.content?.byKey[key] else { return [] }
            let mapping = model.epgMappings[key]
            let choice = model.channelLogoChoice(ch)
            var channels: [EpgGuideChannel]
            if let matches {
                channels = matches
            } else {
                switch remote {
                case .country(.ok(let r))?: channels = r.channels
                case .country(.error(let message))?: return [.info("err", message, "")]
                default: return [loadingItem]
                }
            }
            channels = channels.filter { $0.id != nil && (!logo || $0.icon != nil) }
            var list: [Item] = channels.map { g -> Item in
                if logo {
                    return .logoCheck("g:\(g.id!)", g.name ?? g.id!, choice == g.icon, subtitle: g.now, logo: g.icon) {
                        model.setChannelLogo(ch, g.icon)
                        popToCurrent()
                    }
                }
                return .check("g:\(g.id!)", g.name ?? g.id!, mapping?.country == country && mapping?.channelId == g.id, subtitle: g.now) {
                    Task { if await model.setEpgMapping(ch, country: country, guide: g) { popToCurrent() } }
                }
            }
            if matches != nil {
                list.append(.nav("all", s.t("All channels in %@", "Todos os canais de %@", "\(name)"), to: .epgCountry(key, logo, country, name, nil)))
            }
            if logo {
                list.append(.action("whole", s.t("Use %@'s logos on every channel", "Usar os logótipos de %@ em todos os canais", "\(name)"),
                                    subtitle: s.t("Channels with the same name", "Nos canais com o mesmo nome")) {
                    model.setLogoSource(country)
                    popToCurrent()
                })
            } else {
                list.append(.action("whole", s.t("Use %@'s guide on the whole playlist", "Usar o guia de %@ na lista inteira", "\(name)"),
                                    subtitle: s.t("Channels you changed one by one stay as they are", "Os que mudaste um a um ficam como estão")) {
                    model.applyEpgCountry(country, name: name)
                    popToCurrent()
                })
            }
            return list

        case .logoSource:
            var list: [Item] = [.check(Prefs.LogoSource.playlist, s.t("From the playlist", "Da lista"), prefs.logoSource == Prefs.LogoSource.playlist) {
                model.setLogoSource(nil)
                back()
            }]
            guard model.hasAccount else {
                list.append(.action("account", s.t("By country", "Por país"), subtitle: s.t("Needs a GOAT account", "Precisa de conta GOAT")) {})
                return list
            }
            list.append(.check(Prefs.LogoSource.guide, s.t("From each channel's guide", "Do guia de cada canal"), prefs.logoSource == Prefs.LogoSource.guide,
                               subtitle: s.t("Channels with a guide chosen in GoatTV", "Nos canais com guia escolhido na GoatTV")) {
                model.setLogoSource(Prefs.LogoSource.guide)
                back()
            })
            list.append(.header("hc", s.t("By country", "Por país")))
            switch remote {
            case .countries(.ok(let countries))?:
                for c in countries.filter({ $0.code != nil && $0.channels > 0 }).sorted(by: { ($0.name ?? $0.code!) < ($1.name ?? $1.code!) }) {
                    list.append(.check("k:\(c.code!)", c.name ?? c.code!.uppercased(), prefs.logoSource == c.code) {
                        model.setLogoSource(c.code)
                        back()
                    })
                }
            case .countries(.error(let message))?:
                list.append(.info("err", message, ""))
            default:
                list.append(loadingItem)
            }
            return list

        case .epgCountries:
            switch remote {
            case .countries(.ok(let countries))?:
                return countries.filter { $0.code != nil && $0.channels > 0 }.sorted { ($0.name ?? $0.code!) < ($1.name ?? $1.code!) }.map { c -> Item in
                    let name = c.name ?? c.code!.uppercased()
                    return .action("k:\(c.code!)", name, subtitle: "\(c.channels)") {
                        model.applyEpgCountry(c.code!, name: name)
                        back()
                    }
                }
            case .countries(.error(let message))?:
                return [.info("err", message, "")]
            default:
                return [loadingItem]
            }
        default:
            return []
        }
    }

    private func fieldTitle(_ key: String) -> String {
        switch key {
        case "name": return s.t("Name", "Nome")
        case "server": return s.t("Server", "Servidor")
        case "user": return s.t("Username", "Utilizador")
        case "pass": return s.t("Password", "Password")
        case "url": return s.t("Playlist address (M3U)", "Endereço da lista (M3U)")
        default: return s.t("Guide address (XMLTV)", "Endereço do guia (XMLTV)")
        }
    }

    private func fieldValue(_ key: String) -> String {
        switch key {
        case "name": return form.name
        case "server": return form.server
        case "user": return form.username
        case "pass": return form.password
        case "url": return form.url
        default: return form.epgUrl
        }
    }

    private func setField(_ key: String, _ value: String) {
        switch key {
        case "name": form.name = value
        case "server": form.server = value
        case "user": form.username = value
        case "pass": form.password = value
        case "url": form.url = value
        default: form.epgUrl = value
        }
    }

    private func trackName(_ tracks: [PlayerEngine.Track], _ id: Int32) -> String? {
        if id < 0 { return s.t("Off", "Desligadas") }
        return tracks.first { $0.id == id }?.name
    }

    /// "j••••@example.com", como no Android.
    static func maskEmail(_ email: String) -> String {
        guard let at = email.firstIndex(of: "@"), at > email.startIndex else { return email }
        return String(email[email.startIndex]) + "••••••" + email[at...]
    }

    // MARK: Opções de cada definição (as mesmas do Android)

    struct Option {
        let value: JSON?
        let label: String
        var swatch: AnyShapeStyle?
    }

    private func pickItem(_ key: String) -> Item {
        let sp = spec(key)
        let label = sp.options.first { $0.value == sp.current }?.label
        return .nav(key, sp.title, value: label, to: .pick(key))
    }

    private func spec(_ key: String) -> (title: String, options: [Option], current: JSON?) {
        let p = model.prefs
        func o(_ v: JSON?, _ label: String, _ swatch: AnyShapeStyle? = nil) -> Option { Option(value: v, label: label, swatch: swatch) }
        func n(_ v: Int) -> JSON? { .number(Double(v)) }
        func after(_ v: Int) -> String { s.t("After %@ s", "Ao fim de %@ s", "\(v)") }
        func refresh(_ h: Int) -> String { h == 0 ? s.t("Every time the app opens", "Sempre que a app abre") : s.t("Every %1$@ h", "De %1$@ em %1$@ h", "\(h)") }
        switch key {
        case "language":
            return (s.r("language"),
                    [o("auto", s.r("language_auto"))] + Strings.languages.map { o(.string($0.code), $0.name) },
                    .string(p.language == "pt" ? "pt-PT" : p.language))
        case "startup":
            return (s.t("When the app opens", "Ao abrir a app"),
                    [o("last", s.t("Last channel", "Último canal")), o("list", s.t("Last channel with the list open", "Último canal com a lista aberta"))],
                    .string(p.startup))
        case "engine":
            return (s.t("Player", "Player"),
                    [o("exo", s.t("Default (Apple player for HLS, VLC for the rest)", "Normal (o da Apple nos HLS, o VLC no resto)")),
                     o("vlc", s.t("VLC (for streams that don't open)", "VLC (para streams que não abram)"))],
                    .string(p.engine))
        case "decoder":
            return (s.t("Video decoder (VLC)", "Descodificador de vídeo (VLC)"),
                    [o("hw", s.t("Hardware", "Hardware")), o("sw", s.t("Software (compatibility)", "Software (compatibilidade)"))],
                    .string(p.decoder))
        case "deinterlace":
            return (s.t("Deinterlace (VLC)", "Desentrelaçar (VLC)"),
                    [o("yadif2x", s.t("Smooth (50/60 frames)", "Suave (50/60 imagens)")), o("yadif", s.t("Normal", "Normal")),
                     o("off", s.t("Off", "Desligado"))],
                    .string(p.deinterlace))
        case "hdr":
            return (s.t("HDR channels", "Canais HDR"),
                    [o("auto", s.t("In HDR when the TV supports it", "Em HDR quando a TV aceita")), o("sdr", s.t("Always in SDR", "Sempre em SDR"))],
                    .string(p.hdr))
        case "back_action":
            return (s.t("Back button in the player", "Botão Voltar no player"),
                    [o("guide", s.t("Opens the TV guide", "Abre o guia TV")), o("exit", s.t("Exits the app", "Sai da app"))],
                    .string(p.backAction))
        case "stream_format":
            return (s.t("Stream format", "Formato do stream"),
                    [o(nil, "Auto"), o("ts", "TS (MPEG-TS)"), o("m3u8", "HLS (m3u8)")],
                    p.streamFormat.map { .string($0) })
        case "buffer":
            return (s.t("Buffer", "Buffer"),
                    [o("live", s.t("Live (closest to real time)", "Live (o mais perto do direto)")),
                     o("small", s.t("Small (fast start)", "Pequeno (arranque rápido)")),
                     o("normal", s.t("Normal", "Normal")),
                     o("large", s.t("Large (unstable network)", "Grande (rede instável)"))],
                    .string(p.buffer))
        case "aspect":
            return (s.t("Aspect ratio", "Proporção"),
                    [o("fit", s.t("Fit", "Ajustar")), o("zoom", s.t("Fill (crop)", "Preencher (cortar)")), o("fill", s.t("Stretch", "Esticar"))],
                    .string(p.aspect))
        case "reconnect_seconds":
            return (s.t("Reconnect if the stream fails", "Reconectar se a stream falhar"),
                    [o(n(0), s.t("No", "Não"))] + [2, 5, 10, 30].map { o(n($0), after($0)) },
                    n(p.reconnectSeconds))
        case "up_down":
            return (s.t("↑/↓ keys in the player", "Teclas ↑/↓ no player"),
                    [o("zap", s.t("Change channel", "Mudar de canal")), o("list", s.t("Open the list", "Abrir a lista"))],
                    .string(p.upDown))
        case "info_seconds":
            return (s.t("Info when switching", "Informação ao mudar de canal"),
                    [o(n(0), s.t("Don't show", "Não mostrar"))] + [3, 4, 6, 10].map { o(n($0), s.t("%@ seconds", "%@ segundos", "\($0)")) },
                    n(p.infoSeconds))
        case "sort":
            return (s.t("Sort channels", "Ordenar canais"),
                    [o("number", s.t("Playlist order", "Pela ordem da lista")), o("name", s.t("By name (A–Z)", "Por nome (A–Z)"))],
                    .string(p.sort))
        case "panel_width":
            return (s.t("Panel width", "Largura dos painéis"),
                    [o(n(300), s.t("Narrow", "Estreita")), o(n(340), s.t("Normal", "Normal")), o(n(400), s.t("Wide", "Larga")), o(n(460), s.t("Extra wide", "Muito larga"))],
                    n(p.panelWidth))
        case "accent":
            let names: [(String, String)] = [("itv", "GoatTV"), ("violet", s.t("Violet", "Violeta")), ("mint", s.t("Mint", "Menta")),
                                              ("sunset", s.t("Sunset", "Pôr do sol")), ("gold", s.t("Gold", "Dourado")), ("white", s.t("White", "Branco"))]
            return (s.t("Accent colour", "Cor de destaque"),
                    names.map { o(.string($0.0), $0.1, AnyShapeStyle(Theme.accent($0.0))) },
                    .string(p.accent))
        case "outline_color":
            let names: [(String, String)] = [("white", s.t("White", "Branco")), ("accent", s.t("Accent colour", "Cor de destaque")),
                                              ("blue", s.t("Blue", "Azul")), ("violet", s.t("Violet", "Violeta")), ("pink", s.t("Pink", "Rosa")),
                                              ("red", s.t("Red", "Vermelho")), ("orange", s.t("Orange", "Laranja")),
                                              ("yellow", s.t("Yellow", "Amarelo")), ("green", s.t("Green", "Verde"))]
            return (s.t("Selector colour", "Cor do seletor"),
                    names.map { o(.string($0.0), $0.1, Theme.outline($0.0, accent: p.accent)) },
                    .string(p.outlineColor))
        case "ui_scale":
            return (s.r("ui_scale"), stride(from: 80, through: 120, by: 5).map { o(n($0), "\($0)%") }, n(p.uiScale))
        case "text_size":
            return (s.t("Text size", "Tamanho do texto"),
                    [o("small", s.t("Small", "Pequeno")), o("normal", s.t("Normal", "Normal")), o("large", s.t("Large", "Grande"))],
                    .string(p.textSize))
        case "glass_opacity":
            return (s.t("Glass opacity", "Opacidade do vidro"),
                    stride(from: 0, through: 100, by: 10).map { o(n($0), "\($0)%") },
                    n(p.glassOpacity))
        case "panel_timeout":
            return (s.t("Auto-close panels", "Fechar painéis sozinho"),
                    [10, 15, 30].map { o(n($0), after($0)) } + [o(n(0), s.t("Never", "Nunca"))],
                    n(p.panelTimeout))
        case "epg_refresh_hours":
            return (s.t("Update TV guide", "Atualizar guia"), Prefs.refreshChoices.map { o(n($0), refresh($0)) }, n(p.epgRefreshHours))
        case "playlist_refresh_hours":
            return (s.t("Update playlists", "Atualizar listas"), Prefs.refreshChoices.map { o(n($0), refresh($0)) }, n(p.playlistRefreshHours))
        case "epg_offset_min":
            let options = stride(from: -180, through: 180, by: 30).map { (m: Int) -> Option in
                let sign = m > 0 ? "+" : (m < 0 ? "−" : "")
                let a = abs(m)
                let label = m == 0 ? s.t("None", "Nenhum") : "\(sign)\(a / 60):\(String(format: "%02d", a % 60))"
                return o(n(m), label)
            }
            return (s.t("Time offset", "Desvio horário"), options, n(p.epgOffsetMin))
        default:
            return (key, [], nil)
        }
    }
}
