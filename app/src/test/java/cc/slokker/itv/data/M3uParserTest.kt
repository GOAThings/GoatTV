package cc.slokker.itv.data

import cc.slokker.itv.data.m3u.M3uParser
import java.io.IOException
import java.io.StringReader
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


    @Test
    fun `bounded lines split like readLine`() {
        assertEquals(listOf("a", "b", "", "c", "d"), StringReader("a\nb\r\n\rc\nd").boundedLines().toList())
        assertEquals(listOf("a"), StringReader("a\n").boundedLines().toList())
    }

    @Test(expected = IOException::class)
    fun `a line with no end stops at the limit`() {
        StringReader("x".repeat(1000)).boundedLines(maxLine = 100).toList()
    }

    @Test(expected = IOException::class)
    fun `a reader stops at the size limit`() {
        LimitedReader(StringReader("x".repeat(1000)), 100).readText()
    }

    @Test
    fun `a body within the limits reads whole`() {
        assertEquals("x".repeat(100), LimitedReader(StringReader("x".repeat(100)), 100).readText())
        assertEquals(100, LimitedInputStream("x".repeat(100).byteInputStream(), 100).readBytes().size)
    }

    @Test(expected = IOException::class)
    fun `a stream stops at the size limit`() {
        LimitedInputStream("x".repeat(1000).byteInputStream(), 100).readBytes()
    }

    @Test
    fun `reserved and repeated group ids never reach the group list`() {
        val r = parse("#EXTM3U\n#EXTINF:-1 group-title=\"__all__\",A\nhttp://example.invalid/a.ts\n#EXTINF:-1 group-title=\"News\",B\nhttp://example.invalid/b.ts")
        val playlist = Playlist(id = "p", type = PlaylistType.M3U, name = "P")
        val content = PlaylistContent.build(playlist, r.categories, r.channels)
        assertEquals(listOf("News"), content.categories.map { it.id })
        // As cópias em cache passam pelo mesmo construtor.
        val cached = PlaylistContent(playlist, listOf(Category("1", "A"), Category("1", "B"), Category(SpecialCategory.FAVORITES, "F")), r.channels)
        assertEquals(listOf("1"), cached.categories.map { it.id })
    }

    @Test
    fun `groups follow the chosen order and new ones stay last in list order`() {
        val playlist = Playlist(id = "p", type = PlaylistType.M3U, name = "P")
        val content = PlaylistContent(playlist, listOf("a", "b", "c", "d").map { Category(it, it) }, emptyList())
        assertEquals(listOf("c", "a", "b", "d"), content.orderedCategories(listOf("c", "a")).map { it.id })
        // Muitos grupos: tem de ser linear, senão isto não acaba (corre na interface).
        val many = PlaylistContent(playlist, (0 until 200_000).map { Category("g$it", "g$it") }, emptyList())
        assertEquals("g0", many.orderedCategories(emptyList()).first().id)
    }

    @Test
    fun `provider dates out of range are dropped`() {
        assertEquals(1_000L, SafeInput.epochMs(1))
        assertNull(SafeInput.epochMs(Long.MAX_VALUE))
        assertNull(SafeInput.epochMs(-1))
    }

    @Test
    fun `control characters leave user agent and referrer values`() {
        assertEquals("probe :sout=x", SafeInput.stripControl("probe\r\n :sout=\u0085x\u2028\u2029\u0000"))
        assertEquals("VLC/3.0 é", SafeInput.stripControl("VLC/3.0 é"))
        assertEquals("VLC/3.0 ", SafeInput.headerSafe("VLC/3.0 é\n"))
    }
}
