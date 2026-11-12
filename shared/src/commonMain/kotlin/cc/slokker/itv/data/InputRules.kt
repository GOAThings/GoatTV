package cc.slokker.itv.data

/**
 * Conversões para o que vem dos servidores IPTV: um valor fora do que faz sentido fica de fora, nunca termina a app.
 * Os limites de tamanho das respostas são de cada plataforma (SafeInput).
 */
object InputRules {
    /** Ano 3000: uma data do fornecedor para lá disto (ou negativa) não é uma data. */
    const val MAX_EPOCH_SECONDS = 32_503_680_000L

    /** Segundos do fornecedor em ms, ou null fora de um intervalo que faça sentido. */
    fun epochMs(seconds: Long): Long? = if (seconds in 0..MAX_EPOCH_SECONDS) seconds * 1000 else null

    /** Um número em texto ("12", "12.0", "1e3"), que os servidores misturam, como Int; null se não for finito ou não couber. */
    fun int(text: String): Int? {
        val v = text.trim().toDoubleOrNull() ?: return null
        if (v.isNaN() || v.isInfinite() || v >= Int.MAX_VALUE.toDouble() + 1 || v <= Int.MIN_VALUE.toDouble() - 1) return null
        return v.toInt()
    }

    /**
     * O texto UTF-8 de [raw] em Base64 (o que não é do alfabeto salta-se, como no Android e na Apple), ou null se não
     * der bytes nem UTF-8 válido.
     */
    fun base64Text(raw: String): String? {
        val bytes = ArrayList<Byte>(raw.length * 3 / 4)
        var acc = 0
        var bits = 0
        for (c in raw) {
            if (c == '=') break
            val v = when (c) {
                in 'A'..'Z' -> c - 'A'
                in 'a'..'z' -> c - 'a' + 26
                in '0'..'9' -> c - '0' + 52
                '+', '-' -> 62
                '/', '_' -> 63
                else -> continue
            }
            acc = ((acc shl 6) or v) and 0x3FFFF
            bits += 6
            if (bits >= 8) {
                bits -= 8
                bytes += (acc shr bits and 0xFF).toByte()
            }
        }
        if (bytes.isEmpty() && raw.isNotEmpty()) return null
        return try {
            bytes.toByteArray().decodeToString(throwOnInvalidSequence = true)
        } catch (e: CharacterCodingException) {
            null
        }
    }
}
