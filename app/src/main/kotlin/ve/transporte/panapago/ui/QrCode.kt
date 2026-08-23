package ve.transporte.panapago.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Pinta un QR.
 *
 * Se usa correccion de errores M: aguanta suciedad y reflejos en la pantalla
 * sin inflar demasiado el codigo. Los mensajes de este protocolo rondan los
 * 700-1000 caracteres, que caben de sobra.
 */
@Composable
fun QrCode(content: String, modifier: Modifier = Modifier, sizePx: Int = 720) {
    val bitmap = remember(content, sizePx) { renderQr(content, sizePx) }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "Codigo QR",
        modifier = modifier,
        contentScale = ContentScale.Fit,
    )
}

private fun renderQr(content: String, size: Int): Bitmap {
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 1,
        EncodeHintType.CHARACTER_SET to "UTF-8",
    )
    val matrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
    val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.RGB_565)
    val pixels = IntArray(matrix.width * matrix.height)
    for (y in 0 until matrix.height) {
        val offset = y * matrix.width
        for (x in 0 until matrix.width) {
            pixels[offset + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
        }
    }
    bitmap.setPixels(pixels, 0, matrix.width, 0, 0, matrix.width, matrix.height)
    return bitmap
}
