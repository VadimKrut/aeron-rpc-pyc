package ru.pathcreator.pyc.rpc.core.envelope;

/**
 * Фиксированный транспортный заголовок RPC-протокола.
 *
 * <p>Envelope — это 24-байтный little-endian заголовок, который записывается
 * перед каждой пользовательской полезной нагрузкой. Он принадлежит
 * транспортному протоколу и не зависит от пользовательского codec layer.</p>
 *
 * <p>Envelope is a fixed 24-byte little-endian header written before every
 * user payload. It belongs to the transport protocol and is independent of the
 * user message codec layer.</p>
 *
 * <p>Структура заголовка / Envelope layout:</p>
 *
 * <pre>{@code
 * offset   size   field           description
 * 0        2      magic           fixed RPC signature, 0xAE01
 * 2        2      version         envelope format version
 * 4        4      messageTypeId   routing message type
 * 8        8      correlationId   transport-level correlation id
 * 16       4      flags           bit 0: request, bit 1: heartbeat, bit 2: error
 * 20       4      payloadLength   payload size after the envelope, in bytes
 * }</pre>
 *
 * <p>Размер envelope намеренно равен 24 байтам. / The envelope length is
 * intentionally 24 bytes:</p>
 * <ul>
 *     <li>размер кратен 8 и сохраняет естественное выравнивание полей /
 *     the size is divisible by 8 and keeps the fields naturally aligned;</li>
 *     <li>{@link #MAGIC} and {@link #VERSION} помогают выявлять чужие,
 *     поврежденные или устаревшие кадры / help diagnose foreign, corrupted, or
 *     outdated frames;</li>
 *     <li>{@link #OFFSET_PAYLOAD_LEN} хранит длину payload явно, что удобно для
 *     валидации и вложенных форматов / stores the payload length explicitly,
 *     which helps with validation and nested formats.</li>
 * </ul>
 *
 * @see EnvelopeCodec
 */
public final class Envelope {

    /**
     * Размер заголовка envelope в байтах.
     *
     * <p>Size of the envelope header in bytes.</p>
     */
    public static final int LENGTH = 24;

    /**
     * Фиксированная сигнатура протокола в начале каждого envelope.
     *
     * <p>Fixed protocol signature written at the beginning of every envelope.</p>
     */
    public static final short MAGIC = (short) 0xAE01;

    /**
     * Текущая версия формата envelope.
     *
     * <p>Current envelope format version.</p>
     */
    public static final short VERSION = 1;

    /**
     * Смещение поля {@link #MAGIC} внутри envelope, в байтах.
     *
     * <p>Byte offset of the {@link #MAGIC} field inside the envelope.</p>
     */
    public static final int OFFSET_MAGIC = 0;

    /**
     * Смещение поля {@link #VERSION} внутри envelope, в байтах.
     *
     * <p>Byte offset of the {@link #VERSION} field inside the envelope.</p>
     */
    public static final int OFFSET_VERSION = 2;

    /**
     * Смещение поля идентификатора типа сообщения внутри envelope, в байтах.
     *
     * <p>Byte offset of the message type identifier field inside the
     * envelope.</p>
     */
    public static final int OFFSET_MESSAGE_TYPE = 4;

    /**
     * Смещение поля correlation id внутри envelope, в байтах.
     *
     * <p>Byte offset of the correlation identifier field inside the
     * envelope.</p>
     */
    public static final int OFFSET_CORRELATION_ID = 8;

    /**
     * Смещение поля флагов внутри envelope, в байтах.
     *
     * <p>Byte offset of the flags field inside the envelope.</p>
     */
    public static final int OFFSET_FLAGS = 16;

    /**
     * Смещение поля длины payload внутри envelope, в байтах.
     *
     * <p>Byte offset of the payload length field inside the envelope.</p>
     */
    public static final int OFFSET_PAYLOAD_LEN = 20;

    /**
     * Флаг, помечающий envelope как RPC-запрос.
     *
     * <p>Flag bit that marks an envelope as an RPC request.</p>
     */
    public static final int FLAG_IS_REQUEST = 1;

    /**
     * Флаг, помечающий envelope как heartbeat frame.
     *
     * <p>Flag bit that marks an envelope as a heartbeat frame.</p>
     */
    public static final int FLAG_IS_HEARTBEAT = 1 << 1;

    /**
     * Флаг, помечающий envelope как structured remote error response.
     *
     * <p>Error frames still use the normal response message type id and
     * correlation id, but their payload is interpreted as a transport error
     * body instead of a user response payload.</p>
     */
    public static final int FLAG_IS_ERROR = 1 << 2;

    /**
     * Зарезервированный тип сообщения для heartbeat frame-ов.
     *
     * <p>Пользовательские message type id должны быть положительными и не
     * должны использовать reserved values.</p>
     *
     * <p>Reserved message type identifier used for heartbeat frames.
     * User-defined message type identifiers should be positive and must not use
     * reserved values.</p>
     */
    public static final int RESERVED_HEARTBEAT = -1;

    /**
     * Зарезервированный тип сообщения для optional protocol handshake frame-ов.
     *
     * <p>Reserved message type identifier used for optional protocol handshake
     * frames.</p>
     */
    public static final int RESERVED_PROTOCOL_HANDSHAKE = -2;

    /**
     * Запрещает создание экземпляров класса с константами.
     *
     * <p>Prevents instantiation of this constants-only class.</p>
     */
    private Envelope() {
    }
}