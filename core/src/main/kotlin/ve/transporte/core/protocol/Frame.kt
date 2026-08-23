package ve.transporte.core.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * Trama binaria de transporte y almacenamiento: campos con prefijo de longitud.
 *
 * Es la misma idea que [CanonicalWriter] pero sin etiqueta de dominio, porque
 * estos bytes no se firman: solo viajan (QR) o se guardan (disco cifrado).
 */
internal class FrameWriter {
    private val out = ByteArrayOutputStream(512)

    fun bytes(v: ByteArray) = apply {
        out.write(ByteBuffer.allocate(4).putInt(v.size).array())
        out.write(v)
    }

    fun str(v: String) = bytes(v.toByteArray(StandardCharsets.UTF_8))
    fun long(v: Long) = bytes(ByteBuffer.allocate(8).putLong(v).array())
    fun int(v: Int) = long(v.toLong())
    fun build(): ByteArray = out.toByteArray()
}

internal class FrameFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

internal class FrameReader(private val buf: ByteArray) {
    private var pos = 0

    fun bytes(): ByteArray {
        if (pos + 4 > buf.size) throw FrameFormatException("trama truncada en el prefijo de longitud")
        val len = ByteBuffer.wrap(buf, pos, 4).int
        pos += 4
        if (len < 0 || pos + len > buf.size) throw FrameFormatException("longitud de campo invalida: $len")
        val out = buf.copyOfRange(pos, pos + len)
        pos += len
        return out
    }

    fun str(): String = String(bytes(), StandardCharsets.UTF_8)

    fun long(): Long {
        val b = bytes()
        if (b.size != 8) throw FrameFormatException("entero mal formado (${b.size} bytes)")
        return ByteBuffer.wrap(b).long
    }

    fun int(): Int {
        val v = long()
        if (v > Int.MAX_VALUE || v < Int.MIN_VALUE) throw FrameFormatException("entero fuera de rango: $v")
        return v.toInt()
    }

    fun end() {
        if (pos != buf.size) throw FrameFormatException("bytes sobrantes en la trama (${buf.size - pos})")
    }
}
