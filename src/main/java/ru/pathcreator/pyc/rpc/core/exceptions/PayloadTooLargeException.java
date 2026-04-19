package ru.pathcreator.pyc.rpc.core.exceptions;

/**
 * Исключение, которое сообщает, что полезная нагрузка превышает лимит канала.
 *
 * <p>Исключение выбрасывается до отправки сообщения, если размер payload больше
 * настроенного максимального размера сообщения. Это защищает канал от фрагментов,
 * которые не должны передаваться через обычный RPC-путь.</p>
 *
 * <p>Exception indicating that the payload exceeds the channel message size limit.
 * It is thrown before sending when the payload is larger than the configured
 * maximum message size.</p>
 */
public final class PayloadTooLargeException extends RpcException {
    /**
     * Фактический размер отклоненного payload.
     */
    private final int actual;

    /**
     * Максимально допустимый размер payload.
     */
    private final int limit;

    /**
     * Создает исключение с фактическим размером payload и допустимым лимитом.
     *
     * <p>Creates an exception with the actual payload size and the configured limit.</p>
     *
     * @param actual фактический размер payload в байтах / actual payload size in bytes
     * @param limit  максимально допустимый размер payload в байтах / maximum allowed payload size in bytes
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
     * @return фактический размер payload в байтах / actual payload size in bytes
     */
    public int actual() {
        return actual;
    }

    /**
     * Возвращает максимально допустимый размер payload для канала.
     *
     * <p>Returns the maximum payload size allowed by the channel.</p>
     *
     * @return лимит размера payload в байтах / payload size limit in bytes
     */
    public int limit() {
        return limit;
    }
}