package cc.slokker.itv.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsMergeTest {

    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    @Test
    fun `diff sends only what changed, objects key by key`() {
        val base = json("""{"buffer":"live","group_order":{"p1":["a","b"],"p2":["x"]},"afr":true}""")
        val target = json("""{"buffer":"large","group_order":{"p1":["b","a"],"p2":["x"]}}""")
        assertEquals(json("""{"buffer":"large","group_order":{"p1":["b","a"]},"afr":null}"""), SettingsMerge.diff(base, target))
        assertEquals(json("{}"), SettingsMerge.diff(base, base))
    }

    @Test
    fun `apply follows RFC 7396`() {
        val base = json("""{"a":1,"o":{"x":1,"y":2},"arr":[1,2]}""")
        val patch = json("""{"a":null,"o":{"y":null,"z":3},"arr":[3]}""")
        assertEquals(json("""{"o":{"x":1,"z":3},"arr":[3]}"""), SettingsMerge.apply(base, patch))
    }

    @Test
    fun `rebase keeps ids added elsewhere in id sets`() {
        // Aqui escondeu-se p1:12; entretanto o site escondeu p1:7 e mudou o buffer.
        val base = json("""{"hidden_groups":["p1:3"],"buffer":"live"}""")
        val local = json("""{"hidden_groups":["p1:3","p1:12"],"buffer":"live"}""")
        val server = json("""{"hidden_groups":["p1:3","p1:7"],"buffer":"large"}""")
        assertEquals(json("""{"hidden_groups":["p1:3","p1:7","p1:12"],"buffer":"large"}"""), SettingsMerge.rebase(server, base, local))
        // E mostrar outra vez (tirar do conjunto) também não apaga o do site.
        val unhide = json("""{"hidden_groups":[],"buffer":"live"}""")
        assertEquals(json("""{"hidden_groups":["p1:7"],"buffer":"large"}"""), SettingsMerge.rebase(server, base, unhide))
    }

    @Test
    fun `rebase treats a set missing locally as empty`() {
        // Visto na SHIELD: hidden_channels ainda não existia na app; o site escondeu um canal; a app escondeu outro.
        val base = json("""{"buffer":"live"}""")
        val local = json("""{"buffer":"live","hidden_channels":["p1:14103"]}""")
        val server = json("""{"buffer":"live","hidden_channels":["p1:site"]}""")
        assertEquals(json("""{"buffer":"live","hidden_channels":["p1:site","p1:14103"]}"""), SettingsMerge.rebase(server, base, local))
    }

    @Test
    fun `rebase keeps other playlists' orders changed elsewhere`() {
        val base = json("""{"channel_order":{"p1:12":["a","b"]}}""")
        val local = json("""{"channel_order":{"p1:12":["b","a"]}}""")
        val server = json("""{"channel_order":{"p1:12":["a","b"],"p2:4":["z"]}}""")
        assertEquals(json("""{"channel_order":{"p1:12":["b","a"],"p2:4":["z"]}}"""), SettingsMerge.rebase(server, base, local))
    }

    @Test
    fun `only top-level arrays ask for settings_rev`() {
        assertTrue(SettingsMerge.hasTopLevelArray(json("""{"hidden_groups":["p1:3"]}""")))
        assertFalse(SettingsMerge.hasTopLevelArray(json("""{"group_order":{"p1":["a"]},"buffer":"live"}""")))
    }
}
