package ru.pathcreator.pyc.rpc.core.exceptions;

/**
 * Исключение, которое сигнализирует, что сообщение не удалось отправить
 * из-за backpressure.
 *
 * <p>Exception indicating that a message could not be sent because of
 * backpressure.</p>
 */
public final class BackpressureException extends RpcException {

    /**
     * Создаёт исключение с описанием причины backpressure.
     *
     * <p>Creates an exception with a backpressure reason message.</p>
     *
     * @param message описание причины / reason message
     */
    public BackpressureException(final String message) {
        super(message);
    }
}