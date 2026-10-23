package cc.slokker.itv.data

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test

class MergeRequestTest {

    /** O pedido tem de levar os null do merge (o Gson do Retrofit é criado com serializeNulls, como aqui). */
    @Test
    fun `merge patch keeps nulls when sent`() {
        val base = JsonParser.parseString("""{"channel_order":{"p1:280":["a","b"]},"parental_pin_sha256":"x"}""").asJsonObject
        val target = JsonParser.parseString("""{"channel_order":{}}""").asJsonObject
        val body = JsonObject().apply { add("settings_merge", SettingsMerge.diff(base, target)) }
        assertEquals(
            """{"settings_merge":{"channel_order":{"p1:280":null},"parental_pin_sha256":null}}""",
            GsonBuilder().serializeNulls().create().toJson(body),
        )
    }
}
