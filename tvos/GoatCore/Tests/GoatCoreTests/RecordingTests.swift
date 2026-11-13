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
        // O TS primeiro; o HLS quando não há outro; sem tentativas nenhumas não há o que gravar.
        let hls = planRec(candidates: [StreamCandidate(url: "http://x/1.m3u8", format: "m3u8")])
        try expect(hls.url, "http://x/1.m3u8")
        try expect(hls.urlIsHLS, true)
        try expect(decide(w.fromMs, r: hls), D.start)
        let both = planRec(candidates: [StreamCandidate(url: "http://x/1.m3u8", format: "m3u8"), StreamCandidate(url: "http://x/1.ts", format: "ts")])
        try expect(both.url, "http://x/1.ts")
        try expect(both.urlIsHLS, false)
        try expect(planRec().url, "http://x/101.ts")
        let none = planRec(candidates: [])
        try expect(none.url, nil)
        try expect(decide(w.fromMs - 30_000, r: none), D.wait)
        try expect(decide(w.fromMs, r: none), D.skip(RecordingPlan.skipHls))
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

/// A cópia do anel para o ficheiro: os mesmos casos do GoatTV Android (RecordingWriterTest).
extension CoreTests {
    /// Um pacote TS de 188 bytes; com [pcrMs], leva o PCR no campo de adaptação.
    private static func recPacket(pcrMs: Int64? = nil, fill: UInt8 = 0x11) -> [UInt8] {
        var p = [UInt8](repeating: fill, count: 188)
        p[0] = 0x47
        p[1] = 0x01
        p[2] = 0x00
        if let pcrMs {
            p[3] = 0x30
            p[4] = 7
            p[5] = 0x10
            let base = pcrMs * 90
            p[6] = UInt8(truncatingIfNeeded: base >> 25)
            p[7] = UInt8(truncatingIfNeeded: base >> 17)
            p[8] = UInt8(truncatingIfNeeded: base >> 9)
            p[9] = UInt8(truncatingIfNeeded: base >> 1)
            p[10] = UInt8(truncatingIfNeeded: ((base & 1) << 7) | 0x7E)
            p[11] = 0
        } else {
            p[3] = 0x10
        }
        return p
    }

    /// [seconds] de vídeo a partir do PCR [fromMs]: um PCR e quatro pacotes a cada 100 ms.
    private static func recStream(_ fromMs: Int64, _ seconds: Int, fill: UInt8 = 0x11) -> [UInt8] {
        var out: [UInt8] = []
        for i in 0..<(seconds * 10) {
            out += recPacket(pcrMs: fromMs + Int64(i) * 100, fill: fill)
            for _ in 0..<4 { out += recPacket(fill: fill) }
        }
        return out
    }

    private static let recGroup = 5 * 188

