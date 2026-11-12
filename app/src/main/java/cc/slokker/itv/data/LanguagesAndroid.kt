package cc.slokker.itv.data

import java.util.Locale

/** O nome de [code] em [locale], com maiúscula (como nas faixas do player). */
fun Languages.displayName(code: String, locale: Locale): String =
    Locale.forLanguageTag(code).getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }
