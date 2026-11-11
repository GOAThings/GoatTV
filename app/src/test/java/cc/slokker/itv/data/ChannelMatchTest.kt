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
        ChannelMatch.Entry(c.str("key") ?: "k$i", c.str("name")!!, c.str("epg"), c.str("group"))
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
            val target = ChannelMatch.Entry("main", t.str("name")!!, t.str("epg"), t.str("group"))
            val manual = c.get("manual")?.asBoolean == true
            val result = ChannelMatch.decide(target, ChannelMatch.Matcher(entries(c, "pool")), c.str("override"), manual)
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
            val overrides = overrides(c)
            val cov = ChannelMatch.coverage(entries(c, "main"), ChannelMatch.Matcher(entries(c, "pool")), overrides, c.get("manual")?.asBoolean == true)
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

    @Test
    fun `the review list says how each channel is covered`() {
        for (e in cases.getAsJsonArray("review")) {
            val c = e.asJsonObject
            val label = c.str("case")!!
            val overrides = overrides(c)
            val rows = ChannelMatch.review(entries(c, "main"), ChannelMatch.Matcher(entries(c, "pool")), overrides, c.get("manual").asBoolean)
            val expected = c.getAsJsonArray("expect").map { r ->
                val a = r.asJsonArray
                Triple(a[0].asString, a[1].takeIf { !it.isJsonNull }?.asInt, a[2].takeIf { !it.isJsonNull }?.asInt)
            }
            assertEquals(label, expected, rows.map { Triple(it.state.name.lowercase(), it.backup, it.suggestion) })
            assertEquals(label, rows.indices.toList(), rows.map { it.main })
            c.getAsJsonArray("failovers")?.let { f ->
                assertEquals("$label/failovers", f.map { r -> r.asJsonArray.map { it.asString } }, rows.map { it.failovers })
            }
            for ((name, count) in c.getAsJsonObject("filters").entrySet()) {
                val filter = ChannelMatch.Filter.valueOf(name.uppercase())
                assertEquals("$label/$name", count.asInt, rows.count { with(ChannelMatch) { it.passes(filter) } })
            }
        }
    }

    /** As escolhas à mão de um caso: uma chave, "off" (nenhum) ou um array de failovers, como no `settings`. */
    private fun overrides(c: JsonObject): Map<String, List<String>> =
        c.getAsJsonObject("overrides")?.entrySet()?.associate { (k, v) ->
            k to when {
                v.isJsonArray -> v.asJsonArray.map { it.asString }
                v.asString == ChannelMatch.OFF -> emptyList()
                else -> listOf(v.asString)
            }
        }.orEmpty()

    @Test
    fun `failovers keep the chosen order and drop the ones that are gone`() {
        for (e in cases.getAsJsonArray("failovers")) {
            val c = e.asJsonObject
            val t = c.getAsJsonObject("target")
            val target = ChannelMatch.Entry(t.str("key") ?: "main", t.str("name")!!, t.str("epg"), t.str("group"))
            val mainKeys = c.getAsJsonArray("mainKeys")?.map { it.asString }?.toSet().orEmpty()
            val chosen = c.get("chosen")?.takeIf { !it.isJsonNull }?.asJsonArray?.map { it.asString }
            val result = ChannelMatch.failovers(target, ChannelMatch.Matcher(entries(c, "pool")), chosen, c.get("manual")?.asBoolean == true, mainKeys::contains)
            assertEquals(c.str("case"), c.getAsJsonArray("expect").map { it.asString }, result)
        }
    }
}
