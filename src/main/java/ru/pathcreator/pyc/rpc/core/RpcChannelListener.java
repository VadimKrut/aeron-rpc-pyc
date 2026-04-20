package ru.pathcreator.pyc.rpc.core;

import ru.pathcreator.pyc.rpc.core.exceptions.RpcException;

/**
 * Optional listener interface for channel lifecycle, error, and recovery events.
 *
 * <p>The listener API is intentionally disabled by default. When no listeners
 * are configured, the transport hot path only pays one null/length check
 * before any event dispatch.</p>
 */
public interface RpcChannelListener {

    default void onCallStarted(final RpcChannel channel, final int requestMessageTypeId, final long correlationId) {
    }

    default void onCallSucceeded(final RpcChannel channel, final int requestMessageTypeId, final long correlationId) {
    }

    default void onCallTimedOut(final RpcChannel channel, final int requestMessageTypeId, final long correlationId, final long timeoutNs) {
    }

    default void onCallFailed(final RpcChannel channel, final int requestMessageTypeId, final long correlationId, final RpcException failure) {
    }

    default void onRemoteError(final RpcChannel channel, final int responseMessageTypeId, final long correlationId, final RpcException failure) {
    }

    default void onChannelUp(final RpcChannel channel) {
    }

    default void onChannelDown(final RpcChannel channel) {
    }

    default void onDrainStarted(final RpcChannel channel) {
    }

    default void onDrainFinished(final RpcChannel channel) {
    }

    default void onReconnectAttempt(final RpcChannel channel, final ReconnectStrategy strategy) {
    }

    default void onReconnectSucceeded(final RpcChannel channel, final ReconnectStrategy strategy) {
    }

    default void onReconnectFailed(final RpcChannel channel, final ReconnectStrategy strategy, final Throwable failure) {
    }

    default void onProtocolHandshakeStarted(final RpcChannel channel) {
    }

    default void onProtocolHandshakeSucceeded(final RpcChannel channel, final int remoteVersion, final long remoteCapabilities) {
    }

    default void onProtocolHandshakeFailed(final RpcChannel channel, final RpcException failure) {
    }
}