    private static func recRing() throws -> (TimeshiftRing, URL) {
        let dir = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("rec-writer-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let ring = try TimeshiftRing(path: dir.appendingPathComponent("ring.ts").path, capacity: 1 << 22, maxAgeMs: 30 * 60_000) { 1_000 }
        return (ring, dir)
    }

    private static func recWrite(_ ring: TimeshiftRing, _ data: [UInt8]) throws {
        var o = 0
        while o < data.count {
            let n = min(7_000, data.count - o)
            try ring.write(Array(data[o..<(o + n)]))
            o += n
        }
    }

    /// Corre o [writer] noutra thread; [meanwhile] faz o resto do teste enquanto ele grava.
    private static func recRun(_ writer: RecordingWriter, meanwhile: () throws -> Void = {}) rethrows -> RecordingWriter.Result? {
        let done = DispatchSemaphore(value: 0)
        let box = ResultBox()
        Thread { box.value = writer.run(); done.signal() }.start()
        try meanwhile()
        _ = done.wait(timeout: .now() + 10)
        return box.value
    }

    private final class ResultBox: @unchecked Sendable { var value: RecordingWriter.Result? }

    static func recordingWriterCopiesTheProgrammeCutAtIndexPoints() throws {
        let (ring, dir) = try recRing()
        defer { ring.close(); try? FileManager.default.removeItem(at: dir) }
        let data = recStream(0, 20)
        ring.beginSegment()
        try recWrite(ring, data)
        let now: Int64 = 100_000
        let live = ring.liveMediaMs
        try expect(live, 19_900)
        let range = try expectValue(RecordingCut.range(ring.index, liveMediaMs: live, nowWall: now, startWall: now - (live - 5_000), endWall: now - (live - 15_000)))
        try expect(range.startOffset, Int64(50 * recGroup))
        try expect(range.startMediaMs, 5_000)
        try expect(range.endMediaMs, 15_000)
        try expect(range.late, false)
        let out = dir.appendingPathComponent("a.ts")
        let result = RecordingWriter(ring: ring, out: out, startOffset: range.startOffset, endMediaMs: range.endMediaMs, freeBytes: { .max }).run()
        try expect(result.state, RecordingState.done)
        try expect(result.bytes, Int64(100 * recGroup))
        try expect(result.mediaEndMs, 15_000)
        try expect([UInt8](try Data(contentsOf: out)), Array(data[(50 * recGroup)..<(150 * recGroup)]))
    }

    static func recordingWriterFollowsLiveUntilTheEnd() throws {
        let (ring, dir) = try recRing()
        defer { ring.close(); try? FileManager.default.removeItem(at: dir) }
        let first = recStream(0, 4)
        ring.beginSegment()
        try recWrite(ring, first)
        let out = dir.appendingPathComponent("b.ts")
        let second = recStream(4_000, 4, fill: 0x22)
        let result = try recRun(RecordingWriter(ring: ring, out: out, startOffset: 0, endMediaMs: 6_000, freeBytes: { .max })) {
            Thread.sleep(forTimeInterval: 0.3)
            try recWrite(ring, second)
        }
        try expect(result?.state, RecordingState.done)
        try expect([UInt8](try Data(contentsOf: out)), Array((first + second)[0..<(60 * recGroup)]))
    }

    static func recordingWriterStopsAndKeepsWhatItHas() throws {
        let (ring, dir) = try recRing()
        defer { ring.close(); try? FileManager.default.removeItem(at: dir) }
        ring.beginSegment()
        try recWrite(ring, recStream(0, 3))
        let out = dir.appendingPathComponent("c.ts")
        let writer = RecordingWriter(ring: ring, out: out, startOffset: 0, endMediaMs: 60_000, freeBytes: { .max })
        let stopped = recRun(writer) {
            Thread.sleep(forTimeInterval: 0.3)
            writer.stop()
        }
        try expect(stopped?.state, RecordingState.stopped)
        try expect(stopped?.reason, RecordingReason.user)
        try expect((try Data(contentsOf: out)).count, 30 * recGroup)

        // O anel fechou (a ligação foi para outro canal): acaba com o que tem.
        let (ring2, dir2) = try recRing()
        defer { try? FileManager.default.removeItem(at: dir2) }
        ring2.beginSegment()
        try recWrite(ring2, recStream(0, 3))
        let closed = recRun(RecordingWriter(ring: ring2, out: dir2.appendingPathComponent("d.ts"), startOffset: 0, endMediaMs: 60_000, freeBytes: { .max })) {
            Thread.sleep(forTimeInterval: 0.3)
            ring2.close()
        }
        try expect(closed?.state, RecordingState.stopped)
        try expect(closed?.reason, RecordingReason.connection)
        try expect(closed?.bytes, Int64(30 * recGroup))
    }

    static func recordingWriterSegmentsSpaceAndNothing() throws {
        // A ligação caiu e voltou: continua no segmento novo e conta o buraco.
        let (ring, dir) = try recRing()
        defer { ring.close(); try? FileManager.default.removeItem(at: dir) }
        let a = recStream(0, 2), b = recStream(50_000, 2, fill: 0x33)
        ring.beginSegment()
        try recWrite(ring, a)
        ring.endSegment(final: false)
        ring.beginSegment()
        try recWrite(ring, b)
        ring.endSegment(final: true)
        let out = dir.appendingPathComponent("e.ts")
        let result = RecordingWriter(ring: ring, out: out, startOffset: 0, endMediaMs: 60_000, freeBytes: { .max }).run()
        try expect(result.gaps, 1)
        try expect([UInt8](try Data(contentsOf: out)), a + b)

        // Sem espaço: pára com o que tem.
        let (ring2, dir2) = try recRing()
        defer { ring2.close(); try? FileManager.default.removeItem(at: dir2) }
        ring2.beginSegment()
        try recWrite(ring2, recStream(0, 10))
        let low = RecordingWriter(ring: ring2, out: dir2.appendingPathComponent("f.ts"), startOffset: 0, endMediaMs: 60_000,
                                  freeBytes: { RecordingSpace.reserveBytes }, checkBytes: 10_000).run()
        try expect(low.state, RecordingState.stopped)
        try expect(low.reason, RecordingReason.lowSpace)
        try expect((10_000...300_000).contains(low.bytes), true, "\(low.bytes)")

        // Sem nada gravado: falhou.
        let (ring3, dir3) = try recRing()
        defer { ring3.close(); try? FileManager.default.removeItem(at: dir3) }
        ring3.beginSegment()
        ring3.endSegment(final: true)
        try expect(RecordingWriter(ring: ring3, out: dir3.appendingPathComponent("g.ts"), startOffset: 0, endMediaMs: 60_000, freeBytes: { .max }).run().state,
                   RecordingState.failed)
    }
}
