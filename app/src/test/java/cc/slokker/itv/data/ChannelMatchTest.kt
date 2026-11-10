package cc.slokker.itv.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O "mesmo canal" na lista de reserva. Os casos estão em channel_match.json, que o GoatCore da Apple
 * (ChannelMatchTests.swift) também lê: as duas plataformas escolhem o mesmo canal.
 */
class ChannelMatchTest {
    private val cases: JsonObject =
        JsonParser.parseString(javaClass.classLoader!!.getResource("channel_match.json")!!.readText()).asJsonObject

    private fun JsonObject.str(k: String): String? = get(k)?.takeIf { !it.isJsonNull }?.asString

    private fun entries(o: JsonObject, k: String) = o.getAsJsonArray(k).mapIndexed { i, e ->
        val c = e.asJsonObject
        ChannelMatch.Entry(c.str("key") ?: "k$i", c.str("name")!!, c.str("epg"))
    }

    @Test
    fun `names are normalised the same way as on Apple`() {
        val list = cases.getAsJsonArray("normalize")
        assertTrue(list.size() >= 50)
        for (e in list) {
            val c = e.asJsonObject
            val name = c.str("name")!!
            val expected = ChannelMatch.Parsed(c.str("base")!!, c.str("country"), c.get("quality").asInt, c.get("shift").asInt)
            assertEquals(name, expected, ChannelMatch.parse(name))
        }
    }

    @Test
    fun `the same channel or none, never a wrong one`() {
        val list = cases.getAsJsonArray("match")
        assertTrue(list.size() >= 30)
        for (e in list) {
            val c = e.asJsonObject
            val t = c.getAsJsonObject("target")
            val result = ChannelMatch.Matcher(entries(c, "pool")).find(t.str("name")!!, t.str("epg"), c.str("override"))
            val kind = when (result) {
                is ChannelMatch.Result.Match -> "match"
                is ChannelMatch.Result.Unsure -> "unsure"
                ChannelMatch.Result.Missing -> "missing"
            }
            val label = c.str("case")!!
            assertEquals(label, c.str("kind"), kind)
            assertEquals(label, c.get("expect").takeIf { !it.isJsonNull }?.asInt, (result as? ChannelMatch.Result.Match)?.index)
        }
    }

    @Test
    fun `coverage counts matched, unsure and missing channels`() {
        for (e in cases.getAsJsonArray("coverage")) {
            val c = e.asJsonObject
            val overrides = c.getAsJsonObject("overrides")?.entrySet()?.associate { it.key to it.value.asString }.orEmpty()
            val cov = ChannelMatch.coverage(entries(c, "main"), ChannelMatch.Matcher(entries(c, "pool")), overrides)
            val label = c.str("case")!!
            assertEquals(label, c.getAsJsonArray("main").size(), cov.total)
            assertEquals(label, c.get("matched").asInt, cov.matched)
            assertEquals(label, c.get("unsure").asInt, cov.unsure)
            assertEquals(label, c.get("missing").asInt, cov.missing)
            assertEquals(label, c.getAsJsonArray("unsureNames").map { it.asString }, cov.unsureNames)
            assertEquals(label, c.getAsJsonArray("missingNames").map { it.asString }, cov.missingNames)
        }
    }

    @Test
    fun `candidates are ranked by similarity`() {
        for (e in cases.getAsJsonArray("ranked")) {
            val c = e.asJsonObject
            val t = c.getAsJsonObject("target")
            val ranked = ChannelMatch.Matcher(entries(c, "pool")).ranked(t.str("name")!!, t.str("epg"), c.get("limit").asInt)
            assertEquals(c.str("case"), c.getAsJsonArray("expect").map { it.asInt }, ranked)
        }
    }

    @Test
    fun `levenshtein distance`() {
        assertEquals(0, ChannelMatch.levenshtein("sic", "sic"))
        assertEquals(3, ChannelMatch.levenshtein("", "sic"))
        assertEquals(1, ChannelMatch.levenshtein("sporttv1", "sporttv2"))
        assertEquals(3, ChannelMatch.levenshtein("kitten", "sitting"))
    }
}
