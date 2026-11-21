package cc.slokker.itv.playback

import kotlin.test.Test
import kotlin.test.assertEquals

class FreezeWatchTest {
    /** Um motor de mentira: cada [second] avança o que estiver ligado e chama o [FreezeWatch.tick]. */
    private class Engine {
        val watch = FreezeWatch()
        var now = 1_000_000L
        var frames = 0L
        var pts = 0L
        var audio = 0L
        var bytes = 0L
        var samples = 0L
        var video = true
        var sound = true
        var data = true
        var demux = true
        var samePts = false
        var hasVideo = true
        var hasAudio = true
        var hold = false
        var buffering = false
        var stuckMs = -1L
        var targetMs = 6_000L

        fun second(): Int {
            now += 1_000
            if (video) {
                frames += 25
                if (!samePts) pts += 1_000
            }
            if (sound) audio += 47
            if (data) bytes += 250_000
            if (data && demux) samples += 72
            return watch.tick(now, frames, pts, audio, bytes, samples, hasVideo, hasAudio, hold, buffering, stuckMs, targetMs)
        }

        /** [n] segundos; devolve o primeiro que disparou e em que segundo (0 = nenhum). */
        fun run(n: Int): Pair<Int, Int> {
            for (i in 1..n) {
                val k = second()
                if (k != FreezeWatch.NONE) return k to i
            }
            return FreezeWatch.NONE to 0
        }
    }

    private fun playing(seconds: Int = 15) = Engine().also { e -> e.run(seconds) }

    @Test fun healthyChannelNeverTriggers() {
        assertEquals(FreezeWatch.NONE to 0, playing().run(600))
    }

    @Test fun videoFrozenWithAudioTriggersAfterSixSeconds() {
        val e = playing()
        e.video = false
        assertEquals(FreezeWatch.VIDEO to 6, e.run(30))
        // Uma vez por abertura.
        assertEquals(FreezeWatch.NONE to 0, e.run(30))
    }

    @Test fun framesWithAStuckTimeCountAsFrozen() {
        val e = playing()
        e.samePts = true
        assertEquals(FreezeWatch.VIDEO to 6, e.run(30))
    }

    @Test fun stillImageWithTimeAdvancingIsFine() {
        // As imagens são iguais, mas o tempo delas anda: não se julga o conteúdo.
        assertEquals(FreezeWatch.NONE to 0, playing().run(120))
    }

    @Test fun videoFrozenWhileBufferingStillTriggersWhenAudioPlays() {
        // No GoatCore do Android uma imagem que não sai põe o estado "a carregar"; o som prova que os dados chegam.
        val e = playing()
        e.video = false
        e.buffering = true
        assertEquals(FreezeWatch.VIDEO to 6, e.run(30))
    }

    @Test fun networkStallIsLeftToTheStallLogic() {
        // Parou tudo (a rede): nem som, nem bytes. É a paragem de sempre que trata.
        val e = playing()
        e.video = false
        e.sound = false
        e.data = false
        e.buffering = true
        assertEquals(FreezeWatch.NONE to 0, e.run(60))
    }

    @Test fun audioMissingWithVideoTriggersAfterEightSeconds() {
        val e = playing()
        e.sound = false
        assertEquals(FreezeWatch.AUDIO to 8, e.run(30))
    }

    @Test fun audioMissingWhileBufferingDoesNotTrigger() {
        val e = playing()
        e.sound = false
        e.buffering = true
        assertEquals(FreezeWatch.NONE to 0, e.run(30))
    }

    @Test fun channelThatNeverHadAudioIsFine() {
        val e = Engine()
        e.sound = false
        assertEquals(FreezeWatch.NONE to 0, e.run(120))
    }

    @Test fun bytesWithoutSamplesTriggerAfterEightSeconds() {
        val e = playing()
        e.demux = false
        // Sem amostras também param a imagem e o som (o leitor diz "a carregar"): conta pelos bytes.
        e.video = false
        e.sound = false
        e.buffering = true
        assertEquals(FreezeWatch.UNDECODABLE to 8, e.run(30))
    }

    @Test fun hlsSegmentsInBurstsDoNotLookUndecodable() {
        // Um segmento de 10 s de uma vez: bytes e amostras chegam juntos, e nada entre eles.
        val e = playing()
        e.data = false
        for (i in 1..60) {
            e.data = i % 10 == 0
            assertEquals(FreezeWatch.NONE, e.second(), "second $i")
        }
    }

