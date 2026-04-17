package ru.pathcreator.pyc.envelope;

/**
 * Envelope — собственный транспортный заголовок RPC-ядра, 24 байта, fixed,
 * little-endian. НЕ SBE, НЕ зависит от кодека пользователя.
 * <p>
 * Формат:
 * <pre>
 *   offset  size  field           notes
 *   [0..1]   2    magic           = 0xAE01 (фиксированная сигнатура ядра)
 *   [2..3]   2    version         версия формата envelope (сейчас = 1)
 *   [4..7]   4    messageTypeId   int32, user-defined, маршрутизация по handler-у
 *   [8..15]  8    correlationId   int64, транспортный
 *   [16..19] 4    flags           bit0=isRequest, bit1=isHeartbeat
 *   [20..23] 4    payloadLength   длина payload ПОСЛЕ envelope
 * </pre>
 * <p>
 * Дальше payload: любые байты — SBE, Kryo, protobuf, plain bytes. Ядру
 * всё равно, это отвечает {@code MessageCodec} пользователя.
 * <p>
 * Почему 24 байта:
 * - кратно 8, хорошо ложится на cache line;
 * - magic+version дают диагностику ("не наш пакет" / "старая версия");
 * - payloadLength избыточен (Aeron знает length фрагмента), но полезен при
 * валидации и возможном nested-формате в будущем.
 */
public final class Envelope {

    public static final int LENGTH = 24;

    public static final short MAGIC = (short) 0xAE01;
    public static final short VERSION = 1;

    // Offsets
    public static final int OFFSET_MAGIC = 0;
    public static final int OFFSET_VERSION = 2;
    public static final int OFFSET_MESSAGE_TYPE = 4;
    public static final int OFFSET_CORRELATION_ID = 8;
    public static final int OFFSET_FLAGS = 16;
    public static final int OFFSET_PAYLOAD_LEN = 20;

    // Flags
    public static final int FLAG_IS_REQUEST = 1;    // bit 0
    public static final int FLAG_IS_HEARTBEAT = 1 << 1;

    // Reserved message type ids — пользовательские ID должны быть > 0 и != RESERVED_*.
    public static final int RESERVED_HEARTBEAT = -1;

    private Envelope() {
    }
}