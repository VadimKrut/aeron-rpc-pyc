package ru.pathcreator.pyc.rpc.core.envelope;

import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

class EnvelopeCodecTest {

    @Test
    void encodesAndDecodesAllEnvelopeFields() {
        final UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(Envelope.LENGTH));

        EnvelopeCodec.encode(
                buffer,
                0,
                42,
                123_456_789L,
                Envelope.FLAG_IS_REQUEST,
                128
        );

        assertEquals(Envelope.MAGIC, EnvelopeCodec.magic(buffer, 0));
        assertEquals(Envelope.VERSION, EnvelopeCodec.version(buffer, 0));
        assertEquals(42, EnvelopeCodec.messageTypeId(buffer, 0));
        assertEquals(123_456_789L, EnvelopeCodec.correlationId(buffer, 0));
        assertEquals(Envelope.FLAG_IS_REQUEST, EnvelopeCodec.flags(buffer, 0));
        assertEquals(128, EnvelopeCodec.payloadLength(buffer, 0));
    }

    @Test
    void checksRequestAndHeartbeatFlags() {
        final int flags = Envelope.FLAG_IS_REQUEST | Envelope.FLAG_IS_HEARTBEAT;

        assertTrue(EnvelopeCodec.isRequest(flags));
        assertTrue(EnvelopeCodec.isHeartbeat(flags));
        assertFalse(EnvelopeCodec.isRequest(0));
        assertFalse(EnvelopeCodec.isHeartbeat(0));
    }
}