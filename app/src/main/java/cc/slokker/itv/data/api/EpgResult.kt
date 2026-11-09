package cc.slokker.itv.data.api

/** Resultado de um pedido ao guia da conta: o valor, ou a mensagem de erro a mostrar. */
sealed interface EpgResult<out T> {
    data class Ok<T>(val value: T) : EpgResult<T>
    data class Error(val message: String) : EpgResult<Nothing>
}
