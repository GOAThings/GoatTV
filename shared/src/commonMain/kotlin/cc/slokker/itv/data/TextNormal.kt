package cc.slokker.itv.data

/** O texto em NFKD (os acentos separados das letras, os caracteres de compatibilidade como "ᴴᴰ" desfeitos). */
internal expect fun nfkd(s: String): String
