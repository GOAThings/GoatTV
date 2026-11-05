package cc.slokker.itv.data

import java.io.InputStream

/**
 * O início de um pedido à porta dos telemóveis ([cc.slokker.itv.PhoneChannels.PORT]), lido com limite de tamanho.
 * Só se responde a pedidos diretos, como os do GoatRemote (um IP ou um nome de casa em Host, e sem Origin): o que
 * vier de uma página web é recusado. As mesmas regras da porta 6470 do GoatLauncher.
 */
internal class LanRequest private constructor(
    val method: String,
    val path: String,
    private val hasOrigin: Boolean,
    private val hosts: List<String>,
) {
    val allowed: Boolean get() = !hasOrigin && hosts.size == 1 && (isIpHost(hosts[0]) || isHomeName(hosts[0]))

    companion object {
        const val MAX_HEAD_BYTES = 8 * 1024

        /** Lê até à linha em branco. null: fechou antes disso, passou de [MAX_HEAD_BYTES] ou não se percebe. */
        fun read(input: InputStream): LanRequest? {
            val head = StringBuilder()
            var emptyLine = true
            while (head.length < MAX_HEAD_BYTES) {
                val byte = input.read()
                if (byte < 0) return null
                val char = byte.toChar()
                head.append(char)
                if (char == '\n') {
                    if (emptyLine) return parse(head.toString())
                    emptyLine = true
                } else if (char != '\r') {
                    emptyLine = false
                }
            }
            return null
        }

        private fun parse(head: String): LanRequest? {
            val lines = head.split('\n').map { it.trimEnd('\r') }
            val requestLine = lines.first().split(' ')
            val method = requestLine[0].takeIf { it.isNotEmpty() } ?: return null
            val path = requestLine.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return null
            val headers = lines.drop(1).filter { ':' in it }.map { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
            return LanRequest(method, path, headers.any { it.first == "origin" }, headers.filter { it.first == "host" }.map { it.second })
        }

        /** Um IPv4 ou um IPv6 entre parênteses retos, com ou sem porta. Nunca um nome (nem se tenta resolvê-lo). */
        fun isIpHost(host: String): Boolean {
            if (host.startsWith('[')) {
                val end = host.indexOf(']')
                if (end < 0 || !isIpv6(host.substring(1, end))) return false
                val rest = host.substring(end + 1)
                return rest.isEmpty() || (rest.startsWith(':') && isPort(rest.substring(1)))
            }
            val parts = host.split(':')
            return when (parts.size) {
                1 -> isIpv4(parts[0])
                2 -> isIpv4(parts[0]) && isPort(parts[1])
                else -> false
            }
        }

        /** Um nome que só existe dentro de casa (uma TV juntada à mão pelo nome): um site não o consegue ter. */
        fun isHomeName(host: String): Boolean {
            val parts = host.lowercase().split(':')
            if (parts.size > 2 || (parts.size == 2 && !isPort(parts[1]))) return false
            val name = parts[0]
            if (name.isEmpty() || !name.all { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '.' }) return false
            return '.' !in name || HOME_SUFFIXES.any { name.endsWith(it) }
        }

        private val HOME_SUFFIXES = listOf(".local", ".lan", ".home", ".home.arpa", ".internal")

        private fun isPort(text: String): Boolean =
            text.length in 1..5 && text.all { it in '0'..'9' } && text.toInt() <= 65535

        private fun isIpv4(text: String): Boolean {
            val parts = text.split('.')
            return parts.size == 4 && parts.all { p -> p.length in 1..3 && p.all { it in '0'..'9' } && p.toInt() <= 255 }
        }

        private fun isIpv6(text: String): Boolean {
            val address = text.substringBefore('%')
            if ('%' in text && !text.substringAfter('%').matches(ZONE)) return false
            val gap = address.indexOf("::")
            if (gap != address.lastIndexOf("::")) return false
            if (gap < 0) return groups(address, last = true) == 8
            val before = groups(address.substring(0, gap), last = false)
            val after = groups(address.substring(gap + 2), last = true)
            return before >= 0 && after >= 0 && before + after <= 7
        }

        /** Quantos grupos de 16 bits tem [part] (um IPv4 no fim conta 2), ou -1 se não estiver bem escrito. */
        private fun groups(part: String, last: Boolean): Int {
            if (part.isEmpty()) return 0
            val items = part.split(':')
            var count = 0
            for ((i, item) in items.withIndex()) {
                count += when {
                    item.length in 1..4 && item.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' } -> 1
                    last && i == items.lastIndex && isIpv4(item) -> 2
                    else -> return -1
                }
            }
            return count
        }

        private val ZONE = Regex("[A-Za-z0-9._~-]+")
    }
}
