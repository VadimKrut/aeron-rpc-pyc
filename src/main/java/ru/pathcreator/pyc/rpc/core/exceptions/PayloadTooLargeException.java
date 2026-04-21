package ru.pathcreator.pyc.rpc.core.exceptions;

/**
 * Исключение, которое сигнализирует, что payload превышает лимит канала.
 *
 * <p>Exception indicating that the payload exceeds the channel limit.</p>
 */
public final class PayloadTooLargeException extends RpcException {
    /**
     * фактический размер payload / actual payload size.
     */
    private final int actual;

    /**
     * лимит payload для канала / configured payload size limit.
     */
    private final int limit;

    /**
     * Создаёт исключение с фактическим размером payload и лимитом.
     *
     * <p>Creates an exception with the actual payload size and the configured limit.</p>
     *
     * @param actual фактический размер в байтах / actual payload size in bytes
     * @param limit  лимит в байтах / maximum allowed size in bytes
     */
    public PayloadTooLargeException(final int actual, final int limit) {
        super("Payload too large: " + actual + " bytes > limit " + limit + " bytes.");
        this.actual = actual;
        this.limit = limit;
    }

    /**
     * Возвращает фактический размер payload, который был отклонен.
     *
     * <p>Returns the actual payload size that was rejected.</p>
     *
     * @return actual payload size
     */
    public int actual() {
        return actual;
    }

    /**
     * Возвращает лимит размера payload для канала.
     *
     * <p>Returns the payload size limit for the channel.</p>
     *
     * @return configured payload size limit
     */
    public int limit() {
        return limit;
    }
}