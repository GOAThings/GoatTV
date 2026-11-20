package cc.slokker.itv.playback

import kotlin.test.Test
import kotlin.test.assertEquals

/** "Informação do stream": as unidades, os nomes dos codecs, perfis e níveis, e o débito a rolar. */
class StreamStatsTest {
    private val words = StreamStatsWords(
        progressive = "Progressive", interlaced = "Interlaced", deinterlacing = "Deinterlacing", upscaling = "Upscaling",
        toneMapping = "HDR to SDR", none = "None", auto = "Auto", fallback = "fallback", failover = "failover",
    )

    private fun input(
        container: String = "TS", hlsVariant: String = "", hlsPinned: Boolean = false,
        videoCodec: String = "H264", profile: Int = 100, level: Int = 40, bitDepth: Int = 8,
        width: Int = 1920, height: Int = 1080, fps: Float = 25f, interlaced: Int = 1,
        deinterlacing: Boolean = true, upscaling: Boolean = false, toneMapping: Boolean = false, transfer: Int = 1,
        audioCodec: String = "AC3", channels: Int = 6, sampleRate: Int = 48_000, track: String = "Português · 5.1",
        kbps: Int = 5_200, bufferMs: Long = 12_345, dropped: Int = 3, rendered: Int = 4_512,
        errors: Int = 0, concealed: Int = 0, reconnects: Int = 1,
        engine: String = "GoatCore", fallback: Boolean = false, source: String = "RTP 1 HD · Lista", failover: Boolean = false,
    ) = StreamStatsInput(
        container, hlsVariant, hlsPinned, videoCodec, profile, level, bitDepth, width, height, fps, interlaced,
        deinterlacing, upscaling, toneMapping, transfer, audioCodec, channels, sampleRate, track, kbps, bufferMs,
        dropped, rendered, errors, concealed, reconnects, engine, fallback, source, failover,
    )

    private fun rows(i: StreamStatsInput) = StreamStats.rows(i, words).associate { it.key to it.value }

    @Test
    fun allRowsInOrder() {
        val keys = StreamStats.rows(input(), words).map { it.key }
        assertEquals(
            listOf(
                "stats_engine", "stats_source", "probe_format", "video", "resolution", "stats_frame_rate", "stats_scan",
                "dynamic_range", "stats_processing", "audio", "stats_audio_track", "probe_bitrate", "buffer", "probe_dropped",
                "stats_decode_errors", "stats_concealed", "probe_reconnects",
            ),
            keys,
        )
    }

    @Test
    fun interlacedH264WithAc3() {
        val r = rows(input())
        assertEquals("GoatCore", r["stats_engine"])
        assertEquals("TS", r["probe_format"])
        assertEquals("H.264 High@L4.0 · 8-bit", r["video"])
        assertEquals("1920×1080", r["resolution"])
        assertEquals("25 fps", r["stats_frame_rate"])
        assertEquals("Interlaced", r["stats_scan"])
        assertEquals("SDR", r["dynamic_range"])
        assertEquals("Deinterlacing", r["stats_processing"])
        assertEquals("AC-3 · 5.1 · 48 kHz", r["audio"])
        assertEquals("5.2 Mbit/s", r["probe_bitrate"])
        assertEquals("12.3 s", r["buffer"])
        assertEquals("3 / 4512", r["probe_dropped"])
        assertEquals("1", r["probe_reconnects"])
    }

    @Test
    fun hevcHdrOverHls() {
        val r = rows(
            input(
                container = "HLS", hlsVariant = "2160p · 18.0 Mbit/s", videoCodec = "HEVC", profile = 2, level = 153, bitDepth = 10,
                width = 3840, height = 2160, fps = 50f, interlaced = 0, deinterlacing = false, toneMapping = true, transfer = 16,
                audioCodec = "EAC3", channels = 8, sampleRate = 44_100,
            ),
        )
        assertEquals("HLS · 2160p · 18.0 Mbit/s · Auto", r["probe_format"])
        assertEquals("HEVC Main 10@L5.1 · 10-bit", r["video"])
        assertEquals("Progressive", r["stats_scan"])
        assertEquals("HDR10", r["dynamic_range"])
        assertEquals("HDR to SDR", r["stats_processing"])
        assertEquals("E-AC-3 · 7.1 · 44.1 kHz", r["audio"])
        assertEquals("HLS · 1080p", StreamStats.container(input(container = "HLS", hlsVariant = "1080p", hlsPinned = true), words))
        assertEquals("HLG", StreamStats.hdrName(18, true))
    }

