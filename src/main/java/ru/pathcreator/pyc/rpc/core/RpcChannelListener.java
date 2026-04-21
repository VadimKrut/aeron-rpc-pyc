package ru.pathcreator.pyc.rpc.core;

import ru.pathcreator.pyc.rpc.core.exceptions.RpcException;

/**
 * Опциональный listener для событий жизненного цикла, ошибок и recovery канала.
 *
 * <p>Optional listener interface for channel lifecycle, error, and recovery
 * events.</p>
 *
 * <p>Listener API намеренно выключен по умолчанию. Когда listeners не
 * настроены, hot path транспорта платит только за одну простую проверку
 * null/length перед возможным dispatch.</p>
 *
 * <p>The listener API is intentionally disabled by default. When no listeners
 * are configured, the transport hot path only pays one null/length check
 * before any event dispatch.</p>
 */
public interface RpcChannelListener {

    /**
     * Вызывается при старте client-side call.
     *
     * <p>Called when a client-side call starts.</p>
     *
     * @param channel              source channel
     * @param requestMessageTypeId request message type id
     * @param correlationId        call correlation id
     */
    default void onCallStarted(final RpcChannel channel, final int requestMessageTypeId, final long correlationId) {
    }

    /**
     * Вызывается при успешном завершении client-side call.
     *
     * <p>Called when a client-side call completes successfully.</p>
     *
     * @param channel              source channel
     * @param requestMessageTypeId request message type id
     * @param correlationId        call correlation id
     */
    default void onCallSucceeded(final RpcChannel channel, final int requestMessageTypeId, final long correlationId) {
    }

    /**
     * Вызывается при timeout client-side call.
     *
     * <p>Called when a client-side call times out.</p>
     *
     * @param channel              source channel
     * @param requestMessageTypeId request message type id
     * @param correlationId        call correlation id
     * @param timeoutNs            timeout in nanoseconds
     */
    default void onCallTimedOut(final RpcChannel channel, final int requestMessageTypeId, final long correlationId, final long timeoutNs) {
    }

    /**
     * Вызывается при локальном сбое client-side call.
     *
     * <p>Called when a client-side call fails locally.</p>
     *
     * @param channel              source channel
     * @param requestMessageTypeId request message type id
     * @param correlationId        call correlation id
     * @param failure              local failure
     */
    default void onCallFailed(final RpcChannel channel, final int requestMessageTypeId, final long correlationId, final RpcException failure) {
    }

    /**
     * Вызывается при structured remote error от другой стороны.
     *
     * <p>Called when a structured remote error is received from the peer.</p>
     *
     * @param channel               source channel
     * @param responseMessageTypeId response message type id
     * @param correlationId         call correlation id
     * @param failure               remote failure
     */
    default void onRemoteError(final RpcChannel channel, final int responseMessageTypeId, final long correlationId, final RpcException failure) {
    }

    /**
     * Вызывается, когда канал считается поднятым.
     *
     * <p>Called when the channel is considered up.</p>
     *
     * @param channel source channel
     */
    default void onChannelUp(final RpcChannel channel) {
    }

    /**
     * Вызывается, когда канал считается down.
     *
     * <p>Called when the channel is considered down.</p>
     *
     * @param channel source channel
     */
    default void onChannelDown(final RpcChannel channel) {
    }

    /**
     * Вызывается при старте drain mode.
     *
     * <p>Called when drain mode starts.</p>
     *
     * @param channel source channel
     */
    default void onDrainStarted(final RpcChannel channel) {
    }

    /**
     * Вызывается по завершении drain mode.
     *
     * <p>Called when drain mode finishes.</p>
     *
     * @param channel source channel
     */
    default void onDrainFinished(final RpcChannel channel) {
    }

    /**
     * Вызывается при попытке reconnect/recovery.
     *
     * <p>Called when reconnect or recovery is attempted.</p>
     *
     * @param channel  source channel
     * @param strategy active reconnect strategy
     */
    default void onReconnectAttempt(final RpcChannel channel, final ReconnectStrategy strategy) {
    }

    /**
     * Вызывается при успешном reconnect/recovery.
     *
     * <p>Called when reconnect or recovery succeeds.</p>
     *
     * @param channel  source channel
     * @param strategy active reconnect strategy
     */
    default void onReconnectSucceeded(final RpcChannel channel, final ReconnectStrategy strategy) {
    }

    /**
     * Вызывается при неуспешном reconnect/recovery.
     *
     * <p>Called when reconnect or recovery fails.</p>
     *
     * @param channel  source channel
     * @param strategy active reconnect strategy
     * @param failure  failure cause
     */
    default void onReconnectFailed(final RpcChannel channel, final ReconnectStrategy strategy, final Throwable failure) {
    }

    /**
     * Вызывается при старте protocol handshake.
     *
     * <p>Called when protocol handshake starts.</p>
     *
     * @param channel source channel
     */
    default void onProtocolHandshakeStarted(final RpcChannel channel) {
    }

    /**
     * Вызывается при успешном protocol handshake.
     *
     * <p>Called when protocol handshake succeeds.</p>
     *
     * @param channel            source channel
     * @param remoteVersion      remote protocol version
     * @param remoteCapabilities remote protocol capability bits
     */
    default void onProtocolHandshakeSucceeded(final RpcChannel channel, final int remoteVersion, final long remoteCapabilities) {
    }

    /**
     * Вызывается при провале protocol handshake.
     *
     * <p>Called when protocol handshake fails.</p>
     *
     * @param channel source channel
     * @param failure failure cause
     */
    default void onProtocolHandshakeFailed(final RpcChannel channel, final RpcException failure) {
    }
}