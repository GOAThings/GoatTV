package cc.slokker.itv.data

import cc.slokker.itv.data.m3u.M3uParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uParserTest {

    private fun parse(text: String) = M3uParser.parse(text.lineSequence())

    /**
     * A lista de teste do backend (thegoat.systems/test-fixtures/organize.m3u): o site calcula os mesmos
     * channelKeys e groupIds para organizar grupos e canais. Se isto falhar, a app e o site deixam de bater certo.
     */
    @Test
    fun `channel keys and groups match the backend fixture`() {
        val text = javaClass.classLoader!!.getResource("organize.m3u")!!.readText()
        val r = parse(text)
        assertEquals(
            listOf(
                "SIC.pt" to "Portugal",
                "ueaaa29a28652" to "Portugal", // tvg-id vazio
                "u7aa953b3260a" to "Portugal", // TVI: sem tvg-id
                "u6a4815eaa311" to "Portugal", // SIC (backup): tvg-id repetido
                "ESPN.us" to "Sports, US",
                "Euronews.fr" to PlaylistContent.OTHERS,
                "ud319eeb165a2" to PlaylistContent.OTHERS, // URL com acentos, group-title vazio
                "u6c2602101058" to "Sports, US", // tvg-id repetido, URL com espaços nas pontas
                "Kids1" to "Infantil", // #EXTGRP com espaços, sem group-title
                "Kids2" to "Infantil", // group-title vazio: vale o #EXTGRP (com um #EXTVLCOPT pelo meio)
                "News1" to "Notícias", // group-title preenchido: o #EXTGRP é ignorado
                "Docs1" to "Documentários", // dois #EXTGRP: vale o último
                "NoGrp" to PlaylistContent.OTHERS, // o #EXTGRP não passa para o canal seguinte
            ),
            r.channels.map { it.key to it.categoryId },
        )
        assertEquals("ESPN, East", r.channels[4].name)
    }

    @Test
    fun `reads attributes, name and url`() {
        val r = parse(
            """
            #EXTM3U x-tvg-url="https://example.com/epg.xml"
            #EXTINF:-1 tvg-id="rtp1.pt" tvg-name="RTP 1" tvg-logo="https://example.com/rtp1.png" group-title="Generalistas",RTP 1
            http://host/stream/123.m3u8
            """.trimIndent(),
        )
        assertEquals("https://example.com/epg.xml", r.epgUrl)
        val ch = r.channels.single()
        assertEquals("rtp1.pt", ch.key)
        assertEquals("RTP 1", ch.name)
        assertEquals("https://example.com/rtp1.png", ch.logo)
        assertEquals("Generalistas", ch.categoryId)
        assertEquals("http://host/stream/123.m3u8", ch.url)
        assertEquals(listOf("Generalistas"), r.categories.map { it.name })
    }

    @Test
    fun `commas inside attributes do not break the name`() {
        val r = parse(
            """
            #EXTM3U
            #EXTINF:-1 group-title="Notícias, Info",CNN Portugal, HD
            http://host/a.ts
            """.trimIndent(),
        )
        val ch = r.channels.single()
        assertEquals("CNN Portugal, HD", ch.name)
        assertEquals("Notícias, Info", ch.categoryId)
    }

    @Test
    fun `channels without group go to Outros and without tvg-id get a url hash`() {
        val r = parse(
            """
            #EXTM3U
            #EXTINF:-1,Sem grupo
            http://host/x.m3u8
            """.trimIndent(),
        )
        val ch = r.channels.single()
        assertEquals(PlaylistContent.OTHERS, ch.categoryId)
        assertEquals(M3uParser.urlKey("http://host/x.m3u8"), ch.key)
        assertEquals(M3uParser.urlKey("http://host/x.m3u8"), M3uParser.urlKey("http://host/x.m3u8"))
    }

    @Test
    fun `skips movies and series`() {
        val r = parse(
            """
            #EXTM3U
            #EXTINF:-1,Filme
            http://host/movie/u/p/1.mp4
            #EXTINF:-1,Série
            http://host/series/u/p/2.mkv
            #EXTINF:-1,Outro filme
            http://host/vod/3.mkv?token=1
            #EXTINF:-1,Canal
            http://host/live/u/p/4.ts
            """.trimIndent(),
        )
        assertEquals(listOf("Canal"), r.channels.map { it.name })
        assertEquals(1, r.channels.single().num)
    }

    @Test
    fun `repeated tvg-id gets a distinct key`() {
        val r = parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="sic.pt",SIC
            http://host/sic.m3u8
            #EXTINF:-1 tvg-id="sic.pt",SIC HD
            http://host/sichd.m3u8
            """.trimIndent(),
        )
        assertEquals("sic.pt", r.channels[0].key)
        assertNotEquals("sic.pt", r.channels[1].key)
    }

    @Test
    fun `reads EXTGRP and EXTVLCOPT and ignores orphan urls`() {
        val r = parse(
            "﻿#EXTM3U\n" +
                "http://host/orphan.ts\n" +
                "#EXTINF:-1,Canal\n" +
                "#EXTGRP:Desporto\n" +
                "#EXTVLCOPT:http-user-agent=Foo/1.0\n" +
                "#EXTVLCOPT:http-referrer=https://ref.example/\n" +
                "\n" +
                "http://host/c.ts\n",
        )
        val ch = r.channels.single()
        assertEquals("Desporto", ch.categoryId)
        assertEquals("Foo/1.0", ch.userAgent)
        assertEquals("https://ref.example/", ch.referrer)
        assertNull(r.epgUrl)
    }

    @Test
    fun `vod heuristic`() {
        assertTrue(M3uParser.isVod("http://h/movie/1.ts"))
        assertTrue(M3uParser.isVod("http://h/a/b.MP4"))
        assertTrue(!M3uParser.isVod("http://h/live/1.ts"))
    }
}
