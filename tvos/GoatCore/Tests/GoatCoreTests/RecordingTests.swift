import Foundation
@testable import GoatCore

/// As gravações: os mesmos casos do GoatTV Android (RecordingCutTest, RecordingPlanTest e as fichas de
/// RecordingFilesTest), para as duas apps lerem os mesmos ficheiros.
extension CoreTests {
    /// As fichas do Android (app/src/test/resources/formats), tal e qual.
    static let recordingFixture = #"{"version":1,"id":"20261109-203000-a1b2c3","playlistId":"local0123456789abcdef","channelKey":"101","channelName":"RTP 1","logo":"https://logos.example.com/rtp1.png","title":"Telejornal","description":null,"programmeStartMs":1762719000000,"programmeEndMs":1762722600000,"startMs":1762718940000,"endMs":1762722720000,"fileStartMs":1762718940250,"fileEndMs":1762722720000,"mediaStartMs":125500,"mediaEndMs":3905250,"bytes":3780000000,"state":"done","reason":null,"gaps":1,"scheduledId":"s-1762719000000-101"}"#
    static let scheduleFixture = #"{"version":1,"recordings":[{"id":"s-1762719000000-101","playlistId":"local0123456789abcdef","channelKey":"101","channelName":"RTP 1","logo":null,"title":"Telejornal","startMs":1762719000000,"endMs":1762722600000,"candidates":[{"url":"http://iptv.example.com/live/u/p/101.ts","format":"ts"},{"url":"http://iptv.example.com/live/u/p/101.m3u8","format":"m3u8"}],"userAgent":"VLC/3.0.20 LibVLC/3.0.20","referrer":null,"seriesId":"series-1"}],"series":[{"id":"series-1","playlistId":"local0123456789abcdef","channelKey":"101","channelName":"RTP 1","logo":null,"title":"Telejornal"}],"handled":{"local0123456789abcdef|101|1762632600000":1762632600000},"paddingBeforeMin":2,"paddingAfterMin":5}"#

