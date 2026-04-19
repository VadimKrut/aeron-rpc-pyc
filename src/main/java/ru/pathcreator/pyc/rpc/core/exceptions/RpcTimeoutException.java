package ru.pathcreator.pyc.rpc.core.exceptions;

/**
 * Исключение, которое сообщает, что RPC-вызов не завершился за отведенное время.
 *
 * <p>Исключение содержит идентификатор корреляции исходного запроса и таймаут,
 * после которого ожидание ответа было прекращено. Эти значения полезны для
 * диагностики зависших или потерянных запросов.</p>
 *
 * <p>Exception indicating that an RPC call did not complete within the configured
 * timeout. It contains the original request correlation identifier and the timeout
 * used by the waiting code.</p>
 */
public final class RpcTimeoutException extends RpcException {
    /**
     * Идентификатор корреляции запроса, для которого истек таймаут.
     */
    private final long correlationId;

    /**
     * Таймаут ожидания в наносекундах.
     */
    private final long timeoutNs;

    /**
     * Создает исключение с идентификатором корреляции и таймаутом ожидания.
     *
     * <p>Creates an exception with the request correlation identifier and timeout.</p>
     *
     * @param correlationId идентификатор корреляции запроса / request correlation identifier
     * @param timeoutNs     таймаут ожидания в наносекундах / timeout in nanoseconds
     */
    public RpcTimeoutException(final long correlationId, final long timeoutNs) {
        super("RPC timeout after " + (timeoutNs / 1_000_000) + "ms, correlationId=" + correlationId);
        this.correlationId = correlationId;
        this.timeoutNs = timeoutNs;
    }

    /**
     * Возвращает идентификатор корреляции запроса, для которого истек таймаут.
     *
     * <p>Returns the correlation identifier of the request that timed out.</p>
     *
     * @return идентификатор корреляции запроса / request correlation identifier
     */
    public long correlationId() {
        return correlationId;
    }

    /**
     * Возвращает таймаут ожидания, после которого вызов был прерван.
     *
     * <p>Returns the timeout after which the call was interrupted.</p>
     *
     * @return таймаут в наносекундах / timeout in nanoseconds
     */
    public long timeoutNs() {
        return timeoutNs;
    }
}