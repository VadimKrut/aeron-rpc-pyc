package ru.pathcreator.pyc.internal;

import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

class PendingCallTest {

    @Test
    void prepareInitializesPendingState() {
        final PendingCall call = new PendingCall();

        call.prepare(Thread.currentThread(), 77L, 88);

        assertFalse(call.isCompleted());
        assertFalse(call.isFailed());
        assertNull(call.failureReason());
        assertEquals(77L, call.correlationId());
        assertEquals(88, call.expectedResponseTypeId());
        assertEquals(0, call.responseLength());
    }

    @Test
    void completeOkCopiesResponsePayload() {
        final PendingCall call = new PendingCall();
        final UnsafeBuffer source = new UnsafeBuffer(ByteBuffer.allocateDirect(16));
        source.putByte(4, (byte) 10);
        source.putByte(5, (byte) 20);
        source.putByte(6, (byte) 30);

        call.prepare(Thread.currentThread(), 100L);
        call.completeOk(source, 4, 3);

        assertTrue(call.isCompleted());
        assertFalse(call.isFailed());
        assertEquals(3, call.responseLength());
        assertEquals(10, call.responseBuffer().getByte(0));
        assertEquals(20, call.responseBuffer().getByte(1));
        assertEquals(30, call.responseBuffer().getByte(2));
    }

    @Test
    void completeFailStoresReason() {
        final PendingCall call = new PendingCall();

        call.prepare(Thread.currentThread(), 200L);
        call.completeFail("channel down");

        assertTrue(call.isCompleted());
        assertTrue(call.isFailed());
        assertEquals("channel down", call.failureReason());
    }

    @Test
    void resetClearsReusableState() {
        final PendingCall call = new PendingCall();

        call.prepare(Thread.currentThread(), 300L);
        call.completeFail("timeout");
        call.reset();

        assertFalse(call.isCompleted());
        assertFalse(call.isFailed());
        assertNull(call.failureReason());
        assertEquals(0L, call.correlationId());
        assertEquals(0, call.expectedResponseTypeId());
        assertEquals(0, call.responseLength());
    }
}