    static func recordingFilesReadAndWriteTheAndroidFormat() throws {
        let info = try expectValue(RecordingFiles.parseInfo(Data(recordingFixture.utf8)))
        try expect(info.id, "20261109-203000-a1b2c3")
        try expect(info.state, RecordingState.done)
        try expect(info.description, nil)
        try expect(info.gaps, 1)
        try expect(info.bytes, 3_780_000_000)
        try expect(info.durationMs, 3_779_750)
        try expect(info.json, try JSON.parse(Data(recordingFixture.utf8)))

        let schedule = Schedule(json: try JSON.parse(Data(scheduleFixture.utf8)))
        try expect(schedule.recordings.count, 1)
        try expect(schedule.recordings.first?.url, "http://iptv.example.com/live/u/p/101.ts")
        try expect(schedule.series.first?.title, "Telejornal")
        try expect(schedule.handled["local0123456789abcdef|101|1762632600000"], 1_762_632_600_000)
        try expect(schedule.paddingBeforeMin, 2)
        try expect(schedule.paddingAfterMin, 5)
        try expect(schedule.json, try JSON.parse(Data(scheduleFixture.utf8)))

        // Sem id ou estado não é uma ficha; uma margem fora das opções fica a de omissão.
        try expect(RecordingFiles.parseInfo(Data(#"{"id":"x"}"#.utf8)) == nil, true)
        let odd = Schedule(json: .object(["paddingBeforeMin": 7, "recordings": .array([.object(["id": "a"])])]))
        try expect(odd.paddingBeforeMin, RecordingPlan.paddingBeforeDefault)
        try expect(odd.recordings.isEmpty, true)

        // No disco: a ficha ao lado do vídeo, a lista à parte, e apagar leva os dois.
        let dir = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("goat-rec-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: dir) }
        let files = RecordingFiles(dir: dir.appendingPathComponent("files"), scheduleFile: dir.appendingPathComponent("schedule.json"))
        try files.write(info)
        var newer = info
        newer.id = "b"
        newer.fileStartMs += 1
        try files.write(newer)
        try Data([0x47]).write(to: files.videoFile("b"))
        try expect(files.list().map(\.id), ["b", info.id])
        try expect(files.read("b")?.fileStartMs, info.fileStartMs + 1)
        try expect(files.delete("b"), true)
        try expect(FileManager.default.fileExists(atPath: files.videoFile("b").path), false)
        try expect(files.list().map(\.id), [info.id])
        try expect(files.loadSchedule(), Schedule())
        try files.saveSchedule(schedule)
        try expect(files.loadSchedule(), schedule)
    }

    private static func expectValue<T>(_ v: T?, line: Int = #line) throws -> T {
        guard let v else { throw Failure(description: "linha \(line): veio nil") }
        return v
    }

    /// Um ponto a cada 500 ms, de [from] a [to] (tempo do vídeo), 1000 bytes cada.
    private static func cutIndex(_ from: Int64, _ to: Int64) -> [TimeshiftRing.Entry] {
        stride(from: from, through: to, by: 500).map { TimeshiftRing.Entry(mediaMs: $0, offset: $0 * 2) }
    }

    static func recordingCut() throws {
        let now: Int64 = 1_000_000
        let r = try expectValue(RecordingCut.range(cutIndex(0, 600_000), liveMediaMs: 600_000, nowWall: now, startWall: now - 420_000, endWall: now + 1_200_000))
        try expect(r.startMediaMs, 180_000)
        try expect(r.startOffset, 360_000)
        try expect(r.endMediaMs, 1_800_000)
        try expect(r.fileStartMs, now - 420_000)
        try expect(r.late, false)
        // Entre pontos fica no anterior (meio segundo a mais, nunca a menos).
        let between = try expectValue(RecordingCut.range(cutIndex(0, 600_000), liveMediaMs: 600_000, nowWall: now, startWall: now - 420_300, endWall: now))
        try expect(between.startMediaMs, 179_500)
        try expect(between.fileStartMs, now - 420_500)
        // O programa começou antes do que o anel guarda: começa no mais antigo e diz que chegou tarde.
        let late = try expectValue(RecordingCut.range(cutIndex(300_000, 600_000), liveMediaMs: 600_000, nowWall: now, startWall: now - 3_600_000, endWall: now + 60_000))
        try expect(late.startMediaMs, 300_000)
        try expect(late.late, true)
        try expect(late.endMediaMs, 660_000)
        // O fim nunca antes do início; sem índice não há corte.
        let backwards = try expectValue(RecordingCut.range(cutIndex(0, 10_000), liveMediaMs: 10_000, nowWall: now, startWall: now - 5_000, endWall: now - 60_000))
        try expect(backwards.startMediaMs, backwards.endMediaMs)
        try expect(RecordingCut.range([], liveMediaMs: 0, nowWall: 0, startWall: 0, endWall: 0) == nil, true)
        let entries = cutIndex(0, 10_000)
        try expect(RecordingCut.atOrBefore(entries, -1) == nil, true)
        try expect(RecordingCut.atOrBefore(entries, 499)?.mediaMs, 0)
        try expect(RecordingCut.atOrBefore(entries, 500)?.mediaMs, 500)
        try expect(RecordingCut.atOrBefore(entries, 99_999)?.mediaMs, 10_000)
    }

    private static let planMin: Int64 = 60_000
    private static let planStart: Int64 = 10_000_000

    private static func planRec(_ id: String = "r1", _ channel: String = "101", startMs: Int64 = planStart, endMs: Int64? = nil,
                                candidates: [StreamCandidate]? = nil) -> ScheduledRecording {
        ScheduledRecording(id: id, playlistId: "p1", channelKey: channel, channelName: "Ch \(channel)", logo: nil, title: "News",
                           startMs: startMs, endMs: endMs ?? startMs + 30 * planMin,
                           candidates: candidates ?? [StreamCandidate(url: "http://x/\(channel).ts", format: "ts"), StreamCandidate(url: "http://x/\(channel).m3u8", format: "m3u8")],
                           userAgent: "UA")
    }

    static func recordingPlanDecisions() throws {
        typealias D = RecordingPlan.Decision
        let w = RecordingPlan.window(planStart, planStart + 30 * planMin, 2, 5)
        func decide(_ now: Int64, _ watching: RecordingPlan.Watching? = nil, busy: [RecordingPlan.Busy] = [], answer: RecordingPlan.Answer? = nil,
                    r: ScheduledRecording = planRec()) -> D {
            RecordingPlan.decide(r, w, now: now, watching: watching, busy: busy, answer: answer)
        }
        try expect(w.fromMs, planStart - 2 * planMin)
        try expect(w.toMs, planStart + 35 * planMin)
        try expect(RecordingPlan.window(planStart, planStart + planMin, 0, 0), RecordingPlan.Window(fromMs: planStart, toMs: planStart + planMin))
        try expect(RecordingPlan.paddingOptions, Prefs.paddingOptions)
        // Sem nada a ver, grava à hora.
        try expect(decide(w.fromMs - 2 * planMin), D.wait)
        try expect(decide(w.fromMs - 30_000), D.wait)
        try expect(decide(w.fromMs), D.start)
        try expect(decide(planStart + 10 * planMin), D.start)
        try expect(decide(w.toMs), D.expired)
        // No mesmo canal partilha a ligação; o mesmo canal noutra lista é outro canal.
        let same = RecordingPlan.Watching("p1", "101")
        try expect(decide(w.fromMs - 30_000, same), D.wait)
        try expect(decide(w.fromMs, same), D.start)
        try expect(decide(w.fromMs - 30_000, RecordingPlan.Watching("p2", "101")), D.prompt)
        // A ver outro canal: pergunta e, sem resposta, muda à hora.
        let other = RecordingPlan.Watching("p1", "202")
        try expect(decide(w.fromMs - 61_000, other), D.wait)
        try expect(decide(w.fromMs - 60_000, other), D.prompt)
        try expect(decide(w.fromMs - 1, other), D.prompt)
        try expect(decide(w.fromMs, other), D.switchChannel)
        try expect(decide(w.fromMs - 30_000, other, answer: .switchChannel), D.switchChannel)
        try expect(decide(w.fromMs - 30_000, other, answer: .skip), D.skip(RecordingPlan.skipUser))
        try expect(decide(w.fromMs + planMin, other, answer: .skip), D.skip(RecordingPlan.skipUser))
        // O catch-up do mesmo canal usa outra ligação: conta como outro canal.
        try expect(decide(w.fromMs - 30_000, RecordingPlan.Watching("p1", "101", live: false)), D.prompt)
        // Outra gravação a gravar ganha; a gravar o mesmo canal, grava também.
        let busy = [RecordingPlan.Busy("p1", "303")]
        try expect(decide(w.fromMs - 30_000, busy: busy), D.wait)
        try expect(decide(w.fromMs, busy: busy), D.skip(RecordingPlan.skipBusy))
        try expect(decide(w.fromMs, RecordingPlan.Watching("p1", "999"), busy: [RecordingPlan.Busy("p1", "101")]), D.start)
        // Só HLS não se grava.
        let hls = planRec(candidates: [StreamCandidate(url: "http://x/1.m3u8", format: "m3u8")])
        try expect(hls.url, nil)
        try expect(decide(w.fromMs - 30_000, r: hls), D.wait)
        try expect(decide(w.fromMs, r: hls), D.skip(RecordingPlan.skipHls))
        try expect(planRec().url, "http://x/101.ts")
    }

    static func recordingPlanConflictsWakeSeriesAndPrune() throws {
        let min = planMin, start = planStart
        let a = planRec("a", "101")
        let b = planRec("b", "202", startMs: start + 30 * min, endMs: start + 60 * min)
        let c = planRec("c", "101", startMs: start + 10 * min)
        let d = planRec("d", "303", startMs: start + 90 * min, endMs: start + 120 * min)
        try expect(RecordingPlan.conflicts(a, [a, b, c, d], 2, 5).map(\.id), ["b"])
        try expect(RecordingPlan.conflicts(a, [a, b, c, d], 0, 0).isEmpty, true)

        let s = Schedule(recordings: [planRec("a"), planRec("b", startMs: start + 60 * min, endMs: start + 90 * min)])
        try expect(RecordingPlan.nextWake(s, 2, 5, now: start - 60 * min), start - 2 * min)
        try expect(RecordingPlan.nextWake(s, 2, 5, now: start + 5 * min), start + 58 * min)
        try expect(RecordingPlan.nextWake(Schedule(), 2, 5, now: start) == nil, true)

        let rule = SeriesRule(id: "s1", playlistId: "p1", channelKey: "101", channelName: "Ch 101", logo: nil, title: "Telejornal")
        let day = 24 * 60 * min
        let entries = [
            EpgEntry(title: "Telejornal", startMs: start - 60 * min, endMs: start - 30 * min),
            EpgEntry(title: "TELEJORNAL ", startMs: start, endMs: start + 30 * min),
            EpgEntry(title: "Desporto", startMs: start + 30 * min, endMs: start + 60 * min),
            EpgEntry(title: "Telejornál", startMs: start + day, endMs: start + day + 30 * min),
            EpgEntry(title: "Telejornal", startMs: start + 8 * day, endMs: start + 8 * day + 30 * min),
        ]
        let marked = Schedule(recordings: [planRec(startMs: start)], handled: [ScheduledRecording.instanceKey("p1", "101", start + day): start])
        try expect(RecordingPlan.seriesEntries(rule, entries, Schedule(), now: start - min), [entries[1], entries[3]])
        try expect(RecordingPlan.seriesEntries(rule, entries, Schedule(), now: start + min), [entries[3]])
        try expect(RecordingPlan.seriesEntries(rule, entries, marked, now: start - min).isEmpty, true)

        let old = Schedule(recordings: [planRec("a")], handled: ["old": start - 4 * day, "new": start])
        let pruned = RecordingPlan.prune(old, 2, 5, now: start + 36 * min)
        try expect(pruned.recordings.isEmpty, true)
        try expect(Set(pruned.handled.keys), ["new"])
        try expect(RecordingPlan.prune(old, 2, 5, now: start + 34 * min).recordings.count, 1)

        let reserve = RecordingSpace.reserveBytes
        try expect(RecordingSpace.canStart(free: reserve + 600_000_000, bytesPerSecond: nil), true)
        try expect(RecordingSpace.canStart(free: reserve + 599_999_999, bytesPerSecond: nil), false)
        try expect(RecordingSpace.canStart(free: reserve + 60_000_000, bytesPerSecond: 100_000), true)
        try expect(RecordingSpace.mustStop(free: reserve), true)
        try expect(RecordingSpace.mustStop(free: reserve + 1), false)
        try expect(RecordingSpace.rate(bytes: 1_000, mediaMs: 4_999) == nil, true)
        try expect(RecordingSpace.rate(bytes: 5_000_000, mediaMs: 10_000), 500_000)
    }
}
