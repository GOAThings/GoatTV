package cc.slokker.itv.ui.common

import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester

/** Pede foco durante alguns frames, até o elemento existir (listas e animações ainda a compor). */
internal suspend fun focusWithRetry(requester: FocusRequester) {
    repeat(10) {
        withFrameNanos { }
        if (runCatching { requester.requestFocus() }.getOrDefault(false)) return
    }
}
