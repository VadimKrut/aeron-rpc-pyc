package ru.pathcreator.pyc.exceptions;

/**
 * Вызов не уложился в отведённый timeout.
 */
public final class RpcTimeoutException extends RpcException {
    private final long correlationId;
    private final long timeoutNs;

    public RpcTimeoutException(final long correlationId, final long timeoutNs) {
        super("RPC timeout after " + (timeoutNs / 1_000_000) + "ms, correlationId=" + correlationId);
        this.correlationId = correlationId;
        this.timeoutNs = timeoutNs;
    }

    public long correlationId() {
        return correlationId;
    }

    public long timeoutNs() {
        return timeoutNs;
    }
}