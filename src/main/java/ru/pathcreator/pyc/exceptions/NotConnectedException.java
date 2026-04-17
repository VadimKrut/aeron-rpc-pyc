package ru.pathcreator.pyc.exceptions;

/**
 * Канал DOWN: либо Publication.NOT_CONNECTED от Aeron, либо heartbeat не
 * приходил дольше допустимого. Failfast, не ждём таймаута.
 */
public final class NotConnectedException extends RpcException {
    public NotConnectedException(final String reason) {
        super(reason);
    }
}