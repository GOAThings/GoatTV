package cc.slokker.itv.ui.state

import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import cc.slokker.itv.ui.i18n.I18n
import cc.slokker.itv.ui.i18n.Strings
import java.util.Locale

/**
 * Textos para os testes no PC: cada um é o nome do recurso (o id) com os argumentos, para se poder comparar
 * sem as traduções (ex.: `I18n.s.epgRefreshed` dá sempre o mesmo texto).
 */
object TestStrings {
    fun install(): Strings {
        val res = object : Resources(null, null, null) {
            override fun getString(id: Int): String = "s$id"
            override fun getString(id: Int, vararg formatArgs: Any?): String = "s$id" + formatArgs.joinToString(",", "(", ")")
            override fun getQuantityString(id: Int, quantity: Int, vararg formatArgs: Any?): String = "q$id" + formatArgs.joinToString(",", "(", ")")
            override fun getConfiguration(): Configuration = Configuration().apply { @Suppress("DEPRECATION") locale = Locale.ENGLISH }
        }
        val context = object : ContextWrapper(null) {
            override fun getResources(): Resources = res
        }
        return Strings(context).also { I18n.s = it }
    }
}
