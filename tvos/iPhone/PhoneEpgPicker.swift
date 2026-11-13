import GoatCore
import SwiftUI

/// Com conta: escolher o guia ou o logótipo de um canal (sugestões, pesquisa e países, como na TV).
struct PhoneEpgPick: View {
    @Environment(AppModel.self) private var model
    let channel: Channel
    let logo: Bool
    let done: () -> Void
    @State private var query: String
    @State private var suggestions: AppModel.EpgResult<[EpgGuideChannel]>?
    @State private var search: AppModel.EpgResult<[EpgCountryResult]>?

    init(channel: Channel, logo: Bool, done: @escaping () -> Void) {
        self.channel = channel
        self.logo = logo
        self.done = done
        _query = State(initialValue: channel.name)
    }

    var body: some View {
        let s = model.strings
        let mapping = model.epgMappings[channel.key]
        let choice = model.channelLogoChoice(channel)
        List {
            if logo {
                PhoneEpgRow(title: s.t("Automatic", "Automático"), checked: choice == nil) {
                    model.setChannelLogo(channel, nil)
                    done()
                }
                PhoneEpgRow(title: s.t("From the playlist", "Da lista"), logo: channel.logo ?? "", checked: choice == "") {
                    model.setChannelLogo(channel, "")
                    done()
                }
            } else {
                PhoneEpgRow(title: s.t("Playlist's guide", "Guia da lista"), checked: mapping == nil) {
                    guard mapping != nil else { return done() }
                    Task { if await model.removeEpgMapping(channel) { done() } }
                }
            }
            if case .ok(let list)? = suggestions {
                let usable = list.filter { $0.id != nil && $0.country != nil && (!logo || $0.icon != nil) }
                if !usable.isEmpty {
                    Section(s.t("Suggestions", "Sugestões")) {
                        ForEach(usable, id: \.self) { g in
                            PhoneEpgRow(title: g.name ?? g.id ?? "", subtitle: [g.countryName, g.now].compactMap { $0 }.joined(separator: " · "),
                                        logo: logo ? (g.icon ?? "") : nil, checked: phoneEpgChosen(model, channel, logo, g.country, g)) {
                                phoneEpgChoose(model, channel, logo, g.country!, g, done)
                            }
                        }
                    }
                }
            }
            Section(s.t("Countries with “%@”", "Países com “%@”", query)) {
                switch search {
                case nil:
                    ProgressView()
                case .error(let message)?:
                    Text(message).foregroundStyle(Theme.textFaint)
                case .ok(let results)?:
                    let countries = results.compactMap { r -> EpgCountryResult? in
                        var c = r
                        c.channels = r.channels.filter { !logo || $0.icon != nil }
                        return c.country != nil && !c.channels.isEmpty ? c : nil
                    }
                    if countries.isEmpty { Text(s.t("Nothing found for “%@”", "Nada encontrado para “%@”", query)).foregroundStyle(Theme.textFaint) }
                    ForEach(countries, id: \.self) { c in
                        let name = c.countryName ?? c.country!.uppercased()
                        NavigationLink {
                            PhoneEpgCountry(channel: channel, logo: logo, country: c.country!, name: name, matches: c.channels, done: done)
                        } label: {
                            LabeledContent(name, value: "\(c.channels.count)")
                        }
                    }
                }
            }
        }
        .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: s.t("Search another name", "Procurar outro nome"))
        .autocorrectionDisabled()
        .navigationTitle(logo ? s.t("Channel logo", "Logótipo do canal") : s.t("Guide channel", "Canal do guia"))
        .navigationBarTitleDisplayMode(.inline)
        .task { suggestions = await model.epgSuggest(channel) }
        .task(id: query) {
            search = nil
            try? await Task.sleep(for: .milliseconds(400))
            guard !Task.isCancelled else { return }
            let result = await model.epgSearch(query)
            if !Task.isCancelled { search = result }
        }
    }
}

/// Os canais de guia de um país: os que a pesquisa encontrou, ou todos.
private struct PhoneEpgCountry: View {
    @Environment(AppModel.self) private var model
    let channel: Channel
    let logo: Bool
    let country: String
    let name: String
    let matches: [EpgGuideChannel]?
    let done: () -> Void
    @State private var all: AppModel.EpgResult<EpgCountryResult>?

    var body: some View {
        let s = model.strings
        List {
            if matches == nil, case .error(let message)? = all { Text(message).foregroundStyle(Theme.textFaint) }
            if matches == nil, all == nil { ProgressView() }
            ForEach(channels, id: \.self) { g in
                PhoneEpgRow(title: g.name ?? g.id!, subtitle: g.now, logo: logo ? (g.icon ?? "") : nil, checked: phoneEpgChosen(model, channel, logo, country, g)) {
                    phoneEpgChoose(model, channel, logo, country, g, done)
                }
            }
            if matches != nil {
                NavigationLink(s.t("All channels in %@", "Todos os canais de %@", name)) {
                    PhoneEpgCountry(channel: channel, logo: logo, country: country, name: name, matches: nil, done: done)
                }
            }
            if logo {
                Button(s.t("Use %@'s logos on every channel", "Usar os logótipos de %@ em todos os canais", name)) {
                    model.setLogoSource(country)
                    done()
                }
            } else {
                Button(s.t("Use %@'s guide on the whole playlist", "Usar o guia de %@ na lista inteira", name)) {
                    model.applyEpgCountry(country, name: name)
                    done()
                }
            }
        }
        .navigationTitle(name)
        .navigationBarTitleDisplayMode(.inline)
        .task { if matches == nil { all = await model.epgCountryChannels(country) } }
    }

    private var channels: [EpgGuideChannel] {
        var list = matches ?? []
        if matches == nil, case .ok(let r)? = all { list = r.channels }
        return list.filter { $0.id != nil && (!logo || $0.icon != nil) }
    }
}

