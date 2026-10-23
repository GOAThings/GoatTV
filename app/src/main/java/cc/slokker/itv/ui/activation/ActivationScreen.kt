package cc.slokker.itv.ui.activation

import androidx.compose.ui.text.style.TextDirection
import androidx.activity.compose.BackHandler
import cc.slokker.itv.ui.glass.GlassButton
import cc.slokker.itv.ui.theme.ItvShapes
import androidx.compose.ui.platform.LocalContext
import cc.slokker.itv.ui.i18n.I18n
import cc.slokker.itv.ui.i18n.LocalStrings
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import cc.slokker.itv.data.api.BackendApi
import cc.slokker.itv.data.api.BACKEND_APP
import cc.slokker.itv.data.api.BackendDevice
import cc.slokker.itv.data.api.BackendUser
import cc.slokker.itv.data.api.DevicePollRequest
import cc.slokker.itv.data.api.DeviceStartRequest
import cc.slokker.itv.ui.common.Logo
import cc.slokker.itv.ui.glass.AuroraBackground
import cc.slokker.itv.ui.glass.glass
import cc.slokker.itv.ui.theme.ItvColors
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import retrofit2.HttpException
import java.io.IOException

@Stable
private class ActivationState {
    var code by mutableStateOf<String?>(null)
    var qr by mutableStateOf<ImageBitmap?>(null)
    /** O endereço vem do servidor (ex.: thegoat.systems/ativar); vazio até chegar. */
    var verificationUrl by mutableStateOf("")
    var expiresAt by mutableLongStateOf(0L)
    var error by mutableStateOf<String?>(null)
}

/**
 * Ativação por código: a TV mostra um código e um QR; a pessoa confirma-o no telemóvel em /ativar.
 * O polling vive enquanto este ecrã estiver visível. [onBack] (o botão e Voltar) sai sem ativar:
 * [backLabel] é "Usar sem conta" na primeira vez, ou "Voltar" quando já há listas nesta TV.
 */
@Composable
fun ActivationScreen(
    api: BackendApi,
    backLabel: String,
    onBack: () -> Unit,
    onApproved: (token: String, user: BackendUser?, device: BackendDevice?) -> Unit,
) {
    val state = remember { ActivationState() }
    val context = LocalContext.current
    BackHandler(onBack = onBack)

    LaunchedEffect(Unit) {
        runActivation(api, state, deviceName(context), onApproved)
    }

    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
    }

    val s = LocalStrings.current
    AuroraBackground {
        Row(
            Modifier
                .align(Alignment.Center)
                .glass(ItvShapes.Panel)
                .padding(48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(56.dp),
        ) {
            Column(Modifier.width(500.dp)) {
                Logo(size = 36)
                Spacer(Modifier.height(16.dp))
                Text(s.activateTitle, style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.height(28.dp))
                Text(s.activateGoTo, color = ItvColors.TextDim, style = MaterialTheme.typography.titleMedium)
                Text(state.verificationUrl, color = ItvColors.Sky, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(20.dp))
                Text(s.activateEnterCode, color = ItvColors.TextDim, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = state.code ?: "· · ·",
                    style = MaterialTheme.typography.displayLarge.copy(fontSize = 76.sp, letterSpacing = 6.sp, textDirection = TextDirection.Ltr),
                )
                Spacer(Modifier.height(20.dp))
                val remaining = ((state.expiresAt - now) / 1000).coerceAtLeast(0)
                val error = state.error
                when {
                    error != null -> Text(error, color = ItvColors.Error, style = MaterialTheme.typography.bodyLarge)
                    state.code != null -> Text(
                        s.activateExpires(remaining / 60, remaining % 60),
                        color = ItvColors.TextFaint,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    else -> Text(s.activateRequesting, color = ItvColors.TextFaint, style = MaterialTheme.typography.bodyLarge)
                }
                Spacer(Modifier.height(24.dp))
                GlassButton(backLabel, onBack)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(280.dp)
                        .clip(ItvShapes.Panel)
                        .background(Color.White),
                    contentAlignment = Alignment.Center,
                ) {
                    state.qr?.let { Image(it, contentDescription = s.activateQrDescription, Modifier.size(244.dp)) }
                }
                Spacer(Modifier.height(14.dp))
                Text(s.activateQr, color = ItvColors.TextDim, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private suspend fun runActivation(
    api: BackendApi,
    state: ActivationState,
    deviceName: String,
    onApproved: (String, BackendUser?, BackendDevice?) -> Unit,
) {
    while (true) {
        val start = try {
            api.deviceStart(DeviceStartRequest(BACKEND_APP, deviceName))
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            state.error = I18n.s.activateServerError(e.code())
            delay(if (e.code() == 429) 30_000 else 10_000)
            continue
        } catch (e: IOException) {
            state.error = I18n.s.activateOffline
            delay(5_000)
            continue
        }
        val secret = start.deviceSecret
        val code = start.code
        val url = start.verificationUrl
        if (secret == null || code == null || url == null) {
            state.error = I18n.s.activateBadResponse
            delay(10_000)
            continue
        }

        state.error = null
        state.code = code
        state.verificationUrl = url.removePrefix("https://").removePrefix("http://")
        state.qr = qrBitmap(start.verificationUrlComplete ?: url)
        state.expiresAt = System.currentTimeMillis() + (start.expiresIn ?: 600) * 1000L
        var interval = (start.interval ?: 5).coerceAtLeast(2) * 1000L

        // Pergunta a cada `interval` segundos até aprovar ou expirar; ao expirar, pede código novo.
        poll@ while (System.currentTimeMillis() < state.expiresAt) {
            delay(interval)
            val res = try {
                api.devicePoll(DevicePollRequest(secret))
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                continue
            }
            when (res.code()) {
                200 -> {
                    val body = res.body()
                    if (body?.status == "approved" && body.token != null) {
                        onApproved(body.token, body.user, body.device)
                        return
                    }
                }
                202 -> Unit
                404, 410 -> break@poll
                429 -> interval += 5_000
            }
        }
    }
}

private fun qrBitmap(text: String, size: Int = 512): ImageBitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val pixels = IntArray(size * size) { i ->
        if (matrix[i % size, i / size]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** O nome desta TV na conta: o que se deu à box no Android (ex.: "Sala"), senão o modelo. */
private fun deviceName(context: android.content.Context): String {
    val named = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
        android.provider.Settings.Global.getString(context.contentResolver, android.provider.Settings.Global.DEVICE_NAME)
    } else {
        null
    }
    return named?.takeIf { it.isNotBlank() } ?: Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android TV"
}
