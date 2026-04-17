package ru.pathcreator.pyc.exceptions;

/**
 * Канал не принял сообщение по политике backpressure.
 * <p>
 * Бросается когда:
 * - policy = FAIL_FAST и sender queue заполнена;
 * - policy = BLOCK и offerTimeout истёк;
 * - publication.offer() возвращает BACK_PRESSURED дольше offerTimeout.
 */
public final class BackpressureException extends RpcException {
    public BackpressureException(final String message) {
        super(message);
    }
}