/// A lista inteira: o guia de um país, ou a origem dos logótipos.
struct PhoneEpgCountries: View {
    @Environment(AppModel.self) private var model
    let logo: Bool
    let done: () -> Void
    @State private var countries: AppModel.EpgResult<[EpgCountryInfo]>?

    var body: some View {
        let s = model.strings
        NavigationStack {
            List {
                if logo {
                    PhoneEpgRow(title: s.t("From the playlist", "Da lista"), checked: model.prefs.logoSource == Prefs.LogoSource.playlist) {
                        model.setLogoSource(nil)
                        done()
                    }
                    PhoneEpgRow(title: s.t("From each channel's guide", "Do guia de cada canal"), checked: model.prefs.logoSource == Prefs.LogoSource.guide) {
                        model.setLogoSource(Prefs.LogoSource.guide)
                        done()
                    }
                }
                switch countries {
                case nil:
                    ProgressView()
                case .error(let message)?:
                    Text(message).foregroundStyle(Theme.textFaint)
                case .ok(let list)?:
                    ForEach(list.filter { $0.code != nil && $0.channels > 0 }.sorted { ($0.name ?? $0.code!) < ($1.name ?? $1.code!) }, id: \.self) { c in
                        let name = c.name ?? c.code!.uppercased()
                        PhoneEpgRow(title: name, subtitle: logo ? nil : "\(c.channels)", checked: logo && model.prefs.logoSource == c.code) {
                            if logo { model.setLogoSource(c.code) } else { model.applyEpgCountry(c.code!, name: name) }
                            done()
                        }
                    }
                }
            }
            .navigationTitle(logo ? s.t("Logo source", "Origem dos logótipos") : s.t("Guide by country", "Guia por país"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(s.t("Close", "Fechar"), action: done) } }
        }
        .task { countries = await model.epgCountries() }
    }
}

private struct PhoneEpgRow: View {
    let title: String
    var subtitle: String?
    /// nil: sem logótipo nesta linha.
    var logo: String?
    let checked: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                if let logo { ChannelLogo(url: URL(string: logo), name: title, size: CGSize(width: 56, height: 38)) }
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).foregroundStyle(Theme.text)
                    if let subtitle, !subtitle.isEmpty { Text(subtitle).font(.footnote).foregroundStyle(Theme.textFaint).lineLimit(1) }
                }
                Spacer()
                if checked { Image(systemName: "checkmark").foregroundStyle(Theme.sky) }
            }
        }
    }
}

@MainActor private func phoneEpgChosen(_ model: AppModel, _ channel: Channel, _ logo: Bool, _ country: String?, _ g: EpgGuideChannel) -> Bool {
    if logo { return model.channelLogoChoice(channel) == g.icon }
    let mapping = model.epgMappings[channel.key]
    return mapping?.country == country && mapping?.channelId == g.id
}

@MainActor private func phoneEpgChoose(_ model: AppModel, _ channel: Channel, _ logo: Bool, _ country: String, _ g: EpgGuideChannel, _ done: @escaping () -> Void) {
    if logo {
        model.setChannelLogo(channel, g.icon)
        done()
    } else {
        Task { if await model.setEpgMapping(channel, country: country, guide: g) { done() } }
    }
}

/// Escolher o segundo canal: a lista (se houver mais do que uma permitida), o grupo e o canal.
struct PhoneSecondPicker: View {
    @Environment(AppModel.self) private var model
    let onPick: (PlaylistContent, Channel, String) -> Void
    let onClose: () -> Void
    @State private var content: PlaylistContent?
    @State private var group: ChannelGroup?
    @State private var loading = false
    @State private var failed = false

    var body: some View {
        let s = model.strings
        let allowed = model.playlists.filter { model.secondAllowed($0, replacing: model.second == nil ? nil : 0) }
        NavigationStack {
            List {
                if loading {
                    ProgressView()
                } else if let content, let group {
                    ForEach(model.channelsForSecond(content, group.id)) { ch in
                        Button {
                            onPick(content, ch, group.id)
                        } label: {
                            HStack(spacing: 12) {
                                if model.prefs.showLogos {
                                    ChannelLogo(url: ch.logo.flatMap(URL.init(string:)), name: ch.name, size: CGSize(width: 56, height: 38))
                                }
                                Text(ch.name).foregroundStyle(Theme.text)
                            }
                        }
                    }
                } else if let content {
                    ForEach(model.groupsForSecond(content)) { g in
                        Button(g.name) { group = g }.foregroundStyle(Theme.text)
                    }
                } else if allowed.isEmpty {
                    Text(s.t("Your playlist only allows one connection at a time.", "A tua lista só permite uma ligação de cada vez."))
                        .foregroundStyle(Theme.textFaint)
                } else {
                    if failed { Text(s.t("Couldn't load the playlist.", "Não foi possível carregar a lista.")).foregroundStyle(Theme.error) }
                    ForEach(allowed, id: \.id) { p in
                        Button(p.name) { open(p) }.foregroundStyle(Theme.text)
                    }
                }
            }
            .navigationTitle(group?.name ?? content?.playlist.name ?? s.t("Second channel", "Segundo canal"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(s.t("Close", "Fechar"), action: onClose)
                }
                if group != nil {
                    ToolbarItem(placement: .topBarLeading) {
                        Button { group = nil } label: { Image(systemName: "chevron.left") }
                    }
                }
            }
        }
        .onAppear { if allowed.count == 1 { open(allowed[0]) } }
    }

    private func open(_ playlist: Playlist) {
        loading = true
        failed = false
        Task {
            content = try? await model.loadForSecond(playlist)
            failed = content == nil
            loading = false
        }
    }
}
