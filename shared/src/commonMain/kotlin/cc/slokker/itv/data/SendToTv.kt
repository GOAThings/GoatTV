package cc.slokker.itv.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** O que o telemóvel manda no `POST /open`: só ids e nomes, nunca endereços nem credenciais do fornecedor. */
class SendRequest(
    /** O id da lista aberta no telemóvel (no Modo TV Pro, o da lista única). */
    val playlist: String,
    /** A chave do canal nessa lista. */
    val channel: String,
    /** O nome do canal, para o procurar nas listas da TV quando ela não tem essa lista. */
    val name: String,
    /** A tabela do Modo TV Pro do telemóvel ("pt"), ou vazio fora dele. */
    val pro: String,
    /** O nome do telemóvel, para o aviso na TV ("" = não disse). */
    val from: String,
)

/** A resposta ao `GET /hello`: o nome do aparelho, a versão e (no Android) o pacote, para o GoatLauncher o abrir. */
class SendHello(val name: String, val version: String, val pkg: String)

/**
 * "Enviar para a TV": o telemóvel diz à GoatTV de uma TV da rede de casa que canal abrir, e a TV abre-o das listas
 * dela. O que as plataformas partilham: o serviço e a porta, o JSON, a escolha do canal na TV e os códigos.
 *
 *   GET /hello   {"app":"GoatTV","name":"<aparelho>","version":"…","package":"…"}
 *   POST /open   {"playlist":"<id>","channel":"<chave>","name":"<nome>","pro":"<tabela ou vazio>","from":"<telemóvel>"}
 *                200 com o nome do canal aberto ({"name":"…"}); 404 sem canal que sirva; 409 com a GoatTV atrás de outra app;
 *                503 sem listas (ativação, a carregar).
 *
 * A porta é a dos telemóveis (6471, ver [LanRequest]): as mesmas regras de quem pode falar.
 */
object SendToTv {
    const val PORT = 6471

    /** O serviço que a TV anuncia na rede (Bonjour/mDNS) e que o telemóvel procura. */
    const val SERVICE = "_goattv._tcp"

    /** A porta do GoatLauncher, que sabe trazer uma app para a frente (`POST /open/<pacote>`). */
    const val LAUNCHER_PORT = 6470

    const val APP = "GoatTV"

    /** Com 409 ou 503, quantas vezes o telemóvel tenta outra vez, e de quanto em quanto tempo. */
    const val RETRIES = 6
    const val RETRY_MS = 1000L
    const val MAX_BODY_BYTES = 4096

    const val OK = 200
    const val NOT_FOUND = 404
    const val NOT_IN_FRONT = 409
    const val NO_LISTS = 503

    /** O que a TV faz com um pedido (ver [route]). */
    const val ROUTE_HERE = 0
    const val ROUTE_SWITCH = 1
    const val ROUTE_NAME = 2

    /** O que o telemóvel diz à pessoa (ver [outcome]). */
    const val PLAYING = 0
    const val MISSING = 1
    const val CLOSED = 2
    const val FAILED = 3

    /** O passo seguinte do telemóvel depois de uma resposta ao /open (ver [step]). */
    const val STEP_DONE = 0
    const val STEP_LAUNCH = 1
    const val STEP_WAIT = 2

    private const val MAX_TEXT = 300
    private const val MAX_FROM = 40

    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    /** O corpo do `POST /open`. */
    fun body(playlist: String, channel: String, name: String, pro: String, from: String): String = buildJsonObject {
        put("playlist", playlist)
        put("channel", channel)
        put("name", name)
        put("pro", pro)
        put("from", clean(from, MAX_FROM))
    }.toString()

    /** O pedido em [body], ou null se não for um objeto JSON com um canal (a chave ou o nome). */
    fun parse(body: String): SendRequest? {
        val o = obj(body) ?: return null
        val request = SendRequest(
            text(o, "playlist", MAX_TEXT), text(o, "channel", MAX_TEXT), clean(text(o, "name", MAX_TEXT), MAX_TEXT),
            text(o, "pro", MAX_TEXT), clean(text(o, "from", MAX_TEXT), MAX_FROM),
        )
        return request.takeIf { it.channel.isNotEmpty() || it.name.isNotEmpty() }
    }

    /** O corpo da resposta ao /open: o nome do canal que a TV abriu ("" sem canal). */
    fun opened(name: String): String = buildJsonObject { put("name", name) }.toString()

    /** O nome do canal que a TV diz ter aberto, ou "" se não disse. */
    fun parseOpened(body: String): String = obj(body)?.let { clean(text(it, "name", MAX_TEXT), MAX_TEXT) }.orEmpty()

    /** O caminho do GoatLauncher que traz a app [pkg] para a frente (só com um pacote que veio de [parseHello]). */
    fun launcherPath(pkg: String): String = "/open/$pkg"

    /** A resposta ao `GET /hello`. */
    fun hello(name: String, version: String, pkg: String): String = buildJsonObject {
        put("app", APP)
        put("name", clean(name, MAX_FROM))
        put("version", version)
        put("package", pkg)
    }.toString()

