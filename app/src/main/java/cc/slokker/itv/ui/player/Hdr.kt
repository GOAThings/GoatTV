@file:OptIn(UnstableApi::class)

package cc.slokker.itv.ui.player

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import cc.slokker.itv.data.Prefs

/**
 * HDR dos canais: que tipo traz o vídeo e o que a TV aceita (pela box).
 * O vídeo HDR vai para a TV tal como vem; a TV muda sozinha para HDR (na box, "Ajustar à gama dinâmica").
 * Quando a TV não aceita o tipo do canal (ou com "Converter para SDR"), pede-se ao descodificador
 * que converta para SDR (Android 13+), para as cores não ficarem lavadas.
 */
enum class HdrType(val label: String, val displayType: Int) {
    HDR10("HDR10", Display.HdrCapabilities.HDR_TYPE_HDR10),
    HLG("HLG", Display.HdrCapabilities.HDR_TYPE_HLG),
    DOLBY_VISION("Dolby Vision", Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION),
    ;

    companion object {
        /** O HDR do vídeo [format], ou null se for SDR. */
        fun of(format: Format?): HdrType? = when {
            format == null -> null
            format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION -> DOLBY_VISION
            format.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 -> HDR10
            format.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG -> HLG
            else -> null
        }

        /** Os tipos de HDR que a TV aceita (vazio = só SDR, ou a box não diz). */
        fun supportedByDisplay(context: Context): Set<HdrType> {
            val display = (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
                ?.getDisplay(Display.DEFAULT_DISPLAY) ?: return emptySet()
            val types = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                display.mode.supportedHdrTypes
            } else {
                @Suppress("DEPRECATION")
                display.hdrCapabilities?.supportedHdrTypes
            } ?: return emptySet()
            return entries.filter { it.displayType in types }.toSet()
        }

        /**
         * O descodificador deve converter [format] para SDR? Só quando o vídeo é HDR e a TV não o aceita,
         * ou quando se escolheu "Converter para SDR". O Dolby Vision sem suporte na TV é descodificado
         * como HEVC (a camada base, HDR10), por isso basta a TV aceitar HDR10.
         */
        fun needsSdr(context: Context, format: Format, mode: String): Boolean {
            val type = of(format) ?: return false
            if (mode == Prefs.Hdr.SDR) return true
            val display = supportedByDisplay(context)
            return when (type) {
                DOLBY_VISION -> DOLBY_VISION !in display && HDR10 !in display
                else -> type !in display
            }
        }
    }
}

/** "HDR10 · BT.2020 · 10 bits" (ou "SDR") para a informação do stream. */
internal fun dynamicRangeLabel(format: Format?): String {
    val type = HdrType.of(format) ?: return "SDR"
    val color = format?.colorInfo
    return listOfNotNull(
        type.label,
        "BT.2020".takeIf { color?.colorSpace == C.COLOR_SPACE_BT2020 },
        color?.lumaBitdepth?.takeIf { it > 8 && it != Format.NO_VALUE }?.let { "$it bits" },
    ).joinToString(" · ")
}
