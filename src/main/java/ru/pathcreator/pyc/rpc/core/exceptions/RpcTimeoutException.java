package ru.pathcreator.pyc.rpc.core.exceptions;

/**
 * Исключение, которое сообщает, что RPC-вызов не завершился в пределах timeout.
 *
 * <p>Exception indicating that an RPC call did not complete within the
 * configured timeout.</p>
 *
 * <p>Исключение содержит correlation id исходного запроса и timeout, после
 * которого ожидание было прервано.</p>
 *
 * <p>The exception contains the correlation id of the original request and the
 * timeout after which waiting was interrupted.</p>
 */
public final class RpcTimeoutException extends RpcException {
    /**
     * correlation id запроса, упавшего по timeout / correlation id of the timed-out request.
     */
    private final long correlationId;

    /**
     * timeout ожидания в наносекундах / timeout value in nanoseconds.
     */
    private final long timeoutNs;

    /**
     * Создаёт timeout-исключение для конкретного correlation id.
     *
     * <p>Creates a timeout exception for a specific correlation id.</p>
     *
     * @param correlationId идентификатор корреляции / request correlation id
     * @param timeoutNs     timeout ожидания в наносекундах / timeout in nanoseconds
     */
    public RpcTimeoutException(final long correlationId, final long timeoutNs) {
        super("RPC timeout after " + (timeoutNs / 1_000_000) + "ms, correlationId=" + correlationId);
        this.correlationId = correlationId;
        this.timeoutNs = timeoutNs;
    }

    /**
     * Возвращает correlation id запроса, который завершился по timeout.
     *
     * <p>Returns the correlation id of the request that timed out.</p>
     *
     * @return timed-out request correlation id
     */
    public long correlationId() {
        return correlationId;
    }

    /**
     * Возвращает timeout ожидания в наносекундах.
     *
     * <p>Returns the timeout value in nanoseconds.</p>
     *
     * @return timeout value in nanoseconds
     */
    public long timeoutNs() {
        return timeoutNs;
    }
}