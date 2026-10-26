package cc.slokker.itv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BugReportTest {

    @Test
    fun redactsKnownSecretsAndCredentialShapes() {
        val log = """
            Opening http://iptv.example:8080/live/joao/s3cr3t/123.ts
            GET http://iptv.example/player_api.php?username=joao&password=s3cr3t&action=get_live_streams
            M3U https://lists.example/get.php?token=abcdef123&type=m3u
            Proxy http://bob:hunter2@proxy.example/x
            Authorization: tok_live_999 sent
            Error with tok_live_999 and myplaylistpass
        """.trimIndent()
        val out = BugReport.redact(log, listOf("tok_live_999", "myplaylistpass", "ab"))
        for (secret in listOf("joao", "s3cr3t", "abcdef123", "hunter2", "tok_live_999", "myplaylistpass", "bob:")) {
            assertFalse("$secret leaked:\n$out", out.contains(secret))
        }
        assertTrue(out.contains("http://iptv.example:8080/live/***/***/123.ts"))
        assertTrue(out.contains("action=get_live_streams"))
        // Segredos de menos de 3 caracteres não se trocam (senão apagava letras soltas).
        assertTrue(out.contains("Opening"))
    }

    @Test
    fun keepsTheNewestLinesThatFit() {
        val log = (1..100).joinToString("\n") { "line $it" }
        val out = BugReport.trim("HEAD\n", log, max = 60)
        assertTrue(out.startsWith("HEAD\n"))
        assertTrue(out.endsWith("line 100"))
        assertFalse(out.contains("line 1\n"))
        assertEquals(true, out.toByteArray().size <= 60)
    }
}
