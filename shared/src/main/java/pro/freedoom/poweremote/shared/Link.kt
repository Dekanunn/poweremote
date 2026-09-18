package pro.freedoom.poweremote.shared

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Общие константы канала связи между пультом (телефон) и приёмником (плеер).
 *
 * Транспорт — Bluetooth RFCOMM (SPP-подобный сервис с собственным UUID).
 * Кадр: [1 байт тип][4 байта длина, big-endian][полезная нагрузка].
 */
object Link {
    /** Собственный UUID сервиса. Должен совпадать в обоих приложениях. */
    val SERVICE_UUID: UUID = UUID.fromString("6f2c1a7e-9b34-4d55-8c21-3f7ae0d91b42")

    /** Имя записи в SDP. */
    const val SDP_NAME = "PoweRemoteLink"

    /** Тип кадра: UTF-8 JSON. */
    const val TYPE_JSON = 1

    /** Тип кадра: JPEG обложки. */
    const val TYPE_ART = 2

    /** Защита от мусора в потоке. */
    const val MAX_FRAME = 2 * 1024 * 1024

    /** Как часто приёмник шлёт состояние, даже если ничего не изменилось (мс). */
    const val PUSH_INTERVAL_MS = 2000L

    /** Пульт считает связь мёртвой, если кадров не было дольше этого времени (мс). */
    const val STALE_MS = 9000L
}

/** Один прочитанный кадр. */
class Frame(val type: Int, val payload: ByteArray)

/**
 * Потокобезопасная запись кадров. Все отправки идут через один synchronized-метод,
 * иначе два потока могут перемешать заголовок и тело кадра.
 */
class FrameWriter(out: OutputStream) {
    private val d = DataOutputStream(out)

    @Synchronized
    @Throws(Exception::class)
    fun write(type: Int, payload: ByteArray) {
        d.writeByte(type)
        d.writeInt(payload.size)
        if (payload.isNotEmpty()) d.write(payload)
        d.flush()
    }

    @Throws(Exception::class)
    fun writeText(type: Int, text: String) = write(type, text.toByteArray(Charsets.UTF_8))
}

/** Чтение кадров. Блокирующее — вызывать только из своего потока. */
class FrameReader(input: InputStream) {
    private val d = DataInputStream(input)

    @Throws(Exception::class)
    fun read(): Frame {
        val type = d.readUnsignedByte()
        val len = d.readInt()
        if (len < 0 || len > Link.MAX_FRAME) throw IllegalStateException("Некорректная длина кадра: $len")
        val buf = ByteArray(len)
        if (len > 0) d.readFully(buf)
        return Frame(type, buf)
    }
}
