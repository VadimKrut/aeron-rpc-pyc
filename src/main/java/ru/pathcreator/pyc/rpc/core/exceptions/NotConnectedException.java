package ru.pathcreator.pyc.rpc.core.exceptions;

/**
 * Исключение, которое сообщает, что RPC-канал сейчас не connected к peer-у.
 *
 * <p>Exception indicating that the RPC channel is not currently connected to
 * the peer.</p>
 */
public final class NotConnectedException extends RpcException {

    /**
     * Создаёт исключение с описанием причины отсутствия соединения.
     *
     * <p>Creates an exception with a not-connected reason message.</p>
     *
     * @param reason описание причины / reason message
     */
    public NotConnectedException(final String reason) {
        super(reason);
    }
}