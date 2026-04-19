package ru.pathcreator.pyc.rpc.core.exceptions;

/**
 * Исключение, которое сообщает, что RPC-канал не подключен к удаленной стороне.
 *
 * <p>Исключение выбрасывается в fail-fast сценариях, когда продолжать ожидание
 * ответа бессмысленно: Aeron вернул состояние {@code NOT_CONNECTED} или heartbeat
 * от удаленной стороны не приходил дольше допустимого времени.</p>
 *
 * <p>Exception indicating that the RPC channel is not connected to the remote side.
 * It is thrown in fail-fast scenarios, for example when Aeron reports
 * {@code NOT_CONNECTED} or heartbeat frames have not been received for too long.</p>
 */
public final class NotConnectedException extends RpcException {
    /**
     * Создает исключение с описанием причины потери соединения.
     *
     * <p>Creates an exception with a not-connected reason message.</p>
     *
     * @param reason описание причины / reason message
     */
    public NotConnectedException(final String reason) {
        super(reason);
    }
}