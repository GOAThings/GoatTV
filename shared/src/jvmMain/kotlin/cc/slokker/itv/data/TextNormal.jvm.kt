package cc.slokker.itv.data

import java.text.Normalizer

internal actual fun nfkd(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFKD)
