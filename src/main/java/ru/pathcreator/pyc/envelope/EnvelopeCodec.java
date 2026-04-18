package ru.pathcreator.pyc.envelope;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;

import java.nio.ByteOrder;

/**
 * Кодек для прямого чтения и записи envelope без создания дополнительных объектов.
 *
 * <p>Все методы этого класса статические и работают напрямую с {@link DirectBuffer}
 * или {@link MutableDirectBuffer}. Поля envelope записываются и читаются в формате
 * little-endian. Использование явного {@link ByteOrder} делает код независимым
 * от порядка байтов платформы.</p>
 *
 * <p>Codec for direct zero-allocation reading and writing of envelope headers.
 * All methods are static and operate directly on {@link DirectBuffer} or
 * {@link MutableDirectBuffer}. Envelope fields are encoded and decoded using
 * little-endian byte order.</p>
 *
 * @see Envelope
 */
public final class EnvelopeCodec {

    private static final ByteOrder LE = ByteOrder.LITTLE_ENDIAN;

    /**
     * Запрещает создание экземпляров этого класса с утилитными методами.
     *
     * <p>Prevents instantiation of this utility class.</p>
     */
    private EnvelopeCodec() {
    }

    /**
     * Записывает envelope в указанный буфер, начиная с заданного смещения.
     *
     * <p>Метод записывает ровно {@link Envelope#LENGTH} байт в область
     * {@code buffer[offset..offset + Envelope.LENGTH)}. Полезная нагрузка этим
     * методом не записывается: он формирует только транспортный заголовок.</p>
     *
     * <p>Writes an envelope header into the provided buffer starting at the
     * specified offset. The method writes exactly {@link Envelope#LENGTH} bytes
     * and does not write the payload itself.</p>
     *
     * @param buffer        буфер назначения / destination buffer
     * @param offset        смещение начала envelope в буфере / envelope start offset in the buffer
     * @param messageTypeId идентификатор типа сообщения для маршрутизации /
     *                      message type identifier used for routing
     * @param correlationId транспортный идентификатор корреляции /
     *                      transport-level correlation identifier
     * @param flags         битовые флаги envelope / envelope bit flags
     * @param payloadLength длина полезной нагрузки после envelope, в байтах /
     *                      payload length after the envelope, in bytes
     */
    public static void encode(
            final MutableDirectBuffer buffer,
            final int offset,
            final int messageTypeId,
            final long correlationId,
            final int flags,
            final int payloadLength
    ) {
        buffer.putShort(offset + Envelope.OFFSET_MAGIC, Envelope.MAGIC, LE);
        buffer.putShort(offset + Envelope.OFFSET_VERSION, Envelope.VERSION, LE);
        buffer.putInt(offset + Envelope.OFFSET_MESSAGE_TYPE, messageTypeId, LE);
        buffer.putLong(offset + Envelope.OFFSET_CORRELATION_ID, correlationId, LE);
        buffer.putInt(offset + Envelope.OFFSET_FLAGS, flags, LE);
        buffer.putInt(offset + Envelope.OFFSET_PAYLOAD_LEN, payloadLength, LE);
    }

    /**
     * Читает сигнатуру протокола из envelope.
     *
     * <p>Reads the protocol signature from an envelope.</p>
     *
     * @param b      буфер с envelope / buffer containing the envelope
     * @param offset смещение начала envelope в буфере / envelope start offset in the buffer
     * @return значение поля {@link Envelope#MAGIC} / value of the {@link Envelope#MAGIC} field
     */
    public static short magic(final DirectBuffer b, final int offset) {
        return b.getShort(offset + Envelope.OFFSET_MAGIC, LE);
    }

    /**
     * Читает версию формата из envelope.
     *
     * <p>Reads the envelope format version.</p>
     *
     * @param b      буфер с envelope / buffer containing the envelope
     * @param offset смещение начала envelope в буфере / envelope start offset in the buffer
     * @return значение поля {@link Envelope#VERSION} / value of the {@link Envelope#VERSION} field
     */
    public static short version(final DirectBuffer b, final int offset) {
        return b.getShort(offset + Envelope.OFFSET_VERSION, LE);
    }

    /**
     * Читает идентификатор типа сообщения из envelope.
     *
     * <p>Reads the message type identifier from an envelope.</p>
     *
     * @param b      буфер с envelope / buffer containing the envelope
     * @param offset смещение начала envelope в буфере / envelope start offset in the buffer
     * @return идентификатор типа сообщения / message type identifier
     */
    public static int messageTypeId(final DirectBuffer b, final int offset) {
        return b.getInt(offset + Envelope.OFFSET_MESSAGE_TYPE, LE);
    }

    /**
     * Читает транспортный идентификатор корреляции из envelope.
     *
     * <p>Reads the transport-level correlation identifier from an envelope.</p>
     *
     * @param b      буфер с envelope / buffer containing the envelope
     * @param offset смещение начала envelope в буфере / envelope start offset in the buffer
     * @return идентификатор корреляции / correlation identifier
     */
    public static long correlationId(final DirectBuffer b, final int offset) {
        return b.getLong(offset + Envelope.OFFSET_CORRELATION_ID, LE);
    }

    /**
     * Читает битовые флаги из envelope.
     *
     * <p>Reads the bit flags from an envelope.</p>
     *
     * @param b      буфер с envelope / buffer containing the envelope
     * @param offset смещение начала envelope в буфере / envelope start offset in the buffer
     * @return значение поля флагов / flags field value
     */
    public static int flags(final DirectBuffer b, final int offset) {
        return b.getInt(offset + Envelope.OFFSET_FLAGS, LE);
    }

    /**
     * Читает длину полезной нагрузки из envelope.
     *
     * <p>Reads the payload length from an envelope.</p>
     *
     * @param b      буфер с envelope / buffer containing the envelope
     * @param offset смещение начала envelope в буфере / envelope start offset in the buffer
     * @return длина полезной нагрузки после envelope, в байтах /
     * payload length after the envelope, in bytes
     */
    public static int payloadLength(final DirectBuffer b, final int offset) {
        return b.getInt(offset + Envelope.OFFSET_PAYLOAD_LEN, LE);
    }

    /**
     * Проверяет, установлен ли флаг RPC-запроса.
     *
     * <p>Checks whether the RPC request flag is set.</p>
     *
     * @param flags значение поля флагов envelope / envelope flags field value
     * @return {@code true}, если envelope помечен как запрос /
     * {@code true} if the envelope is marked as a request
     */
    public static boolean isRequest(final int flags) {
        return (flags & Envelope.FLAG_IS_REQUEST) != 0;
    }

    /**
     * Проверяет, установлен ли флаг heartbeat-кадра.
     *
     * <p>Checks whether the heartbeat frame flag is set.</p>
     *
     * @param flags значение поля флагов envelope / envelope flags field value
     * @return {@code true}, если envelope помечен как heartbeat /
     * {@code true} if the envelope is marked as a heartbeat frame
     */
    public static boolean isHeartbeat(final int flags) {
        return (flags & Envelope.FLAG_IS_HEARTBEAT) != 0;
    }

    /**
     * Checks whether the error flag is set.
     *
     * @param flags envelope flags
     * @return {@code true} when the frame is a structured error response
     */
    public static boolean isError(final int flags) {
        return (flags & Envelope.FLAG_IS_ERROR) != 0;
    }
}