    @Test
    fun vlcFallbackWithLittleKnown() {
        val r = rows(
            input(
                videoCodec = "MPEG-2", profile = -1, level = -1, bitDepth = -1, fps = 0f, interlaced = -1, deinterlacing = false,
                transfer = -1, audioCodec = "MP2", channels = 2, sampleRate = 0, kbps = -1, bufferMs = -1, dropped = -1, rendered = -1,
                errors = -1, concealed = -1, engine = "VLC", fallback = true, source = "RTP 1 (Reserva)", failover = true, track = "",
            ),
        )
        assertEquals("VLC (fallback)", r["stats_engine"])
        assertEquals("RTP 1 (Reserva) (failover)", r["stats_source"])
        assertEquals("MPEG-2", r["video"])
        assertEquals("—", r["stats_frame_rate"])
        assertEquals("—", r["stats_scan"])
        assertEquals("None", r["stats_processing"])
        assertEquals("MP2 · 2.0", r["audio"])
        assertEquals("—", r["stats_audio_track"])
        assertEquals("—", r["probe_bitrate"])
        assertEquals("—", r["buffer"])
        assertEquals("—", r["probe_dropped"])
        assertEquals("—", r["stats_decode_errors"])
    }

    @Test
    fun units() {
        assertEquals("29.97 fps", StreamStats.fps(29.97f))
        assertEquals("23.98 fps", StreamStats.fps(23.976f))
        assertEquals("59.94 fps", StreamStats.fps(59.94f))
        assertEquals("50 fps", StreamStats.fps(50.001f))
        assertEquals("", StreamStats.fps(0f))
        assertEquals("1.0", StreamStats.channels(1))
        assertEquals("3 ch", StreamStats.channels(3))
        assertEquals("32 kHz", StreamStats.sampleRate(32_000))
        assertEquals("88.2 kHz", StreamStats.sampleRate(88_200))
        assertEquals("0.0 s", StreamStats.seconds(0))
        assertEquals("850 kbit/s", StreamProbe.bitrate(850))
        assertEquals("12", StreamStats.frames(12, -1))
    }

    @Test
    fun profilesAndLevels() {
        assertEquals("Main", StreamStats.profileName("H264", 77))
        assertEquals("Baseline", StreamStats.profileName("H.264", 66))
        assertEquals("", StreamStats.profileName("H264", 999))
        assertEquals("3.1", StreamStats.levelName("H264", 31))
        assertEquals("1b", StreamStats.levelName("H264", 9))
        assertEquals("4", StreamStats.levelName("HEVC", 120))
        assertEquals("4.1", StreamStats.levelName("HEVC", 123))
        assertEquals("6.2", StreamStats.levelName("HEVC", 186))
        assertEquals("", StreamStats.levelName("MPEG2_VIDEO", 8))
        assertEquals("H.264 Main", StreamStats.video(input(profile = 77, level = -1, bitDepth = -1)))
    }

    @Test
    fun rollingBitrateByMediaTime() {
        val b = RollingBitrate()
        assertEquals(-1, b.kbps())
        // 4 Mbit/s de vídeo: 500 000 bytes por segundo de vídeo, a chegar aos solavancos (um HLS de 2 em 2 s).
        var bytes = 0L
        var media = 0L
        for (s in 0..10) {
            if (s % 2 == 0 && s > 0) {
                bytes += 1_000_000
                media += 2_000
            }
            b.add(s * 1_000L, bytes, media)
        }
        assertEquals(4_000, b.kbps())
    }

    @Test
    fun rollingBitrateByWallClockAndReset() {
        val b = RollingBitrate()
        for (s in 0..20) b.add(s * 1_000L, s * 250_000L, -1)
        // Só os últimos 10 s: 2 Mbit/s.
        assertEquals(2_000, b.kbps())
        // O motor foi refeito: o contador volta ao zero e a conta recomeça.
        b.add(21_000, 1_000, -1)
        assertEquals(-1, b.kbps())
        b.add(22_000, 126_000, -1)
        b.add(23_000, 251_000, -1)
        assertEquals(1_000, b.kbps())
    }

    @Test
    fun rollingBitrateIgnoresClockJumps() {
        val b = RollingBitrate()
        // O relógio do canal salta 1 h a meio: vale o relógio da parede.
        b.add(0, 0, 0)
        b.add(5_000, 625_000, 3_600_000)
        assertEquals(1_000, b.kbps())
    }
}