    @Test fun oneJunkSegmentIsNotEnough() {
        val e = playing()
        e.data = false
        e.run(9)
        // Um segmento sem amostras (só um segundo de bytes), depois nada.
        e.data = true
        e.demux = false
        assertEquals(FreezeWatch.NONE, e.second())
        e.data = false
        assertEquals(FreezeWatch.NONE to 0, e.run(30))
    }

    @Test fun playlistNotAdvancingTriggers() {
        val e = playing()
        e.stuckMs = 0
        var kind = FreezeWatch.NONE
        var at = 0
        for (i in 1..40) {
            e.stuckMs += 1_000
            kind = e.second()
            if (kind != FreezeWatch.NONE) {
                at = i
                break
            }
        }
        assertEquals(FreezeWatch.PLAYLIST, kind)
        assertEquals(20, at)
    }

    @Test fun playlistLimitFollowsTheSegmentLength() {
        assertEquals(20_000L, FreezeWatch.playlistLimitMs(2_000))
        assertEquals(30_000L, FreezeWatch.playlistLimitMs(10_000))
    }

    @Test fun pauseNeverTriggersAndRestartsTheCount() {
        val e = playing()
        e.hold = true
        e.video = false
        e.sound = false
        e.data = false
        e.stuckMs = 120_000
        assertEquals(FreezeWatch.NONE to 0, e.run(120))
        // Volta a dar: a playlist "parada" durante a pausa não conta, e tudo anda.
        e.hold = false
        e.video = true
        e.sound = true
        e.data = true
        e.stuckMs = -1
        assertEquals(FreezeWatch.NONE to 0, e.run(60))
    }

    @Test fun concealmentHoldDelaysTheVerdict() {
        val e = playing()
        e.video = false
        e.hold = true // a esconder a falha (a última imagem fica), ou atrás do direto
        e.run(2)
        e.hold = false
        // Conta desde que a espera acabou.
        assertEquals(FreezeWatch.VIDEO to 6, e.run(30))
    }

    @Test fun nothingInTheFirstSecondsAfterOpening() {
        val e = Engine()
        e.run(3)
        e.video = false
        // Seis segundos sem imagem, mas ainda no arranque: só depois dos 10 s.
        val (kind, at) = e.run(30)
        assertEquals(FreezeWatch.VIDEO, kind)
        assertEquals(8, at)
    }

    @Test fun radioNeverTriggers() {
        val e = Engine()
        e.hasVideo = false
        e.video = false
        e.demux = false
        assertEquals(FreezeWatch.NONE to 0, e.run(120))
    }

    @Test fun resetStartsAgain() {
        val e = playing()
        e.video = false
        assertEquals(FreezeWatch.VIDEO, e.run(30).first)
        e.watch.reset()
        e.video = true
        assertEquals(FreezeWatch.NONE to 0, e.run(60))
    }

    @Test fun sleepingAppRestartsTheCount() {
        val e = playing()
        e.video = false
        e.run(3)
        e.now += 60_000
        // O salto do relógio recomeça as contas: mais seis segundos.
        assertEquals(FreezeWatch.VIDEO to 7, e.run(30))
    }

    @Test fun liveCheckCountsOnlyEmptyReloads() {
        val c = HlsLiveCheck()
        assertEquals(-1L, c.stuckMs(0))
        c.reloaded(0, 3, 6_000, live = true)
        assertEquals(-1L, c.stuckMs(1_000))
        c.reloaded(3_000, 0, 6_000, live = true)
        c.reloaded(6_000, 0, 6_000, live = true)
        assertEquals(7_000L, c.stuckMs(10_000))
        c.reloaded(9_000, 1, 6_000, live = true)
        assertEquals(-1L, c.stuckMs(10_000))
        c.reloaded(12_000, 0, 6_000, live = false)
        assertEquals(-1L, c.stuckMs(60_000))
    }

    @Test fun liveStallFollowsTheCurrentReader() {
        val a = HlsLiveCheck()
        val b = HlsLiveCheck()
        HlsLiveStall.current = a
        a.reloaded(0, 0, 4_000, live = true)
        assertEquals(5_000L, HlsLiveStall.stuckMs(5_000))
        assertEquals(4_000L, HlsLiveStall.targetMs())
        HlsLiveStall.current = b
        HlsLiveStall.release(a)
        assertEquals(b, HlsLiveStall.current)
        HlsLiveStall.release(b)
        assertEquals(-1L, HlsLiveStall.stuckMs(5_000))
    }
}
