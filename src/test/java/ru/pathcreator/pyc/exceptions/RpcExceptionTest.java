package ru.pathcreator.pyc.exceptions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class RpcExceptionTest {

    @Test
    void storesMessageAndCause() {
        final RuntimeException cause = new RuntimeException("root");
        final RpcException exception = new RpcException("rpc failed", cause);

        assertEquals("rpc failed", exception.getMessage());
        assertSame(cause, exception.getCause());
    }

    @Test
    void payloadTooLargeStoresActualAndLimit() {
        final PayloadTooLargeException exception = new PayloadTooLargeException(200, 100);

        assertEquals(200, exception.actual());
        assertEquals(100, exception.limit());
    }

    @Test
    void timeoutStoresCorrelationIdAndTimeout() {
        final RpcTimeoutException exception = new RpcTimeoutException(55L, 1_000_000L);

        assertEquals(55L, exception.correlationId());
        assertEquals(1_000_000L, exception.timeoutNs());
    }
}