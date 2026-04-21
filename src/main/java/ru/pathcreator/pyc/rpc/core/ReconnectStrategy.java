package ru.pathcreator.pyc.rpc.core;

/**
 * Стратегия поведения, когда вызов стартует при временно disconnected канале.
 *
 * <p>Strategy used when a call starts while the channel is temporarily
 * disconnected.</p>
 */
public enum ReconnectStrategy {
    /**
     * Сразу завершить вызов ошибкой, если канал не connected.
     *
     * <p>Fail immediately if the channel is not connected.</p>
     */
    FAIL_FAST,

    /**
     * Ждать, пока текущие Aeron publication и heartbeat снова станут connected.
     *
     * <p>Wait until the existing Aeron publication and heartbeat become
     * connected again.</p>
     */
    WAIT_FOR_CONNECTION,

    /**
     * Пересоздать локальную пару publication/subscription при disconnect, а
     * затем ждать, пока пересозданный путь станет connected.
     *
     * <p>Recreate the local publication/subscription pair when the transport is
     * disconnected, then wait for the recreated path to become connected.</p>
     *
     * <p>Это дороже, чем {@link #WAIT_FOR_CONNECTION}, и должно
     * рассматриваться как opt-in recovery mode для сред, где локальные ресурсы
     * канала могут требовать rebuild после disconnect.</p>
     *
     * <p>This is more expensive than {@link #WAIT_FOR_CONNECTION} and should
     * be treated as an opt-in recovery mode for environments where local
     * channel resources may need to be rebuilt after a disconnect.</p>
     */
    RECREATE_ON_DISCONNECT
}