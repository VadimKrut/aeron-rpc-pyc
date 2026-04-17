package ru.pathcreator.pyc.envelope;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;

import java.nio.ByteOrder;

/**
 * Прямое чтение/запись envelope без объектов (zero-alloc).
 * <p>
 * Все методы статические. Buffer должен быть little-endian (DirectBuffer
 * в Aeron — LE по соглашению, но getShort/getInt с явным ByteOrder гарантирует
 * независимость от платформы).
 */
public final class EnvelopeCodec {

    private static final ByteOrder LE = ByteOrder.LITTLE_ENDIAN;

    private EnvelopeCodec() {
    }

    /**
     * Записать envelope в buffer[offset..offset+LENGTH).
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

    public static short magic(final DirectBuffer b, final int offset) {
        return b.getShort(offset + Envelope.OFFSET_MAGIC, LE);
    }

    public static short version(final DirectBuffer b, final int offset) {
        return b.getShort(offset + Envelope.OFFSET_VERSION, LE);
    }

    public static int messageTypeId(final DirectBuffer b, final int offset) {
        return b.getInt(offset + Envelope.OFFSET_MESSAGE_TYPE, LE);
    }

    public static long correlationId(final DirectBuffer b, final int offset) {
        return b.getLong(offset + Envelope.OFFSET_CORRELATION_ID, LE);
    }

    public static int flags(final DirectBuffer b, final int offset) {
        return b.getInt(offset + Envelope.OFFSET_FLAGS, LE);
    }

    public static int payloadLength(final DirectBuffer b, final int offset) {
        return b.getInt(offset + Envelope.OFFSET_PAYLOAD_LEN, LE);
    }

    public static boolean isRequest(final int flags) {
        return (flags & Envelope.FLAG_IS_REQUEST) != 0;
    }

    public static boolean isHeartbeat(final int flags) {
        return (flags & Envelope.FLAG_IS_HEARTBEAT) != 0;
    }
}