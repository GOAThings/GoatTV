package cc.slokker.itv.media

import platform.Foundation.NSData

/** O DASH para o Swift: os segmentos de um `SegmentBase` pelo índice (`sidx`) que o HlsReader leu. */
object AppleDash {
    fun withIndex(playlist: HlsPlaylist, data: NSData): HlsPlaylist = DashManifest.withIndex(playlist, AppleMedia.bytes(data))
}
