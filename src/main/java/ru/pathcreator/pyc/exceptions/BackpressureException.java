package ru.pathcreator.pyc.exceptions;

/**
 * Исключение, которое сообщает, что сообщение не удалось отправить из-за backpressure.
 *
 * <p>Исключение используется, когда канал или Aeron publication временно не могут
 * принять новые данные. Типичные причины: заполненная очередь отправителя,
 * истечение времени ожидания отправки или длительный ответ {@code BACK_PRESSURED}
 * от {@code publication.offer()}.</p>
 *
 * <p>Exception indicating that a message could not be sent because of backpressure.
 * It is used when the channel or Aeron publication cannot currently accept more
 * data, for example because a sender queue is full or an offer timeout has expired.</p>
 */
public final class BackpressureException extends RpcException {
    /**
     * Создает исключение с описанием причины backpressure.
     *
     * <p>Creates an exception with a backpressure reason message.</p>
     *
     * @param message описание причины / reason message
     */
    public BackpressureException(final String message) {
        super(message);
    }
}