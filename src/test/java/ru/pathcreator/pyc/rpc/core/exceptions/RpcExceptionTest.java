package ru.pathcreator.pyc.rpc.core.exceptions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

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
    void applicationExceptionStoresKnownStatusCode() {
        final RpcApplicationException exception = new RpcApplicationException(RpcStatus.BAD_REQUEST, "bad");

        assertEquals(RpcStatus.BAD_REQUEST.code(), exception.statusCode());
        assertEquals("bad", exception.getMessage());
    }

    @Test
    void applicationExceptionStoresCustomStatusCode() {
        final RpcApplicationException exception = new RpcApplicationException(1001, "domain");

        assertEquals(1001, exception.statusCode());
        assertEquals("domain", exception.getMessage());
    }

    @Test
    void applicationExceptionRejectsReservedCustomCodes() {
        final IllegalArgumentException exception = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new RpcApplicationException(999, "bad"));

        assertEquals("custom application statusCode must be >= 1000", exception.getMessage());
    }

    @Test
    void remoteExceptionResolvesKnownStatus() {
        final RemoteRpcException exception = new RemoteRpcException(413, "too large");

        assertEquals(413, exception.statusCode());
        assertEquals(RpcStatus.PAYLOAD_TOO_LARGE, exception.status());
    }

    @Test
    void resolvesEveryKnownRpcStatusFromNumericCode() {
        for (final RpcStatus status : RpcStatus.values()) {
            assertEquals(status, RpcStatus.fromCode(status.code()));
        }
    }

    @Test
    void remoteExceptionLeavesCustomStatusUnresolved() {
        final RemoteRpcException exception = new RemoteRpcException(1001, "domain");

        assertEquals(1001, exception.statusCode());
        assertNull(exception.status());
        assertFalse(exception.isRetryable());
    }

    @Test
    void statusHelpersClassifyBuiltInStatuses() {
        assertTrue(RpcStatus.OK.isSuccess());
        assertTrue(RpcStatus.BAD_REQUEST.isClientError());
        assertTrue(RpcStatus.INTERNAL_SERVER_ERROR.isServerError());
        assertTrue(RpcStatus.SERVICE_UNAVAILABLE.isRetryable());
        assertFalse(RpcStatus.BAD_REQUEST.isRetryable());
    }

    @Test
    void remoteExceptionExposesStatusHelpers() {
        final RemoteRpcException retryable = new RemoteRpcException(503, "busy");
        final RemoteRpcException clientError = new RemoteRpcException(400, "bad request");

        assertTrue(retryable.isRetryable());
        assertTrue(retryable.isServerError());
        assertFalse(retryable.isClientError());

        assertFalse(clientError.isRetryable());
        assertTrue(clientError.isClientError());
        assertFalse(clientError.isServerError());
    }

    @Test
    void timeoutStoresCorrelationIdAndTimeout() {
        final RpcTimeoutException exception = new RpcTimeoutException(55L, 1_000_000L);

        assertEquals(55L, exception.correlationId());
        assertEquals(1_000_000L, exception.timeoutNs());
    }
}