    /** Quem respondeu ao /hello, ou null se não for uma GoatTV. */
    fun parseHello(body: String): SendHello? {
        val o = obj(body) ?: return null
        if (text(o, "app", MAX_TEXT) != APP) return null
        val pkg = text(o, "package", MAX_TEXT).takeIf { p -> p.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '.' || it == '_' } }.orEmpty()
        return SendHello(clean(text(o, "name", MAX_TEXT), MAX_FROM), clean(text(o, "version", MAX_TEXT), MAX_FROM), pkg)
    }

    /**
     * Por onde a TV começa: o canal está na lista aberta ([ROUTE_HERE]: a mesma lista, ou a mesma tabela do Modo TV
     * Pro), noutra lista dela ([ROUTE_SWITCH]: a mesma conta, os ids são iguais), ou procura-se pelo nome
     * ([ROUTE_NAME]). No Modo TV Pro da TV ([proOnTv]) nunca se sai da lista única. Quando a chave não existir (ou não
     * se puder ver) no sítio dito, quem chama passa ao nome.
     */
    fun route(request: SendRequest, openId: String, playlists: List<String>, proOnTv: Boolean): Int = when {
        request.channel.isEmpty() || request.playlist.isEmpty() -> ROUTE_NAME
        request.playlist == openId -> ROUTE_HERE
        !proOnTv && request.playlist in playlists -> ROUTE_SWITCH
        else -> ROUTE_NAME
    }

    /**
     * A chave do canal de [pool] (os canais que a TV pode abrir sem PIN) que é o [name] do telemóvel: o mesmo nome
     * escrito da mesma maneira, ou o que o [ChannelMatch] das listas de reserva acha (com dúvida não se escolhe:
     * nunca se põe a dar um canal errado).
     */
    fun byName(name: String, pool: List<ChannelMatch.Entry>): String? {
        val wanted = name.trim()
        if (wanted.isEmpty()) return null
        pool.firstOrNull { it.name.trim().equals(wanted, ignoreCase = true) }?.let { return it.key }
        val found = ChannelMatch.Matcher(pool).find(wanted, null)
        return (found as? ChannelMatch.Result.Match)?.let { pool[it.index].key }
    }

    /** O código da resposta ao /open: primeiro a app à frente, depois haver listas, depois o canal. */
    fun status(inFront: Boolean, ready: Boolean, found: Boolean): Int = when {
        !inFront -> NOT_IN_FRONT
        !ready -> NO_LISTS
        !found -> NOT_FOUND
        else -> OK
    }

    /** A linha de estado HTTP de [code]. */
    fun statusLine(code: Int): String = when (code) {
        OK -> "200 OK"
        NOT_FOUND -> "404 Not Found"
        NOT_IN_FRONT -> "409 Conflict"
        NO_LISTS -> "503 Service Unavailable"
        else -> "400 Bad Request"
    }

    /** O telemóvel tenta outra vez daqui a pouco: a TV está a vir para a frente ou ainda a carregar as listas. */
    fun retry(status: Int): Boolean = status == NOT_IN_FRONT || status == NO_LISTS

    /**
     * Depois da resposta [status] à tentativa [attempt] (0 = a primeira): acabou ([STEP_DONE]), pedir ao GoatLauncher
     * que traga a GoatTV para a frente ([STEP_LAUNCH], uma vez e só se a TV disse o pacote em [canLaunch]) ou esperar
     * [RETRY_MS] e tentar outra vez ([STEP_WAIT]). Sem GoatLauncher (Apple TV) um 409 acaba logo.
     */
    fun step(status: Int, attempt: Int, launched: Boolean, canLaunch: Boolean): Int = when {
        !retry(status) || attempt >= RETRIES -> STEP_DONE
        status == NOT_IN_FRONT && !launched -> if (canLaunch) STEP_LAUNCH else STEP_DONE
        else -> STEP_WAIT
    }

    /** O que dizer no telemóvel com a última resposta ([status] 0 = não respondeu). */
    fun outcome(status: Int): Int = when (status) {
        OK -> PLAYING
        NOT_FOUND -> MISSING
        NOT_IN_FRONT -> CLOSED
        else -> FAILED
    }

    /** As TVs pela ordem da folha: a última usada primeiro, depois pelo nome. Devolve as posições em [names]. */
    fun order(names: List<String>, last: String?): List<Int> =
        names.indices.sortedWith(compareBy<Int> { if (names[it] == last) 0 else 1 }.thenBy { names[it].lowercase() })

    /** Um texto vindo da rede, para mostrar: sem caracteres de controlo e com tamanho limitado. */
    fun clean(text: String, max: Int = MAX_FROM): String =
        text.filter { it >= ' ' && it != '\u007f' }.trim().take(max).trim()

    private fun obj(body: String): JsonObject? =
        if (body.length > MAX_BODY_BYTES) null else runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()

    private fun text(o: JsonObject, key: String, max: Int): String =
        (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.take(max).orEmpty()
}
