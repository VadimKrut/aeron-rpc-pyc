package ru.pathcreator.pyc.rpc.core.envelope;

/**
 * Фиксированный транспортный заголовок, который используется RPC-ядром.
 *
 * <p>Envelope представляет собой 24-байтный little-endian заголовок,
 * который записывается перед каждой пользовательской полезной нагрузкой.
 * Он относится к транспортному протоколу и не зависит от пользовательского
 * кодека сообщений. Полезная нагрузка после заголовка может быть представлена
 * любым бинарным форматом: SBE, Kryo, Protocol Buffers, обычным массивом байтов
 * или другим форматом, который поддерживает пользовательский кодек.</p>
 *
 * <p>Envelope is a fixed 24-byte little-endian header written before every
 * user payload. It belongs to the transport protocol and is independent of
 * the user message codec. The payload may use any binary format supported by
 * the user codec.</p>
 *
 * <p>Структура envelope / Envelope layout:</p>
 *
 * <pre>{@code
 * offset   size   field           описание / notes
 * 0        2      magic           фиксированная RPC-сигнатура / fixed RPC signature, 0xAE01
 * 2        2      version         версия формата / envelope format version
 * 4        4      messageTypeId   тип сообщения для маршрутизации / message type used for routing
 * 8        8      correlationId   идентификатор корреляции / transport-level correlation id
 * 16       4      flags           бит 0: request, бит 1: heartbeat, бит 2: error /
 *                                 bit 0: request, bit 1: heartbeat, bit 2: error
 * 20       4      payloadLength   размер payload после envelope / payload size after the envelope, in bytes
 * }</pre>
 *
 * <p>Размер envelope намеренно равен 24 байтам / The envelope length is
 * intentionally 24 bytes:</p>
 * <ul>
 *     <li>размер кратен 8 и сохраняет естественное выравнивание полей /
 *     the size is divisible by 8 and keeps the fields naturally aligned;</li>
 *     <li>{@link #MAGIC} и {@link #VERSION} помогают диагностировать чужие,
 *     поврежденные или устаревшие кадры / {@link #MAGIC} and {@link #VERSION}
 *     help diagnose foreign, corrupted, or outdated frames;</li>
 *     <li>{@link #OFFSET_PAYLOAD_LEN} указывает длину полезной нагрузки явно /
 *     {@link #OFFSET_PAYLOAD_LEN} stores the payload length explicitly,
 *     which is useful for validation and future nested formats.</li>
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
     * Фиксированная сигнатура протокола, которая записывается в начало каждого envelope.
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
     * <p>Byte offset of the message type identifier field inside the envelope.</p>
     */
    public static final int OFFSET_MESSAGE_TYPE = 4;

    /**
     * Смещение поля идентификатора корреляции внутри envelope, в байтах.
     *
     * <p>Byte offset of the correlation identifier field inside the envelope.</p>
     */
    public static final int OFFSET_CORRELATION_ID = 8;

    /**
     * Смещение поля флагов внутри envelope, в байтах.
     *
     * <p>Byte offset of the flags field inside the envelope.</p>
     */
    public static final int OFFSET_FLAGS = 16;

    /**
     * Смещение поля длины полезной нагрузки внутри envelope, в байтах.
     *
     * <p>Byte offset of the payload length field inside the envelope.</p>
     */
    public static final int OFFSET_PAYLOAD_LEN = 20;

    /**
     * Битовый флаг, который помечает envelope как RPC-запрос.
     *
     * <p>Flag bit that marks an envelope as an RPC request.</p>
     */
    public static final int FLAG_IS_REQUEST = 1;

    /**
     * Битовый флаг, который помечает envelope как heartbeat-кадр.
     *
     * <p>Flag bit that marks an envelope as a heartbeat frame.</p>
     */
    public static final int FLAG_IS_HEARTBEAT = 1 << 1;

    /**
     * Bit flag that marks an envelope as a structured remote error response.
     *
     * <p>Error frames still use the normal response message type id and
     * correlation id, but their payload is interpreted as a transport error
     * body instead of a user response payload.</p>
     */
    public static final int FLAG_IS_ERROR = 1 << 2;

    /**
     * Зарезервированный идентификатор типа сообщения для heartbeat-кадров.
     *
     * <p>Пользовательские идентификаторы типов сообщений должны быть положительными
     * и не должны использовать зарезервированные значения.</p>
     *
     * <p>Reserved message type identifier used for heartbeat frames.
     * User-defined message type identifiers should be positive and must not use
     * reserved values.</p>
     */
    public static final int RESERVED_HEARTBEAT = -1;

    /**
     * Reserved message type identifier used for optional protocol handshake frames.
     */
    public static final int RESERVED_PROTOCOL_HANDSHAKE = -2;

    /**
     * Запрещает создание экземпляров этого класса с константами.
     *
     * <p>Prevents instantiation of this constants-only class.</p>
     */
    private Envelope() {
    }
}